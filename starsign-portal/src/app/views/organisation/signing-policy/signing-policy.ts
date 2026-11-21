import { Component, computed, inject, OnInit, signal } from '@angular/core';
import {
  disabled,
  form,
  FormField,
  FormRoot,
  max,
  maxLength,
  min,
  required,
  validate,
} from '@angular/forms/signals';
import { hasPermission } from '@auth/page-permissions';
import { OrganisationSigningPolicyDTO } from '@models/organisation';
import { SignatureAssuranceLevel, VerificationMethod } from '@models/signing';
import { ExecutionMode } from '@models/workflow';
import { OrganisationApi } from '@services/organisation/organisation-api';
import { PlatformConfigurationApi } from '@services/platform/platform-configuration-api';
import { FieldErrors } from '@shared/field-errors/field-errors';
import { apiErrorMessage } from '@store/app-state';
import { SessionStore } from '@store/session.store';
import { toast } from 'ngx-sonner';
import { firstValueFrom } from 'rxjs';

interface PolicyModel {
  defaultExecutionMode: ExecutionMode;
  defaultExpiryDays: number;
  invitationValidityHours: number;
  singleUseInvitationLinks: boolean;
  defaultVerificationMethod: VerificationMethod;
  minimumAssuranceLevel: SignatureAssuranceLevel;
  allowDrawnSignature: boolean;
  allowTypedSignature: boolean;
  allowUploadedSignatureImage: boolean;
  allowSignatoryDecline: boolean;
  declineTerminatesWorkflow: boolean;
  requireDeclineReason: boolean;
  sendCompletedCopyToSignatories: boolean;
  digitalSignatureEnabled: boolean;
  certificateAlias: string;
  maxUploadSizeMb: number;
  documentRetentionDays: number;
  auditRetentionDays: number;
  consentText: string;
}

/** Limits of OrganisationServiceImpl.checkSigningPolicy when the platform sets none. */
const DEFAULT_MAX_INVITATION_HOURS = 8760;
const DEFAULT_MAX_UPLOAD_MB = 100;

const ASSURANCE_ORDER = [
  SignatureAssuranceLevel.ELECTRONIC_BASIC,
  SignatureAssuranceLevel.ELECTRONIC_VERIFIED,
  SignatureAssuranceLevel.ADVANCED_DIGITAL,
  SignatureAssuranceLevel.QUALIFIED,
];

/** How signing works by default: routing, expiry, verification, signature methods and retention. */
@Component({
  selector: 'app-signing-policy',
  imports: [FieldErrors, FormField, FormRoot],
  templateUrl: './signing-policy.html',
})
export class SigningPolicy implements OnInit {
  private readonly organisationApi = inject(OrganisationApi);
  private readonly platformApi = inject(PlatformConfigurationApi);
  private readonly session = inject(SessionStore);

