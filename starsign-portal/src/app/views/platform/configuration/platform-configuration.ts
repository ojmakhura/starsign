import { DatePipe } from '@angular/common';
import { Component, computed, inject, OnInit, signal } from '@angular/core';
import {
  disabled,
  form,
  FormField,
  FormRoot,
  max,
  maxLength,
  min,
  pattern,
  readonly,
  required,
  validate,
} from '@angular/forms/signals';
import { RouterLink } from '@angular/router';
import { Can } from '@auth/can.directive';
import { hasPermission } from '@auth/page-permissions';
import {
  ConfigurationValueType,
  SecurityPolicyDTO,
  SystemConfigurationDTO,
} from '@models/platform';
import { SignatureAssuranceLevel } from '@models/signing';
import { PlatformConfigurationApi } from '@services/platform/platform-configuration-api';
import { FieldErrors } from '@shared/field-errors/field-errors';
import { apiErrorMessage } from '@store/app-state';
import { SessionStore } from '@store/session.store';
import { toast } from 'ngx-sonner';
import { firstValueFrom } from 'rxjs';
import { humanise } from '../platform-format';

/** The API shows sensitive values as this; saving it back keeps the stored value. */
const REDACTED = '********';

interface SettingModel {
  configKey: string;
  category: string;
  valueType: ConfigurationValueType;
  configValue: string;
  description: string;
  sensitive: boolean;
}

interface PolicyModel {
  invitationTokenBytes: number | null;
  maxInvitationValidityHours: number | null;
  accessTokenTtlMinutes: number | null;
  otpLength: number | null;
  otpTtlMinutes: number | null;
  maxOtpAttempts: number | null;
  signingRateLimitPerMinute: number | null;
  apiRateLimitPerMinute: number | null;
  maxUploadSizeMb: number | null;
  malwareScanRequired: boolean;
  sanitisePdfOnUpload: boolean;
  rejectEncryptedPdf: boolean;
  rejectJavascriptPdf: boolean;
  auditSealIntervalMinutes: number | null;
  ipAddressCaptureEnabled: boolean;
  minimumAssuranceLevel: SignatureAssuranceLevel;
  requireMfaForAdmins: boolean;
}

/** Numeric security-policy fields with the ranges PlatformConfigurationServiceImpl enforces. */
const POLICY_NUMBERS: {
  key: keyof PolicyModel;
  label: string;
  unit: string;
  min: number;
  max: number;
}[] = [
  { key: 'otpLength', label: 'One-time password length', unit: 'digits', min: 4, max: 10 },
  { key: 'otpTtlMinutes', label: 'One-time password validity', unit: 'minutes', min: 1, max: 60 },
  { key: 'maxOtpAttempts', label: 'One-time password attempts', unit: 'attempts', min: 1, max: 20 },
  { key: 'accessTokenTtlMinutes', label: 'Signing access token lifetime', unit: 'minutes', min: 1, max: 1440 },
  { key: 'invitationTokenBytes', label: 'Invitation token randomness', unit: 'bytes', min: 16, max: 128 },
  { key: 'maxInvitationValidityHours', label: 'Longest invitation validity', unit: 'hours', min: 1, max: 8760 },
  { key: 'signingRateLimitPerMinute', label: 'Signing rate limit', unit: 'per minute', min: 1, max: 10000 },
  { key: 'apiRateLimitPerMinute', label: 'API rate limit', unit: 'per minute', min: 1, max: 100000 },
  { key: 'maxUploadSizeMb', label: 'Largest upload', unit: 'MB', min: 1, max: 1024 },
  { key: 'auditSealIntervalMinutes', label: 'Audit trail sealing interval', unit: 'minutes', min: 1, max: 10080 },
];

const POLICY_FLAGS: { key: keyof PolicyModel; label: string; hint: string }[] = [
  { key: 'requireMfaForAdmins', label: 'Require MFA for administrators', hint: 'Organisation and platform administrators.' },
  { key: 'malwareScanRequired', label: 'Require a malware scan on upload', hint: 'Blocks uploads when no scanner is configured.' },
  { key: 'sanitisePdfOnUpload', label: 'Sanitise PDFs on upload', hint: 'Strips active content from uploaded PDFs.' },
  { key: 'rejectEncryptedPdf', label: 'Reject encrypted PDFs', hint: 'Encrypted documents cannot be stamped or sealed.' },
  { key: 'rejectJavascriptPdf', label: 'Reject PDFs with JavaScript', hint: 'Refuses documents that embed scripts.' },
  { key: 'ipAddressCaptureEnabled', label: 'Capture signer IP addresses', hint: 'Recorded in the signing evidence.' },
];

