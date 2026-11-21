import { Component, computed, effect, inject, input, signal } from '@angular/core';
import {
  disabled,
  form,
  FormField,
  FormRoot,
  maxLength,
  pattern,
  required,
} from '@angular/forms/signals';
import { Router, RouterLink } from '@angular/router';
import { RoleDTO } from '@models/identity';
import { RoleApi } from '@services/identity/role-api';
import { FieldErrors } from '@shared/field-errors/field-errors';
import { apiErrorMessage, apiFieldErrors } from '@store/app-state';
import { SessionStore } from '@store/session.store';
import { toast } from 'ngx-sonner';
import { firstValueFrom } from 'rxjs';

interface RoleModel {
  code: string;
  name: string;
  description: string;
}

/**
 * Creates a custom role or edits one (POST /roles). The code is fixed once created; of a
 * built-in role only the description may change.
 */
@Component({
  selector: 'app-role-form',
  imports: [FieldErrors, FormField, FormRoot, RouterLink],
  templateUrl: './role-form.html',
})
export class RoleForm {
  /** The role being edited; absent when creating one. */
  readonly id = input<string>();

  private readonly roleApi = inject(RoleApi);
  private readonly session = inject(SessionStore);
  private readonly router = inject(Router);

  protected readonly role = signal<RoleDTO | null>(null);
  protected readonly loadError = signal<string | null>(null);
  protected readonly isNew = computed(() => !this.id());
  protected readonly builtIn = computed(() => !!this.role()?.builtIn);

  protected readonly model = signal<RoleModel>({ code: '', name: '', description: '' });

  protected readonly roleForm = form(
    this.model,
    (path) => {
      disabled(path.code, { when: () => !this.isNew() });
      disabled(path.name, { when: () => this.builtIn() });
      required(path.code, { message: 'Give the role a code.' });
      pattern(path.code, /^[A-Z][A-Z0-9_]*$/, {
        message: 'Use capital letters, digits and underscores, starting with a letter (e.g. CONTRACT_REVIEWER).',
      });
      maxLength(path.code, 64);
      required(path.name, { message: 'Give the role a name.' });
      maxLength(path.name, 100);
      maxLength(path.description, 500, { message: 'Keep the description under 500 characters.' });
    },
    {
      submission: {
        action: async (field) => {
          const organisationId = this.session.organisationId();
          if (!organisationId) {
            return { kind: 'server', message: 'Your account is not linked to an organisation.' };
          }
          try {
            const saved = await firstValueFrom(this.roleApi.saveRole(this.toRequest(organisationId)));
            toast.success(this.isNew() ? 'Role created' : 'Role saved', {
              description: saved.name || saved.code || undefined,
            });
            this.router.navigate(['/roles', saved.id]);
            return undefined;
          } catch (error) {
            const fieldErrors = apiFieldErrors(error);
            const mapped = (['code', 'name', 'description'] as const)
              .filter((key) => fieldErrors[key])
              .map((key) => ({ kind: 'server', message: fieldErrors[key], fieldTree: field[key] }));
            return mapped.length
              ? mapped
              : { kind: 'server', message: apiErrorMessage(error, 'The role could not be saved.') };
          }
        },
      },
    },
  );

  protected readonly serverError = computed(
    () =>
      this.roleForm()
        .errors()
        .find((error) => error.kind === 'server')?.message ?? null,
  );

  constructor() {
    effect(() => {
      const id = this.id();
      if (id) {
        void this.load(id);
      }
    });
  }

  private async load(id: string): Promise<void> {
    this.loadError.set(null);
    try {
      const role = await firstValueFrom(this.roleApi.findRoleById(id));
      this.role.set(role);
      this.model.set({
        code: role.code ?? '',
        name: role.name ?? '',
        description: role.description ?? '',
      });
    } catch (error) {
      this.loadError.set(apiErrorMessage(error, 'Unable to load the role.'));
    }
  }

  /** Upper-cases the code as it is typed. */
  protected normaliseCode(): void {
    this.model.update((m) => ({ ...m, code: m.code.toUpperCase().replace(/[\s-]+/g, '_') }));
  }

  private toRequest(organisationId: string): RoleDTO {
    const model = this.model();
    const existing = this.role();
    // Permissions are left out: the API keeps them (they are set on the role page).
    return existing
      ? {
          id: existing.id,
          rowVersion: existing.rowVersion,
          code: existing.code,
          name: existing.builtIn ? existing.name : model.name.trim(),
          description: model.description.trim() || null,
          organisationId: existing.organisationId,
        }
      : {
          code: model.code.trim(),
          name: model.name.trim(),
          description: model.description.trim() || null,
          organisationId,
        };
  }
}
