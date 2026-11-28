import { inject, Injectable, signal } from '@angular/core';
import { NotificationDTO, NotificationEventType, NotificationInboxDTO } from '@models/notification';
import type { Novu, Notification as NovuNotification } from '@novu/js';
import { firstValueFrom } from 'rxjs';
import { NotificationApi } from './notification-api';

/** One entry of the user's in-app notifications, whichever side it came from. */
export interface InboxItem {
  /** Novu message id when the inbox is in Novu, else the StarSign notification id. */
  id: string;
  /** The StarSign notification id (Novu: from the in-app step's data). */
  notificationId: string | null;
  subject: string;
  body: string | null;
  eventType: NotificationEventType | null;
  createdAt: string | null;
  read: boolean;
  /** Portal-relative path, or null. */
  linkPath: string | null;
}

const PAGE_SIZE = 50;

/**
 * The signed-in user's in-app notifications. When the webservice delivers them through Novu
 * (GET /notifications/inbox enabled), they are read from the user's Novu inbox, with live
 * updates over Novu's socket; otherwise from StarSign (GET /notifications/me). Marking a Novu
 * message read marks StarSign's copy read too, so the organisation view stays in step.
 */
@Injectable({ providedIn: 'root' })
export class NotificationInboxService {
  private readonly api = inject(NotificationApi);

  /** True once connected to Novu; false when StarSign holds the inbox. */
  readonly novuEnabled = signal(false);
  readonly unreadCount = signal(0);
  /** Bumped when Novu pushes a new message, so an open list can reload. */
  readonly received = signal(0);

  private novu: Novu | null = null;
  private connecting: Promise<void> | null = null;
  private readonly messages = new Map<string, NovuNotification>();
  private readonly unsubscribers: Array<() => void> = [];

  /** Connects once per sign-in; later calls wait for the same connection. */
  connect(): Promise<void> {
    this.connecting ??= this.open().catch((error) => {
      // The portal still works without the inbox; the next call tries again
      console.warn('Notification inbox unavailable', error);
      this.connecting = null;
    });
    return this.connecting;
  }

  disconnect(): void {
    this.unsubscribers.splice(0).forEach((off) => off());
    this.novu = null;
    this.connecting = null;
    this.messages.clear();
    this.novuEnabled.set(false);
    this.unreadCount.set(0);
  }

  async list(unreadOnly: boolean): Promise<InboxItem[]> {
    await this.connect();
    const novu = this.novu;
    if (!novu) {
      const list = await firstValueFrom(this.api.findMine(unreadOnly || null));
      if (!unreadOnly) {
        this.unreadCount.set(list.filter((n) => !n.readAt).length);
      }
      return list.map(fromStarSign);
    }
    const { data, error } = await novu.notifications.list({
      limit: PAGE_SIZE,
      archived: false,
      ...(unreadOnly ? { read: false } : {}),
    });
    if (error) {
      throw error;
    }
    this.messages.clear();
    return (data?.notifications ?? []).map((message) => {
      this.messages.set(message.id, message);
      return fromNovu(message);
    });
  }

  /** Marks one notification read; returns the updated item. */
  async markRead(item: InboxItem): Promise<InboxItem> {
    if (item.read) {
      return item;
    }
    const message = this.messages.get(item.id);
    if (this.novu && message) {
      const { error } = await message.read();
      if (error) {
        throw error;
      }
      this.syncStarSign(item.notificationId);
    } else {
      await firstValueFrom(this.api.markRead(item.id));
      this.unreadCount.update((count) => Math.max(0, count - 1));
    }
    return { ...item, read: true };
  }

  async markAllRead(items: InboxItem[]): Promise<void> {
    const unread = items.filter((item) => !item.read);
    if (this.novu) {
      const { error } = await this.novu.notifications.readAll();
      if (error) {
        throw error;
      }
      unread.forEach((item) => this.syncStarSign(item.notificationId));
      return;
    }
    for (const item of unread) {
      await this.markRead(item);
    }
  }

  private async open(): Promise<void> {
    const inbox = await firstValueFrom(this.api.inbox());
    if (!inbox?.enabled || !inbox.applicationIdentifier || !inbox.subscriberId) {
      const unread = await firstValueFrom(this.api.findMine(true));
      this.unreadCount.set(unread.length);
      return;
    }
    // Loaded only when the inbox is in Novu: it stays out of the initial bundle
    const { Novu } = await import('@novu/js');
    const novu = new Novu({
      applicationIdentifier: inbox.applicationIdentifier,
      subscriberId: inbox.subscriberId,
      subscriberHash: inbox.subscriberHash ?? undefined,
      apiUrl: inbox.backendUrl ?? undefined,
      ...socketConfig(inbox),
    });
    this.novu = novu;
    this.novuEnabled.set(true);
    this.unsubscribers.push(
      novu.on('notifications.unread_count_changed', ({ result }) =>
        this.unreadCount.set(result.total),
      ),
      novu.on('notifications.notification_received', () => this.received.update((n) => n + 1)),
    );
    const { data } = await novu.notifications.count({ read: false, archived: false });
    if (data && 'count' in data) {
      this.unreadCount.set(data.count);
    }
  }

  /** StarSign's copy follows Novu's read state; a failure only leaves the organisation view behind. */
  private syncStarSign(notificationId: string | null): void {
    if (notificationId) {
      this.api.markRead(notificationId).subscribe({ error: () => undefined });
    }
  }
}

/**
 * Novu's self-hosted socket is socket.io: an address with a path (behind the ngrok proxy,
 * https://host/novu/ws) is the origin plus socket.io's path under it, not a namespace.
 */
function socketConfig(inbox: NotificationInboxDTO): {
  socketUrl?: string;
  socketOptions?: Record<string, unknown>;
} {
  if (!inbox.socketUrl) {
    return {};
  }
  const url = new URL(inbox.socketUrl, window.location.origin);
  const prefix = url.pathname.replace(/\/+$/, '');
  if (!prefix) {
    return { socketUrl: url.origin, socketOptions: { socketType: 'self-hosted' } };
  }
  return {
    socketUrl: url.origin,
    socketOptions: { socketType: 'self-hosted', path: `${prefix}/socket.io/` },
  };
}

function fromStarSign(n: NotificationDTO): InboxItem {
  return {
    id: n.id ?? '',
    notificationId: n.id ?? null,
    subject: n.subject || label(n.eventType),
    body: null,
    eventType: n.eventType ?? null,
    createdAt: n.createdAt ?? null,
    read: !!n.readAt,
    linkPath: portalPath(n.linkPath),
  };
}

function fromNovu(message: NovuNotification): InboxItem {
  const data = (message.data ?? {}) as Record<string, unknown>;
  const text = (key: string): string | null =>
    typeof data[key] === 'string' && data[key] ? (data[key] as string) : null;
  const eventType = text('eventType') as NotificationEventType | null;
  return {
    id: message.id,
    notificationId: text('notificationId'),
    subject: message.subject || message.body || label(eventType),
    body: message.subject ? message.body : null,
    eventType,
    createdAt: message.createdAt,
    read: message.isRead,
    linkPath: portalPath(text('linkPath') ?? message.redirect?.url),
  };
}

/** Portal-relative links only; anything else is not followed. */
function portalPath(path: string | null | undefined): string | null {
  return path && path.startsWith('/') && !path.startsWith('//') ? path : null;
}

function label(value: string | null | undefined): string {
  return (value ?? '').replaceAll('_', ' ').toLowerCase();
}
