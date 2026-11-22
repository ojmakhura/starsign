import { DOCUMENT } from '@angular/common';
import { computed, effect, inject, Injectable, signal } from '@angular/core';

/** What the user picked; "system" follows the operating system's light/dark setting. */
export type ThemePreference = 'light' | 'dark' | 'system';
/** The colour mode in effect, the same names the branding tokens use (ThemeTokensDTO.light / .dark). */
export type ColourMode = 'light' | 'dark';

/** Shared with the inline script in index.html, which applies the theme before Angular boots. */
export const THEME_STORAGE_KEY = 'starsign.theme';

/**
 * Light and dark mode for the portal. Sets data-theme on <html>, which switches the colour tokens in
 * styles/tailwind.css, and remembers the choice in localStorage (per browser, so it also applies on the
 * sign-in page and the external signing portal before anyone is signed in).
 */
@Injectable({ providedIn: 'root' })
export class ThemeService {
  private readonly document = inject(DOCUMENT);
  private readonly media = this.document.defaultView?.matchMedia?.('(prefers-color-scheme: dark)') ?? null;
  private readonly systemDark = signal(this.media?.matches ?? true);

  readonly preference = signal<ThemePreference>(readPreference());
  readonly mode = computed<ColourMode>(() => {
    const preference = this.preference();
    return preference === 'system' ? (this.systemDark() ? 'dark' : 'light') : preference;
  });

  constructor() {
    this.media?.addEventListener('change', (event) => this.systemDark.set(event.matches));
    effect(() => {
      const mode = this.mode();
      const root = this.document.documentElement;
      root.dataset['theme'] = mode;
      root.style.colorScheme = mode;
    });
  }

  set(preference: ThemePreference): void {
    this.preference.set(preference);
    try {
      if (preference === 'system') {
        localStorage.removeItem(THEME_STORAGE_KEY);
      } else {
        localStorage.setItem(THEME_STORAGE_KEY, preference);
      }
    } catch {
      // Storage blocked (private mode): the choice lasts for this page only.
    }
  }
}

function readPreference(): ThemePreference {
  try {
    const stored = localStorage.getItem(THEME_STORAGE_KEY);
    return stored === 'light' || stored === 'dark' ? stored : 'system';
  } catch {
    return 'system';
  }
}
