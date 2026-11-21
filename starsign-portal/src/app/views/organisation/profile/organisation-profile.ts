import { DatePipe } from '@angular/common';
import { Component, computed, inject, OnInit, signal } from '@angular/core';
import {
  disabled,
  email,
  form,
  FormField,
  FormRoot,
  maxLength,
  pattern,
  required,
  validate,
} from '@angular/forms/signals';
import { hasPermission } from '@auth/page-permissions';
import { AddressDTO, OrganisationDTO, OrganisationStatus } from '@models/organisation';
import { OrganisationApi } from '@services/organisation/organisation-api';
import { FieldErrors } from '@shared/field-errors/field-errors';
import { apiErrorMessage, apiFieldErrors } from '@store/app-state';
import { SessionStore } from '@store/session.store';
import { toast } from 'ngx-sonner';
import { firstValueFrom } from 'rxjs';

interface AddressModel {
  line1: string;
  line2: string;
  city: string;
  postalCode: string;
  country: string;
}

interface ProfileModel {
  name: string;
  tradingName: string;
  emailAddress: string;
  phoneNumber: string;
  website: string;
  timeZone: string;
  locale: string;
  physical: AddressModel;
  postal: AddressModel;
}

/** Same rules as OrganisationServiceImpl.handleUpdateProfile. */
const PHONE = /^\+?[0-9 ()-]{6,32}$/;
const WEBSITE = /^(https?:\/\/)?[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)+([/?#].*)?$/i;

const STATUS_TONE: Record<string, string> = {
  [OrganisationStatus.ACTIVE]: 'sealed',
  [OrganisationStatus.PENDING_VERIFICATION]: 'pending',
  [OrganisationStatus.SUSPENDED]: 'voided',
  [OrganisationStatus.INACTIVE]: 'neutral',
  [OrganisationStatus.CLOSED]: 'voided',
};

const STATUS_HELP: Record<string, string> = {
  [OrganisationStatus.ACTIVE]: 'The organisation can upload documents and start signing workflows.',
  [OrganisationStatus.PENDING_VERIFICATION]:
    'Waiting for the email address to be verified or for platform approval. Documents cannot be uploaded yet.',
  [OrganisationStatus.SUSPENDED]:
    'Suspended by the platform: no documents can be uploaded or sent for signing.',
  [OrganisationStatus.INACTIVE]: 'Inactive: no documents can be uploaded or sent for signing.',
  [OrganisationStatus.CLOSED]: 'Closed for good. The profile can no longer be changed.',
};

const emptyAddress = (): AddressModel => ({ line1: '', line2: '', city: '', postalCode: '', country: '' });

/** The organisation's profile: registration details, contact details and addresses. */
@Component({
  selector: 'app-organisation-profile',
  imports: [DatePipe, FieldErrors, FormField, FormRoot],
  templateUrl: './organisation-profile.html',
})
export class OrganisationProfile implements OnInit {
  private readonly organisationApi = inject(OrganisationApi);
  private readonly session = inject(SessionStore);

  protected readonly organisation = signal<OrganisationDTO | null>(null);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);

  protected readonly closed = computed(() => this.organisation()?.status === OrganisationStatus.CLOSED);
  protected readonly canEdit = computed(
    () => hasPermission(['organisations:edit'], this.session.permissions()) && !this.closed(),
  );
  protected readonly statusTone = computed(() => STATUS_TONE[this.organisation()?.status ?? ''] ?? 'neutral');
  protected readonly statusHelp = computed(() => STATUS_HELP[this.organisation()?.status ?? ''] ?? null);

  protected readonly timeZones: string[] = (() => {
    try {
      return (Intl as unknown as { supportedValuesOf(key: string): string[] }).supportedValuesOf('timeZone');
    } catch {
      return [];
    }
  })();

  protected readonly model = signal<ProfileModel>({
    name: '',
    tradingName: '',
    emailAddress: '',
    phoneNumber: '',
    website: '',
    timeZone: '',
    locale: '',
    physical: emptyAddress(),
    postal: emptyAddress(),
  });

  protected readonly profileForm = form(
    this.model,
    (path) => {
      disabled(path, { when: () => !this.canEdit() });
      required(path.name, { message: 'Give the organisation name.' });
      maxLength(path.name, 200);
      maxLength(path.tradingName, 200);
      required(path.emailAddress, { message: 'Give the organisation email address.' });
      email(path.emailAddress, { message: 'Enter a valid email address.' });
      pattern(path.phoneNumber, PHONE, { message: 'Use 6 to 32 digits, spaces, brackets or dashes, optionally starting with +.' });
      pattern(path.website, WEBSITE, { message: 'Enter a web address such as https://example.co.bw.' });
      validate(path.timeZone, ({ value }) =>
        value() && this.timeZones.length && !this.timeZones.includes(value())
          ? { kind: 'timeZone', message: 'Choose a time zone from the list, e.g. Africa/Gaborone.' }
          : null,
      );
      pattern(path.locale, /^[A-Za-z]{2,3}([-_][A-Za-z0-9]{2,8})*$/, { message: 'Use a locale such as en or en-BW.' });
      required(path.physical.line1, { message: 'Give the first line of the physical address.' });
      required(path.physical.city, { message: 'Give the city.' });
      required(path.physical.country, { message: 'Give the country.' });
    },
    {
      submission: {
        action: async (field) => {
          const organisationId = this.session.organisationId();
          if (!organisationId) {
            return { kind: 'server', message: 'Your account is not linked to an organisation.' };
          }
          try {
            const saved = await firstValueFrom(
              this.organisationApi.updateProfile(organisationId, this.toRequest(this.model())),
            );
            this.apply(saved);
            toast.success('Profile saved');
            return undefined;
          } catch (error) {
            const fieldErrors = apiFieldErrors(error);
            const mapped = (['name', 'tradingName', 'emailAddress', 'phoneNumber', 'website', 'timeZone', 'locale'] as const)
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

  ngOnInit(): void {
    void this.load();
  }

  protected async load(): Promise<void> {
    const organisationId = this.session.organisationId();
    if (!organisationId) {
      this.loading.set(false);
      return;
    }
    this.loading.set(true);
    this.loadError.set(null);
    try {
      this.apply(await firstValueFrom(this.organisationApi.findById(organisationId)));
    } catch (error) {
      this.loadError.set(apiErrorMessage(error, 'Unable to load the organisation.'));
    } finally {
      this.loading.set(false);
    }
  }

  protected copyPhysicalToPostal(): void {
    this.model.update((m) => ({ ...m, postal: { ...m.physical, postalCode: m.postal.postalCode } }));
  }

  private apply(organisation: OrganisationDTO): void {
    this.organisation.set(organisation);
    this.model.set({
      name: organisation.name ?? '',
      tradingName: organisation.tradingName ?? '',
      emailAddress: organisation.emailAddress ?? '',
      phoneNumber: organisation.phoneNumber ?? '',
      website: organisation.website ?? '',
      timeZone: organisation.timeZone ?? '',
      locale: organisation.locale ?? '',
      physical: toAddressModel(organisation.physicalAddress),
      postal: toAddressModel(organisation.postalAddress),
    });
    this.profileForm().reset();
  }

  /** A PATCH: blank optional values clear the field; the registration number is never sent. */
  private toRequest(model: ProfileModel): OrganisationDTO {
    return {
      rowVersion: this.organisation()?.rowVersion ?? null,
      name: model.name.trim(),
      tradingName: model.tradingName.trim(),
      emailAddress: model.emailAddress.trim(),
      phoneNumber: model.phoneNumber.trim(),
      website: model.website.trim(),
      timeZone: model.timeZone.trim() || null,
      locale: model.locale.trim() || null,
      physicalAddress: toAddress(model.physical),
      postalAddress: toAddress(model.postal),
    };
  }
}

function toAddressModel(address: AddressDTO | null | undefined): AddressModel {
  return {
    line1: address?.line1 ?? '',
    line2: address?.line2 ?? '',
    city: address?.city ?? '',
    postalCode: address?.postalCode ?? '',
    country: address?.country ?? '',
  };
}

function toAddress(address: AddressModel): AddressDTO {
  return {
    line1: address.line1.trim() || null,
    line2: address.line2.trim() || null,
    city: address.city.trim() || null,
    postalCode: address.postalCode.trim() || null,
    country: address.country.trim() || null,
  };
}
