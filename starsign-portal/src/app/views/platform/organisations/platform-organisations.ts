import { DatePipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { form, FormField, validate } from '@angular/forms/signals';
import { RouterLink } from '@angular/router';
import {
  OrganisationListDTO,
  OrganisationSearchCriteria,
  OrganisationStatus,
} from '@models/organisation';
import { OrganisationApi } from '@services/organisation/organisation-api';
import { FieldErrors } from '@shared/field-errors/field-errors';
import { Pager } from '@shared/pagination/pager';
import { PagedSearch } from '@shared/pagination/pagination';
import { endOfDay, humanise, startOfDay } from '../platform-format';
import { organisationTone } from './organisation-status';

interface OrganisationFilter {
  name: string;
  registrationNumber: string;
  status: OrganisationStatus | '';
  createdFrom: string;
  createdTo: string;
}

const EMPTY: OrganisationFilter = {
  name: '',
  registrationNumber: '',
  status: '',
  createdFrom: '',
  createdTo: '',
};

/** Every organisation on the platform (POST /organisations/search/paged), with filters and paging. */
@Component({
  selector: 'app-platform-organisations',
  imports: [DatePipe, FieldErrors, FormField, Pager, RouterLink],
  templateUrl: './platform-organisations.html',
})
export class PlatformOrganisations {
  private readonly organisationApi = inject(OrganisationApi);

  protected readonly statuses = Object.values(OrganisationStatus);
  protected readonly humanise = humanise;
  protected readonly tone = organisationTone;

  protected readonly filter = signal<OrganisationFilter>({ ...EMPTY });
  protected readonly filterForm = form(this.filter, (path) => {
    validate(path.createdTo, ({ value, valueOf }) => {
      const from = valueOf(path.createdFrom);
      return from && value() && value() < from
        ? { kind: 'dateRange', message: 'The end date is before the start date.' }
        : null;
    });
  });

  protected readonly results = new PagedSearch<OrganisationSearchCriteria, OrganisationListDTO>(
    (criteria) => this.organisationApi.pagedSearch(criteria),
    'Unable to search the organisations.',
  );

  constructor() {
    this.search();
  }

  /** Runs the search with the filter's criteria, from the first page. */
  protected search(event?: Event): void {
    event?.preventDefault();
    if (this.filterForm().invalid()) {
      return;
    }
    const f = this.filter();
    void this.results.search({
      name: f.name.trim() || null,
      registrationNumber: f.registrationNumber.trim() || null,
      status: f.status || null,
      createdFrom: startOfDay(f.createdFrom),
      createdTo: endOfDay(f.createdTo),
    });
  }

  protected reset(): void {
    this.filterForm().reset({ ...EMPTY });
    this.search();
  }
}
