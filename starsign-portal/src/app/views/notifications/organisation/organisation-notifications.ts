import { DatePipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { form, FormField } from '@angular/forms/signals';
import { RouterLink } from '@angular/router';
import { SortDirection } from '@models/common';
import {
  NotificationChannel,
  NotificationDTO,
  NotificationEventType,
  NotificationSearchCriteria,
  NotificationStatus,
} from '@models/notification';
import { NotificationApi } from '@services/notification/notification-api';
import { FieldErrors } from '@shared/field-errors/field-errors';
import { apiErrorMessage } from '@store/app-state';
import { SessionStore } from '@store/session.store';
import { firstValueFrom } from 'rxjs';
import { dateRange } from '../../documents/search-dates';
import { NotificationTabs } from '../notification-tabs';

interface NotificationFilter {
  eventType: NotificationEventType | '';
  channel: NotificationChannel | '';
  status: NotificationStatus | '';
  workflowId: string;
  from: string;
  to: string;
}

const EMPTY: NotificationFilter = {
  eventType: '',
  channel: '',
  status: '',
  workflowId: '',
  from: '',
  to: '',
};

const PAGE_SIZE = 25;

/** Every notification the organisation sent: delivery status, attempts and errors. */
@Component({
  selector: 'app-organisation-notifications',
  imports: [DatePipe, FieldErrors, FormField, NotificationTabs, RouterLink],
  templateUrl: './organisation-notifications.html',
})
export class OrganisationNotifications {
  private readonly notificationApi = inject(NotificationApi);
  private readonly session = inject(SessionStore);

  protected readonly eventTypes = Object.values(NotificationEventType);
  protected readonly channels = Object.values(NotificationChannel);
  protected readonly statuses = Object.values(NotificationStatus);

  protected readonly filter = signal<NotificationFilter>({ ...EMPTY });
  protected readonly filterForm = form(this.filter, (path) => dateRange(path.from, path.to));

  protected readonly notifications = signal<NotificationDTO[]>([]);
  protected readonly page = signal(0);
  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);
  private readonly applied = signal<NotificationSearchCriteria>({});

  protected readonly hasNext = computed(() => this.notifications().length === PAGE_SIZE);

  constructor() {
    void this.search();
  }

  protected statusChip(status: NotificationStatus | null | undefined): string {
    switch (status) {
      case NotificationStatus.SENT:
      case NotificationStatus.DELIVERED:
        return 'chip--sealed';
      case NotificationStatus.FAILED:
      case NotificationStatus.CANCELLED:
        return 'chip--voided';
      case NotificationStatus.QUEUED:
      case NotificationStatus.SENDING:
      case NotificationStatus.RETRY_SCHEDULED:
        return 'chip--pending';
      default:
        return 'chip--neutral';
    }
  }

  protected label(value: string | null | undefined): string {
    return (value ?? '—').replaceAll('_', ' ');
  }

  protected async search(event?: Event): Promise<void> {
    event?.preventDefault();
    if (this.filterForm().invalid()) {
      return;
    }
    const f = this.filter();
    this.applied.set({
      organisationId: this.session.organisationId(),
      eventType: f.eventType || null,
      channel: f.channel || null,
      status: f.status || null,
      workflowId: f.workflowId.trim() || null,
      // The API takes local date-times without a zone.
      from: f.from ? `${f.from}T00:00:00` : null,
      to: f.to ? `${f.to}T23:59:59` : null,
      pageSize: PAGE_SIZE,
      sortField: 'createdAt',
      sortDirection: SortDirection.DESC,
    });
    await this.load(0);
  }

  protected reset(): void {
    this.filter.set({ ...EMPTY });
    void this.search();
  }

  protected async load(page: number): Promise<void> {
    this.loading.set(true);
    this.error.set(null);
    try {
      this.notifications.set(
        await firstValueFrom(this.notificationApi.search({ ...this.applied(), pageNumber: page })),
      );
      this.page.set(page);
    } catch (error) {
      this.error.set(apiErrorMessage(error, 'Unable to search the notifications.'));
    } finally {
      this.loading.set(false);
    }
  }
}
