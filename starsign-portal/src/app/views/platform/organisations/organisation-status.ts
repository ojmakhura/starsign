import { OrganisationStatus } from '@models/organisation';
import { ChipTone } from '@shared/status-chip/status-meta';

/** Chip tone per organisation status. */
export const ORGANISATION_TONES: Record<OrganisationStatus, ChipTone> = {
  [OrganisationStatus.PENDING_VERIFICATION]: 'pending',
  [OrganisationStatus.ACTIVE]: 'sealed',
  [OrganisationStatus.SUSPENDED]: 'voided',
  [OrganisationStatus.INACTIVE]: 'neutral',
  [OrganisationStatus.CLOSED]: 'voided',
};

export const organisationTone = (status: OrganisationStatus | null | undefined): ChipTone =>
  (status && ORGANISATION_TONES[status]) || 'neutral';

/**
 * The OrganisationStatus state machine, as OrganisationServiceImpl.TRANSITIONS enforces it.
 * A reason is required for every move except to ACTIVE.
 */
export const ORGANISATION_TRANSITIONS: Record<OrganisationStatus, OrganisationStatus[]> = {
  [OrganisationStatus.PENDING_VERIFICATION]: [OrganisationStatus.ACTIVE, OrganisationStatus.CLOSED],
  [OrganisationStatus.ACTIVE]: [
    OrganisationStatus.SUSPENDED,
    OrganisationStatus.INACTIVE,
    OrganisationStatus.CLOSED,
  ],
  [OrganisationStatus.SUSPENDED]: [
    OrganisationStatus.ACTIVE,
    OrganisationStatus.INACTIVE,
    OrganisationStatus.CLOSED,
  ],
  [OrganisationStatus.INACTIVE]: [OrganisationStatus.ACTIVE, OrganisationStatus.CLOSED],
  [OrganisationStatus.CLOSED]: [],
};
