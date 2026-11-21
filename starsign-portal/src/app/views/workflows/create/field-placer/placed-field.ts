import { DocumentPageDTO } from '@models/document';
import { FieldType, SigningFieldDTO } from '@models/workflow';

/** A field on the document while the workflow is being prepared. Ratios use a top-left origin. */
export interface PlacedField {
  key: string;
  /** SignatoryRow.key of the recipient who fills it. */
  recipientKey: string;
  type: FieldType;
  page: number;
  x: number;
  y: number;
  w: number;
  h: number;
  required: boolean;
  label: string;
}

export interface PlacerRecipient {
  key: string;
  name: string;
  color: string;
}

/** Recipient colours, readable on a white page. */
export const RECIPIENT_COLOURS = [
  '#2563eb',
  '#db2777',
  '#059669',
  '#d97706',
  '#7c3aed',
  '#0891b2',
  '#dc2626',
  '#4d7c0f',
];

export interface FieldKind {
  type: FieldType;
  label: string;
  icon: string;
  /** Default size in PDF points. */
  width: number;
  height: number;
}

export const FIELD_KINDS: FieldKind[] = [
  { type: FieldType.SIGNATURE, label: 'Signature', icon: 'bi-pen', width: 170, height: 45 },
  { type: FieldType.INITIALS, label: 'Initials', icon: 'bi-type', width: 60, height: 36 },
  { type: FieldType.DATE, label: 'Date signed', icon: 'bi-calendar-event', width: 100, height: 22 },
  { type: FieldType.FULL_NAME, label: 'Full name', icon: 'bi-person', width: 160, height: 22 },
  { type: FieldType.JOB_TITLE, label: 'Job title', icon: 'bi-briefcase', width: 160, height: 22 },
  { type: FieldType.ORGANISATION, label: 'Company', icon: 'bi-building', width: 160, height: 22 },
  { type: FieldType.TEXT, label: 'Text', icon: 'bi-input-cursor-text', width: 160, height: 22 },
  { type: FieldType.CHECKBOX, label: 'Checkbox', icon: 'bi-check-square', width: 16, height: 16 },
  { type: FieldType.COMMENT, label: 'Comment', icon: 'bi-chat-left-text', width: 200, height: 60 },
];

export const fieldKind = (type: FieldType): FieldKind =>
  FIELD_KINDS.find((kind) => kind.type === type) ?? FIELD_KINDS[0];

/** Page size as viewed (rotation applied), in points. */
export function viewedSize(page: DocumentPageDTO | undefined): { width: number; height: number } {
  const width = page?.widthPoints || 612;
  const height = page?.heightPoints || 792;
  const sideways = page?.rotation === 90 || page?.rotation === 270;
  return sideways ? { width: height, height: width } : { width, height };
}

/** Rounds a ratio to 6 places so the API's bounds check (x + w <= 1) holds. */
const ratio = (value: number) => Math.round(value * 1e6) / 1e6;

export function toSigningFields(
  fields: PlacedField[],
  signatoryIds: Map<string, string>,
  pages: DocumentPageDTO[],
): SigningFieldDTO[] {
  return fields.map((field) => {
    const page = pages.find((p) => p.pageNumber === field.page);
    return {
      signatoryId: signatoryIds.get(field.recipientKey) ?? null,
      fieldType: field.type,
      label: field.label.trim() || fieldKind(field.type).label,
      pageNumber: field.page,
      xRatio: ratio(field.x),
      yRatio: ratio(field.y),
      widthRatio: ratio(Math.min(field.w, 1 - field.x)),
      heightRatio: ratio(Math.min(field.h, 1 - field.y)),
      pageWidthPoints: page?.widthPoints ?? null,
      pageHeightPoints: page?.heightPoints ?? null,
      pageRotation: page?.rotation ?? 0,
      required: field.type === FieldType.SIGNATURE || field.type === FieldType.INITIALS ? true : field.required,
    };
  });
}
