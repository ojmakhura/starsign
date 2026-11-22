import { DatePipe } from '@angular/common';
import { Component, computed, effect, inject, OnInit, signal } from '@angular/core';
import {
  disabled,
  form,
  FormField,
  FormRoot,
  maxLength,
  pattern,
  required,
  validate,
} from '@angular/forms/signals';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { hasPermission } from '@auth/page-permissions';
import {
  NotificationChannel,
  NotificationEventType,
  NotificationTemplateDTO,
} from '@models/notification';
import { NotificationTemplateApi } from '@services/notification/notification-template-api';
import { FieldErrors } from '@shared/field-errors/field-errors';
import { apiErrorMessage, apiFieldErrors } from '@store/app-state';
import { SessionStore } from '@store/session.store';
import { toast } from 'ngx-sonner';
import { firstValueFrom } from 'rxjs';
import {
  CHANNEL_META,
  CHANNELS,
  emailLayout,
  EVENT_META,
  EVENT_TYPES,
  NOVU_PAYLOAD,
  NOVU_WORKFLOW_PREFIX,
  novuWorkflowId,
  placeholdersOf,
  renderTemplate,
  SAMPLE_MODEL,
} from '../template-meta';

interface TemplateModel {
  code: string;
  eventType: NotificationEventType | '';
  channel: NotificationChannel | '';
  locale: string;
  subjectTemplate: string;
  bodyTemplate: string;
  active: boolean;
  systemDefault: boolean;
}

/** Same rules as NotificationTemplateServiceImpl.check. */
const CODE = /^[A-Za-z0-9][A-Za-z0-9_.-]{0,99}$/;
const LOCALE = /^[A-Za-z]{2,3}([-_][A-Za-z0-9]{2,8})*$/;

/**
 * Views, creates and edits one notification template, with a rendered preview. Organisation users
 * edit only their organisation's templates; platform defaults are read-only and can be customised
 * (copied into an organisation template that takes precedence).
 */
@Component({
  selector: 'app-notification-template-editor',
  imports: [DatePipe, FieldErrors, FormField, FormRoot, RouterLink],
  templateUrl: './notification-template-editor.html',
  styles: `
    .preview-frame {
      inline-size: 100%;
      block-size: 520px;
      border: 0;
      border-radius: var(--radius-control);
      background: #fff;
    }
    .preview-text {
      margin: 0;
      padding: 0.75rem;
      border-radius: var(--radius-control);
      background: var(--color-obsidian);
      white-space: pre-wrap;
      word-break: break-word;
      font: inherit;
    }
    .body-editor {
      min-block-size: 280px;
      font-family: var(--font-mono, monospace);
    }
  `,
})
export class NotificationTemplateEditor implements OnInit {
  private readonly templateApi = inject(NotificationTemplateApi);
  protected readonly session = inject(SessionStore);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly sanitizer = inject(DomSanitizer);

  protected readonly Math = Math;
  protected readonly eventTypes = EVENT_TYPES;
  protected readonly channels = CHANNELS;
  protected readonly eventMeta = EVENT_META;
  protected readonly channelMeta = CHANNEL_META;
  protected readonly novuPrefix = NOVU_WORKFLOW_PREFIX;

  /** The saved template (null while creating). */
  protected readonly template = signal<NotificationTemplateDTO | null>(null);
  /** The platform template being customised (?from=). */
  protected readonly source = signal<NotificationTemplateDTO | null>(null);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly deleting = signal(false);

  /** Body rendered by the API (POST /notification-templates/preview), when asked for. */
  protected readonly serverPreview = signal<string | null>(null);
  protected readonly serverPreviewing = signal(false);

  private readonly permissions = computed(() => this.session.permissions());
  protected readonly creating = computed(() => !this.template());
  /** Organisation the template belongs to; null for platform templates. */
  protected readonly ownerId = computed(() =>
    this.creating() ? this.session.organisationId() : (this.template()?.organisationId ?? null),
  );
  protected readonly isPlatformTemplate = computed(() => !this.ownerId());
  /** Tenant users change only their own organisation's templates; platform admins (no organisation) any. */
  protected readonly canEdit = computed(
    () =>
      hasPermission(['notification-templates:edit'], this.permissions()) &&
      (!this.session.organisationId() || this.ownerId() === this.session.organisationId()),
  );
  protected readonly canCustomise = computed(
    () =>
      !this.creating() &&
      this.isPlatformTemplate() &&
      !!this.session.organisationId() &&
      hasPermission(['notification-templates:edit'], this.permissions()),
  );
  protected readonly canDelete = computed(
    () =>
      !this.creating() &&
      this.canEdit() &&
      !this.template()?.systemDefault &&
      hasPermission(['notification-templates:delete'], this.permissions()),
  );
  protected readonly canServerPreview = computed(() =>
    hasPermission(['notification-templates:view'], this.permissions()),
  );

