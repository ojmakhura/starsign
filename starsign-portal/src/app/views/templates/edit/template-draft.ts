import {
  applyEach,
  hidden,
  max,
  maxLength,
  min,
  required,
  schema,
  validate,
  validateTree,
} from '@angular/forms/signals';
import { SignatureAssuranceLevel, VerificationMethod } from '@models/signing';
import { SigningTemplateDTO, SigningTemplateFieldDTO, SigningTemplateSignatoryDTO } from '@models/template';
import { ExecutionMode, FieldType, SignatoryType } from '@models/workflow';

/** A signatory placeholder. `key` links fields to the role before it has a server id. */
export interface RoleRow {
  key: string;
  id: string;
  roleName: string;
  stageNumber: number;
  signatoryType: SignatoryType;
  /** Blank = the template's default (own account for internal users). */
  verificationMethod: VerificationMethod | '';
  mandatory: boolean;
  canDecline: boolean;
  colour: string;
}

/** A field placed for a role; position and size are percentages of the page. */
export interface FieldRow {
  id: string;
  roleKey: string;
  fieldType: FieldType;
  label: string;
  pageNumber: number;
  x: number;
  y: number;
  width: number;
  height: number;
  required: boolean;
  /** Comma-separated options of a radio field. */
  options: string;
}

export interface TemplateDraft {
  name: string;
  description: string;
  message: string;
  executionMode: ExecutionMode;
  defaultExpiryDays: number;
  /** Blank = the organisation's signing policy decides. */
  assuranceLevel: SignatureAssuranceLevel | '';
  verificationMethod: VerificationMethod | '';
  baseDocumentId: string;
  pageCount: number | null;
  roles: RoleRow[];
  fields: FieldRow[];
}

export const MAX_ROLES = 25;
export const MAX_FIELDS = 200;

let nextKey = 0;
const newKey = () => `role-${++nextKey}`;

export const newRole = (stageNumber = 1, index = 0): RoleRow => ({
  key: newKey(),
  id: '',
  roleName: index ? `Signatory ${index + 1}` : '',
  stageNumber,
  signatoryType: SignatoryType.EXTERNAL_PERSON,
  verificationMethod: '',
  mandatory: true,
  canDecline: true,
  colour: '',
});

export const newField = (roleKey = '', fieldType = FieldType.SIGNATURE): FieldRow => ({
  id: '',
  roleKey,
  fieldType,
  label: '',
  pageNumber: 1,
  x: 10,
  y: 80,
  width: 30,
  height: 6,
  required: true,
  options: '',
});

export const newTemplateDraft = (): TemplateDraft => ({
  name: '',
  description: '',
  message: '',
  executionMode: ExecutionMode.SEQUENTIAL,
  defaultExpiryDays: 30,
  assuranceLevel: '',
  verificationMethod: '',
  baseDocumentId: '',
  pageCount: null,
  roles: [newRole()],
  fields: [],
});

const percent = (ratio: number | null | undefined) => Math.round((ratio ?? 0) * 10_000) / 100;
const ratio = (value: number) => Math.round(value * 100) / 10_000;

/** The editable copy of a stored template; roles are sorted by stage, then signing order. */
export function toTemplateDraft(template: SigningTemplateDTO): TemplateDraft {
  const roles: RoleRow[] = [...(template.signatories ?? [])]
    .sort(
      (a, b) =>
        (a.stageNumber ?? 1) - (b.stageNumber ?? 1) || (a.signingOrder ?? 0) - (b.signingOrder ?? 0),
    )
    .map((role) => ({
      key: newKey(),
      id: role.id ?? '',
      roleName: role.roleName ?? '',
      stageNumber: role.stageNumber ?? 1,
      signatoryType: role.signatoryType ?? SignatoryType.EXTERNAL_PERSON,
      verificationMethod: role.verificationMethod ?? '',
      mandatory: role.mandatory !== false,
      canDecline: role.canDecline !== false,
      colour: role.colour ?? '',
    }));
  const keyOf = (roleId?: string | null) => roles.find((role) => role.id === roleId)?.key ?? '';
  return {
    name: template.name ?? '',
    description: template.description ?? '',
    message: template.message ?? '',
    executionMode: template.executionMode ?? ExecutionMode.SEQUENTIAL,
    defaultExpiryDays: template.defaultExpiryDays ?? 30,
    assuranceLevel: template.assuranceLevel ?? '',
    verificationMethod: template.verificationMethod ?? '',
    baseDocumentId: template.baseDocumentId ?? '',
    pageCount: template.pageCount ?? null,
    roles,
    fields: (template.fields ?? []).map((field) => ({
      id: field.id ?? '',
      roleKey: keyOf(field.signatoryRoleId),
      fieldType: field.fieldType ?? FieldType.SIGNATURE,
      label: field.label ?? '',
      pageNumber: field.pageNumber ?? 1,
      x: percent(field.xRatio),
      y: percent(field.yRatio),
      width: percent(field.widthRatio),
      height: percent(field.heightRatio),
      required: field.required !== false,
      options: (field.options ?? []).join(', '),
    })),
  };
}

/**
 * The request body. Roles and fields are sent in full (the API replaces both lists);
 * a field names an existing role by id and a new one by its name. Signing order
 * within a stage follows the list order (the API numbers it).
 */
