import { DatePipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { form, FormField } from '@angular/forms/signals';
import { RouterLink } from '@angular/router';
import { Can } from '@auth/can.directive';
import { SigningWorkflowListDTO, SigningWorkflowSearchCriteria, WorkflowStatus } from '@models/workflow';
import { WorkflowApi } from '@services/workflow/workflow-api';
import { FieldErrors } from '@shared/field-errors/field-errors';
import { Pager } from '@shared/pagination/pager';
import { PagedSearch } from '@shared/pagination/pagination';
import { StatusChip } from '@shared/status-chip/status-chip';
import { statusLabel } from '@shared/status-chip/status-meta';
import { LoaderState } from '@store/loader-state';
import { dateRange, endOfDay, startOfDay } from '../documents/search-dates';

interface WorkflowFilter {
  text: string;
  status: WorkflowStatus | '';
  signatoryEmail: string;
  createdFrom: string;
  createdTo: string;
}

const EMPTY: WorkflowFilter = {
  text: '',
  status: '',
  signatoryEmail: '',
  createdFrom: '',
  createdTo: '',
};

@Component({
  selector: 'app-workflows',
  imports: [Can, DatePipe, FieldErrors, FormField, Pager, RouterLink, StatusChip],
  templateUrl: './workflows.html',
})
export class Workflows {
  private readonly workflowApi = inject(WorkflowApi);
  protected readonly results = new PagedSearch<SigningWorkflowSearchCriteria, SigningWorkflowListDTO>(
    (criteria) => this.workflowApi.pagedSearch(criteria),
    'Unable to search workflows.',
  );
  protected readonly statuses = Object.values(WorkflowStatus);
  protected readonly statusLabel = statusLabel;

  protected readonly filter = signal<WorkflowFilter>({ ...EMPTY });
  protected readonly filterForm = form(this.filter, (path) => {
    dateRange(path.createdFrom, path.createdTo);
  });

  /** Quick status tabs share the same model as the filter form. */
  protected readonly tabs: { label: string; status: WorkflowStatus | '' }[] = [
    { label: 'All', status: '' },
    { label: 'In progress', status: WorkflowStatus.IN_PROGRESS },
    { label: 'Drafts', status: WorkflowStatus.DRAFT },
    { label: 'Completed', status: WorkflowStatus.COMPLETED },
  ];

  constructor() {
    inject(LoaderState).track(() => this.results.loading(), 'Searching workflows…');
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
      signatoryEmail: f.signatoryEmail.trim() || null,
      createdFrom: startOfDay(f.createdFrom),
      createdTo: endOfDay(f.createdTo),
    });
  }

  protected reset(): void {
    this.filterForm().reset({ ...EMPTY });
    this.search();
  }

  /** A status tab filters at once. */
  protected selectTab(status: WorkflowStatus | ''): void {
    this.filterForm.status().value.set(status);
    this.search();
  }

  protected progress(signed?: number | null, total?: number | null): number {
    signed ??= 0;
    total ??= 0;
    return total ? Math.round((signed / total) * 100) : 0;
  }
}
