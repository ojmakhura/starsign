import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Can } from '@auth/can.directive';
import { RoleDTO } from '@models/identity';
import { RoleApi } from '@services/identity/role-api';
import { apiErrorMessage } from '@store/app-state';
import { SessionStore } from '@store/session.store';
import { toast } from 'ngx-sonner';
import { firstValueFrom } from 'rxjs';

/** The organisation's roles: built-in ones and its own custom roles (GET /roles). */
@Component({
  selector: 'app-roles',
  imports: [Can, RouterLink],
  templateUrl: './roles.html',
})
export class Roles implements OnInit {
  private readonly roleApi = inject(RoleApi);
  private readonly session = inject(SessionStore);

  protected readonly roles = signal<RoleDTO[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly busyId = signal<string | null>(null);

  protected readonly customCount = computed(() => this.roles().filter((role) => !role.builtIn).length);

  ngOnInit(): void {
    void this.load();
  }

  protected async load(): Promise<void> {
    const organisationId = this.session.organisationId();
    if (!organisationId) {
      this.loading.set(false);
      this.error.set('Your account is not linked to an organisation.');
      return;
    }
    this.loading.set(true);
    this.error.set(null);
    try {
      const roles = await firstValueFrom(this.roleApi.findRoles(organisationId));
      // Built-in roles first, then custom ones, each by name.
      this.roles.set(
        [...roles].sort(
          (a, b) =>
            Number(!a.builtIn) - Number(!b.builtIn) ||
            (a.name ?? a.code ?? '').localeCompare(b.name ?? b.code ?? ''),
        ),
      );
    } catch (error) {
      this.error.set(apiErrorMessage(error, 'Unable to load the roles.'));
    } finally {
      this.loading.set(false);
    }
  }

  protected async remove(role: RoleDTO): Promise<void> {
    if (role.builtIn || !role.id) {
      return;
    }
    if (!confirm(`Delete the role ${role.name || role.code}? Users lose the permissions it gives.`)) {
      return;
    }
    this.busyId.set(role.id);
    try {
      await firstValueFrom(this.roleApi.removeRole(role.id));
      toast.success('Role deleted', { description: role.name || role.code || undefined });
      await this.load();
    } catch (error) {
      toast.error(apiErrorMessage(error, 'The role could not be deleted.'));
    } finally {
      this.busyId.set(null);
    }
  }
}
