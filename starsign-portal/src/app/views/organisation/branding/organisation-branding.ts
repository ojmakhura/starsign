import { DatePipe } from '@angular/common';
import { Component, computed, DestroyRef, effect, inject, OnInit, signal } from '@angular/core';
import {
  disabled,
  email,
  form,
  FormField,
  FormRoot,
  maxLength,
  pattern,
} from '@angular/forms/signals';
import { hasPermission } from '@auth/page-permissions';
import {
  BrandingAsset,
  BrandingDTO,
  BrandingPreviewDTO,
  BrandingRequest,
  BrandingSeedsDTO,
  ContrastIssueDTO,
} from '@models/branding';
import { BrandingApi } from '@services/branding/branding-api';
import { EnvLoaderService } from '@services/env-loader.service';
import { FieldErrors } from '@shared/field-errors/field-errors';
import { apiErrorMessage } from '@store/app-state';
import { SessionStore } from '@store/session.store';
import { toast } from 'ngx-sonner';
import { firstValueFrom } from 'rxjs';

type SeedKey = keyof Required<BrandingSeedsDTO>;
type SeedsModel = Record<SeedKey, string>;

interface BrandingModel {
  appName: string;
  seeds: SeedsModel;
  supportEmail: string;
  supportUrl: string;
  signingPortalMessage: string;
  emailFooter: string;
}

interface AssetSlot {
  kind: BrandingAsset;
  slug: string;
  label: string;
  hint: string;
  accept: string;
  url: (branding: BrandingDTO) => string | null | undefined;
}

const HEX = /^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$/;
const SEED_KEYS: SeedKey[] = ['primary', 'secondary', 'tertiary', 'neutral', 'error', 'success', 'warning', 'info'];
const SEED_HINTS: Record<SeedKey, string> = {
  primary: 'Buttons, links and highlights',
  secondary: 'Secondary actions and chips',
  tertiary: 'Accents',
  neutral: 'Backgrounds and surfaces',
  error: 'Errors and declines',
  success: 'Completed and signed',
  warning: 'Expiring and pending',
  info: 'Information',
};
const MAX_ASSET_BYTES = 1_048_576;

/** The organisation's look: app name, colour seeds, logos and the wording around signing. */
@Component({
  selector: 'app-organisation-branding',
  imports: [DatePipe, FieldErrors, FormField, FormRoot],
  templateUrl: './organisation-branding.html',
  styles: `
    .swatch {
      inline-size: 38px;
      block-size: 38px;
      flex: none;
      padding: 0;
      border: 1px solid var(--color-hairline-strong);
      border-radius: var(--radius-control);
      background: none;
      cursor: pointer;
    }
    .swatch:disabled {
      cursor: not-allowed;
      opacity: 0.6;
    }
    .asset-box {
      display: grid;
      place-items: center;
      block-size: 72px;
      border-radius: var(--radius-control);
      border: 1px dashed var(--color-hairline-strong);
      overflow: hidden;
    }
    .asset-box img {
      max-block-size: 60px;
      max-inline-size: 90%;
      object-fit: contain;
    }
    .preview {
      border-radius: 10px;
      overflow: hidden;
      background: var(--p-color-background);
      color: var(--p-color-on-background);
      border: 1px solid var(--p-color-outline-variant);
      font-size: 0.875rem;
    }
    .preview__bar {
      display: flex;
      align-items: center;
      gap: 0.6rem;
      padding: 0.7rem 0.9rem;
      background: var(--p-color-surface-container);
      color: var(--p-color-on-surface);
      border-bottom: 1px solid var(--p-color-outline-variant);
      font-weight: 600;
    }
    .preview__bar img {
      max-block-size: 26px;
      max-inline-size: 120px;
      object-fit: contain;
    }
    .preview__card {
      margin: 0.9rem;
      padding: 0.9rem;
      border-radius: 8px;
      background: var(--p-color-surface-container-low);
      color: var(--p-color-on-surface);
      display: flex;
      flex-direction: column;
      gap: 0.6rem;
    }
    .preview__muted {
      color: var(--p-color-on-surface-variant);
    }
    .preview__btn {
      align-self: flex-start;
      padding: 0.45rem 0.9rem;
      border-radius: 6px;
      border: 0;
      background: var(--p-color-primary);
      color: var(--p-color-on-primary);
      font-weight: 600;
    }
    .preview__btn--secondary {
      background: var(--p-color-secondary-container);
      color: var(--p-color-on-secondary-container);
    }
    .preview__link {
      color: var(--p-color-primary);
      text-decoration: underline;
    }
    .preview__chip {
      display: inline-block;
      padding: 0.1rem 0.5rem;
      border-radius: 999px;
      font-size: 0.75rem;
    }
    .preview__footer {
      padding: 0.6rem 0.9rem;
      font-size: 0.75rem;
      color: var(--p-color-on-surface-variant);
      border-top: 1px solid var(--p-color-outline-variant);
      white-space: pre-line;
    }
  `,
})
export class OrganisationBranding implements OnInit {
  private readonly brandingApi = inject(BrandingApi);
  private readonly env = inject(EnvLoaderService);
  private readonly session = inject(SessionStore);

