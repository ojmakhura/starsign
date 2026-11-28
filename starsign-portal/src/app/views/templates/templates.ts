import { DatePipe } from '@angular/common';
import { Component, DestroyRef, inject, signal } from '@angular/core';
import { form, FormField } from '@angular/forms/signals';
import { RouterLink } from '@angular/router';
import { Can } from '@auth/can.directive';
import { SigningTemplateListDTO, SigningTemplateSearchCriteria, TemplateStatus } from '@models/template';
import { TemplateApi } from '@services/template/template-api';
import { Pager } from '@shared/pagination/pager';
import { PagedSearch } from '@shared/pagination/pagination';
import { LoaderState } from '@store/loader-state';
import { templateTone } from './template-meta';

interface TemplateFilter {
  name: string;
  status: TemplateStatus | '';
}

const EMPTY: TemplateFilter = { name: '', status: '' };

/** The organisation's reusable signing templates, searchable by name and status. */
@Component({
  selector: 'app-templates',
  imports: [Can, DatePipe, FormField, Pager, RouterLink],
  templateUrl: './templates.html',
})
export class Templates {
  private readonly templateApi = inject(TemplateApi);

  protected readonly templateTone = templateTone;
  protected readonly filter = signal<TemplateFilter>({ ...EMPTY });
  protected readonly filterForm = form(this.filter);

  protected readonly tabs: { label: string; status: TemplateStatus | '' }[] = [
    { label: 'All', status: '' },
    { label: 'Active', status: TemplateStatus.ACTIVE },
    { label: 'Drafts', status: TemplateStatus.DRAFT },
    { label: 'Archived', status: TemplateStatus.ARCHIVED },
  ];

  protected readonly results = new PagedSearch<SigningTemplateSearchCriteria, SigningTemplateListDTO>(
    (criteria) => this.templateApi.pagedSearch(criteria),
    'Unable to load the templates.',
  );

  constructor() {
    inject(DestroyRef).onDestroy(
      inject(LoaderState).track(() => this.results.loading(), 'Searching templates…'),
    );
    this.search();
  }

  /** Runs the search with the filter's criteria, from the first page. */
  protected search(event?: Event): void {
    event?.preventDefault();
    const f = this.filter();
    void this.results.search({ name: f.name.trim() || null, status: f.status || null });
  }

  protected reset(): void {
    this.filterForm().reset({ ...EMPTY });
    this.search();
  }

  /** A status tab filters at once. */
  protected selectTab(status: TemplateStatus | ''): void {
    this.filterForm.status().value.set(status);
    this.search();
  }
}