  protected readonly model = signal<TemplateModel>(toModel({ locale: 'en', active: true }));

  protected readonly templateForm = form(
    this.model,
    (path) => {
      disabled(path, { when: () => !this.canEdit() });
      required(path.code, { message: 'Give the template a code.' });
      pattern(path.code, CODE, { message: "Up to 100 letters, digits, '.', '-' or '_', starting with a letter or digit." });
      required(path.eventType, { message: 'Say which event the template is for.' });
      required(path.channel, { message: 'Say which channel the template is for.' });
      required(path.locale, { message: 'Give the locale, e.g. en or en-BW.' });
      pattern(path.locale, LOCALE, { message: 'Use a locale such as en or en-BW.' });
      validate(path.subjectTemplate, ({ value, valueOf }) =>
        valueOf(path.channel) === NotificationChannel.EMAIL && !value().trim()
          ? { kind: 'required', message: 'An email template needs a subject.' }
          : null,
      );
      maxLength(path.subjectTemplate, 255, { message: 'The subject is limited to 255 characters.' });
      required(path.bodyTemplate, { message: 'Write the body.' });
    },
    {
      submission: {
        action: async (field) => {
          try {
            const saved = await firstValueFrom(this.templateApi.saveTemplate(this.toRequest(this.model())));
            toast.success(this.creating() ? 'Template created' : 'Template saved', {
              description: saved.code ?? undefined,
            });
            if (this.creating()) {
              this.router.navigate(['/notification-templates', saved.id], { replaceUrl: true });
            } else {
              this.apply(saved);
            }
            return undefined;
          } catch (error) {
            const fieldErrors = apiFieldErrors(error);
            const mapped = (['code', 'eventType', 'channel', 'locale', 'subjectTemplate', 'bodyTemplate'] as const)
              .filter((key) => fieldErrors[key])
              .map((key) => ({ kind: 'server', message: fieldErrors[key], fieldTree: field[key] }));
            return mapped.length
              ? mapped
              : { kind: 'server', message: apiErrorMessage(error, 'The template could not be saved.') };
          }
        },
      },
    },
  );

  protected readonly serverError = computed(
    () =>
      this.templateForm()
        .errors()
        .find((error) => error.kind === 'server')?.message ?? null,
  );

  protected readonly selectedEvent = computed(() => {
    const event = this.model().eventType;
    return event ? EVENT_META[event] : null;
  });
  protected readonly novuWorkflow = computed(() => novuWorkflowId(this.model().eventType || null));
  protected readonly novuPayload = computed(() => {
    const channel = this.model().channel;
    return channel ? (NOVU_PAYLOAD[channel] ?? null) : null;
  });
  protected readonly channelLabel = computed(() => {
    const channel = this.model().channel;
    return channel ? CHANNEL_META[channel].label : '';
  });
  protected readonly isEmail = computed(() => this.model().channel === NotificationChannel.EMAIL);

  /** Placeholders used in the template that the event does not provide (they render as nothing). */
  protected readonly unknownPlaceholders = computed(() => {
    const known = this.selectedEvent()?.variables;
    if (!known) {
      return [];
    }
    const m = this.model();
    return placeholdersOf(`${m.subjectTemplate}\n${m.bodyTemplate}`).filter((name) => !known.includes(name.split('.')[0]));
  });

  protected readonly renderedSubject = computed(() =>
    renderTemplate(this.model().subjectTemplate, SAMPLE_MODEL, false),
  );
  private readonly renderedBody = computed(
    () => this.serverPreview() ?? renderTemplate(this.model().bodyTemplate, SAMPLE_MODEL, this.isEmail()),
  );
  protected readonly renderedText = computed(() => this.renderedBody());
  /**
   * Email bodies are HTML: shown in a sandboxed frame (no scripts, no same origin), inside the default
   * e-mail layout (logo header and footer) the API sends them in, unless the body is a whole document.
   */
  protected readonly renderedHtml = computed<SafeHtml>(() => {
    const body = this.renderedBody();
    const isHtml = /<[a-z][^>]*>/i.test(body);
    const content = isHtml ? body : `<div style="white-space:pre-line">${body}</div>`;
    return this.sanitizer.bypassSecurityTrustHtml(emailLayout(content, this.renderedSubject() ?? '', SAMPLE_MODEL));
  });

