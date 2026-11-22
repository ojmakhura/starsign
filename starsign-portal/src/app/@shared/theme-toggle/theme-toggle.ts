import { Component, inject, input } from '@angular/core';
import { ThemePreference, ThemeService } from '@core/theme/theme.service';

/** Light / dark / system switch, a three-way segmented control of icon buttons. */
@Component({
  selector: 'app-theme-toggle',
  template: `
    <div class="theme-toggle" role="radiogroup" aria-label="Colour theme">
      @for (option of options; track option.value) {
        <button
          type="button"
          role="radio"
          class="theme-toggle__option"
          [class.theme-toggle__option--active]="theme.preference() === option.value"
          [attr.aria-checked]="theme.preference() === option.value"
          [attr.aria-label]="option.label"
          [title]="option.label"
          (click)="theme.set(option.value)"
        >
          <i class="bi" [class]="option.icon" aria-hidden="true"></i>
          @if (labels()) {
            <span>{{ option.short }}</span>
          }
        </button>
      }
    </div>
  `,
  host: { '[class.theme-toggle-host--labels]': 'labels()' },
  styles: `
    .theme-toggle {
      display: inline-flex;
      gap: 2px;
      padding: 2px;
      border-radius: var(--radius-control);
      border: 1px solid var(--color-hairline);
      background: var(--color-abyss);
    }
    .theme-toggle__option {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      gap: 0.375rem;
      min-width: 30px;
      height: 28px;
      padding: 0 0.5rem;
      border: 0;
      border-radius: 2px;
      background: none;
      color: var(--color-ink-muted);
      font: 500 var(--text-label-md) / 1 var(--font-sans);
      cursor: pointer;
    }
    .theme-toggle__option:hover {
      color: var(--color-ink);
    }
    .theme-toggle__option--active {
      background: color-mix(in srgb, var(--color-cyan) 14%, transparent);
      color: var(--color-cyan);
    }
    .theme-toggle__option:focus-visible {
      box-shadow: var(--shadow-focus);
      outline: none;
    }
    :host(.theme-toggle-host--labels) .theme-toggle {
      display: flex;
    }
    :host(.theme-toggle-host--labels) .theme-toggle__option {
      flex: 1;
    }
  `,
})
export class ThemeToggle {
  protected readonly theme = inject(ThemeService);
  /** Show "Light", "Dark", "System" next to the icons (the account menu). */
  readonly labels = input(false);

  protected readonly options: { value: ThemePreference; label: string; short: string; icon: string }[] = [
    { value: 'light', label: 'Light theme', short: 'Light', icon: 'bi-sun' },
    { value: 'dark', label: 'Dark theme', short: 'Dark', icon: 'bi-moon-stars' },
    { value: 'system', label: 'Match system theme', short: 'System', icon: 'bi-circle-half' },
  ];
}
