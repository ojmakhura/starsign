import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, computed, DestroyRef, inject, input, resource, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Can } from '@auth/can.directive';
import { SigningTemplateDTO, SigningTemplateSignatoryDTO, TemplateStatus } from '@models/template';
import { TemplateApi } from '@services/template/template-api';
import { apiErrorMessage } from '@store/app-state';
import { LoaderState } from '@store/loader-state';
import { toast } from 'ngx-sonner';
import { firstValueFrom } from 'rxjs';
import {
  activationIssues,
  assuranceLabel,
  fieldTypeLabel,
  highestFieldPage,
  signatoryTypeLabel,
  stagesOf,
  templateTone,
  verificationLabel,
} from '../template-meta';

/** One template: its roles by stage, its field layout and its lifecycle actions. */
@Component({
  selector: 'app-template-details',
  imports: [Can, DatePipe, DecimalPipe, RouterLink],
  templateUrl: './template-details.html',
})
export class TemplateDetails {
  readonly id = input.required<string>();

  private readonly templateApi = inject(TemplateApi);

  protected readonly template = resource({
    params: () => this.id(),
    loader: ({ params }) => firstValueFrom(this.templateApi.findById(params)),
  });
  protected readonly tpl = computed<SigningTemplateDTO | null>(() =>
    this.template.hasValue() ? this.template.value() : null,
  );
  protected readonly loadError = computed(() => {
    const error = this.template.error() as (Error & { cause?: unknown }) | undefined;
    return error ? apiErrorMessage(error.cause ?? error, 'The template could not be loaded.') : null;
  });

  protected readonly stages = computed(() => stagesOf(this.tpl()));
  protected readonly issues = computed(() => activationIssues(this.tpl()));
  protected readonly highestPage = computed(() => highestFieldPage(this.tpl()));
  protected readonly busy = signal<'activate' | 'archive' | null>(null);

  protected readonly TemplateStatus = TemplateStatus;
  protected readonly templateTone = templateTone;
  protected readonly signatoryTypeLabel = signatoryTypeLabel;
  protected readonly verificationLabel = verificationLabel;
  protected readonly assuranceLabel = assuranceLabel;
  protected readonly fieldTypeLabel = fieldTypeLabel;

  constructor() {
    inject(DestroyRef).onDestroy(
      inject(LoaderState).track(
        () => this.template.isLoading() || !!this.busy(),
        'Loading template…',
      ),
    );
  }

  protected roleName(roleId: string | null | undefined): string {
    return this.tpl()?.signatories?.find((role) => role.id === roleId)?.roleName ?? '—';
  }

  protected fieldCount(role: SigningTemplateSignatoryDTO): number {
    return (this.tpl()?.fields ?? []).filter((field) => field.signatoryRoleId === role.id).length;
  }

  protected async activate(): Promise<void> {
    await this.act('activate', () => this.templateApi.activate(this.id()), 'Template activated');
  }

  protected async archive(): Promise<void> {
    if (
      !confirm(
        `Archive "${this.tpl()?.name}"? It can no longer be used or changed; workflows already made from it are not affected.`,
      )
    ) {
      return;
    }
    await this.act('archive', () => this.templateApi.archive(this.id()), 'Template archived');
  }

  private async act(
    action: 'activate' | 'archive',
    call: () => ReturnType<TemplateApi['activate']>,
    done: string,
  ): Promise<void> {
    this.busy.set(action);
    try {
      const updated = await firstValueFrom(call());
      this.template.set(updated);
      toast.success(done, { description: updated.name ?? undefined });
    } catch (error) {
      toast.error(apiErrorMessage(error, `The template could not be ${action}d.`));
    } finally {
      this.busy.set(null);
    }
  }
}
