import { DatePipe } from '@angular/common';
import { ThemeService } from '@core/theme/theme.service';
import { Component, computed, DestroyRef, effect, inject, OnInit, signal, untracked } from '@angular/core';
import { email, form, FormField, FormRoot, maxLength, pattern } from '@angular/forms/signals';
import { RouterLink } from '@angular/router';
import {
  BrandingAsset,
  BrandingDTO,
  BrandingPreviewDTO,
  BrandingRequest,
  BrandingSeedsDTO,
} from '@models/branding';
import { BrandingApi } from '@services/branding/branding-api';
import { FieldErrors } from '@shared/field-errors/field-errors';
import { apiErrorMessage } from '@store/app-state';
import { toast } from 'ngx-sonner';
import { firstValueFrom } from 'rxjs';

type SeedKey = keyof BrandingSeedsDTO;

interface BrandingModel {
  appName: string;
  seeds: Record<SeedKey, string>;
  supportEmail: string;
  supportUrl: string;
  signingPortalMessage: string;
  emailFooter: string;
}

/** BrandingServiceImpl.DEFAULT_SEEDS: what an empty seed falls back to (the StarSign logo's colours). */
const DEFAULT_SEEDS: Record<SeedKey, string> = {
  primary: '#0284c7',
  secondary: '#1e2a4a',
  tertiary: '#fcd34d',
  neutral: '#0f172a',
  error: '#ff5c6c',
  success: '#2e7d32',
  warning: '#f59e0b',
  info: '#0288d1',
};

/** The StarSign files (public/, built by brand/build.py) used while a slot has no upload. */
const DEFAULT_ASSETS: Record<string, string> = {
  'logo-light': '/brand/logo-light.svg',
  'logo-dark': '/brand/logo-dark.svg',
  favicon: '/favicon.svg',
  'email-logo': '/brand/email-logo.png',
};
const SEED_KEYS = Object.keys(DEFAULT_SEEDS) as SeedKey[];
const HEX = /^#[0-9a-fA-F]{6}$/;

interface AssetSlot {
  kind: BrandingAsset;
  slug: string;
  label: string;
  accept: string;
  hint: string;
  urlKey: 'logoLightUrl' | 'logoDarkUrl' | 'faviconUrl' | 'emailLogoUrl';
  /** Background the preview is drawn on. */
  surface: 'light' | 'dark';
}

const ASSETS: AssetSlot[] = [
  {
    kind: BrandingAsset.LOGO_LIGHT,
    slug: 'logo-light',
    label: 'Logo (light backgrounds)',
    accept: 'image/png,image/jpeg,image/webp,image/svg+xml',
    hint: 'PNG, JPEG, WebP or SVG, up to 1 MB.',
    urlKey: 'logoLightUrl',
    surface: 'light',
  },
  {
    kind: BrandingAsset.LOGO_DARK,
    slug: 'logo-dark',
    label: 'Logo (dark backgrounds)',
    accept: 'image/png,image/jpeg,image/webp,image/svg+xml',
    hint: 'PNG, JPEG, WebP or SVG, up to 1 MB.',
    urlKey: 'logoDarkUrl',
    surface: 'dark',
  },
  {
    kind: BrandingAsset.FAVICON,
    slug: 'favicon',
    label: 'Favicon',
    accept: 'image/png,image/x-icon,image/vnd.microsoft.icon,.ico',
    hint: 'PNG or ICO, up to 1 MB.',
    urlKey: 'faviconUrl',
    surface: 'dark',
  },
  {
    kind: BrandingAsset.EMAIL_LOGO,
    slug: 'email-logo',
    label: 'Email logo',
    accept: 'image/png,image/jpeg',
    hint: 'PNG or JPEG (email clients do not render SVG), up to 1 MB.',
    urlKey: 'emailLogoUrl',
    surface: 'light',
  },
];

const MAX_ASSET_BYTES = 1024 * 1024;

/**
 * The platform branding (/branding): the defaults every organisation's portal, signing
 * pages and emails inherit — name, colour seeds, logos and support details.
 */
@Component({
  selector: 'app-platform-branding',
  imports: [DatePipe, FieldErrors, FormField, FormRoot, RouterLink],
  templateUrl: './platform-branding.html',
})
export class PlatformBranding implements OnInit {
  private readonly brandingApi = inject(BrandingApi);

