import { DatePipe } from '@angular/common';
import { Component, computed, DestroyRef, inject, resource, signal } from '@angular/core';
import { debounce, form, FormField } from '@angular/forms/signals';
import { RouterLink } from '@angular/router';
import { Can } from '@auth/can.directive';
import { SigningTemplateDTO, SigningTemplateSearchCriteria, TemplateStatus } from '@models/template';
import { TemplateApi } from '@services/template/template-api';
import { apiErrorMessage } from '@store/app-state';
import { LoaderState } from '@store/loader-state';
import { firstValueFrom } from 'rxjs';
import { stagesOf, templateTone } from './template-meta';

interface TemplateFilter {
  name: string;
  status: TemplateStatus | '';
}

/** The organisation's reusable signing templates, searchable by name and status. */
@Component({
  selector: 'app-templates',
  imports: [Can, DatePipe, FormField, RouterLink],
  templateUrl: './templates.html',
})
export class Templates {
  private readonly templateApi = inject(TemplateApi);

  protected readonly templateTone = templateTone;
  protected readonly filter = signal<TemplateFilter>({ name: '', status: '' });
  protected readonly filterForm = form(this.filter, (path) => {
    debounce(path.name, 350);
  });

  protected readonly tabs: { label: string; status: TemplateStatus | '' }[] = [
    { label: 'All', status: '' },
    { label: 'Active', status: TemplateStatus.ACTIVE },
    { label: 'Drafts', status: TemplateStatus.DRAFT },
    { label: 'Archived', status: TemplateStatus.ARCHIVED },
  ];

  private readonly criteria = computed<SigningTemplateSearchCriteria>(() => {
    const f = this.filter();
    return { name: f.name.trim() || null, status: f.status || null, pageSize: 200 };
  });

  protected readonly templates = resource({
    params: () => this.criteria(),
    loader: ({ params }) => firstValueFrom(this.templateApi.search(params)),
  });
  protected readonly list = computed(() =>
    this.templates.hasValue() ? this.templates.value() : [],
  );
  protected readonly error = computed(() => {
    // A non-Error rejection (HttpErrorResponse) reaches the resource wrapped, as `cause`.
    const error = this.templates.error() as (Error & { cause?: unknown }) | undefined;
    return error ? apiErrorMessage(error.cause ?? error, 'Unable to load the templates.') : null;
  });

  constructor() {
    inject(DestroyRef).onDestroy(
      inject(LoaderState).track(() => this.templates.isLoading(), 'Searching templates…'),
    );
  }

  protected selectTab(status: TemplateStatus | ''): void {
    this.filterForm.status().value.set(status);
  }

  protected stageCount(template: SigningTemplateDTO): number {
    return stagesOf(template).length;
  }
}
