import { DatePipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { NotificationDTO } from '@models/notification';
import { NotificationApi } from '@services/notification/notification-api';
import { apiErrorMessage } from '@store/app-state';
import { toast } from 'ngx-sonner';
import { firstValueFrom } from 'rxjs';
import { NotificationTabs } from './notification-tabs';

/** The signed-in user's own (in-app) notifications. */
@Component({
  selector: 'app-notifications',
  imports: [DatePipe, NotificationTabs, RouterLink],
  templateUrl: './notifications.html',
})
export class Notifications {
  private readonly notificationApi = inject(NotificationApi);

  protected readonly notifications = signal<NotificationDTO[]>([]);
  protected readonly unreadOnly = signal(false);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly busyId = signal<string | null>(null);

  protected readonly unreadCount = computed(
    () => this.notifications().filter((n) => !n.readAt).length,
  );

  constructor() {
    void this.load();
  }

  protected setUnreadOnly(value: boolean): void {
    this.unreadOnly.set(value);
    void this.load();
  }

  protected async load(): Promise<void> {
    this.loading.set(true);
    this.error.set(null);
    try {
      this.notifications.set(
        await firstValueFrom(this.notificationApi.findMine(this.unreadOnly() || null)),
      );
    } catch (error) {
      this.error.set(apiErrorMessage(error, 'Unable to load your notifications.'));
    } finally {
      this.loading.set(false);
    }
  }

  protected async markRead(notification: NotificationDTO): Promise<void> {
    if (!notification.id || notification.readAt) {
      return;
    }
    this.busyId.set(notification.id);
    try {
      const updated = await firstValueFrom(this.notificationApi.markRead(notification.id));
      this.notifications.update((list) =>
        this.unreadOnly()
          ? list.filter((n) => n.id !== notification.id)
          : list.map((n) => (n.id === notification.id ? { ...n, ...updated } : n)),
      );
    } catch (error) {
      toast.error(apiErrorMessage(error, 'The notification could not be marked as read.'));
    } finally {
      this.busyId.set(null);
    }
  }

  protected async markAllRead(): Promise<void> {
    const unread = this.notifications().filter((n) => !n.readAt);
    for (const notification of unread) {
      await this.markRead(notification);
    }
  }

  /** Portal-relative links only; anything else is not followed. */
  protected link(notification: NotificationDTO): string | null {
    const path = notification.linkPath;
    return path && path.startsWith('/') && !path.startsWith('//') ? path : null;
  }

  protected label(value: string | null | undefined): string {
    return (value ?? '').replaceAll('_', ' ').toLowerCase();
  }
}