  protected readonly branding = signal<BrandingDTO | null>(null);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly preview = signal<BrandingPreviewDTO | null>(null);
  protected readonly previewing = signal(false);
  protected readonly previewMode = signal<'light' | 'dark'>('light');
  /** Slug of the asset being uploaded or removed. */
  protected readonly busyAsset = signal<string | null>(null);
  protected readonly resetting = signal(false);

  protected readonly seedKeys = SEED_KEYS;
  protected readonly seedHints = SEED_HINTS;

  protected readonly canEdit = computed(() =>
    hasPermission(['branding:configure'], this.session.permissions()),
  );

  protected readonly assetSlots: AssetSlot[] = [
    { kind: BrandingAsset.LOGO_LIGHT, slug: 'logo-light', label: 'Logo for light backgrounds', hint: 'PNG, JPEG, WebP or SVG', accept: 'image/png,image/jpeg,image/webp,image/svg+xml', url: (b) => b.logoLightUrl },
    { kind: BrandingAsset.LOGO_DARK, slug: 'logo-dark', label: 'Logo for dark backgrounds', hint: 'PNG, JPEG, WebP or SVG', accept: 'image/png,image/jpeg,image/webp,image/svg+xml', url: (b) => b.logoDarkUrl },
    { kind: BrandingAsset.FAVICON, slug: 'favicon', label: 'Favicon', hint: 'PNG or ICO', accept: 'image/png,image/x-icon,.ico', url: (b) => b.faviconUrl },
    { kind: BrandingAsset.EMAIL_LOGO, slug: 'email-logo', label: 'Email logo', hint: 'PNG or JPEG (email clients do not show SVG)', accept: 'image/png,image/jpeg', url: (b) => b.emailLogoUrl },
  ];

  protected readonly model = signal<BrandingModel>(toModel({}));

  protected readonly brandingForm = form(
    this.model,
    (path) => {
      disabled(path, { when: () => !this.canEdit() });
      maxLength(path.appName, 100, { message: 'The app name is limited to 100 characters.' });
      for (const key of SEED_KEYS) {
        pattern(path.seeds[key], HEX, { message: 'Use a hex colour such as #1a73e8.' });
      }
      email(path.supportEmail, { message: 'Enter a valid email address.' });
      maxLength(path.supportEmail, 254);
      pattern(path.supportUrl, /^https?:\/\/[^\s/]+\.[^\s]+$/i, {
        message: 'Use a web address starting with https:// or http://.',
      });
      maxLength(path.supportUrl, 500);
      maxLength(path.signingPortalMessage, 1000, { message: 'Keep the message under 1000 characters.' });
      maxLength(path.emailFooter, 2000, { message: 'Keep the footer under 2000 characters.' });
    },
    {
      submission: {
        action: async () => {
          const organisationId = this.session.organisationId();
          if (!organisationId) {
            return { kind: 'server', message: 'Your account is not linked to an organisation.' };
          }
          try {
            const saved = await firstValueFrom(
              this.brandingApi.saveForOrganisation(organisationId, this.toRequest(this.model())),
            );
            this.apply(saved);
            toast.success('Branding saved', {
              description: 'The portal, signing pages and emails use it from now on.',
            });
            return undefined;
          } catch (error) {
            return { kind: 'server', message: apiErrorMessage(error, 'The branding could not be saved.') };
          }
        },
      },
    },
  );

