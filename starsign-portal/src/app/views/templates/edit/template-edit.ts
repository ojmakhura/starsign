import { Component, computed, DestroyRef, effect, inject, input, resource, signal, untracked } from '@angular/core';
import { FieldTree, form, FormField, FormRoot } from '@angular/forms/signals';
import { Router, RouterLink } from '@angular/router';
import { DocumentListDTO } from '@models/document';
import { SigningTemplateDTO, TemplateStatus } from '@models/template';
import { ExecutionMode, FieldType, SignatoryType } from '@models/workflow';
import { DocumentApi } from '@services/document/document-api';
import { TemplateApi } from '@services/template/template-api';
import { FieldErrors } from '@shared/field-errors/field-errors';
import { apiErrorMessage, apiFieldErrors } from '@store/app-state';
import { LoaderState } from '@store/loader-state';
import { toast } from 'ngx-sonner';
import { firstValueFrom } from 'rxjs';
import {
  ASSURANCE_LEVELS,
  FIELD_TYPES,
  SIGNATORY_TYPES,
  VERIFICATION_METHODS,
} from '../template-meta';
import {
  MAX_FIELDS,
  MAX_ROLES,
  newField,
  newRole,
  newTemplateDraft,
  TemplateDraft,
  templateDraftSchema,
  toTemplateDraft,
  toTemplateDTO,
} from './template-draft';

/** Creates a template (/templates/new) or edits one (/templates/:id/edit). */
@Component({
  selector: 'app-template-edit',
  imports: [FieldErrors, FormField, FormRoot, RouterLink],
  templateUrl: './template-edit.html',
})
export class TemplateEdit {
  /** Route parameter; absent when creating. */
  readonly id = input<string>();

  private readonly templateApi = inject(TemplateApi);
  private readonly documentApi = inject(DocumentApi);
  private readonly router = inject(Router);

  protected readonly editing = computed(() => !!this.id());

  /** The stored template being edited. */
  protected readonly original = resource({
    params: () => this.id(),
    loader: ({ params }) => firstValueFrom(this.templateApi.findById(params)),
  });
  protected readonly loadError = computed(() => {
    const error = this.original.error() as (Error & { cause?: unknown }) | undefined;
    return error ? apiErrorMessage(error.cause ?? error, 'The template could not be loaded.') : null;
  });
  protected readonly archived = computed(
    () => this.original.hasValue() && this.original.value()?.status === TemplateStatus.ARCHIVED,
  );
  protected readonly active = computed(
    () => this.original.hasValue() && this.original.value()?.status === TemplateStatus.ACTIVE,
  );

  /** Documents that may serve as the template's base document (optional). */
  protected readonly documents = resource({
    loader: () => firstValueFrom(this.documentApi.search({ includeArchived: false, pageSize: 200 })),
  });
  protected readonly documentList = computed<DocumentListDTO[]>(() =>
    this.documents.hasValue() ? this.documents.value() : [],
  );

  protected readonly draft = signal<TemplateDraft>(newTemplateDraft());
  protected readonly templateForm = form(this.draft, templateDraftSchema, {
    submission: { action: (field) => this.submit(field) },
  });

  protected readonly ExecutionMode = ExecutionMode;
  protected readonly FieldType = FieldType;
  protected readonly signatoryTypes = SIGNATORY_TYPES;
  protected readonly verificationMethods = VERIFICATION_METHODS;
  protected readonly assuranceLevels = ASSURANCE_LEVELS;
  protected readonly fieldTypes = FIELD_TYPES;
  protected readonly maxRoles = MAX_ROLES;
  protected readonly maxFields = MAX_FIELDS;

  protected readonly formError = computed(
    () =>
      this.templateForm()
        .errors()
        .find((error) => error.kind === 'server')?.message ?? null,
  );

  /** Mandatory roles without a required signature field: the template cannot be activated. */
  protected readonly unsignedRoles = computed(() => {
    const draft = this.draft();
    return draft.roles
      .filter(
        (role) =>
          role.mandatory &&
          role.roleName.trim() &&
          !draft.fields.some((f) => f.roleKey === role.key && f.fieldType === FieldType.SIGNATURE),
      )
      .map((role) => role.roleName.trim());
  });

