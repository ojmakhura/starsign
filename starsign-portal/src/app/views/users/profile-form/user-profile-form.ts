import { Component, computed, effect, inject, input, output, signal } from '@angular/core';
import {
  disabled,
  form,
  FormField,
  FormRoot,
  maxLength,
  pattern,
  required,
} from '@angular/forms/signals';
import { OrganisationUserDTO, UserProfileUpdateDTO } from '@models/identity';
import { UserApi } from '@services/identity/user-api';
import { FieldErrors } from '@shared/field-errors/field-errors';
import { apiErrorMessage, apiFieldErrors } from '@store/app-state';
import { toast } from 'ngx-sonner';
import { firstValueFrom } from 'rxjs';

interface ProfileModel {
  firstName: string;
  lastName: string;
  jobTitle: string;
  phoneNumber: string;
  locale: string;
}

const PHONE = /^\+?[0-9 ()-]{6,32}$/;
const LOCALE = /^[A-Za-z]{2,3}([-_][A-Za-z0-9]{2,8})*$/;

/**
 * A user's name, job title, phone number and locale (PATCH /users/{id}, users:edit).
 * Read-only when `editable` is false. Emits the saved user.
 */
@Component({
  selector: 'app-user-profile-form',
  imports: [FieldErrors, FormField, FormRoot],
  templateUrl: './user-profile-form.html',
})
export class UserProfileForm {
  readonly user = input.required<OrganisationUserDTO>();
  readonly editable = input(false);
  readonly saved = output<OrganisationUserDTO>();

  private readonly userApi = inject(UserApi);

  protected readonly model = signal<ProfileModel>(toModel({}));

  protected readonly profileForm = form(
    this.model,
    (path) => {
      disabled(path, { when: () => !this.editable() });
      required(path.firstName, { message: 'Give the first name.' });
      maxLength(path.firstName, 100);
      required(path.lastName, { message: 'Give the last name.' });
      maxLength(path.lastName, 100);
      maxLength(path.jobTitle, 100);
      pattern(path.phoneNumber, PHONE, {
        message: 'Use 6 to 32 digits, spaces, brackets or dashes, optionally starting with +.',
      });
      pattern(path.locale, LOCALE, { message: 'Use a locale such as en or en-BW.' });
    },
    {
      submission: {
        action: async (field) => {
          const id = this.user().id;
          if (!id) {
            return { kind: 'server', message: 'This account has no StarSign profile to update.' };
          }
          try {
            const saved = await firstValueFrom(this.userApi.updateProfile(id, this.toRequest()));
            toast.success('Profile saved');
            this.saved.emit(saved);
            return undefined;
          } catch (error) {
            const fieldErrors = apiFieldErrors(error);
            const mapped = (['firstName', 'lastName', 'jobTitle', 'phoneNumber', 'locale'] as const)
              .filter((key) => fieldErrors[key])
              .map((key) => ({ kind: 'server', message: fieldErrors[key], fieldTree: field[key] }));
            return mapped.length
              ? mapped
              : { kind: 'server', message: apiErrorMessage(error, 'The profile could not be saved.') };
          }
        },
      },
    },
  );

  protected readonly serverError = computed(
    () =>
      this.profileForm()
        .errors()
        .find((error) => error.kind === 'server')?.message ?? null,
  );

  constructor() {
    effect(() => this.model.set(toModel(this.user())));
  }

  protected reset(): void {
    this.model.set(toModel(this.user()));
    this.profileForm().reset();
  }

  /** Empty strings clear the optional fields (the API trims them to null). */
  private toRequest(): UserProfileUpdateDTO {
    const model = this.model();
    return {
      firstName: model.firstName.trim(),
      lastName: model.lastName.trim(),
      jobTitle: model.jobTitle.trim(),
      phoneNumber: model.phoneNumber.trim(),
      locale: model.locale.trim(),
    };
  }
}

function toModel(user: OrganisationUserDTO): ProfileModel {
  return {
    firstName: user.firstName ?? '',
    lastName: user.lastName ?? '',
    jobTitle: user.jobTitle ?? '',
    phoneNumber: user.phoneNumber ?? '',
    locale: user.locale ?? '',
  };
}
