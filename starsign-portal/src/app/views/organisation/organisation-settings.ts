import { Component, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { SessionStore } from '@store/session.store';

interface SettingsTab {
  label: string;
  icon: string;
  link: string;
}

/** Frame of the organisation settings: header, tabs and the active tab's page. */
@Component({
  selector: 'app-organisation-settings',
  imports: [RouterLink, RouterLinkActive, RouterOutlet],
  templateUrl: './organisation-settings.html',
  styles: `
    .settings-tabs {
      display: inline-flex;
      flex-wrap: wrap;
      align-self: flex-start;
      gap: 3px;
      padding: 3px;
      border-radius: var(--radius-control);
      background: var(--color-abyss);
      border: 1px solid var(--color-hairline);
    }
    .settings-tabs a {
      display: inline-flex;
      align-items: center;
      gap: 0.4rem;
      padding: 0.4rem 0.8rem;
      border-radius: 2px;
      color: var(--color-ink-muted);
      font-size: var(--text-body-sm);
      text-decoration: none;
    }
    .settings-tabs a:hover {
      color: var(--color-ink);
    }
    .settings-tabs a.is-active {
      background: color-mix(in srgb, var(--color-cyan) 12%, transparent);
      color: var(--color-cyan);
    }
    .settings-tabs a:focus-visible {
      outline: none;
      box-shadow: var(--shadow-focus);
    }
  `,
})
export class OrganisationSettings {
  protected readonly session = inject(SessionStore);

  protected readonly tabs: SettingsTab[] = [
    { label: 'Profile', icon: 'bi-building', link: 'profile' },
    { label: 'Signing policy', icon: 'bi-shield-check', link: 'signing-policy' },
    { label: 'Notifications', icon: 'bi-bell', link: 'notifications' },
    { label: 'Branding', icon: 'bi-palette', link: 'branding' },
  ];
}
