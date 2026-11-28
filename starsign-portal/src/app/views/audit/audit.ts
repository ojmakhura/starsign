import { DatePipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { form, FormField, min, validate } from '@angular/forms/signals';
import { RouterLink } from '@angular/router';
import { Can } from '@auth/can.directive';
import {
  AuditChainVerificationDTO,
  AuditEventDTO,
  AuditEventSearchCriteria,
  AuditEventType,
  AuditOutcome,
} from '@models/audit';
import { SortDirection } from '@models/common';
import { AuditApi } from '@services/audit/audit-api';
import { FieldErrors } from '@shared/field-errors/field-errors';
import { Pager } from '@shared/pagination/pager';
import { PagedSearch } from '@shared/pagination/pagination';
import { saveFile } from '@shared/save-file';
import { apiErrorMessage } from '@store/app-state';
import { SessionStore } from '@store/session.store';
import { toast } from 'ngx-sonner';
import { firstValueFrom } from 'rxjs';
import { dateRange } from '../documents/search-dates';

interface AuditFilter {
  eventType: AuditEventType | '';
  outcome: AuditOutcome | '';
  actorId: string;
  documentId: string;
  workflowId: string;
  from: string;
  to: string;
}

interface ChainModel {
  chainKey: string;
  fromSequence: number | null;
  toSequence: number | null;
}

const EMPTY: AuditFilter = {
  eventType: '',
  outcome: '',
  actorId: '',
  documentId: '',
  workflowId: '',
  from: '',
  to: '',
};

/** The chain of platform-level events (organisation users have their tenant's chain). */
const PLATFORM_CHAIN = 'platform';

/** The tamper-evident audit trail: search, inspect, export, verify and seal the hash chain. */
@Component({
  selector: 'app-audit',
  imports: [Can, DatePipe, FieldErrors, FormField, Pager, RouterLink],
  templateUrl: './audit.html',
})
export class Audit {
  private readonly auditApi = inject(AuditApi);
  private readonly session = inject(SessionStore);

  protected readonly eventTypes = Object.values(AuditEventType);
  protected readonly outcomes = Object.values(AuditOutcome);

  protected readonly filter = signal<AuditFilter>({ ...EMPTY });
  protected readonly filterForm = form(this.filter, (path) => dateRange(path.from, path.to));

  protected readonly results = new PagedSearch<AuditEventSearchCriteria, AuditEventDTO>(
    (criteria) => this.auditApi.pagedSearch(criteria),
    'Unable to search the audit trail.',
  );
  protected readonly exporting = signal(false);
  protected readonly selected = signal<AuditEventDTO | null>(null);

  // Chain integrity ------------------------------------------------------------
  protected readonly chain = signal<ChainModel>({
    chainKey: this.session.currentUser()?.organisation?.tenantId || PLATFORM_CHAIN,
    fromSequence: null,
    toSequence: null,
  });
  protected readonly chainForm = form(this.chain, (path) => {
    validate(path.chainKey, ({ value }) =>
      value().trim() ? null : { kind: 'required', message: 'Enter the chain key.' },
    );
    min(path.fromSequence, 1, { message: 'Sequence numbers start at 1.' });
    min(path.toSequence, 1, { message: 'Sequence numbers start at 1.' });
    validate(path.toSequence, ({ value, valueOf }) => {
      const from = valueOf(path.fromSequence);
      return from != null && value() != null && value()! < from
        ? { kind: 'range', message: 'The last sequence is before the first.' }
        : null;
    });
  });
  protected readonly verification = signal<AuditChainVerificationDTO | null>(null);
  protected readonly chainBusy = signal<'verify' | 'seal' | null>(null);

  constructor() {
    void this.search();
  }

  protected label(value: string | null | undefined): string {
    return (value ?? '—').replaceAll('_', ' ');
  }

  protected async search(event?: Event): Promise<void> {
    event?.preventDefault();
    if (this.filterForm().invalid()) {
      return;
    }
    const f = this.filter();
    await this.results.search({
      eventTypes: f.eventType ? [f.eventType] : null,
      outcome: f.outcome || null,
      actorId: f.actorId.trim() || null,
      documentId: f.documentId.trim() || null,
      workflowId: f.workflowId.trim() || null,
      // The API takes local date-times without a zone.
      from: f.from ? `${f.from}T00:00:00` : null,
      to: f.to ? `${f.to}T23:59:59` : null,
      sortField: 'occurredAt',
      sortDirection: SortDirection.DESC,
    });
  }

  protected reset(): void {
    this.filter.set({ ...EMPTY });
    void this.search();
  }

  protected async exportCsv(): Promise<void> {
    this.exporting.set(true);
    try {
      const { pageNumber, pageSize, ...criteria } = this.results.applied() ?? {};
      const blob = await firstValueFrom(this.auditApi.exportCsv(criteria));
      const stamp = new Date().toISOString().slice(0, 19).replace(/[-:]/g, '').replace('T', '-');
      saveFile(blob, `audit-events-${stamp}.csv`);
      toast.success('Audit export downloaded', { description: 'The export itself is recorded in the audit chain.' });
    } catch (error) {
      toast.error('Export failed', { description: apiErrorMessage(error) });
    } finally {
      this.exporting.set(false);
    }
  }

  protected useChainOf(event: AuditEventDTO): void {
    if (event.chainKey) {
      this.chain.update((c) => ({ ...c, chainKey: event.chainKey! }));
      this.verification.set(null);
    }
    this.selected.set(null);
    document.getElementById('chain-key')?.focus();
  }

  protected async verify(event?: Event): Promise<void> {
    event?.preventDefault();
    if (this.chainForm().invalid()) {
      return;
    }
    const { chainKey, fromSequence, toSequence } = this.chain();
    this.chainBusy.set('verify');
    this.verification.set(null);
    try {
      this.verification.set(
        await firstValueFrom(this.auditApi.verifyChain(chainKey.trim(), fromSequence, toSequence)),
      );
    } catch (error) {
      toast.error('Verification failed to run', { description: apiErrorMessage(error) });
    } finally {
      this.chainBusy.set(null);
    }
  }

  protected async seal(): Promise<void> {
    const chainKey = this.chain().chainKey.trim();
    if (!chainKey) {
      return;
    }
    if (
      !confirm(
        `Seal the audit chain "${chainKey}"? All unsealed events are verified and checkpointed with a signed seal. This cannot be undone.`,
      )
    ) {
      return;
    }
    this.chainBusy.set('seal');
    try {
      const record = await firstValueFrom(this.auditApi.seal(chainKey));
      toast.success('Audit chain sealed', {
        description: `Events ${record.fromSequence}–${record.toSequence} (${record.eventCount} events) are sealed.`,
      });
    } catch (error) {
      toast.error('The chain could not be sealed', { description: apiErrorMessage(error) });
    } finally {
      this.chainBusy.set(null);
    }
  }

  protected outcomeChip(outcome: AuditOutcome | null | undefined): string {
    return outcome === AuditOutcome.SUCCESS ? 'chip--sealed' : outcome ? 'chip--voided' : 'chip--neutral';
  }

  /** Details are stored as JSON; show them indented when they parse. */
  protected prettyDetails(details: string | null | undefined): string {
    if (!details) {
      return '';
    }
    try {
      return JSON.stringify(JSON.parse(details), null, 2);
    } catch {
      return details;
    }
  }
}
