import { DatePipe } from '@angular/common';
import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { form, FormField } from '@angular/forms/signals';
import { RouterLink } from '@angular/router';
import { Can } from '@auth/can.directive';
import { hasPermission } from '@auth/page-permissions';
import { NotificationTemplateDTO } from '@models/notification';
import { NotificationTemplateApi } from '@services/notification/notification-template-api';
import { apiErrorMessage } from '@store/app-state';
import { SessionStore } from '@store/session.store';
import { toast } from 'ngx-sonner';
import { firstValueFrom } from 'rxjs';
import {
  CHANNEL_META,
  CHANNELS,
  EVENT_META,
  EVENT_TYPES,
  NOVU_WORKFLOW_PREFIX,
  novuWorkflowId,
} from './template-meta';

interface FilterModel {
  text: string;
  eventType: string;
  channel: string;
  scope: '' | 'organisation' | 'platform';
}

/**
 * The organisation's notification templates and the platform defaults. Sending picks the active
 * organisation template for the event and channel first, then the platform's (best locale first),
 * then StarSign's built-in wording.
 */
@Component({
  selector: 'app-notification-templates',
  imports: [Can, DatePipe, FormField, RouterLink],
  templateUrl: './notification-templates.html',
})
export class NotificationTemplates implements OnInit {
  private readonly templateApi = inject(NotificationTemplateApi);
  protected readonly session = inject(SessionStore);

  protected readonly templates = signal<NotificationTemplateDTO[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly busyId = signal<string | null>(null);

  protected readonly eventTypes = EVENT_TYPES;
  protected readonly channels = CHANNELS;
  protected readonly eventMeta = EVENT_META;
  protected readonly channelMeta = CHANNEL_META;
  protected readonly novuPrefix = NOVU_WORKFLOW_PREFIX;
  protected readonly novuWorkflowId = novuWorkflowId;

  protected readonly canEdit = computed(() =>
    hasPermission(['notification-templates:edit'], this.session.permissions()),
  );
  protected readonly canDelete = computed(() =>
    hasPermission(['notification-templates:delete'], this.session.permissions()),
  );

  protected readonly filter = signal<FilterModel>({ text: '', eventType: '', channel: '', scope: '' });
  protected readonly filterForm = form(this.filter);

  protected readonly visible = computed(() => {
    const { text, eventType, channel, scope } = this.filter();
    const needle = text.trim().toLowerCase();
    return this.templates()
      .filter((t) => !eventType || t.eventType === eventType)
      .filter((t) => !channel || t.channel === channel)
      .filter((t) => !scope || (scope === 'organisation') === this.isOwn(t))
      .filter(
        (t) =>
          !needle ||
          [t.code, t.subjectTemplate, t.locale, t.eventType && EVENT_META[t.eventType]?.label]
            .filter(Boolean)
            .some((value) => String(value).toLowerCase().includes(needle)),
      )
      .sort(
        (a, b) =>
          EVENT_TYPES.indexOf(a.eventType!) - EVENT_TYPES.indexOf(b.eventType!) ||
          CHANNELS.indexOf(a.channel!) - CHANNELS.indexOf(b.channel!) ||
          Number(this.isOwn(b)) - Number(this.isOwn(a)) ||
          (a.locale ?? '').localeCompare(b.locale ?? ''),
      );
  });

  protected readonly counts = computed(() => {
    const all = this.templates();
    const own = all.filter((t) => this.isOwn(t)).length;
    return { own, platform: all.length - own };
  });

  /** Event+channel pairs that have an active organisation template: those platform templates are not used. */
  private readonly overridden = computed(
    () =>
      new Set(
        this.templates()
          .filter((t) => this.isOwn(t) && t.active !== false)
          .map((t) => `${t.eventType}|${t.channel}`),
      ),
  );

  ngOnInit(): void {
    void this.load();
  }

  protected async load(): Promise<void> {
    const organisationId = this.session.organisationId();
    if (!organisationId) {
      this.loading.set(false);
      this.error.set('Your account is not linked to an organisation: templates are listed per organisation.');
      return;
    }
    this.loading.set(true);
    this.error.set(null);
    try {
      this.templates.set(await firstValueFrom(this.templateApi.findTemplates(organisationId)));
    } catch (error) {
      this.error.set(apiErrorMessage(error, 'Unable to load the templates.'));
    } finally {
      this.loading.set(false);
    }
  }

  protected isOwn(template: NotificationTemplateDTO): boolean {
    return !!template.organisationId;
  }

  protected isOverridden(template: NotificationTemplateDTO): boolean {
    return !this.isOwn(template) && this.overridden().has(`${template.eventType}|${template.channel}`);
  }

  protected canRemove(template: NotificationTemplateDTO): boolean {
    return this.canDelete() && this.isOwn(template) && !template.systemDefault;
  }

  protected clearFilters(): void {
    this.filter.set({ text: '', eventType: '', channel: '', scope: '' });
  }

  protected async remove(template: NotificationTemplateDTO): Promise<void> {
    const fallback = this.templates().some(
      (t) => !this.isOwn(t) && t.eventType === template.eventType && t.channel === template.channel && t.active !== false,
    )
      ? 'the platform template'
      : 'the built-in wording';
    if (!confirm(`Delete the template ${template.code}? ${fallback[0].toUpperCase() + fallback.slice(1)} is used instead. This cannot be undone.`)) {
      return;
    }
    this.busyId.set(template.id ?? null);
    try {
      await firstValueFrom(this.templateApi.removeTemplate(template.id!));
      toast.success('Template deleted', { description: template.code ?? undefined });
      await this.load();
    } catch (error) {
      toast.error(apiErrorMessage(error, 'The template could not be deleted.'));
    } finally {
      this.busyId.set(null);
    }
  }
}
