import { DatePipe } from '@angular/common';
import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { debounce, form, FormField, validate } from '@angular/forms/signals';
import { RouterLink } from '@angular/router';
import { SortDirection } from '@models/common';
import {
  OrganisationListDTO,
  OrganisationSearchCriteria,
  OrganisationStatus,
} from '@models/organisation';
import { OrganisationApi } from '@services/organisation/organisation-api';
import { FieldErrors } from '@shared/field-errors/field-errors';
import { apiErrorMessage } from '@store/app-state';
import { firstValueFrom } from 'rxjs';
import { endOfDay, humanise, startOfDay } from '../platform-format';
import { organisationTone } from './organisation-status';

interface OrganisationFilter {
  name: string;
  registrationNumber: string;
  status: OrganisationStatus | '';
  createdFrom: string;
  createdTo: string;
}

const PAGE_SIZE = 25;

/** Every organisation on the platform (POST /organisations/search), with filters and paging. */
@Component({
  selector: 'app-platform-organisations',
  imports: [DatePipe, FieldErrors, FormField, RouterLink],
  templateUrl: './platform-organisations.html',
})
export class PlatformOrganisations {
  private readonly organisationApi = inject(OrganisationApi);

  protected readonly statuses = Object.values(OrganisationStatus);
  protected readonly humanise = humanise;
  protected readonly tone = organisationTone;
  protected readonly pageSize = PAGE_SIZE;

  protected readonly filter = signal<OrganisationFilter>({
    name: '',
    registrationNumber: '',
    status: '',
    createdFrom: '',
    createdTo: '',
  });
  protected readonly filterForm = form(this.filter, (path) => {
    debounce(path.name, 350);
    debounce(path.registrationNumber, 350);
    validate(path.createdTo, ({ value, valueOf }) => {
      const from = valueOf(path.createdFrom);
      return from && value() && value() < from
        ? { kind: 'dateRange', message: 'The end date is before the start date.' }
        : null;
    });
  });

  protected readonly page = signal(0);
  protected readonly organisations = signal<OrganisationListDTO[]>([]);
  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly hasNext = computed(() => this.organisations().length === PAGE_SIZE);

  private readonly criteria = computed<OrganisationSearchCriteria | null>(() => {
    if (this.filterForm().invalid()) {
      return null;
    }
    const f = this.filter();
    return {
      name: f.name.trim() || null,
      registrationNumber: f.registrationNumber.trim() || null,
      status: f.status || null,
      createdFrom: startOfDay(f.createdFrom),
      createdTo: endOfDay(f.createdTo),
      pageSize: PAGE_SIZE,
      sortField: 'createdAt',
      sortDirection: SortDirection.DESC,
    };
  });

  /** Search sequence number: a slow, stale response never overwrites a newer one. */
  private requestId = 0;

  constructor() {
    // A changed filter starts again from the first page.
    effect(() => {
      this.criteria();
      untracked(() => this.page.set(0));
    });
    effect(() => {
      const criteria = this.criteria();
      const page = this.page();
      if (criteria) {
        untracked(() => void this.search({ ...criteria, pageNumber: page }));
      }
    });
  }

  protected reset(): void {
    this.filter.set({ name: '', registrationNumber: '', status: '', createdFrom: '', createdTo: '' });
  }

  protected reload(): void {
    const criteria = this.criteria();
    if (criteria) {
      void this.search({ ...criteria, pageNumber: this.page() });
    }
  }

  private async search(criteria: OrganisationSearchCriteria): Promise<void> {
    const id = ++this.requestId;
    this.loading.set(true);
    this.error.set(null);
    try {
      const organisations = await firstValueFrom(this.organisationApi.search(criteria));
      if (id === this.requestId) {
        this.organisations.set(organisations);
      }
    } catch (error) {
      if (id === this.requestId) {
        this.organisations.set([]);
        this.error.set(apiErrorMessage(error, 'Unable to search the organisations.'));
      }
    } finally {
      if (id === this.requestId) {
        this.loading.set(false);
      }
    }
  }
}
