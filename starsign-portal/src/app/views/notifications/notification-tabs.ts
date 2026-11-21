import { Component } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { Can } from '@auth/can.directive';

/** Switches between the user's own notifications and the organisation-wide search. */
@Component({
  selector: 'app-notification-tabs',
  imports: [Can, RouterLink, RouterLinkActive],
  template: `
    <nav *appCan="'notifications:list'" class="segmented self-start" aria-label="Notification views">
      <a
        routerLink="/notifications"
        routerLinkActive
        #mine="routerLinkActive"
        [routerLinkActiveOptions]="{ exact: true }"
        [attr.aria-current]="mine.isActive ? 'page' : null"
        >Mine</a
      >
      <a
        routerLink="/notifications/organisation"
        routerLinkActive
        #org="routerLinkActive"
        [attr.aria-current]="org.isActive ? 'page' : null"
        >Organisation</a
      >
    </nav>
  `,
  styles: `
    .segmented a {
      padding: 0.375rem 0.75rem;
      border-radius: 2px;
      font-family: var(--font-mono);
      font-weight: 500;
      font-size: var(--text-label-sm);
      line-height: var(--text-label-sm--line-height);
      letter-spacing: var(--text-label-sm--letter-spacing);
      text-transform: uppercase;
      color: var(--color-ink-muted);
      text-decoration: none;
    }
    .segmented a[aria-current='page'] {
      background: color-mix(in srgb, var(--color-cyan) 12%, transparent);
      color: var(--color-cyan);
    }
    .segmented a:focus-visible {
      box-shadow: var(--shadow-focus);
      outline: none;
    }
  `,
  host: { class: 'contents' },
})
export class NotificationTabs {}
