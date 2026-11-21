import { Component, computed, inject, OnInit, signal } from '@angular/core';
import {
  disabled,
  email,
  form,
  FormField,
  FormRoot,
  max,
  maxLength,
  min,
  required,
  validate,
} from '@angular/forms/signals';
import { RouterLink } from '@angular/router';
import { hasPermission } from '@auth/page-permissions';
import { OrganisationNotificationSettingsDTO } from '@models/organisation';
import { OrganisationApi } from '@services/organisation/organisation-api';
import { FieldErrors } from '@shared/field-errors/field-errors';
import { apiErrorMessage, apiFieldErrors } from '@store/app-state';
import { SessionStore } from '@store/session.store';
import { toast } from 'ngx-sonner';
import { firstValueFrom } from 'rxjs';

interface SettingsModel {
  emailEnabled: boolean;
  smsEnabled: boolean;
  inAppEnabled: boolean;
  senderDisplayName: string;
  replyToEmail: string;
  remindersEnabled: boolean;
  reminderBeforeDeadlineHours: number;
  reminderAfterInactivityHours: number;
  reminderRepeatIntervalHours: number;
  maxReminders: number;
  escalateToOwner: boolean;
  escalateAfterReminders: number;
}

/** Limits of OrganisationServiceImpl.checkNotificationSettings. */
const MAX_HOURS = 720;
const MAX_REMINDERS = 20;

/** Channels, sender details, reminders and escalation for the organisation's notifications. */
@Component({
  selector: 'app-notification-settings',
  imports: [FieldErrors, FormField, FormRoot, RouterLink],
  templateUrl: './notification-settings.html',
})
export class NotificationSettings implements OnInit {
  private readonly organisationApi = inject(OrganisationApi);
  private readonly session = inject(SessionStore);

  protected readonly settings = signal<OrganisationNotificationSettingsDTO | null>(null);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly maxHours = MAX_HOURS;
  protected readonly maxRemindersLimit = MAX_REMINDERS;

  protected readonly canEdit = computed(() =>
    hasPermission(['organisations:configure'], this.session.permissions()),
  );
  protected readonly canSeeTemplates = computed(() =>
    hasPermission(['notification-templates:list'], this.session.permissions()),
  );

  protected readonly model = signal<SettingsModel>(toModel({}));

  protected readonly settingsForm = form(
    this.model,
    (path) => {
      disabled(path, { when: () => !this.canEdit() });
      maxLength(path.senderDisplayName, 100, { message: 'The sender name is limited to 100 characters.' });
      email(path.replyToEmail, { message: 'The reply-to address is not a valid email address.' });
      validate(path.emailEnabled, ({ valueOf }) =>
        valueOf(path.emailEnabled) || valueOf(path.smsEnabled)
          ? null
          : { kind: 'channels', message: 'Keep email or SMS enabled: signatories are invited through them.' },
      );
      for (const [hours, label] of [
        [path.reminderBeforeDeadlineHours, 'The reminder before the deadline'],
        [path.reminderAfterInactivityHours, 'The reminder after inactivity'],
        [path.reminderRepeatIntervalHours, 'The interval between reminders'],
      ] as const) {
        required(hours, { message: 'Give the number of hours.' });
        min(hours, 1, { message: `${label} must be 1 to ${MAX_HOURS} hours.` });
        max(hours, MAX_HOURS, { message: `${label} must be 1 to ${MAX_HOURS} hours.` });
      }
      required(path.maxReminders, { message: 'Give the number of reminders.' });
      min(path.maxReminders, 0, { message: `Send 0 to ${MAX_REMINDERS} reminders.` });
      max(path.maxReminders, MAX_REMINDERS, { message: `Send 0 to ${MAX_REMINDERS} reminders.` });
      validate(path.escalateAfterReminders, ({ value, valueOf }) => {
        if (!valueOf(path.escalateToOwner)) {
          return null;
        }
        const limit = Math.max(valueOf(path.maxReminders) ?? 0, 1);
        return value() >= 1 && value() <= limit
          ? null
          : { kind: 'escalation', message: `Escalate after 1 to ${limit} reminders.` };
      });
    },
    {
      submission: {
        action: async (field) => {
          const organisationId = this.session.organisationId();
          if (!organisationId) {
            return { kind: 'server', message: 'Your account is not linked to an organisation.' };
          }
          try {
            const saved = await firstValueFrom(
              this.organisationApi.updateNotificationSettings(organisationId, this.toRequest(this.model())),
            );
            this.apply(saved);
            toast.success('Notification settings saved');
            return undefined;
          } catch (error) {
            const fieldErrors = apiFieldErrors(error);
            const mapped = (['senderDisplayName', 'replyToEmail'] as const)
              .filter((key) => fieldErrors[key])
              .map((key) => ({ kind: 'server', message: fieldErrors[key], fieldTree: field[key] }));
            return mapped.length
              ? mapped
              : { kind: 'server', message: apiErrorMessage(error, 'The settings could not be saved.') };
          }
        },
      },
    },
  );

