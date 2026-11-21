import { Component, computed, effect, inject, input, resource, signal, untracked } from '@angular/core';
import { form, FormField, FormRoot, maxLength } from '@angular/forms/signals';
import { Router, RouterLink } from '@angular/router';
import { SigningWorkflowDTO } from '@models/workflow';
import { TemplateApi } from '@services/template/template-api';
import { WorkflowApi } from '@services/workflow/workflow-api';
import { FieldErrors } from '@shared/field-errors/field-errors';
import { StatusChip } from '@shared/status-chip/status-chip';
import { apiErrorMessage } from '@store/app-state';
import { toast } from 'ngx-sonner';
import { firstValueFrom } from 'rxjs';

/**
 * /templates/from-workflow/:workflowId — saves a workflow's routing (one role per
 * signatory, its stages and field layout) as a new draft template.
 */
@Component({
  selector: 'app-template-from-workflow',
  imports: [FieldErrors, FormField, FormRoot, RouterLink, StatusChip],
  templateUrl: './template-from-workflow.html',
})
export class TemplateFromWorkflow {
  readonly workflowId = input.required<string>();

  private readonly templateApi = inject(TemplateApi);
  private readonly workflowApi = inject(WorkflowApi);
  private readonly router = inject(Router);

  /** Shown for context only; the template can be made even if this cannot be read. */
  protected readonly workflow = resource({
    params: () => this.workflowId(),
    loader: ({ params }) => firstValueFrom(this.workflowApi.findById(params)),
  });
  protected readonly wf = computed<SigningWorkflowDTO | null>(() =>
    this.workflow.hasValue() ? this.workflow.value() : null,
  );
  protected readonly signatoryCount = computed(() =>
    (this.wf()?.stages ?? []).reduce((sum, stage) => sum + (stage.signatories?.length ?? 0), 0),
  );

  protected readonly model = signal({ name: '' });
  protected readonly nameForm = form(
    this.model,
    (path) => {
      maxLength(path.name, 200);
    },
    { submission: { action: () => this.submit() } },
  );
  protected readonly formError = computed(
    () =>
      this.nameForm()
        .errors()
        .find((error) => error.kind === 'server')?.message ?? null,
  );

  constructor() {
    // Suggest the workflow's title while the name is untouched.
    effect(() => {
      const title = this.wf()?.title;
      untracked(() => {
        const name = this.nameForm.name();
        if (title && !name.dirty()) {
          name.value.set(title);
        }
      });
    });
  }

  private async submit() {
    try {
      const template = await firstValueFrom(
        this.templateApi.createFromWorkflow(this.workflowId(), this.model().name.trim()),
      );
      toast.success('Template created', {
        description: 'Review its roles and fields, then activate it.',
      });
      this.router.navigate(['/templates', template.id]);
      return undefined;
    } catch (error) {
      return {
        kind: 'server',
        message: apiErrorMessage(error, 'The template could not be created from the workflow.'),
      };
    }
  }
}
