import { Component, computed, inject, OnInit, signal } from '@angular/core';
import {
  email,
  form,
  FormField,
  FormRoot,
  maxLength,
  required,
  validate,
} from '@angular/forms/signals';
import { Router, RouterLink } from '@angular/router';
import { RoleDTO, SystemRole, UserInvitationRequestDTO } from '@models/identity';
import { RoleApi } from '@services/identity/role-api';
import { OrganisationApi } from '@services/organisation/organisation-api';
import { FieldErrors } from '@shared/field-errors/field-errors';
import { apiErrorMessage, apiFieldErrors } from '@store/app-state';
import { SessionStore } from '@store/session.store';
import { toast } from 'ngx-sonner';
import { firstValueFrom } from 'rxjs';

interface InviteModel {
  emailAddress: string;
  firstName: string;
  lastName: string;
  jobTitle: string;
  roleCodes: string[];
  message: string;
}

/** The role a new invitee gets by default: uploads documents and sends them for signing. */
const DEFAULT_ROLE = SystemRole.ORGANISATION_USER;

/**
 * Invites a user to the organisation (POST /organisations/{id}/users/invitations).
 * Keycloak creates the account and emails them to set a password; StarSign emails the
 * invitation link, and accepting it gives them the chosen roles.
 */
@Component({
  selector: 'app-user-invite',
  imports: [FieldErrors, FormField, FormRoot, RouterLink],
  templateUrl: './user-invite.html',
})
export class UserInvite implements OnInit {
  private readonly organisationApi = inject(OrganisationApi);
  private readonly roleApi = inject(RoleApi);
  private readonly session = inject(SessionStore);
  private readonly router = inject(Router);

  protected readonly roles = signal<RoleDTO[]>([]);
  protected readonly rolesError = signal<string | null>(null);

  protected readonly model = signal<InviteModel>({
    emailAddress: '',
    firstName: '',
    lastName: '',
    jobTitle: '',
    roleCodes: [],
    message: '',
  });

  protected readonly inviteForm = form(
    this.model,
    (path) => {
      required(path.emailAddress, { message: 'Give the email address.' });
      email(path.emailAddress, { message: 'Enter a valid email address.' });
      required(path.firstName, { message: 'Give the first name.' });
      required(path.lastName, { message: 'Give the last name.' });
      maxLength(path.jobTitle, 100);
      maxLength(path.message, 1000, { message: 'Keep the message under 1000 characters.' });
      validate(path.roleCodes, ({ value }) =>
        value().length ? null : { kind: 'roles', message: 'Choose at least one role.' },
      );
    },
    {
      submission: {
        action: async (field) => {
          const organisationId = this.session.organisationId();
          if (!organisationId) {
            return { kind: 'server', message: 'Your account is not linked to an organisation.' };
          }
          const model = this.model();
          try {
            const invitation = await firstValueFrom(
              this.organisationApi.invite(organisationId, this.toRequest(model)),
            );
            toast.success('Invitation sent', {
              description: `${invitation.emailAddress} will get an email to set a password and accept.`,
            });
            this.router.navigate(['/users']);
            return undefined;
          } catch (error) {
            const fieldErrors = apiFieldErrors(error);
            const mapped = (['emailAddress', 'firstName', 'lastName', 'jobTitle'] as const)
              .filter((key) => fieldErrors[key])
              .map((key) => ({ kind: 'server', message: fieldErrors[key], fieldTree: field[key] }));
            return mapped.length
              ? mapped
              : { kind: 'server', message: apiErrorMessage(error, 'The invitation failed.') };
          }
        },
      },
    },
  );

  protected readonly serverError = computed(
    () =>
      this.inviteForm()
        .errors()
        .find((error) => error.kind === 'server')?.message ?? null,
  );

  async ngOnInit(): Promise<void> {
    const organisationId = this.session.organisationId();
    if (!organisationId) {
      this.rolesError.set('Your account is not linked to an organisation.');
      return;
    }
    try {
      const roles = (await firstValueFrom(this.roleApi.findRoles(organisationId)))
        // Platform roles are never given to organisation users.
        .filter((role) => role.systemRole !== SystemRole.PLATFORM_ADMIN && role.code)
        .sort((a, b) => (a.name ?? a.code!).localeCompare(b.name ?? b.code!));
      this.roles.set(roles);
      const defaultRole = roles.find((role) => role.systemRole === DEFAULT_ROLE || role.code === DEFAULT_ROLE);
      if (defaultRole && !this.model().roleCodes.length) {
        this.model.update((m) => ({ ...m, roleCodes: [defaultRole.code!] }));
      }
    } catch (error) {
      this.rolesError.set(apiErrorMessage(error, 'Unable to load the roles.'));
    }
  }

  protected isChosen(code: string): boolean {
    return this.model().roleCodes.includes(code);
  }

  protected toggleRole(code: string, chosen: boolean): void {
    this.model.update((m) => ({
      ...m,
      roleCodes: chosen
        ? [...new Set([...m.roleCodes, code])]
        : m.roleCodes.filter((roleCode) => roleCode !== code),
    }));
    this.inviteForm.roleCodes().markAsTouched();
  }

  private toRequest(model: InviteModel): UserInvitationRequestDTO {
    return {
      emailAddress: model.emailAddress.trim(),
      firstName: model.firstName.trim(),
      lastName: model.lastName.trim(),
      jobTitle: model.jobTitle.trim() || null,
      roleCodes: model.roleCodes,
      message: model.message.trim() || null,
    };
  }
}
