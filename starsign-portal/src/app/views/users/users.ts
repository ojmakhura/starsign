import { DatePipe } from '@angular/common';
import { Component, computed, effect, inject, OnInit, signal, untracked } from '@angular/core';
import { form, FormField } from '@angular/forms/signals';
import { RouterLink } from '@angular/router';
import { Can } from '@auth/can.directive';
import { hasPermission } from '@auth/page-permissions';
import {
  OrganisationUserListDTO,
  OrganisationUserSearchCriteria,
  RoleDTO,
  SystemRole,
  UserInvitationDTO,
  UserInvitationStatus,
  UserStatus,
} from '@models/identity';
import { RoleApi } from '@services/identity/role-api';
import { UserApi } from '@services/identity/user-api';
import { OrganisationApi } from '@services/organisation/organisation-api';
import { UserInvitationApi } from '@services/identity/user-invitation-api';
import { Pager } from '@shared/pagination/pager';
import { PagedSearch } from '@shared/pagination/pagination';
import { StatusChip } from '@shared/status-chip/status-chip';
import { statusLabel } from '@shared/status-chip/status-meta';
import { apiErrorMessage } from '@store/app-state';
import { SessionStore } from '@store/session.store';
import { toast } from 'ngx-sonner';
import { firstValueFrom } from 'rxjs';

interface UserFilter {
  text: string;
  status: UserStatus | '';
  roleCode: string;
}

/**
 * The organisation's users, searchable by name/email, status and role (POST /users/search/paged),
 * and its open invitations (resend or revoke them).
 */
@Component({
  selector: 'app-users',
  imports: [Can, DatePipe, FormField, Pager, RouterLink, StatusChip],
  templateUrl: './users.html',
})
export class Users implements OnInit {
  private readonly organisationApi = inject(OrganisationApi);
  private readonly invitationApi = inject(UserInvitationApi);
  private readonly userApi = inject(UserApi);
  private readonly roleApi = inject(RoleApi);
  private readonly session = inject(SessionStore);

  protected readonly results = new PagedSearch<OrganisationUserSearchCriteria, OrganisationUserListDTO>(
    (criteria) => this.userApi.pagedSearch(criteria),
    'Unable to load the users.',
  );
  protected readonly invitations = signal<UserInvitationDTO[]>([]);
  /** An error loading the invitations, or the account not being linked to an organisation. */
  protected readonly error = signal<string | null>(null);
  /** Id of the invitation being resent or revoked. */
  protected readonly busyId = signal<string | null>(null);

  protected readonly canListInvitations = computed(() =>
    hasPermission(['invitations:list'], this.session.permissions()),
  );

  protected readonly statuses = Object.values(UserStatus);
  protected readonly statusLabel = statusLabel;
  protected readonly roles = signal<RoleDTO[]>([]);

  protected readonly filter = signal<UserFilter>({ text: '', status: '', roleCode: '' });
  protected readonly filterForm = form(this.filter);
  /** Whether the search shown was filtered. */
  protected readonly filtered = computed(() => {
    const applied = this.results.applied();
    return !!(applied?.text || applied?.status || applied?.roleCode);
  });

  /** Role filter options: the organisation's roles, or the roles seen on its users. */
  protected readonly roleOptions = computed(() => {
    const roles = this.roles();
    if (roles.length) {
      return roles.map((role) => ({ code: role.code!, name: role.name || role.code! }));
    }
    const codes = new Set(this.results.items().flatMap((user) => user.roles ?? []));
    const chosen = this.filter().roleCode;
    if (chosen) {
      codes.add(chosen);
    }
    return [...codes].sort().map((code) => ({ code, name: code }));
  });

  constructor() {
    // The first search waits for the session's organisation.
    effect(() => {
      this.session.organisationId();
      untracked(() => this.search());
    });
  }

  ngOnInit(): void {
    void this.loadRoles();
    void this.loadInvitations();
  }

  protected clearFilter(): void {
    this.filter.set({ text: '', status: '', roleCode: '' });
    this.search();
  }

  /** Runs the search with the filter's criteria, from the first page. */
  protected search(event?: Event): void {
    event?.preventDefault();
    const organisationId = this.session.organisationId();
    if (!organisationId) {
      this.error.set('Your account is not linked to an organisation.');
      return;
    }
    const f = this.filter();
    void this.results.search({
      organisationId,
      text: f.text.trim() || null,
      status: f.status || null,
      roleCode: f.roleCode || null,
    });
  }

  private async loadInvitations(): Promise<void> {
    const organisationId = this.session.organisationId();
    if (!organisationId || !this.canListInvitations()) {
      return;
    }
    try {
      this.invitations.set(
        await firstValueFrom(
          this.organisationApi.findInvitations(organisationId, UserInvitationStatus.PENDING),
        ),
      );
    } catch (error) {
      this.error.set(apiErrorMessage(error, 'Unable to load the invitations.'));
    }
  }

  private async loadRoles(): Promise<void> {
    const organisationId = this.session.organisationId();
    if (!organisationId || !hasPermission(['roles:list'], this.session.permissions())) {
      return;
    }
    try {
      const roles = await firstValueFrom(this.roleApi.findRoles(organisationId));
      this.roles.set(
        roles
          .filter((role) => role.code && role.systemRole !== SystemRole.PLATFORM_ADMIN)
          .sort((a, b) => (a.name ?? a.code!).localeCompare(b.name ?? b.code!)),
      );
    } catch {
      // The role filter falls back to the roles seen on the users.
    }
  }

  protected async resend(invitation: UserInvitationDTO): Promise<void> {
    await this.act(invitation, () => this.invitationApi.resend(invitation.id!), 'Invitation resent');
  }

  protected async revoke(invitation: UserInvitationDTO): Promise<void> {
    if (!confirm(`Revoke the invitation to ${invitation.emailAddress}?`)) {
      return;
    }
    await this.act(invitation, () => this.invitationApi.revoke(invitation.id!), 'Invitation revoked');
  }

  protected fullName(person: { firstName?: string | null; lastName?: string | null }): string {
    return [person.firstName, person.lastName].filter(Boolean).join(' ') || '—';
  }

  private async act(
    invitation: UserInvitationDTO,
    call: () => ReturnType<UserInvitationApi['resend']>,
    done: string,
  ): Promise<void> {
    this.busyId.set(invitation.id ?? null);
    try {
      await firstValueFrom(call());
      toast.success(done, { description: invitation.emailAddress ?? undefined });
      await this.loadInvitations();
    } catch (error) {
      toast.error(apiErrorMessage(error, 'The invitation could not be updated.'));
    } finally {
      this.busyId.set(null);
    }
  }
}
