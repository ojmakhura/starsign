import { Component, computed, inject, input } from '@angular/core';
import { ThemeService } from '@core/theme/theme.service';
import { EnvLoaderService } from '@services/env-loader.service';

/** The StarSign lockups in public/brand (built by brand/build.py): navy wordmark for light, white for dark. */
export const BRAND_LOGO = {
  lockup: { light: '/brand/logo-light.svg', dark: '/brand/logo-dark.svg' },
  wordmark: { light: '/brand/wordmark-light.svg', dark: '/brand/wordmark-dark.svg' },
  mark: '/mark.svg',
  seal: '/brand/seal.svg',
} as const;

/**
 * The logo for the current colour mode: an organisation's own logo pair when given (branding
 * logoLightUrl / logoDarkUrl, either one standing in for the other), else the StarSign lockup.
 */
@Component({
  selector: 'app-brand-logo',
  template: `<img [src]="src()" [alt]="alt()" [style.height]="height()" class="block w-auto max-w-full" />`,
  host: { class: 'inline-block' },
})
export class BrandLogo {
  private readonly theme = inject(ThemeService);
  private readonly env = inject(EnvLoaderService);

  readonly lightUrl = input<string | null | undefined>(null);
  readonly darkUrl = input<string | null | undefined>(null);
  /** lockup: with the tagline; wordmark: mark and name only, for narrow places. */
  readonly variant = input<'lockup' | 'wordmark'>('lockup');
  readonly alt = input('StarSign');
  readonly height = input('2.5rem');

  protected readonly src = computed(() => {
    const light = this.lightUrl();
    const dark = this.darkUrl();
    if (light || dark) {
      const url = this.theme.mode() === 'dark' ? (dark ?? light)! : (light ?? dark)!;
      // Branding asset URLs are API paths (/branding/...), served through the API base URL.
      return /^(https?:)?\/\//i.test(url) ? url : this.env.apiUrl() + url;
    }
    return BRAND_LOGO[this.variant()][this.theme.mode()];
  });
}
