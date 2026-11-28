import { inject, Injectable, signal } from '@angular/core';
import { NotificationDTO, NotificationEventType } from '@models/notification';
import { EnvLoaderService } from '@services/env-loader.service';
import Keycloak from 'keycloak-js';
import { firstValueFrom } from 'rxjs';
import { NotificationApi } from './notification-api';

/** One of the user's in-app notifications. */
export interface InboxItem {
  /** The Novu message id when the inbox is in Novu, else StarSign's notification id; markRead takes either. */
  id: string;
  subject: string;
  eventType: NotificationEventType | null;
  createdAt: string | null;
  read: boolean;
  /** Portal-relative path, or null. */
  linkPath: string | null;
}

/** Retries of the live stream: 2 s, doubling up to a minute. */
const FIRST_RETRY_MS = 2_000;
const MAX_RETRY_MS = 60_000;
/** Unread count refresh when there is no live stream (no Novu inbox or socket). */
const POLL_MS = 60_000;

/**
 * The signed-in user's in-app notifications, all through StarSign: the webservice reads the user's Novu
 * inbox and relays Novu's socket as Server-Sent Events (GET /notifications/me/stream), so the browser never
 * talks to Novu. The stream is read with fetch, as an EventSource cannot send the bearer token. Without a
 * stream (204) the unread count is polled.
 */
@Injectable({ providedIn: 'root' })
export class NotificationInboxService {
  private readonly api = inject(NotificationApi);
  private readonly env = inject(EnvLoaderService);
  private readonly keycloak = inject(Keycloak);

  readonly unreadCount = signal(0);
  /** Bumped when a new notification arrives, so an open list can reload. */
  readonly received = signal(0);
  /** True while the live stream is connected. */
  readonly live = signal(false);

  private running = false;
  private abort: AbortController | null = null;
  private timer: ReturnType<typeof setTimeout> | null = null;
  private retryMs = FIRST_RETRY_MS;

  /** Starts the live stream (or polling) once per sign-in. */
  connect(): void {
    if (this.running) {
      return;
    }
    this.running = true;
    void this.refreshCount();
    void this.openStream();
  }

  disconnect(): void {
    this.running = false;
    this.abort?.abort();
    this.abort = null;
    if (this.timer) {
      clearTimeout(this.timer);
      this.timer = null;
    }
    this.live.set(false);
    this.unreadCount.set(0);
  }

  async list(unreadOnly: boolean): Promise<InboxItem[]> {
    const list = await firstValueFrom(this.api.findMine(unreadOnly || null));
    return list.map(toItem);
  }

  /** Marks one notification read; returns the updated item. */
  async markRead(item: InboxItem): Promise<InboxItem> {
    if (item.read) {
      return item;
    }
    const updated = await firstValueFrom(this.api.markRead(item.id));
    if (!this.live()) {
      this.unreadCount.update((count) => Math.max(0, count - 1));
    }
    return { ...item, ...toItem({ ...updated, id: updated.id ?? item.id }), read: true };
  }

  async markAllRead(): Promise<void> {
    await firstValueFrom(this.api.markAllRead());
    this.unreadCount.set(0);
  }

  async refreshCount(): Promise<void> {
    try {
      this.unreadCount.set(await firstValueFrom(this.api.unreadCount()));
    } catch {
      // The badge keeps its last value; the next event or poll corrects it
    }
  }

  private async openStream(): Promise<void> {
    if (!this.running) {
      return;
    }
    this.abort = new AbortController();
    try {
      await this.keycloak.updateToken(30);
      const response = await fetch(`${this.env.apiUrl()}/notifications/me/stream`, {
        headers: { Accept: 'text/event-stream', Authorization: `Bearer ${this.keycloak.token}` },
        signal: this.abort.signal,
        cache: 'no-store',
      });
      if (response.status === 204) {
        // Nothing to relay (no Novu inbox or socket): poll the count instead
        this.schedule(() => this.poll(), POLL_MS);
        return;
      }
      if (!response.ok || !response.body) {
        throw new Error(`Notification stream answered ${response.status}`);
      }
      this.live.set(true);
      this.retryMs = FIRST_RETRY_MS;
      await this.read(response.body);
    } catch (error) {
      if (!this.running || (error as Error)?.name === 'AbortError') {
        return;
      }
    } finally {
      this.live.set(false);
    }
    // The server ends a stream after a while (and on restarts): reconnect with a fresh token
    this.schedule(() => this.openStream(), this.retryMs);
    this.retryMs = Math.min(this.retryMs * 2, MAX_RETRY_MS);
  }

  private async poll(): Promise<void> {
    if (!this.running) {
      return;
    }
    await this.refreshCount();
    this.schedule(() => this.poll(), POLL_MS);
  }

  private schedule(next: () => Promise<void>, delay: number): void {
    if (!this.running) {
      return;
    }
    this.timer = setTimeout(() => void next(), delay);
  }

  /** Reads Server-Sent Events: blocks of "event:" / "data:" lines separated by a blank line. */
  private async read(body: ReadableStream<Uint8Array>): Promise<void> {
    const reader = body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    for (;;) {
      const { value, done } = await reader.read();
      if (done) {
        return;
      }
      buffer += decoder.decode(value, { stream: true }).replace(/\r\n?/g, '\n');
      let end: number;
      while ((end = buffer.indexOf('\n\n')) >= 0) {
        this.dispatch(buffer.slice(0, end));
        buffer = buffer.slice(end + 2);
      }
    }
  }

  private dispatch(block: string): void {
    let event = 'message';
    const data: string[] = [];
    for (const line of block.split('\n')) {
      if (line.startsWith('event:')) {
        event = line.slice(6).trim();
      } else if (line.startsWith('data:')) {
        data.push(line.slice(5).replace(/^ /, ''));
      }
    }
    if (!data.length) {
      return; // keep-alive comment
    }
    try {
      const payload = JSON.parse(data.join('\n'));
      if (event === 'unread' && typeof payload?.count === 'number') {
        this.unreadCount.set(payload.count);
      } else if (event === 'notification') {
        this.received.update((n) => n + 1);
      }
    } catch {
      // Not ours to understand; ignore
    }
  }
}

function toItem(n: NotificationDTO): InboxItem {
  return {
    id: n.id ?? '',
    subject: n.subject || label(n.eventType),
    eventType: n.eventType ?? null,
    createdAt: n.createdAt ?? null,
    read: !!n.readAt,
    linkPath: portalPath(n.linkPath),
  };
}

/** Portal-relative links only; anything else is not followed. */
function portalPath(path: string | null | undefined): string | null {
  return path && path.startsWith('/') && !path.startsWith('//') ? path : null;
}

function label(value: string | null | undefined): string {
  return (value ?? '').replaceAll('_', ' ').toLowerCase();
}