  protected readonly serverError = computed(
    () =>
      this.brandingForm()
        .errors()
        .find((error) => error.kind === 'server')?.message ?? null,
  );

  /** Tokens of the live preview, else the saved ones. */
  private readonly tokens = computed(() => this.preview()?.tokens ?? this.branding()?.tokens ?? null);

  /** CSS custom properties (--p-color-…) for the preview in the chosen mode. */
  protected readonly previewStyle = computed(() => {
    const tokens = this.tokens();
    const set = (this.previewMode() === 'light' ? tokens?.light : tokens?.dark) ?? {};
    return Object.entries(set)
      .filter(([, value]) => typeof value === 'string' && HEX.test(value))
      .map(([name, value]) => `--p-${name}: ${value}`)
      .join('; ');
  });

  /** Individual token overrides, which this page keeps but does not edit. */
  protected readonly overrideCount = computed(() => {
    const overrides = this.branding()?.overrides;
    return Object.keys(overrides?.light ?? {}).length + Object.keys(overrides?.dark ?? {}).length;
  });

  protected readonly issues = computed<ContrastIssueDTO[]>(() => this.preview()?.issues ?? []);

  protected readonly previewLogo = computed(() => {
    const branding = this.branding();
    if (!branding) {
      return null;
    }
    const url = this.previewMode() === 'light' ? branding.logoLightUrl : (branding.logoDarkUrl ?? branding.logoLightUrl);
    return this.assetSrc(url);
  });

  private previewTimer: ReturnType<typeof setTimeout> | undefined;
  private previewSequence = 0;