  protected readonly policy = signal<OrganisationSigningPolicyDTO | null>(null);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);

  /** Platform limits; only readable with admin-configuration:view, else the API defaults apply. */
  protected readonly maxInvitationHours = signal(DEFAULT_MAX_INVITATION_HOURS);
  protected readonly maxUploadMb = signal(DEFAULT_MAX_UPLOAD_MB);
  protected readonly platformMinimumAssurance = signal<SignatureAssuranceLevel | null>(null);
  protected readonly platformLimitsKnown = signal(false);

  protected readonly canEdit = computed(() =>
    hasPermission(['organisations:configure'], this.session.permissions()),
  );

  protected readonly executionModes = [
    { value: ExecutionMode.SEQUENTIAL, label: 'Sequential', hint: 'Signatories sign one after another, in order.' },
    { value: ExecutionMode.PARALLEL, label: 'Parallel', hint: 'Everyone is invited at once and signs in any order.' },
  ];
  protected readonly verificationMethods = [
    { value: VerificationMethod.EMAIL_LINK, label: 'Email link only' },
    { value: VerificationMethod.EMAIL_OTP, label: 'One-time code by email' },
    { value: VerificationMethod.SMS_OTP, label: 'One-time code by SMS' },
    { value: VerificationMethod.EXTERNAL_IDENTITY_PROVIDER, label: 'External identity provider' },
    { value: VerificationMethod.INTERNAL_ACCOUNT, label: 'StarSign account sign-in' },
  ];
  protected readonly assuranceLevels = [
    { value: SignatureAssuranceLevel.ELECTRONIC_BASIC, label: 'Basic electronic', hint: 'Possession of the email address.' },
    { value: SignatureAssuranceLevel.ELECTRONIC_VERIFIED, label: 'Verified electronic', hint: 'Identity checked with a one-time code or identity provider.' },
    { value: SignatureAssuranceLevel.ADVANCED_DIGITAL, label: 'Advanced digital', hint: 'PAdES certificate signature on the PDF.' },
    { value: SignatureAssuranceLevel.QUALIFIED, label: 'Qualified', hint: 'Through an external qualified trust service.' },
  ];

  protected readonly model = signal<PolicyModel>(toModel({}));

  protected readonly policyForm = form(
    this.model,
    (path) => {
      disabled(path, { when: () => !this.canEdit() });
      required(path.defaultExpiryDays, { message: 'Give the number of days.' });
      min(path.defaultExpiryDays, 1, { message: 'Workflows expire after 1 to 365 days.' });
      max(path.defaultExpiryDays, 365, { message: 'Workflows expire after 1 to 365 days.' });
      required(path.invitationValidityHours, { message: 'Give the number of hours.' });
      min(path.invitationValidityHours, 1, { message: 'At least 1 hour.' });
      max(path.invitationValidityHours, () => this.maxInvitationHours(), {
        message: () => `Signing invitations are valid for 1 to ${this.maxInvitationHours()} hours.`,
      });
      required(path.maxUploadSizeMb, { message: 'Give the size in MB.' });
      min(path.maxUploadSizeMb, 1, { message: 'At least 1 MB.' });
      max(path.maxUploadSizeMb, () => this.maxUploadMb(), {
        message: () => `Uploads may be limited to 1 to ${this.maxUploadMb()} MB.`,
      });
      required(path.documentRetentionDays, { message: 'Give the number of days.' });
      min(path.documentRetentionDays, 1, { message: 'Retention periods must be at least one day.' });
      required(path.auditRetentionDays, { message: 'Give the number of days.' });
      min(path.auditRetentionDays, 1, { message: 'Retention periods must be at least one day.' });
      validate(path.auditRetentionDays, ({ value, valueOf }) =>
        value() < valueOf(path.documentRetentionDays)
          ? { kind: 'auditRetention', message: 'The audit trail must be kept at least as long as the documents.' }
          : null,
      );
      validate(path.minimumAssuranceLevel, ({ value }) => {
        const platform = this.platformMinimumAssurance();
        return platform && ASSURANCE_ORDER.indexOf(value()) < ASSURANCE_ORDER.indexOf(platform)
          ? { kind: 'assurance', message: `The platform requires at least ${this.assuranceLabel(platform)}.` }
          : null;
      });
      validate(path.allowDrawnSignature, ({ valueOf }) =>
        valueOf(path.allowDrawnSignature) || valueOf(path.allowTypedSignature) || valueOf(path.allowUploadedSignatureImage)
          ? null
          : { kind: 'methods', message: 'Allow at least one way to make a signature: drawn, typed or uploaded.' },
      );
      maxLength(path.certificateAlias, 100);
      maxLength(path.consentText, 4000);
    },
    {
      submission: {
        action: async () => {
          const organisationId = this.session.organisationId();
          if (!organisationId) {
            return { kind: 'server', message: 'Your account is not linked to an organisation.' };
          }
          try {
            const saved = await firstValueFrom(
              this.organisationApi.updateSigningPolicy(organisationId, this.toRequest(this.model())),
            );
            this.apply(saved);
            toast.success('Signing policy saved', {
              description: 'New workflows use these defaults; running workflows keep theirs.',
            });
            return undefined;
          } catch (error) {
            return { kind: 'server', message: apiErrorMessage(error, 'The signing policy could not be saved.') };
          }
        },
      },
    },
  );

  protected readonly serverError = computed(
    () =>
      this.policyForm()
        .errors()
        .find((error) => error.kind === 'server')?.message ?? null,
  );

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
    void this.loadPlatformLimits();
    try {
      this.apply(await firstValueFrom(this.organisationApi.getSigningPolicy(organisationId)));
    } catch (error) {
      this.loadError.set(apiErrorMessage(error, 'Unable to load the signing policy.'));
    } finally {
      this.loading.set(false);
    }
  }

  protected assuranceLabel(level: SignatureAssuranceLevel | null | undefined): string {
    return this.assuranceLevels.find((option) => option.value === level)?.label ?? String(level ?? '—');
  }

  protected assuranceBelowPlatform(level: SignatureAssuranceLevel): boolean {
    const platform = this.platformMinimumAssurance();
    return !!platform && ASSURANCE_ORDER.indexOf(level) < ASSURANCE_ORDER.indexOf(platform);
  }

  private async loadPlatformLimits(): Promise<void> {
    if (!hasPermission(['admin-configuration:view'], this.session.permissions())) {
      return;
    }
    try {
      const security = await firstValueFrom(this.platformApi.getSecurityPolicy());
      this.maxInvitationHours.set(security.maxInvitationValidityHours ?? DEFAULT_MAX_INVITATION_HOURS);
      this.maxUploadMb.set(security.maxUploadSizeMb ?? DEFAULT_MAX_UPLOAD_MB);
      this.platformMinimumAssurance.set(security.minimumAssuranceLevel ?? null);
      this.platformLimitsKnown.set(true);
    } catch {
      // Keep the defaults; the API still enforces the platform limits on save.
    }
  }

  private apply(policy: OrganisationSigningPolicyDTO): void {
    this.policy.set(policy);
    this.model.set(toModel(policy));
    this.policyForm().reset();
  }

  private toRequest(model: PolicyModel): OrganisationSigningPolicyDTO {
    return {
      ...model,
      certificateAlias: model.certificateAlias.trim(),
      consentText: model.consentText.trim(),
    };
  }
}