  /** Roles grouped by stage for the summary. */
  protected readonly stageSummary = computed(() => {
    const stages = new Map<number, string[]>();
    for (const role of this.draft().roles) {
      stages.set(role.stageNumber, [...(stages.get(role.stageNumber) ?? []), role.roleName || 'Unnamed role']);
    }
    return [...stages.entries()].sort(([a], [b]) => a - b);
  });

  constructor() {
    inject(DestroyRef).onDestroy(
      inject(LoaderState).track(() => this.original.isLoading(), 'Loading template…'),
    );
    // Fill the form once the stored template has loaded.
    effect(() => {
      if (this.original.hasValue() && this.original.value()) {
        const template = this.original.value() as SigningTemplateDTO;
        untracked(() => this.draft.set(toTemplateDraft(template)));
      }
    });
  }

  protected roleLabel(key: string): string {
    const index = this.draft().roles.findIndex((role) => role.key === key);
    return index < 0 ? '' : this.draft().roles[index].roleName || `Role ${index + 1}`;
  }

  protected addRole(): void {
    this.draft.update((d) => {
      const stage = Math.max(1, ...d.roles.map((role) => role.stageNumber));
      return { ...d, roles: [...d.roles, newRole(stage, d.roles.length)] };
    });
  }

  protected removeRole(index: number): void {
    const role = this.draft().roles[index];
    const placed = this.draft().fields.filter((field) => field.roleKey === role.key).length;
    if (placed && !confirm(`Remove ${role.roleName || 'this role'} and its ${placed} field(s)?`)) {
      return;
    }
    this.draft.update((d) => ({
      ...d,
      roles: d.roles.filter((_, i) => i !== index),
      fields: d.fields.filter((field) => field.roleKey !== role.key),
    }));
  }

  protected moveRole(index: number, offset: -1 | 1): void {
    this.draft.update((d) => {
      const roles = [...d.roles];
      const target = index + offset;
      if (target < 0 || target >= roles.length) {
        return d;
      }
      [roles[index], roles[target]] = [roles[target], roles[index]];
      return { ...d, roles };
    });
  }

  protected addField(roleKey = this.draft().roles[0]?.key ?? ''): void {
    this.draft.update((d) => ({ ...d, fields: [...d.fields, newField(roleKey)] }));
  }

  /** A required signature field for every mandatory role that lacks one. */
  protected addMissingSignatures(): void {
    this.draft.update((d) => ({
      ...d,
      fields: [
        ...d.fields,
        ...d.roles
          .filter(
            (role) =>
              role.mandatory &&
              !d.fields.some((f) => f.roleKey === role.key && f.fieldType === FieldType.SIGNATURE),
          )
          .map((role, i) => ({
            ...newField(role.key),
            label: `${role.roleName.trim() || 'Signatory'} signature`,
            pageNumber: d.pageCount || 1,
            y: Math.max(0, 85 - i * 8),
          })),
      ],
    }));
  }

  protected removeField(index: number): void {
    this.draft.update((d) => ({ ...d, fields: d.fields.filter((_, i) => i !== index) }));
  }

  private async submit(field: FieldTree<TemplateDraft>) {
    const body = toTemplateDTO(this.draft(), this.original.hasValue() ? this.original.value() : null);
    const id = this.id();
    try {
      const saved = await firstValueFrom(
        id ? this.templateApi.update(id, body) : this.templateApi.create(body),
      );
      toast.success(id ? 'Template saved' : 'Template created', {
        description:
          saved.status === TemplateStatus.ACTIVE ? saved.name ?? undefined : 'Activate it to start using it.',
      });
      this.router.navigate(['/templates', saved.id]);
      return undefined;
    } catch (error) {
      const fieldErrors = apiFieldErrors(error);
      const mapped = (['name', 'description', 'message', 'defaultExpiryDays', 'pageCount'] as const)
        .filter((key) => fieldErrors[key])
        .map((key) => ({ kind: 'server', message: fieldErrors[key], fieldTree: field[key] }));
      return mapped.length
        ? mapped
        : { kind: 'server', message: apiErrorMessage(error, 'The template could not be saved.') };
    }
  }

  protected readonly SignatoryType = SignatoryType;
}
