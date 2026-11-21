import { DocumentStatus } from '@models/document';
import { SignatoryStatus, WorkflowStatus } from '@models/workflow';

export type ChipTone = 'pending' | 'sealed' | 'voided' | 'neutral' | 'progress';

/**
 * Audit-trail chip tone per status: cyan for anything awaiting a signature, gold only
 * for cryptographically sealed / completed records, crimson for voided ones.
 */
const TONES: Record<string, ChipTone> = {
  [DocumentStatus.UPLOADED]: 'neutral',
  [DocumentStatus.DRAFT]: 'neutral',
  [DocumentStatus.IN_PREPARATION]: 'progress',
  [DocumentStatus.READY_FOR_SIGNING]: 'progress',
  [DocumentStatus.PENDING_SIGNATURES]: 'pending',
  [DocumentStatus.PARTIALLY_SIGNED]: 'pending',
  [DocumentStatus.COMPLETED]: 'sealed',
  [DocumentStatus.DECLINED]: 'voided',
  [DocumentStatus.CANCELLED]: 'voided',
  [DocumentStatus.EXPIRED]: 'voided',
  [DocumentStatus.ARCHIVED]: 'neutral',
  [WorkflowStatus.READY]: 'progress',
  [WorkflowStatus.IN_PROGRESS]: 'pending',
  [WorkflowStatus.COMPLETING]: 'pending',
  [WorkflowStatus.FAILED]: 'voided',
  [SignatoryStatus.PENDING]: 'neutral',
  [SignatoryStatus.INVITED]: 'pending',
  [SignatoryStatus.VIEWED]: 'pending',
  [SignatoryStatus.VERIFIED]: 'pending',
  [SignatoryStatus.SIGNED]: 'sealed',
  [SignatoryStatus.SKIPPED]: 'neutral',
};

export const statusTone = (status: string | null | undefined): ChipTone =>
  (status && TONES[status]) || 'neutral';

export const statusLabel = (status: string | null | undefined): string =>
  (status ?? 'Unknown').replaceAll('_', ' ');
