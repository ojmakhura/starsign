import { Component, computed, inject, signal } from '@angular/core';
import {
  email,
  form,
  FormField,
  FormRoot,
  maxLength,
  pattern,
  required,
  validate,
} from '@angular/forms/signals';
import { RouterLink } from '@angular/router';
import { OrganisationDTO, OrganisationRegistrationDTO } from '@models/organisation';
import { OrganisationApi } from '@services/organisation/organisation-api';
import { FieldErrors } from '@shared/field-errors/field-errors';
import { apiErrorMessage } from '@store/app-state';
import Keycloak from 'keycloak-js';
import { firstValueFrom } from 'rxjs';

interface RegistrationModel {
  name: string;
  registrationNumber: string;
  tradingName: string;
  emailAddress: string;
  phoneNumber: string;
  website: string;
  line1: string;
  line2: string;
  city: string;
  postalCode: string;
  country: string;
  adminFirstName: string;
  adminLastName: string;
  adminEmailAddress: string;
  adminPhoneNumber: string;
  acceptTerms: boolean;
}

const PHONE = /^\+?[0-9 ()-]{6,20}$/;
const WEBSITE = /^(https?:\/\/)?[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)+(\/\S*)?$/;

/**
 * Public sign-up (POST /organisations). Creates the organisation and its administrator's
 * Keycloak account; Keycloak then emails the administrator to verify their address and
 * choose a password, after which they sign in and invite their users.
 */
@Component({
  selector: 'app-organisation-register',
  imports: [FieldErrors, FormField, FormRoot, RouterLink],
  templateUrl: './organisation-register.html',
})
export class OrganisationRegister {
  private readonly organisationApi = inject(OrganisationApi);
  protected readonly keycloak = inject(Keycloak);

  /** The registered organisation and its administrator's email, once submitted. */
  protected readonly registered = signal<{ organisation: OrganisationDTO; adminEmail: string } | null>(
    null,
  );

  protected readonly model = signal<RegistrationModel>({
    name: '',
    registrationNumber: '',
    tradingName: '',
    emailAddress: '',
    phoneNumber: '',
    website: '',
    line1: '',
    line2: '',
    city: '',
    postalCode: '',
    country: 'Botswana',
    adminFirstName: '',
    adminLastName: '',
    adminEmailAddress: '',
    adminPhoneNumber: '',
    acceptTerms: false,
  });

  protected readonly registerForm = form(
    this.model,
    (path) => {
      required(path.name, { message: 'Give the organisation name.' });
      maxLength(path.name, 200);
      required(path.registrationNumber, { message: 'Give the company registration number.' });
      maxLength(path.registrationNumber, 64);
      maxLength(path.tradingName, 200);
      required(path.emailAddress, { message: 'Give the organisation email address.' });
      email(path.emailAddress, { message: 'Enter a valid email address.' });
      pattern(path.phoneNumber, PHONE, { message: 'Enter a valid phone number.' });
      pattern(path.website, WEBSITE, { message: 'Enter a valid web address.' });

      required(path.line1, { message: 'Give the first line of the address.' });
      required(path.city, { message: 'Give the city or town.' });
      required(path.country, { message: 'Give the country.' });

      required(path.adminFirstName, { message: 'Give the first name.' });
      required(path.adminLastName, { message: 'Give the last name.' });
      required(path.adminEmailAddress, { message: 'Give the administrator email address.' });
      email(path.adminEmailAddress, { message: 'Enter a valid email address.' });
      pattern(path.adminPhoneNumber, PHONE, { message: 'Enter a valid phone number.' });

      validate(path.acceptTerms, ({ value }) =>
        value() ? null : { kind: 'terms', message: 'Accept the terms to register.' },
      );
    },
    {
      submission: {
        action: async () => {
          const model = this.model();
          try {
            const organisation = await firstValueFrom(
              this.organisationApi.register(this.toRegistration(model)),
            );
            this.registered.set({ organisation, adminEmail: model.adminEmailAddress.trim() });
            return undefined;
          } catch (error) {
            return { kind: 'server', message: apiErrorMessage(error, 'The registration failed.') };
          }
        },
      },
    },
  );

  protected readonly serverError = computed(
    () =>
      this.registerForm()
        .errors()
        .find((error) => error.kind === 'server')?.message ?? null,
  );

  protected signIn(): void {
    this.keycloak.login({ redirectUri: window.location.origin + '/' });
  }

  private toRegistration(model: RegistrationModel): OrganisationRegistrationDTO {
    const trim = (value: string) => value.trim() || null;
    return {
      name: model.name.trim(),
      registrationNumber: model.registrationNumber.trim(),
      tradingName: trim(model.tradingName),
      emailAddress: model.emailAddress.trim(),
      phoneNumber: trim(model.phoneNumber),
      website: trim(model.website),
      physicalAddress: {
        line1: model.line1.trim(),
        line2: trim(model.line2),
        city: model.city.trim(),
        postalCode: trim(model.postalCode),
        country: model.country.trim(),
      },
      adminFirstName: model.adminFirstName.trim(),
      adminLastName: model.adminLastName.trim(),
      adminEmailAddress: model.adminEmailAddress.trim(),
      adminPhoneNumber: trim(model.adminPhoneNumber),
      acceptTerms: model.acceptTerms,
      timeZone: Intl.DateTimeFormat().resolvedOptions().timeZone || null,
      locale: navigator.language || null,
    };
  }
}
