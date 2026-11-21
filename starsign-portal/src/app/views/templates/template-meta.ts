import { SignatureAssuranceLevel, VerificationMethod } from '@models/signing';
import { SigningTemplateDTO, SigningTemplateSignatoryDTO, TemplateStatus } from '@models/template';
import { FieldType, SignatoryType } from '@models/workflow';

/** Chip tone of a template status (the shared status chip has no template statuses). */
export const templateTone = (status: TemplateStatus | null | undefined): string =>
  status === TemplateStatus.ACTIVE
    ? 'chip--progress'
    : status === TemplateStatus.ARCHIVED
      ? 'chip--voided'
      : 'chip--neutral';

export const SIGNATORY_TYPES: { value: SignatoryType; label: string }[] = [
  { value: SignatoryType.EXTERNAL_PERSON, label: 'External person' },
  { value: SignatoryType.EXTERNAL_ORGANISATION_REPRESENTATIVE, label: 'Organisation representative' },
  { value: SignatoryType.INTERNAL_USER, label: 'Internal user' },
];

export const VERIFICATION_METHODS: { value: VerificationMethod; label: string }[] = [
  { value: VerificationMethod.EMAIL_LINK, label: 'Email link' },
  { value: VerificationMethod.EMAIL_OTP, label: 'Email one-time code' },
  { value: VerificationMethod.SMS_OTP, label: 'SMS one-time code' },
  { value: VerificationMethod.EXTERNAL_IDENTITY_PROVIDER, label: 'External identity provider' },
  { value: VerificationMethod.INTERNAL_ACCOUNT, label: 'Own StarSign account (internal users)' },
];

export const ASSURANCE_LEVELS: { value: SignatureAssuranceLevel; label: string }[] = [
  { value: SignatureAssuranceLevel.ELECTRONIC_BASIC, label: 'Basic electronic' },
  { value: SignatureAssuranceLevel.ELECTRONIC_VERIFIED, label: 'Verified electronic' },
  { value: SignatureAssuranceLevel.ADVANCED_DIGITAL, label: 'Advanced digital (PAdES)' },
  { value: SignatureAssuranceLevel.QUALIFIED, label: 'Qualified' },
];

export const FIELD_TYPES: { value: FieldType; label: string }[] = [
  { value: FieldType.SIGNATURE, label: 'Signature' },
  { value: FieldType.INITIALS, label: 'Initials' },
  { value: FieldType.FULL_NAME, label: 'Full name' },
  { value: FieldType.DATE, label: 'Date' },
  { value: FieldType.TEXT, label: 'Text' },
  { value: FieldType.ORGANISATION, label: 'Organisation' },
  { value: FieldType.JOB_TITLE, label: 'Job title' },
  { value: FieldType.CHECKBOX, label: 'Checkbox' },
  { value: FieldType.RADIO, label: 'Radio buttons' },
  { value: FieldType.COMMENT, label: 'Comment' },
];

const labelOf = <T>(list: { value: T; label: string }[], value: T | null | undefined) =>
  list.find((item) => item.value === value)?.label ?? (value ? String(value).replaceAll('_', ' ') : '—');

export const signatoryTypeLabel = (value: SignatoryType | null | undefined) => labelOf(SIGNATORY_TYPES, value);
export const verificationLabel = (value: VerificationMethod | null | undefined) =>
  labelOf(VERIFICATION_METHODS, value);
export const assuranceLabel = (value: SignatureAssuranceLevel | null | undefined) =>
  labelOf(ASSURANCE_LEVELS, value);
export const fieldTypeLabel = (value: FieldType | null | undefined) => labelOf(FIELD_TYPES, value);

/** Roles grouped by stage, each stage in signing order. */
export function stagesOf(
  template: SigningTemplateDTO | null | undefined,
): { stageNumber: number; roles: SigningTemplateSignatoryDTO[] }[] {
  const stages = new Map<number, SigningTemplateSignatoryDTO[]>();
  for (const role of template?.signatories ?? []) {
    const stage = role.stageNumber ?? 1;
    stages.set(stage, [...(stages.get(stage) ?? []), role]);
  }
  return [...stages.entries()]
    .sort(([a], [b]) => a - b)
    .map(([stageNumber, roles]) => ({
      stageNumber,
      roles: roles.sort((a, b) => (a.signingOrder ?? 0) - (b.signingOrder ?? 0)),
    }));
}

/** Highest page a field of the template is placed on (0 when there are no fields). */
export const highestFieldPage = (template: SigningTemplateDTO | null | undefined): number =>
  Math.max(0, ...(template?.fields ?? []).map((field) => field.pageNumber ?? 0));

/**
 * What stops the template from being activated (mirrors the API's check): at least one
 * role, and a required signature field for every mandatory role.
 */
export function activationIssues(template: SigningTemplateDTO | null | undefined): string[] {
  const roles = template?.signatories ?? [];
  if (!roles.length) {
    return ['Add at least one signatory role.'];
  }
  return roles
    .filter(
      (role) =>
        role.mandatory !== false &&
        !(template?.fields ?? []).some(
          (field) =>
            field.signatoryRoleId === role.id &&
            field.fieldType === FieldType.SIGNATURE &&
            field.required !== false,
        ),
    )
    .map((role) => `${role.roleName} has no signature field.`);
}
