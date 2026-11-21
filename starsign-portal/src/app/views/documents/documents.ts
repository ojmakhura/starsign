import { DatePipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { debounce, form, FormField, schema } from '@angular/forms/signals';
import { RouterLink } from '@angular/router';
import { Can } from '@auth/can.directive';
import { DocumentSearchCriteria, DocumentStatus } from '@models/document';
import { FieldErrors } from '@shared/field-errors/field-errors';
import { saveFile, toCsv } from '@shared/save-file';
import { StatusChip } from '@shared/status-chip/status-chip';
import { statusLabel } from '@shared/status-chip/status-meta';
import { DocumentApiStore } from '@store/document/document-api.store';
import { LoaderState } from '@store/loader-state';
import { dateRange, endOfDay, startOfDay } from './search-dates';

interface DocumentFilter {
  text: string;
  status: DocumentStatus | '';
  createdFrom: string;
  createdTo: string;
  includeArchived: boolean;
}

const EMPTY_FILTER: DocumentFilter = {
  text: '',
  status: '',
  createdFrom: '',
  createdTo: '',
  includeArchived: false,
};

const filterSchema = schema<DocumentFilter>((path) => {
  debounce(path.text, 350);
  dateRange(path.createdFrom, path.createdTo);
});

@Component({
  selector: 'app-documents',
  imports: [Can, DatePipe, FieldErrors, FormField, RouterLink, StatusChip],
  templateUrl: './documents.html',
})
export class Documents {
  protected readonly store = inject(DocumentApiStore);

  protected readonly filter = signal<DocumentFilter>({ ...EMPTY_FILTER });
  protected readonly filterForm = form(this.filter, filterSchema);
  protected readonly statuses = Object.values(DocumentStatus);
  protected readonly statusLabel = statusLabel;

  /**
   * The search criteria, derived from the filter form. While the form is invalid
   * (e.g. a reversed date range) it is null and the store keeps the last results.
   */
  private readonly criteria = computed<DocumentSearchCriteria | null>(() => {
    if (this.filterForm().invalid()) {
      return null;
    }
    const f = this.filter();
    return {
      text: f.text.trim() || null,
      status: f.status || null,
      createdFrom: startOfDay(f.createdFrom),
      createdTo: endOfDay(f.createdTo),
      includeArchived: f.includeArchived,
    };
  });

  protected readonly activeFilters = computed(() => {
    const f = this.filter();
    return [
      f.text.trim() && { key: 'text' as const, label: `“${f.text.trim()}”` },
      f.status && { key: 'status' as const, label: statusLabel(f.status) },
      f.createdFrom && { key: 'createdFrom' as const, label: `From ${f.createdFrom}` },
      f.createdTo && { key: 'createdTo' as const, label: `To ${f.createdTo}` },
      f.includeArchived && { key: 'includeArchived' as const, label: 'Incl. archived' },
    ].filter(Boolean) as { key: keyof DocumentFilter; label: string }[];
  });

  protected readonly sealedCount = computed(
    () => this.store.dataList().filter((d) => d.status === DocumentStatus.COMPLETED).length,
  );
  protected readonly inFlightCount = computed(
    () =>
      this.store
        .dataList()
        .filter(
          (d) =>
            d.status === DocumentStatus.PENDING_SIGNATURES ||
            d.status === DocumentStatus.PARTIALLY_SIGNED,
        ).length,
  );

  constructor() {
    // rxMethod subscribes to the signal: every valid change to the filter re-runs the search.
    this.store.search(this.criteria);
    inject(LoaderState).track(() => this.store.loading(), 'Searching documents…');
  }

  protected progress(signed?: number | null, total?: number | null): number {
    signed ??= 0;
    total ??= 0;
    return total ? Math.round((signed / total) * 100) : 0;
  }

  protected clearFilter(key: keyof DocumentFilter): void {
    this.filter.update((f) => ({ ...f, [key]: EMPTY_FILTER[key] }));
  }

  protected resetFilters(): void {
    this.filterForm().reset({ ...EMPTY_FILTER });
  }

  protected exportCsv(): void {
    const rows = this.store
      .dataList()
      .map((d) => [
        d.referenceNumber,
        d.title,
        d.status,
        d.ownerName,
        d.pageCount,
        d.signedCount,
        d.signatoryCount,
        d.createdAt,
        d.completedAt,
      ]);
    saveFile(
      toCsv([
        [
          'Reference',
          'Title',
          'Status',
          'Owner',
          'Pages',
          'Signed',
          'Signatories',
          'Created',
          'Completed',
        ],
        ...rows,
      ]),
      'starsign-documents.csv',
    );
  }
}
