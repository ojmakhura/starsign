import { DatePipe } from '@angular/common';
import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { Can } from '@auth/can.directive';
import { hasPermission } from '@auth/page-permissions';
import { PermissionDTO, RoleDTO } from '@models/identity';
import { RoleApi } from '@services/identity/role-api';
import { apiErrorMessage } from '@store/app-state';
import { SessionStore } from '@store/session.store';
import { toast } from 'ngx-sonner';
import { firstValueFrom } from 'rxjs';
import { PermissionMatrix } from '../permission-matrix/permission-matrix';

/**
 * A role and its permissions. Custom roles' permissions are set from the catalogue
 * (PUT /roles/{id}/permissions, roles:manage); built-in roles are read-only.
 */
@Component({
  selector: 'app-role-detail',
  imports: [Can, DatePipe, PermissionMatrix, RouterLink],
  templateUrl: './role-detail.html',
})
export class RoleDetail {
  readonly id = input.required<string>();

  private readonly roleApi = inject(RoleApi);
  private readonly session = inject(SessionStore);
  private readonly router = inject(Router);

  protected readonly role = signal<RoleDTO | null>(null);
  protected readonly catalogue = signal<PermissionDTO[]>([]);
  protected readonly selected = signal<string[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly saving = signal(false);
  protected readonly deleting = signal(false);

  protected readonly canManage = computed(
    () => !this.role()?.builtIn && hasPermission(['roles:manage'], this.session.permissions()),
  );

  /** Catalogue entries a custom organisation role may hold, plus any it already holds. */
  protected readonly editablePermissions = computed(() => {
    const held = new Set(this.assignedCodes());
    return this.catalogue().filter((permission) => !permission.platformOnly || held.has(permission.code ?? ''));
  });

  private readonly assignedCodes = computed(
    () => (this.role()?.permissions ?? []).map((permission) => permission.code).filter((code): code is string => !!code),
  );

  protected readonly dirty = computed(() => {
    const assigned = new Set(this.assignedCodes());
    const selected = new Set(this.selected());
    return assigned.size !== selected.size || [...selected].some((code) => !assigned.has(code));
  });

  constructor() {
    effect(() => {
      void this.load(this.id());
    });
  }

  private async load(id: string): Promise<void> {
    this.loading.set(true);
    this.error.set(null);
    try {
      const role = await firstValueFrom(this.roleApi.findRoleById(id));
      this.setRole(role);
      if (!role.builtIn && hasPermission(['roles:manage'], this.session.permissions())) {
        this.catalogue.set(await firstValueFrom(this.roleApi.getPermissionCatalogue()));
      }
    } catch (error) {
      this.error.set(apiErrorMessage(error, 'Unable to load the role.'));
    } finally {
      this.loading.set(false);
    }
  }

  private setRole(role: RoleDTO): void {
    this.role.set(role);
    this.selected.set(
      (role.permissions ?? []).map((permission) => permission.code).filter((code): code is string => !!code),
    );
  }

  protected reset(): void {
    this.selected.set(this.assignedCodes());
  }

  protected async savePermissions(): Promise<void> {
    const role = this.role();
    if (!role?.id || role.builtIn) {
      return;
    }
    this.saving.set(true);
    try {
      const saved = await firstValueFrom(this.roleApi.setRolePermissions(role.id, this.selected()));
      this.setRole(saved);
      toast.success('Permissions saved', { description: saved.name || saved.code || undefined });
    } catch (error) {
      toast.error(apiErrorMessage(error, 'The permissions could not be saved.'));
    } finally {
      this.saving.set(false);
    }
  }

  protected async remove(): Promise<void> {
    const role = this.role();
    if (!role?.id || role.builtIn) {
      return;
    }
    if (!confirm(`Delete the role ${role.name || role.code}? Users lose the permissions it gives.`)) {
      return;
    }
    this.deleting.set(true);
    try {
      await firstValueFrom(this.roleApi.removeRole(role.id));
      toast.success('Role deleted', { description: role.name || role.code || undefined });
      this.router.navigate(['/roles']);
    } catch (error) {
      toast.error(apiErrorMessage(error, 'The role could not be deleted.'));
    } finally {
      this.deleting.set(false);
    }
  }
}
