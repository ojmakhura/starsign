import { DatePipe } from '@angular/common';
import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { Can } from '@auth/can.directive';
import { hasPermission } from '@auth/page-permissions';
import {
  KeycloakSessionDTO,
  OrganisationUserDTO,
  RoleDTO,
  SystemRole,
  UserStatus,
} from '@models/identity';
import { RoleApi } from '@services/identity/role-api';
import { UserApi } from '@services/identity/user-api';
import { StatusChip } from '@shared/status-chip/status-chip';
import { apiErrorMessage } from '@store/app-state';
import { SessionStore } from '@store/session.store';
import { toast } from 'ngx-sonner';
import { firstValueFrom, Observable } from 'rxjs';
import { groupCodes } from '../../roles/permission-groups';
import { UserProfileForm } from '../profile-form/user-profile-form';
import { UserAccountActions } from '../user-account-actions';

/**
 * One organisation user: profile (users:edit), roles and account actions (users:manage),
 * effective permissions, Keycloak sessions, and removal (users:delete). The API refuses
 * deactivating or removing your own account; the buttons are hidden for it too.
 */
@Component({
  selector: 'app-user-detail',
  imports: [Can, DatePipe, RouterLink, StatusChip, UserProfileForm],
  templateUrl: './user-detail.html',
})
export class UserDetail {
  readonly id = input.required<string>();

  private readonly userApi = inject(UserApi);
  private readonly roleApi = inject(RoleApi);
  private readonly accountActions = inject(UserAccountActions);
  private readonly session = inject(SessionStore);
  private readonly router = inject(Router);