  constructor() {
    // Re-render the preview (server-generated palette + contrast check) as the colours change.
    effect(() => {
      const model = this.model();
      if (!this.branding() || this.brandingForm.seeds().invalid()) {
        return;
      }
      clearTimeout(this.previewTimer);
      this.previewTimer = setTimeout(() => void this.runPreview(model), 400);
    });
    inject(DestroyRef).onDestroy(() => clearTimeout(this.previewTimer));
  }

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
      this.apply(await firstValueFrom(this.brandingApi.getForOrganisation(organisationId)));
    } catch (error) {
      this.loadError.set(apiErrorMessage(error, 'Unable to load the branding.'));
    } finally {
      this.loading.set(false);
    }
  }

  protected assetSrc(url: string | null | undefined): string | null {
    if (!url) {
      return null;
    }
    return /^(https?:)?\/\//i.test(url) ? url : this.env.apiUrl() + url;
  }

  protected setSeed(key: SeedKey, value: string): void {
    this.model.update((m) => ({ ...m, seeds: { ...m.seeds, [key]: value } }));
    this.brandingForm.seeds[key]().markAsDirty();
  }

  protected swatchValue(value: string): string {
    if (/^#[0-9a-fA-F]{6}$/.test(value)) {
      return value;
    }
    if (/^#[0-9a-fA-F]{3}$/.test(value)) {
      return '#' + [...value.slice(1)].map((c) => c + c).join('');
    }
    return '#000000';
  }

  protected async upload(slot: AssetSlot, event: Event): Promise<void> {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    const organisationId = this.session.organisationId();
    if (!file || !organisationId) {
      return;
    }
    if (file.size > MAX_ASSET_BYTES) {
      toast.error(`The ${slot.label.toLowerCase()} is larger than 1 MB.`);
      return;
    }
    this.busyAsset.set(slot.slug);
    try {
      this.applyAssets(await firstValueFrom(this.brandingApi.uploadOrganisationAsset(organisationId, slot.slug, file)));
      toast.success(`${slot.label} uploaded`);
    } catch (error) {
      toast.error(apiErrorMessage(error, 'The file could not be uploaded.'));
    } finally {
      this.busyAsset.set(null);
    }
  }

  protected async removeAsset(slot: AssetSlot): Promise<void> {
    const organisationId = this.session.organisationId();
    if (!organisationId || !confirm(`Remove the ${slot.label.toLowerCase()}? The platform's one is used instead.`)) {
      return;
    }
    this.busyAsset.set(slot.slug);
    try {
      this.applyAssets(await firstValueFrom(this.brandingApi.removeOrganisationAsset(organisationId, slot.slug)));
      toast.success(`${slot.label} removed`);
    } catch (error) {
      toast.error(apiErrorMessage(error, 'The file could not be removed.'));
    } finally {
      this.busyAsset.set(null);
    }
  }

  /** True when the slot's file is the organisation's own (not inherited from the platform). */
  protected ownAsset(slot: AssetSlot): boolean {
    const branding = this.branding();
    const url = branding ? slot.url(branding) : null;
    return !!url && url.includes('/branding/organisations/');
  }

  protected async reset(): Promise<void> {
    const organisationId = this.session.organisationId();
    if (
      !organisationId ||
      !confirm('Remove your organisation\'s branding, including its logos? The platform branding applies again.')
    ) {
      return;
    }
    this.resetting.set(true);
    try {
      await firstValueFrom(this.brandingApi.resetForOrganisation(organisationId));
      toast.success('Branding reset', { description: 'The platform branding applies again.' });
      this.preview.set(null);
      await this.load();
    } catch (error) {
      toast.error(apiErrorMessage(error, 'The branding could not be reset.'));
    } finally {
      this.resetting.set(false);
    }
  }

  private async runPreview(model: BrandingModel): Promise<void> {
    const sequence = ++this.previewSequence;
    this.previewing.set(true);
    try {
      const preview = await firstValueFrom(this.brandingApi.preview(this.toRequest(model)));
      if (sequence === this.previewSequence) {
        this.preview.set(preview);
      }
    } catch {
      // The preview is a convenience: invalid input is reported by the form and on save.
    } finally {
      if (sequence === this.previewSequence) {
        this.previewing.set(false);
      }
    }
  }

  private apply(branding: BrandingDTO): void {
    this.branding.set(branding);
    this.model.set(toModel(branding));
    this.brandingForm().reset();
  }

  /** Asset changes return the whole branding; keep unsaved form edits. */
  private applyAssets(branding: BrandingDTO): void {
    const dirty = this.brandingForm().dirty();
    if (dirty) {
      this.branding.update((current) => ({
        ...current,
        version: branding.version,
        inherited: branding.inherited,
        logoLightUrl: branding.logoLightUrl,
        logoDarkUrl: branding.logoDarkUrl,
        faviconUrl: branding.faviconUrl,
        emailLogoUrl: branding.emailLogoUrl,
      }));
    } else {
      this.apply(branding);
    }
  }

  /** A PUT: the whole branding is replaced; the token overrides are kept as they are. */
  private toRequest(model: BrandingModel): BrandingRequest {
    const seeds: BrandingSeedsDTO = {};
    for (const key of SEED_KEYS) {
      seeds[key] = model.seeds[key].trim() || null;
    }
    return {
      appName: model.appName.trim() || null,
      seeds,
      overrides: this.branding()?.overrides ?? null,
      supportEmail: model.supportEmail.trim() || null,
      supportUrl: model.supportUrl.trim() || null,
      signingPortalMessage: model.signingPortalMessage.trim() || null,
      emailFooter: model.emailFooter.trim() || null,
    };
  }

  protected tokenLabel(name: string | null | undefined): string {
    return (name ?? '').replace(/^color-/, '').replaceAll('-', ' ');
  }
}

function toModel(branding: BrandingDTO): BrandingModel {
  const seeds = {} as SeedsModel;
  for (const key of SEED_KEYS) {
    seeds[key] = branding.seeds?.[key] ?? '';
  }
  return {
    appName: branding.appName ?? '',
    seeds,
    supportEmail: branding.supportEmail ?? '',
    supportUrl: branding.supportUrl ?? '',
    signingPortalMessage: branding.signingPortalMessage ?? '',
    emailFooter: branding.emailFooter ?? '',
  };
}