export function toTemplateDTO(draft: TemplateDraft, original?: SigningTemplateDTO | null): SigningTemplateDTO {
  const signatories: SigningTemplateSignatoryDTO[] = draft.roles.map((role) => ({
    id: role.id || null,
    roleName: role.roleName.trim(),
    stageNumber: role.stageNumber,
    signingOrder: null,
    signatoryType: role.signatoryType,
    mandatory: role.mandatory,
    canDecline: role.canDecline,
    verificationMethod: role.verificationMethod || null,
    colour: role.colour || null,
  }));
  const roleRef = (key: string) => {
    const role = draft.roles.find((r) => r.key === key);
    return role ? role.id || role.roleName.trim() : null;
  };
  const fields: SigningTemplateFieldDTO[] = draft.fields.map((field, index) => {
    const stored = original?.fields?.find((f) => f.id && f.id === field.id);
    return {
      // Keep the attributes this editor does not show.
      ...stored,
      id: field.id || null,
      signatoryRoleId: roleRef(field.roleKey),
      fieldType: field.fieldType,
      label: field.label.trim() || null,
      pageNumber: field.pageNumber,
      xRatio: ratio(field.x),
      yRatio: ratio(field.y),
      widthRatio: ratio(field.width),
      heightRatio: ratio(field.height),
      required: field.fieldType === FieldType.SIGNATURE || field.required,
      options:
        field.fieldType === FieldType.RADIO
          ? field.options
              .split(',')
              .map((option) => option.trim())
              .filter(Boolean)
          : null,
      tabOrder: index + 1,
    };
  });
  return {
    name: draft.name.trim(),
    // Blank strings clear the text on update; null would keep the stored value.
    description: draft.description.trim(),
    message: draft.message.trim(),
    executionMode: draft.executionMode,
    defaultExpiryDays: draft.defaultExpiryDays,
    assuranceLevel: draft.assuranceLevel || null,
    verificationMethod: draft.verificationMethod || null,
    // A blank id clears the base document; null leaves it as it is.
    baseDocumentId: draft.baseDocumentId || (original?.baseDocumentId ? '' : null),
    pageCount: draft.pageCount || null,
    signatories,
    fields,
  };
}

export const templateDraftSchema = schema<TemplateDraft>((path) => {
  required(path.name, { message: 'Give the template a name.' });
  maxLength(path.name, 200);
  maxLength(path.description, 1000, { message: 'Keep the description under 1000 characters.' });
  maxLength(path.message, 2000, { message: 'Keep the message under 2000 characters.' });
  required(path.defaultExpiryDays, { message: 'How many days may a workflow stay open?' });
  min(path.defaultExpiryDays, 1, { message: 'At least one day.' });
  max(path.defaultExpiryDays, 365, { message: 'At most 365 days.' });
  min(path.pageCount, 1, { message: 'At least one page.' });

  validate(path.roles, ({ value }) => {
    if (!value().length) {
      return { kind: 'roles', message: 'Add at least one signatory role.' };
    }
    return value().length > MAX_ROLES
      ? { kind: 'roles', message: `A template can have at most ${MAX_ROLES} roles.` }
      : null;
  });

  // Role names identify the placeholders: flag each duplicate on its own name field.
  validateTree(path.roles, ({ value, fieldTree }) => {
    const seen = new Map<string, number>();
    const errors = [];
    for (const [index, role] of value().entries()) {
      const key = role.roleName.trim().toLowerCase();
      if (!key) {
        continue;
      }
      if (seen.has(key)) {
        errors.push({
          kind: 'duplicateRole',
          message: `Same name as role ${(seen.get(key) ?? 0) + 1}.`,
          fieldTree: fieldTree[index].roleName,
        });
      } else {
        seen.set(key, index);
      }
    }
    return errors;
  });

  applyEach(path.roles, (role) => {
    required(role.roleName, { message: 'Name the role, e.g. "Buyer".' });
    maxLength(role.roleName, 100);
    required(role.stageNumber, { message: 'Which stage?' });
    min(role.stageNumber, 1, { message: 'Stages start at 1.' });
    max(role.stageNumber, MAX_ROLES);
    validate(role.verificationMethod, ({ value, valueOf }) =>
      value() === VerificationMethod.INTERNAL_ACCOUNT &&
      valueOf(role.signatoryType) !== SignatoryType.INTERNAL_USER
        ? { kind: 'verification', message: 'Only internal users sign with their own account.' }
        : null,
    );
  });

  validate(path.fields, ({ value }) =>
    value().length > MAX_FIELDS
      ? { kind: 'fields', message: `A template can have at most ${MAX_FIELDS} fields.` }
      : null,
  );

  applyEach(path.fields, (field) => {
    required(field.roleKey, { message: 'Who fills it in?' });
    required(field.pageNumber, { message: 'Which page?' });
    min(field.pageNumber, 1, { message: 'Pages start at 1.' });
    max(field.pageNumber, ({ valueOf }) => valueOf(path.pageCount) || undefined, {
      message: 'The template does not have that many pages.',
    });
    for (const axis of [field.x, field.y]) {
      required(axis, { message: 'Required.' });
      min(axis, 0, { message: '0–100 %.' });
      max(axis, 100, { message: '0–100 %.' });
    }
    for (const size of [field.width, field.height]) {
      required(size, { message: 'Required.' });
      validate(size, ({ value }) =>
        value() > 0 && value() <= 100 ? null : { kind: 'size', message: 'Above 0, at most 100 %.' },
      );
    }
    validate(field.width, ({ value, valueOf }) =>
      valueOf(field.x) + value() > 100.0001
        ? { kind: 'bounds', message: 'It runs off the right of the page.' }
        : null,
    );
    validate(field.height, ({ value, valueOf }) =>
      valueOf(field.y) + value() > 100.0001
        ? { kind: 'bounds', message: 'It runs off the bottom of the page.' }
        : null,
    );
    hidden(field.options, ({ valueOf }) => valueOf(field.fieldType) !== FieldType.RADIO);
    required(field.options, {
      when: ({ valueOf }) => valueOf(field.fieldType) === FieldType.RADIO,
      message: 'List the options, separated by commas.',
    });
    maxLength(field.label, 200);
  });
});