/**
 * Platform configuration: system settings (GET/POST /admin/configuration) and the
 * security policy every tenant runs under (GET/PUT /admin/configuration/security-policy).
 */
@Component({
  selector: 'app-platform-configuration',
  imports: [Can, DatePipe, FieldErrors, FormField, FormRoot, RouterLink],
  templateUrl: './platform-configuration.html',
})
export class PlatformConfiguration implements OnInit {
  private readonly configurationApi = inject(PlatformConfigurationApi);
  private readonly session = inject(SessionStore);

  protected readonly humanise = humanise;
  protected readonly redacted = REDACTED;
  protected readonly valueTypes = Object.values(ConfigurationValueType);
  protected readonly assuranceLevels = Object.values(SignatureAssuranceLevel);
  protected readonly policyNumbers = POLICY_NUMBERS;
  protected readonly policyFlags = POLICY_FLAGS;

  protected readonly canEdit = computed(() =>
    hasPermission(['admin-configuration:edit'], this.session.permissions()),
  );
  protected readonly canConfigure = computed(() =>
    hasPermission(['admin-configuration:configure'], this.session.permissions()),
  );

  // ---- Settings ----
  protected readonly settings = signal<SystemConfigurationDTO[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly category = signal<string>('');
  protected readonly search = signal('');

  protected readonly categories = computed(() =>
    [...new Set(this.settings().map((s) => s.category ?? '').filter(Boolean))].sort(),
  );
  protected readonly visibleSettings = computed(() => {
    const category = this.category();
    const text = this.search().trim().toLowerCase();
    return this.settings().filter(
      (s) =>
        (!category || s.category === category) &&
        (!text ||
          s.configKey?.toLowerCase().includes(text) ||
          s.description?.toLowerCase().includes(text)),
    );
  });

  /** The setting being edited; null with `editorOpen` for a new one. */
  protected readonly editing = signal<SystemConfigurationDTO | null>(null);
  protected readonly editorOpen = signal(false);
  protected readonly settingModel = signal<SettingModel>(this.emptySetting());
  protected readonly settingForm = form(
    this.settingModel,
    (path) => {
      required(path.configKey, { message: 'Give the key.' });
      pattern(path.configKey, /^[a-z0-9]+([.-][a-z0-9]+)*$/, {
        message: "Lower-case words separated by '.' or '-', e.g. organisations.auto-activate.",
      });
      maxLength(path.configKey, 150);
      readonly(path.configKey, () => this.editing() !== null);
      required(path.category, { message: 'Give the category.' });
      maxLength(path.description, 500);
      readonly(path.sensitive, () => !!this.editing()?.sensitive);
      validate(path.configValue, ({ value, valueOf }) => {
        const v = value().trim();
        const type = valueOf(path.valueType);
        if (v === REDACTED && this.editing()?.sensitive) {
          return null;
        }
        if (!v && type !== ConfigurationValueType.STRING) {
          return { kind: 'required', message: 'Give the value.' };
        }
        switch (type) {
          case ConfigurationValueType.INTEGER:
            return /^-?\d+$/.test(v) ? null : { kind: 'type', message: 'The value must be a whole number.' };
          case ConfigurationValueType.BOOLEAN:
            return /^(true|false)$/i.test(v) ? null : { kind: 'type', message: 'The value must be true or false.' };
          case ConfigurationValueType.DURATION:
            return /^-?P(?!$)(\d+(\.\d+)?D)?(T(?=\d)(\d+(\.\d+)?H)?(\d+(\.\d+)?M)?(\d+(\.\d+)?S)?)?$/i.test(v)
              ? null
              : { kind: 'type', message: 'An ISO-8601 duration, e.g. PT15M or P1D.' };
          case ConfigurationValueType.JSON:
            try {
              JSON.parse(v);
              return null;
            } catch {
              return { kind: 'type', message: 'The value is not valid JSON.' };
            }
          default:
            return null;
        }
      });
    },
    {
      submission: {
        action: async () => {
          const m = this.settingModel();
          const editing = this.editing();
          try {
            const saved = await firstValueFrom(
              this.configurationApi.save({
                ...(editing ?? {}),
                configKey: m.configKey.trim().toLowerCase(),
                category: m.category.trim().toLowerCase(),
                valueType: m.valueType,
                configValue: m.configValue,
                description: m.description.trim() || null,
                sensitive: m.sensitive,
              }),
            );
            toast.success('Setting saved', { description: saved.configKey ?? undefined });
            this.editorOpen.set(false);
            await this.loadSettings();
            return undefined;
          } catch (error) {
            return { kind: 'server', message: apiErrorMessage(error, 'The setting could not be saved.') };
          }
        },
      },
    },
  );
  protected readonly settingServerError = computed(
    () =>
      this.settingForm()
        .errors()
        .find((error) => error.kind === 'server')?.message ?? null,
  );

  // ---- Security policy ----
  protected readonly policy = signal<SecurityPolicyDTO | null>(null);
  protected readonly policyError = signal<string | null>(null);
  protected readonly policyModel = signal<PolicyModel>({
    invitationTokenBytes: null,
    maxInvitationValidityHours: null,
    accessTokenTtlMinutes: null,
    otpLength: null,
    otpTtlMinutes: null,
    maxOtpAttempts: null,
    signingRateLimitPerMinute: null,
    apiRateLimitPerMinute: null,
    maxUploadSizeMb: null,
    malwareScanRequired: false,
    sanitisePdfOnUpload: false,
    rejectEncryptedPdf: false,
    rejectJavascriptPdf: false,
    auditSealIntervalMinutes: null,
    ipAddressCaptureEnabled: false,
    minimumAssuranceLevel: SignatureAssuranceLevel.ELECTRONIC_BASIC,
    requireMfaForAdmins: false,
  });
  protected readonly policyForm = form(
    this.policyModel,
    (path) => {
      for (const field of POLICY_NUMBERS) {
        const fieldPath = path[field.key] as Parameters<typeof min>[0];
        const message = `${field.label}: ${field.min} to ${field.max} ${field.unit}.`;
        required(fieldPath, { message });
        min(fieldPath, field.min, { message });
        max(fieldPath, field.max, { message });
      }
      disabled(path, () => !this.canConfigure());
    },
    {
      submission: {
        action: async () => {
          try {
            const saved = await firstValueFrom(
              this.configurationApi.updateSecurityPolicy({
                ...(this.policy() ?? {}),
                ...this.policyModel(),
              }),
            );
            this.setPolicy(saved);
            toast.success('Security policy saved');
            return undefined;
          } catch (error) {
            return {
              kind: 'server',
              message: apiErrorMessage(error, 'The security policy could not be saved.'),
            };
          }
        },
      },
    },
  );
  protected readonly policyServerError = computed(
    () =>
      this.policyForm()
        .errors()
        .find((error) => error.kind === 'server')?.message ?? null,
  );

  ngOnInit(): void {
    void this.loadSettings();
    void this.loadPolicy();
  }

  protected async loadSettings(): Promise<void> {
    this.loading.set(true);
    this.error.set(null);
    try {
      this.settings.set(await firstValueFrom(this.configurationApi.findAll('all')));
    } catch (error) {
      this.error.set(apiErrorMessage(error, 'Unable to load the configuration.'));
    } finally {
      this.loading.set(false);
    }
  }

  protected create(): void {
    this.editing.set(null);
    this.settingModel.set(this.emptySetting());
    this.settingForm().reset();
    this.editorOpen.set(true);
  }

  protected edit(setting: SystemConfigurationDTO): void {
    this.editing.set(setting);
    this.settingModel.set({
      configKey: setting.configKey ?? '',
      category: setting.category ?? '',
      valueType: setting.valueType ?? ConfigurationValueType.STRING,
      configValue: setting.configValue ?? '',
      description: setting.description ?? '',
      sensitive: !!setting.sensitive,
    });
    this.settingForm().reset();
    this.editorOpen.set(true);
  }

  protected resetPolicy(): void {
    const policy = this.policy();
    if (policy) {
      this.setPolicy(policy);
    }
  }

  private async loadPolicy(): Promise<void> {
    this.policyError.set(null);
    try {
      this.setPolicy(await firstValueFrom(this.configurationApi.getSecurityPolicy()));
    } catch (error) {
      this.policyError.set(apiErrorMessage(error, 'Unable to load the security policy.'));
    }
  }

  private setPolicy(policy: SecurityPolicyDTO): void {
    this.policy.set(policy);
    this.policyModel.update((model) => {
      const next = { ...model };
      for (const key of Object.keys(model) as (keyof PolicyModel)[]) {
        const value = policy[key];
        if (value !== null && value !== undefined) {
          (next as Record<string, unknown>)[key] = value;
        }
      }
      return next;
    });
    this.policyForm().reset();
  }

  private emptySetting(): SettingModel {
    return {
      configKey: '',
      category: this.category?.() || '',
      valueType: ConfigurationValueType.STRING,
      configValue: '',
      description: '',
      sensitive: false,
    };
  }
}
