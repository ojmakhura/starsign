import { DatePipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { form, FormField, schema } from '@angular/forms/signals';
import { RouterLink } from '@angular/router';
import { Can } from '@auth/can.directive';
import { DocumentListDTO, DocumentSearchCriteria, DocumentStatus } from '@models/document';
import { DocumentApi } from '@services/document/document-api';
import { FieldErrors } from '@shared/field-errors/field-errors';
import { Pager } from '@shared/pagination/pager';
import { PagedSearch } from '@shared/pagination/pagination';
import { saveFile, toCsv } from '@shared/save-file';
import { StatusChip } from '@shared/status-chip/status-chip';
import { statusLabel } from '@shared/status-chip/status-meta';
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
  dateRange(path.createdFrom, path.createdTo);
});

@Component({
  selector: 'app-documents',
  imports: [Can, DatePipe, FieldErrors, FormField, Pager, RouterLink, StatusChip],
  templateUrl: './documents.html',
})
export class Documents {
  private readonly documentApi = inject(DocumentApi);
  protected readonly results = new PagedSearch<DocumentSearchCriteria, DocumentListDTO>(
    (criteria) => this.documentApi.pagedSearch(criteria),
    'Unable to search the document vault.',
  );

  protected readonly filter = signal<DocumentFilter>({ ...EMPTY_FILTER });
  protected readonly filterForm = form(this.filter, filterSchema);
  protected readonly statuses = Object.values(DocumentStatus);
  protected readonly statusLabel = statusLabel;

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

  constructor() {
    inject(LoaderState).track(() => this.results.loading(), 'Searching documents…');
    void this.search();
  }

  /** Runs the search with the filter's criteria, from the first page. */
  protected search(event?: Event): void {
    event?.preventDefault();
    if (this.filterForm().invalid()) {
      return;
    }
    const f = this.filter();
    void this.results.search({
      text: f.text.trim() || null,
      statuses: f.status ? [f.status] : null,
      createdFrom: startOfDay(f.createdFrom),
      createdTo: endOfDay(f.createdTo),
      includeArchived: f.includeArchived,
    });
  }

  protected progress(signed?: number | null, total?: number | null): number {
    signed ??= 0;
    total ??= 0;
    return total ? Math.round((signed / total) * 100) : 0;
  }

  protected clearFilter(key: keyof DocumentFilter): void {
    this.filter.update((f) => ({ ...f, [key]: EMPTY_FILTER[key] }));
    this.search();
  }

  protected resetFilters(): void {
    this.filterForm().reset({ ...EMPTY_FILTER });
    this.search();
  }

  protected exportCsv(): void {
    const rows = this.results
      .items()
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
