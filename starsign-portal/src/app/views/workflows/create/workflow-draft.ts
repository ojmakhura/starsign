import {
  applyEach,
  disabled,
  email,
  hidden,
  LogicFn,
  max,
  maxLength,
  min,
  PathKind,
  pattern,
  required,
  schema,
  validate,
  validateTree,
} from '@angular/forms/signals';
import { SignatureCaptureMethod, VerificationMethod } from '@models/signing';
import { CreateWorkflowDTO, ExecutionMode, SignatoryDTO, SignatoryType } from '@models/workflow';
import { toDateInput } from '../../documents/search-dates';

export interface SignatoryRow {
  /** Stable id while editing: placed fields point at it, so reordering keeps them. */
  key: string;
  fullName: string;
  emailAddress: string;
  phoneNumber: string;
  designation: string;
  organisationName: string;
  signatoryType: SignatoryType;
  /** Blank = use the workflow's verification method. */
  verificationMethod: VerificationMethod | '';
  mandatory: boolean;
  canDecline: boolean;
  notifyBySms: boolean;
}

export interface WorkflowDraft {
  documentId: string;
  title: string;
  message: string;
  executionMode: ExecutionMode;
  expiresAt: string;
  verificationMethod: VerificationMethod;
  singleUseInvitations: boolean;
  allowDecline: boolean;
  declineTerminatesWorkflow: boolean;
  sendCompletedCopy: boolean;
  captureDrawn: boolean;
  captureTyped: boolean;
  reminderIntervalHours: number;
  /** Validate and send invitations straight after creating the workflow. */
  sendNow: boolean;
  signatories: SignatoryRow[];
}

export const MAX_SIGNATORIES = 25;
const MAX_EXPIRY_DAYS = 365;
const E164 = /^\+[1-9]\d{6,14}$/;

const addDays = (days: number) => toDateInput(new Date(Date.now() + days * 86_400_000));

export const newSignatory = (): SignatoryRow => ({
  key: crypto.randomUUID(),
  fullName: '',
  emailAddress: '',
  phoneNumber: '',
  designation: '',
  organisationName: '',
  signatoryType: SignatoryType.EXTERNAL_PERSON,
  verificationMethod: '',
  mandatory: true,
  canDecline: true,
  notifyBySms: false,
});

export const newDraft = (documentId = ''): WorkflowDraft => ({
  documentId,
  title: '',
  message: '',
  executionMode: ExecutionMode.SEQUENTIAL,
  expiresAt: addDays(14),
  verificationMethod: VerificationMethod.EMAIL_OTP,
  singleUseInvitations: true,
  allowDecline: true,
  declineTerminatesWorkflow: true,
  sendCompletedCopy: true,
  captureDrawn: true,
  captureTyped: true,
  reminderIntervalHours: 24,
  sendNow: true,
  signatories: [newSignatory()],
});

/**
 * Every rule of a signing workflow in one schema: field rules, cross-field rules,
 * per-signatory rules (applyEach) and whole-list rules (validateTree).
 */