function toModel(policy: OrganisationSigningPolicyDTO): PolicyModel {
  return {
    defaultExecutionMode: policy.defaultExecutionMode ?? ExecutionMode.SEQUENTIAL,
    defaultExpiryDays: policy.defaultExpiryDays ?? 30,
    invitationValidityHours: policy.invitationValidityHours ?? 168,
    singleUseInvitationLinks: policy.singleUseInvitationLinks ?? false,
    defaultVerificationMethod: policy.defaultVerificationMethod ?? VerificationMethod.EMAIL_LINK,
    minimumAssuranceLevel: policy.minimumAssuranceLevel ?? SignatureAssuranceLevel.ELECTRONIC_BASIC,
    allowDrawnSignature: policy.allowDrawnSignature ?? true,
    allowTypedSignature: policy.allowTypedSignature ?? true,
    allowUploadedSignatureImage: policy.allowUploadedSignatureImage ?? true,
    allowSignatoryDecline: policy.allowSignatoryDecline ?? true,
    declineTerminatesWorkflow: policy.declineTerminatesWorkflow ?? true,
    requireDeclineReason: policy.requireDeclineReason ?? false,
    sendCompletedCopyToSignatories: policy.sendCompletedCopyToSignatories ?? true,
    digitalSignatureEnabled: policy.digitalSignatureEnabled ?? false,
    certificateAlias: policy.certificateAlias ?? '',
    maxUploadSizeMb: policy.maxUploadSizeMb ?? 25,
    documentRetentionDays: policy.documentRetentionDays ?? 2555,
    auditRetentionDays: policy.auditRetentionDays ?? 3650,
    consentText: policy.consentText ?? '',
  };
}