  protected readonly serverError = computed(
    () =>
      this.settingsForm()
        .errors()
        .find((error) => error.kind === 'server')?.message ?? null,
  );

  /** A plain-language timeline of the reminders a pending signatory gets. */
  protected readonly reminderSummary = computed(() => {
    const m = this.model();
    if (!m.remindersEnabled || m.maxReminders === 0) {
      return 'Signatories get no automatic reminders.';
    }
    const parts = [
      `First reminder after ${hours(m.reminderAfterInactivityHours)} without activity`,
      `then every ${hours(m.reminderRepeatIntervalHours)}`,
      `up to ${m.maxReminders} ${m.maxReminders === 1 ? 'reminder' : 'reminders'}`,
    ];
    let text = parts.join(', ') + `; one more ${hours(m.reminderBeforeDeadlineHours)} before the workflow expires.`;
    if (m.escalateToOwner) {
      text += ` The workflow owner is told after ${m.escalateAfterReminders} unanswered ${m.escalateAfterReminders === 1 ? 'reminder' : 'reminders'}.`;
    }
    return text;
  });

  ngOnInit(): void {
    void this.load();
  }

  protected async load(): Promise<void> {
    const organisationId = this.session.organisationId();
    if (!organisationId) {
      this.loading.set(false);
      return;
    }
    this.loading.set(true);
    this.loadError.set(null);
    try {
      this.apply(await firstValueFrom(this.organisationApi.getNotificationSettings(organisationId)));
    } catch (error) {
      this.loadError.set(apiErrorMessage(error, 'Unable to load the notification settings.'));
    } finally {
      this.loading.set(false);
    }
  }

  private apply(settings: OrganisationNotificationSettingsDTO): void {
    this.settings.set(settings);
    this.model.set(toModel(settings));
    this.settingsForm().reset();
  }

  /** Blank text fields are sent as '' so the API clears them (null would keep the old value). */
  private toRequest(model: SettingsModel): OrganisationNotificationSettingsDTO {
    return {
      ...model,
      senderDisplayName: model.senderDisplayName.trim(),
      replyToEmail: model.replyToEmail.trim(),
    };
  }
}

function hours(value: number): string {
  if (value % 24 === 0) {
    const days = value / 24;
    return `${days} ${days === 1 ? 'day' : 'days'}`;
  }
  return `${value} ${value === 1 ? 'hour' : 'hours'}`;
}

function toModel(settings: OrganisationNotificationSettingsDTO): SettingsModel {
  return {
    emailEnabled: settings.emailEnabled ?? true,
    smsEnabled: settings.smsEnabled ?? false,
    inAppEnabled: settings.inAppEnabled ?? true,
    senderDisplayName: settings.senderDisplayName ?? '',
    replyToEmail: settings.replyToEmail ?? '',
    remindersEnabled: settings.remindersEnabled ?? true,
    reminderBeforeDeadlineHours: settings.reminderBeforeDeadlineHours ?? 24,
    reminderAfterInactivityHours: settings.reminderAfterInactivityHours ?? 48,
    reminderRepeatIntervalHours: settings.reminderRepeatIntervalHours ?? 48,
    maxReminders: settings.maxReminders ?? 3,
    escalateToOwner: settings.escalateToOwner ?? false,
    escalateAfterReminders: settings.escalateAfterReminders ?? 2,
  };
}