export const workflowDraftSchema = schema<WorkflowDraft>((path) => {
  required(path.documentId, { message: 'Choose the document to be signed.' });
  required(path.title, { message: 'Give the workflow a title.' });
  maxLength(path.title, 200);
  maxLength(path.message, 2000, { message: 'Keep the message under 2000 characters.' });

  required(path.expiresAt, { message: 'Set when the signing request expires.' });
  validate(path.expiresAt, ({ value }) => {
    if (!value()) {
      return null;
    }
    if (value() <= toDateInput(new Date())) {
      return { kind: 'expiry', message: 'The expiry date must be in the future.' };
    }
    return value() > addDays(MAX_EXPIRY_DAYS)
      ? { kind: 'expiry', message: `Requests can stay open for at most ${MAX_EXPIRY_DAYS} days.` }
      : null;
  });

  min(path.reminderIntervalHours, 1, { message: 'Remind at most once an hour.' });
  max(path.reminderIntervalHours, 168, { message: 'Remind at least once a week.' });

  // A decline can only end the workflow if declining is allowed at all.
  disabled(path.declineTerminatesWorkflow, ({ valueOf }) =>
    valueOf(path.allowDecline) ? false : 'Signatories cannot decline',
  );

  validate(path.captureTyped, ({ value, valueOf }) =>
    value() || valueOf(path.captureDrawn)
      ? null
      : { kind: 'capture', message: 'Allow at least one way to sign.' },
  );

  validate(path.signatories, ({ value }) => {
    if (value().length === 0) {
      return { kind: 'signatories', message: 'Add at least one signatory.' };
    }
    return value().length > MAX_SIGNATORIES
      ? {
          kind: 'signatories',
          message: `A workflow can have at most ${MAX_SIGNATORIES} signatories.`,
        }
      : null;
  });

  // Each email may sign once: flag every duplicate on its own email field.
  validateTree(path.signatories, ({ value, fieldTree }) => {
    const seen = new Map<string, number>();
    const errors = [];
    for (const [index, row] of value().entries()) {
      const key = row.emailAddress.trim().toLowerCase();
      if (!key) {
        continue;
      }
      if (seen.has(key)) {
        errors.push({
          kind: 'duplicateEmail',
          message: `Already added as signatory ${(seen.get(key) ?? 0) + 1}.`,
          fieldTree: fieldTree[index].emailAddress,
        });
      } else {
        seen.set(key, index);
      }
    }
    return errors;
  });

  applyEach(path.signatories, (row) => {
    required(row.fullName, { message: 'Name is required.' });
    maxLength(row.fullName, 120);
    required(row.emailAddress, { message: 'Email is required.' });
    email(row.emailAddress, { message: 'Enter a valid email address.' });

    // SMS verification or notification needs a phone number in E.164 form.
    const needsPhone: LogicFn<string, boolean, PathKind.Item> = ({ valueOf }) =>
      valueOf(row.notifyBySms) ||
      (valueOf(row.verificationMethod) || valueOf(path.verificationMethod)) ===
        VerificationMethod.SMS_OTP;
    required(row.phoneNumber, { when: needsPhone, message: 'A mobile number is needed for SMS.' });
    pattern(row.phoneNumber, E164, { message: 'Use international format, e.g. +26771234567.' });

    hidden(
      row.organisationName,
      ({ valueOf }) =>
        valueOf(row.signatoryType) !== SignatoryType.EXTERNAL_ORGANISATION_REPRESENTATIVE,
    );
    required(row.organisationName, {
      when: ({ valueOf }) =>
        valueOf(row.signatoryType) === SignatoryType.EXTERNAL_ORGANISATION_REPRESENTATIVE,
      message: 'Which organisation do they represent?',
    });

    // Per-signatory decline only matters when the workflow allows declining.
    hidden(row.canDecline, ({ valueOf }) => !valueOf(path.allowDecline));
  });
});

export function toCreateWorkflow(draft: WorkflowDraft): CreateWorkflowDTO {
  const expires = new Date(`${draft.expiresAt}T23:59:59`);
  return {
    documentId: draft.documentId,
    title: draft.title.trim(),
    message: draft.message.trim() || null,
    executionMode: draft.executionMode,
    expiresAt: expires.toISOString(),
    verificationMethod: draft.verificationMethod,
    singleUseInvitations: draft.singleUseInvitations,
    allowDecline: draft.allowDecline,
    declineTerminatesWorkflow: draft.allowDecline && draft.declineTerminatesWorkflow,
    sendCompletedCopy: draft.sendCompletedCopy,
    allowedCaptureMethods: [
      ...(draft.captureDrawn ? [SignatureCaptureMethod.DRAWN] : []),
      ...(draft.captureTyped ? [SignatureCaptureMethod.TYPED] : []),
    ],
    reminderIntervalHours: draft.reminderIntervalHours,
  };
}

export function toSignatory(draft: WorkflowDraft, row: SignatoryRow, index: number): SignatoryDTO {
  return {
    fullName: row.fullName.trim(),
    emailAddress: row.emailAddress.trim().toLowerCase(),
    phoneNumber: row.phoneNumber.trim() || null,
    designation: row.designation.trim() || null,
    organisationName:
      row.signatoryType === SignatoryType.EXTERNAL_ORGANISATION_REPRESENTATIVE
        ? row.organisationName.trim()
        : null,
    signatoryType: row.signatoryType,
    // No stage: the API puts every signatory in the single stage it creates with the first one
    // (naming stage 1 fails before that stage exists). Order within it comes from signingOrder.
    stageNumber: null,
    signingOrder: draft.executionMode === ExecutionMode.SEQUENTIAL ? index + 1 : 1,
    mandatory: row.mandatory,
    canDecline: draft.allowDecline && row.canDecline,
    verificationMethod: row.verificationMethod || draft.verificationMethod,
    notifyByEmail: true,
    notifyBySms: row.notifyBySms,
    receiveCompletedCopy: draft.sendCompletedCopy,
  };
}