  protected readonly user = signal<OrganisationUserDTO | null>(null);
  protected readonly permissions = signal<string[]>([]);
  protected readonly sessions = signal<KeycloakSessionDTO[]>([]);
  protected readonly roles = signal<RoleDTO[]>([]);
  protected readonly chosenRoles = signal<string[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly rolesError = signal<string | null>(null);
  /** The account action in progress ("activate", "roles", …). */
  protected readonly busy = signal<string | null>(null);

  protected readonly UserStatus = UserStatus;

  private readonly can = (permission: string) => hasPermission([permission], this.session.permissions());
  protected readonly canEdit = computed(() => this.can('users:edit'));
  protected readonly canManage = computed(() => this.can('users:manage'));
  protected readonly isSelf = computed(() => {
    const me = this.session.currentUser()?.user;
    const user = this.user();
    return !!user && !!me && (me.id === user.id || (!!me.keycloakUserId && me.keycloakUserId === user.keycloakUserId));
  });

  protected readonly fullName = computed(() => {
    const user = this.user();
    return [user?.firstName, user?.lastName].filter(Boolean).join(' ') || user?.emailAddress || 'User';
  });
  protected readonly permissionGroups = computed(() => groupCodes(this.permissions()));
  protected readonly rolesDirty = computed(() => {
    const current = [...(this.user()?.roles ?? [])].sort().join('|');
    return current !== [...this.chosenRoles()].sort().join('|');
  });
  /** Role names by code, for showing the user's roles. */
  protected readonly roleNames = computed(
    () => new Map(this.roles().map((role) => [role.code ?? '', role.name || role.code || ''])),
  );

  constructor() {
    effect(() => {
      void this.load(this.id());
    });
  }

  private async load(id: string): Promise<void> {
    this.loading.set(true);
    this.error.set(null);
    try {
      this.setUser(await firstValueFrom(this.userApi.findById(id)));
    } catch (error) {
      this.error.set(apiErrorMessage(error, 'Unable to load the user.'));
      this.loading.set(false);
      return;
    }
    this.loading.set(false);
    await Promise.all([this.loadPermissions(id), this.loadSessions(id), this.loadRoles()]);
  }

  private setUser(user: OrganisationUserDTO): void {
    this.user.set(user);
    this.chosenRoles.set([...(user.roles ?? [])]);
  }

  private async loadPermissions(id: string): Promise<void> {
    if (!this.can('users:list')) {
      return;
    }
    try {
      this.permissions.set(await firstValueFrom(this.userApi.getPermissions(id)));
    } catch {
      this.permissions.set([]);
    }
  }

  protected async loadSessions(id = this.id()): Promise<void> {
    if (!this.canManage()) {
      return;
    }
    try {
      this.sessions.set(await firstValueFrom(this.userApi.getSessions(id)));
    } catch (error) {
      toast.error(apiErrorMessage(error, 'Unable to load the sessions.'));
    }
  }

  private async loadRoles(): Promise<void> {
    const organisationId = this.session.organisationId() ?? this.user()?.organisationId;
    if (!organisationId || !this.can('roles:list')) {
      return;
    }
    try {
      const roles = (await firstValueFrom(this.roleApi.findRoles(organisationId)))
        // Platform roles are never given to organisation users.
        .filter((role) => role.systemRole !== SystemRole.PLATFORM_ADMIN && role.code)
        .sort((a, b) => (a.name ?? a.code!).localeCompare(b.name ?? b.code!));
      this.roles.set(roles);
    } catch (error) {
      this.rolesError.set(apiErrorMessage(error, 'Unable to load the roles.'));
    }
  }

  protected onProfileSaved(user: OrganisationUserDTO): void {
    this.setUser(user);
  }

  protected isChosen(code: string): boolean {
    return this.chosenRoles().includes(code);
  }

  protected toggleRole(code: string, chosen: boolean): void {
    this.chosenRoles.update((codes) =>
      chosen ? [...new Set([...codes, code])] : codes.filter((existing) => existing !== code),
    );
  }

  protected resetRoles(): void {
    this.chosenRoles.set([...(this.user()?.roles ?? [])]);
  }

  protected async saveRoles(): Promise<void> {
    if (!this.chosenRoles().length && !confirm('Remove every role? The user will lose access to StarSign pages.')) {
      return;
    }
    await this.run('roles', () => this.userApi.assignRoles(this.id(), { roleCodes: this.chosenRoles() }), 'Roles saved');
    await this.loadPermissions(this.id());
  }

  protected async activate(): Promise<void> {
    await this.run('activate', () => this.userApi.activate(this.id()), 'User activated');
  }

  protected async deactivate(): Promise<void> {
    const reason = prompt(
      `Deactivate ${this.fullName()}? They are signed out and can no longer sign in or sign documents.\n\nReason:`,
    );
    if (reason === null) {
      return;
    }
    if (!reason.trim()) {
      toast.error('Say why the user is deactivated.');
      return;
    }
    await this.run('deactivate', () => this.userApi.deactivate(this.id(), reason.trim()), 'User deactivated');
    await this.loadSessions();
  }

  protected async unlock(): Promise<void> {
    await this.run('unlock', () => this.userApi.unlock(this.id()), 'User unlocked');
  }

  protected async requestPasswordReset(): Promise<void> {
    if (!confirm(`Email ${this.user()?.emailAddress} a link to choose a new password?`)) {
      return;
    }
    await this.run('password', () => this.accountActions.requestPasswordReset(this.id()), 'Password reset email sent');
  }

  protected async requireMfa(): Promise<void> {
    if (!confirm(`Require ${this.fullName()} to set up an authenticator app at their next sign-in?`)) {
      return;
    }
    // mfaEnabled says whether a second factor is set up in Keycloak: it changes once the user has done so
    await this.run('mfa', () => this.accountActions.requireMfa(this.id()), 'Two-factor authentication required');
  }

  protected async terminateSessions(): Promise<void> {
    if (!confirm(`Sign ${this.fullName()} out of every session?`)) {
      return;
    }
    await this.run('sessions', () => this.accountActions.terminateSessions(this.id()), 'Sessions ended');
    await this.loadSessions();
  }

  protected async remove(): Promise<void> {
    if (!confirm(`Remove ${this.fullName()} from the organisation? Their account is disabled and they lose access.`)) {
      return;
    }
    const removed = await this.run('remove', () => this.userApi.remove(this.id()), 'User removed');
    if (removed) {
      this.router.navigate(['/users']);
    }
  }

  /** Runs an account action; a returned user replaces the shown one. Resolves true on success. */
  private async run(
    action: string,
    call: () => Observable<OrganisationUserDTO | boolean | void>,
    done: string,
  ): Promise<boolean> {
    this.busy.set(action);
    try {
      const result = await firstValueFrom(call(), { defaultValue: undefined });
      if (result && typeof result === 'object') {
        this.setUser(result);
      }
      toast.success(done, { description: this.user()?.emailAddress ?? undefined });
      return true;
    } catch (error) {
      toast.error(apiErrorMessage(error, 'The action failed.'));
      return false;
    } finally {
      this.busy.set(null);
    }
  }
}
