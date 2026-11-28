import { DatePipe } from '@angular/common';
import { Component, effect, inject, signal, untracked } from '@angular/core';
import { RouterLink } from '@angular/router';
import {
  InboxItem,
  NotificationInboxService,
} from '@services/notification/notification-inbox.service';
import { apiErrorMessage } from '@store/app-state';
import { toast } from 'ngx-sonner';
import { NotificationTabs } from './notification-tabs';

/** The signed-in user's own (in-app) notifications, from their Novu inbox through StarSign (or StarSign's copies). */
@Component({
  selector: 'app-notifications',
  imports: [DatePipe, NotificationTabs, RouterLink],
  templateUrl: './notifications.html',
})
export class Notifications {
  protected readonly inbox = inject(NotificationInboxService);

  protected readonly notifications = signal<InboxItem[]>([]);
  protected readonly unreadOnly = signal(false);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly busyId = signal<string | null>(null);

  protected readonly unreadCount = this.inbox.unreadCount;

  constructor() {
    // Initial load, then again whenever a new notification arrives on the stream
    effect(() => {
      this.inbox.received();
      untracked(() => void this.load());
    });
  }

  protected setUnreadOnly(value: boolean): void {
    this.unreadOnly.set(value);
    void this.load();
  }

  protected async load(): Promise<void> {
    this.loading.set(true);
    this.error.set(null);
    try {
      this.notifications.set(await this.inbox.list(this.unreadOnly()));
    } catch (error) {
      this.error.set(apiErrorMessage(error, 'Unable to load your notifications.'));
    } finally {
      this.loading.set(false);
    }
  }

  protected async markRead(notification: InboxItem): Promise<void> {
    if (notification.read) {
      return;
    }
    this.busyId.set(notification.id);
    try {
      const updated = await this.inbox.markRead(notification);
      this.notifications.update((list) =>
        this.unreadOnly()
          ? list.filter((n) => n.id !== notification.id)
          : list.map((n) => (n.id === notification.id ? updated : n)),
      );
    } catch (error) {
      toast.error(apiErrorMessage(error, 'The notification could not be marked as read.'));
    } finally {
      this.busyId.set(null);
    }
  }

  protected async markAllRead(): Promise<void> {
    this.busyId.set('*');
    try {
      await this.inbox.markAllRead();
      this.notifications.update((list) =>
        this.unreadOnly() ? [] : list.map((n) => ({ ...n, read: true })),
      );
    } catch (error) {
      toast.error(apiErrorMessage(error, 'The notifications could not be marked as read.'));
    } finally {
      this.busyId.set(null);
    }
  }

  protected label(value: string | null | undefined): string {
    return (value ?? '').replaceAll('_', ' ').toLowerCase();
  }
}