  protected readonly seedKeys = SEED_KEYS;
  protected readonly defaults = DEFAULT_SEEDS;
  protected readonly assets = ASSETS;
  protected readonly defaultAssets = DEFAULT_ASSETS;

  protected readonly branding = signal<BrandingDTO | null>(null);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);

  protected readonly model = signal<BrandingModel>(this.toModel(null));
  protected readonly brandingForm = form(
    this.model,
    (path) => {
      maxLength(path.appName, 100, { message: 'Keep the name under 100 characters.' });
      for (const key of SEED_KEYS) {
        pattern(path.seeds[key], HEX, { message: 'A hex colour such as #0284c7, or empty for the default.' });
      }
      email(path.supportEmail, { message: 'Enter a valid email address.' });
      maxLength(path.supportEmail, 254);
      pattern(path.supportUrl, /^https?:\/\/\S+$/, { message: 'A web address starting with https:// or http://.' });
      maxLength(path.signingPortalMessage, 1000, { message: 'Keep the message under 1000 characters.' });
      maxLength(path.emailFooter, 2000, { message: 'Keep the footer under 2000 characters.' });
    },
    {
      submission: {
        action: async () => {
          try {
            const saved = await firstValueFrom(this.brandingApi.savePlatform(this.toRequest()));
            this.apply(saved);
            toast.success('Platform branding saved', {
              description: 'Organisations without their own branding see it from now on.',
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

  // ---- Live preview (POST /branding/preview) ----
  protected readonly preview = signal<BrandingPreviewDTO | null>(null);
  protected readonly previewError = signal<string | null>(null);
  /** Starts in the mode the portal is showing. */
  protected readonly previewMode = signal<'light' | 'dark'>(inject(ThemeService).mode());
  protected readonly previewTokens = computed(() => {
    const tokens = this.preview()?.tokens?.[this.previewMode()] ?? {};
    return Object.entries(tokens)
      .filter(([, value]) => typeof value === 'string' && /^#|^rgb|^hsl/i.test(value as string))
      .map(([name, value]) => ({ name, value: value as string }));
  });

  // ---- Assets ----
  /** Object URLs of the stored files, per slug. */
  protected readonly assetImages = signal<Record<string, string>>({});
  protected readonly assetBusy = signal<string | null>(null);
  protected readonly resetting = signal(false);

  private previewTimer: ReturnType<typeof setTimeout> | undefined;
  private previewId = 0;

  constructor() {
    effect(() => {
      const seeds = this.model().seeds;
      const valid = SEED_KEYS.every((key) => !seeds[key] || HEX.test(seeds[key]));
      if (!valid) {
        return;
      }
      untracked(() => {
        clearTimeout(this.previewTimer);
        this.previewTimer = setTimeout(() => void this.loadPreview(), 400);
      });
    });
    inject(DestroyRef).onDestroy(() => {
      clearTimeout(this.previewTimer);
      Object.values(this.assetImages()).forEach((url) => URL.revokeObjectURL(url));
    });
  }

  ngOnInit(): void {
    void this.load();
  }

  protected async load(): Promise<void> {
    this.loading.set(true);
    this.error.set(null);
    try {
      this.apply(await firstValueFrom(this.brandingApi.getPlatform()));
    } catch (error) {
      this.error.set(apiErrorMessage(error, 'Unable to load the platform branding.'));
    } finally {
      this.loading.set(false);
    }
  }

  protected seedValue(key: SeedKey): string {
    return this.model().seeds[key] || DEFAULT_SEEDS[key];
  }

  protected setSeed(key: SeedKey, value: string): void {
    this.model.update((m) => ({ ...m, seeds: { ...m.seeds, [key]: value } }));
  }

  protected seedField(key: SeedKey) {
    return this.brandingForm.seeds[key];
  }

  protected discard(): void {
    this.model.set(this.toModel(this.branding()));
    this.brandingForm().reset();
  }

  protected async reset(): Promise<void> {
    if (
      !confirm(
        'Reset the platform branding? Its colours, texts and files are removed and every portal shows the StarSign defaults. This cannot be undone.',
      )
    ) {
      return;
    }
    this.resetting.set(true);
    try {
      await firstValueFrom(this.brandingApi.resetPlatform());
      toast.success('Platform branding reset to the StarSign defaults');
      await this.load();
    } catch (error) {
      toast.error(apiErrorMessage(error, 'The branding could not be reset.'));
    } finally {
      this.resetting.set(false);
    }
  }

  protected async upload(slot: AssetSlot, event: Event): Promise<void> {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!file) {
      return;
    }
    if (file.size > MAX_ASSET_BYTES) {
      toast.error(`${file.name} is larger than 1 MB.`);
      return;
    }
    this.assetBusy.set(slot.slug);
    try {
      const saved = await firstValueFrom(this.brandingApi.uploadPlatformAsset(slot.slug, file));
      this.branding.set(saved);
      await this.loadAsset(slot, saved);
      toast.success(`${slot.label} uploaded`);
    } catch (error) {
      toast.error(apiErrorMessage(error, 'The file could not be uploaded.'));
    } finally {
      this.assetBusy.set(null);
    }
  }

  protected async removeAsset(slot: AssetSlot): Promise<void> {
    if (!confirm(`Remove the platform ${slot.label.toLowerCase()}?`)) {
      return;
    }
    this.assetBusy.set(slot.slug);
    try {
      const saved = await firstValueFrom(this.brandingApi.removePlatformAsset(slot.slug));
      this.branding.set(saved);
      this.setAssetImage(slot.slug, null);
      toast.success(`${slot.label} removed`);
    } catch (error) {
      toast.error(apiErrorMessage(error, 'The file could not be removed.'));
    } finally {
      this.assetBusy.set(null);
    }
  }

  private apply(branding: BrandingDTO): void {
    this.branding.set(branding);
    this.model.set(this.toModel(branding));
    this.brandingForm().reset();
    void Promise.all(ASSETS.map((slot) => this.loadAsset(slot, branding)));
  }

  private async loadAsset(slot: AssetSlot, branding: BrandingDTO): Promise<void> {
    const url = branding[slot.urlKey];
    if (!url) {
      this.setAssetImage(slot.slug, null);
      return;
    }
    const version = new URL(url, 'http://local').searchParams.get('v');
    try {
      const blob = await firstValueFrom(this.brandingApi.getPlatformAsset(slot.slug, version));
      this.setAssetImage(slot.slug, URL.createObjectURL(blob));
    } catch {
      this.setAssetImage(slot.slug, null);
    }
  }

  private setAssetImage(slug: string, url: string | null): void {
    this.assetImages.update((images) => {
      if (images[slug]) {
        URL.revokeObjectURL(images[slug]);
      }
      const next = { ...images };
      if (url) {
        next[slug] = url;
      } else {
        delete next[slug];
      }
      return next;
    });
  }

  private async loadPreview(): Promise<void> {
    const id = ++this.previewId;
    try {
      const preview = await firstValueFrom(this.brandingApi.preview(this.toRequest()));
      if (id === this.previewId) {
        this.preview.set(preview);
        this.previewError.set(null);
      }
    } catch (error) {
      if (id === this.previewId) {
        this.previewError.set(apiErrorMessage(error, 'The preview could not be built.'));
      }
    }
  }

  private toModel(branding: BrandingDTO | null): BrandingModel {
    const seeds = branding?.seeds ?? {};
    return {
      appName: branding?.appName ?? '',
      seeds: Object.fromEntries(SEED_KEYS.map((key) => [key, seeds[key] ?? ''])) as Record<SeedKey, string>,
      supportEmail: branding?.supportEmail ?? '',
      supportUrl: branding?.supportUrl ?? '',
      signingPortalMessage: branding?.signingPortalMessage ?? '',
      emailFooter: branding?.emailFooter ?? '',
    };
  }

  private toRequest(): BrandingRequest {
    const m = this.model();
    return {
      appName: m.appName.trim() || null,
      seeds: Object.fromEntries(
        SEED_KEYS.map((key) => [key, m.seeds[key].trim().toLowerCase() || null]),
      ) as BrandingSeedsDTO,
      // Token overrides are not edited here; keep the stored ones.
      overrides: this.branding()?.overrides ?? null,
      supportEmail: m.supportEmail.trim() || null,
      supportUrl: m.supportUrl.trim() || null,
      signingPortalMessage: m.signingPortalMessage.trim() || null,
      emailFooter: m.emailFooter.trim() || null,
    };
  }
}
