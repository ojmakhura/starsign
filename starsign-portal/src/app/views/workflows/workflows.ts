import { DatePipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { debounce, form, FormField } from '@angular/forms/signals';
import { RouterLink } from '@angular/router';
import { Can } from '@auth/can.directive';
import { SigningWorkflowSearchCriteria, WorkflowStatus } from '@models/workflow';
import { FieldErrors } from '@shared/field-errors/field-errors';
import { StatusChip } from '@shared/status-chip/status-chip';
import { statusLabel } from '@shared/status-chip/status-meta';
import { LoaderState } from '@store/loader-state';
import { WorkflowApiStore } from '@store/workflow/workflow-api.store';
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
  imports: [Can, DatePipe, FieldErrors, FormField, RouterLink, StatusChip],
  templateUrl: './workflows.html',
})
export class Workflows {
  protected readonly store = inject(WorkflowApiStore);
  protected readonly statuses = Object.values(WorkflowStatus);
  protected readonly statusLabel = statusLabel;

  protected readonly filter = signal<WorkflowFilter>({ ...EMPTY });
  protected readonly filterForm = form(this.filter, (path) => {
    debounce(path.text, 350);
    debounce(path.signatoryEmail, 500);
    dateRange(path.createdFrom, path.createdTo);
  });

  /** Quick status tabs share the same model as the filter form. */
  protected readonly tabs: { label: string; status: WorkflowStatus | '' }[] = [
    { label: 'All', status: '' },
    { label: 'In progress', status: WorkflowStatus.IN_PROGRESS },
    { label: 'Drafts', status: WorkflowStatus.DRAFT },
    { label: 'Completed', status: WorkflowStatus.COMPLETED },
  ];

  private readonly criteria = computed<SigningWorkflowSearchCriteria | null>(() => {
    if (this.filterForm().invalid()) {
      return null;
    }
    const f = this.filter();
    return {
      text: f.text.trim() || null,
      status: f.status || null,
      signatoryEmail: f.signatoryEmail.trim() || null,
      createdFrom: startOfDay(f.createdFrom),
      createdTo: endOfDay(f.createdTo),
    };
  });

  protected readonly counts = computed(() => {
    const list = this.store.dataList();
    return {
      total: list.length,
      active: list.filter((w) => w.status === WorkflowStatus.IN_PROGRESS).length,
      expiringSoon: list.filter(
        (w) =>
          w.status === WorkflowStatus.IN_PROGRESS &&
          w.expiresAt &&
          new Date(w.expiresAt).getTime() - Date.now() < 3 * 86_400_000,
      ).length,
    };
  });

  constructor() {
    this.store.search(this.criteria);
    inject(LoaderState).track(() => this.store.loading(), 'Searching workflows…');
  }

  protected selectTab(status: WorkflowStatus | ''): void {
    this.filterForm.status().value.set(status);
  }

  protected progress(signed?: number | null, total?: number | null): number {
    signed ??= 0;
    total ??= 0;
    return total ? Math.round((signed / total) * 100) : 0;
  }
}