  constructor() {
    // Any edit makes the server-rendered preview stale.
    effect(() => {
      this.model();
      this.serverPreview.set(null);
    });
    // While creating, suggest a code from the event and channel until the user types one.
    effect(() => {
      const { eventType, channel } = this.model();
      if (!this.creating() || this.source() || this.templateForm.code().dirty() || !eventType || !channel) {
        return;
      }
      const suggested = `${eventType.toLowerCase().replaceAll('_', '-')}.${channel.toLowerCase().replaceAll('_', '-')}`;
      if (this.model().code !== suggested) {
        this.model.update((m) => ({ ...m, code: suggested }));
      }
    });
  }

  ngOnInit(): void {
    void this.load();
  }

  protected async load(): Promise<void> {
    const id = this.route.snapshot.paramMap.get('id');
    const from = this.route.snapshot.queryParamMap.get('from');
    this.loading.set(true);
    this.loadError.set(null);
    try {
      if (id) {
        this.apply(await firstValueFrom(this.templateApi.findTemplateById(id)));
      } else if (from) {
        const source = await firstValueFrom(this.templateApi.findTemplateById(from));
        this.source.set(source);
        this.model.set(toModel({ ...source, systemDefault: false, active: true }));
      }
    } catch (error) {
      this.loadError.set(apiErrorMessage(error, 'Unable to load the template.'));
    } finally {
      this.loading.set(false);
    }
  }

  protected insertPlaceholder(name: string, textarea: HTMLTextAreaElement): void {
    if (!this.canEdit()) {
      return;
    }
    const token = `{{${name}}}`;
    const body = this.model().bodyTemplate;
    const start = textarea.selectionStart ?? body.length;
    const end = textarea.selectionEnd ?? body.length;
    this.model.update((m) => ({ ...m, bodyTemplate: body.slice(0, start) + token + body.slice(end) }));
    this.templateForm.bodyTemplate().markAsDirty();
    queueMicrotask(() => {
      textarea.focus();
      textarea.setSelectionRange(start + token.length, start + token.length);
    });
  }

  protected async renderOnServer(): Promise<void> {
    const model = this.model();
    if (!model.bodyTemplate.trim()) {
      toast.error('Write the body first.');
      return;
    }
    this.serverPreviewing.set(true);
    try {
      const rendered = await firstValueFrom(this.templateApi.previewTemplate(this.toRequest(model), SAMPLE_MODEL));
      this.serverPreview.set(rendered ?? '');
    } catch (error) {
      toast.error(apiErrorMessage(error, 'The API could not render the preview.'), {
        description: 'The preview shown is rendered in the browser with the same rules.',
      });
    } finally {
      this.serverPreviewing.set(false);
    }
  }

  protected async remove(): Promise<void> {
    const template = this.template();
    if (!template?.id || !confirm(`Delete the template ${template.code}? This cannot be undone.`)) {
      return;
    }
    this.deleting.set(true);
    try {
      await firstValueFrom(this.templateApi.removeTemplate(template.id));
      toast.success('Template deleted', { description: template.code ?? undefined });
      this.router.navigate(['/notification-templates']);
    } catch (error) {
      toast.error(apiErrorMessage(error, 'The template could not be deleted.'));
    } finally {
      this.deleting.set(false);
    }
  }

  private apply(template: NotificationTemplateDTO): void {
    this.template.set(template);
    this.model.set(toModel(template));
    this.templateForm().reset();
  }

  private toRequest(model: TemplateModel): NotificationTemplateDTO {
    const current = this.template();
    return {
      ...(current ?? {}),
      organisationId: this.ownerId(),
      code: model.code.trim(),
      eventType: model.eventType || null,
      channel: model.channel || null,
      locale: model.locale.trim(),
      subjectTemplate: model.subjectTemplate.trim() || null,
      bodyTemplate: model.bodyTemplate,
      active: model.active,
      systemDefault: this.isPlatformTemplate() ? model.systemDefault : false,
    };
  }
}

function toModel(template: NotificationTemplateDTO): TemplateModel {
  return {
    code: template.code ?? '',
    eventType: template.eventType ?? '',
    channel: template.channel ?? '',
    locale: template.locale ?? 'en',
    subjectTemplate: template.subjectTemplate ?? '',
    bodyTemplate: template.bodyTemplate ?? '',
    active: template.active ?? true,
    systemDefault: template.systemDefault ?? false,
  };
}
