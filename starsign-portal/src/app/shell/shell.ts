import { Component, computed, HostListener, inject, signal } from '@angular/core';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { hasPermission } from '@auth/page-permissions';
import { isPublicPath, isPublicRoute } from '@auth/public-pages';
import { Loader } from '@shared/loader/loader';
import { EnvLoaderService } from '@services/env-loader.service';
import { SessionStore } from '@store/session.store';
import { InternalSigningApiStore } from '@store/signing/internal-signing-api.store';
import Keycloak from 'keycloak-js';
import { NgxSonnerToaster } from 'ngx-sonner';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { filter, map } from 'rxjs';
import { NAVIGATION } from './navigation';

@Component({
  selector: 'app-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, Loader, NgxSonnerToaster],
  templateUrl: './shell.html',
  styleUrl: './shell.scss',
})
export class Shell {
  protected readonly keycloak = inject(Keycloak);
  protected readonly session = inject(SessionStore);
  protected readonly signing = inject(InternalSigningApiStore);
  private readonly envLoader = inject(EnvLoaderService);
  private readonly router = inject(Router);

  protected readonly accountMenuOpen = signal(false);
  protected readonly mobileMenuOpen = signal(false);
  protected readonly accountUrl = this.envLoader.accountUrl;
  protected readonly year = new Date().getFullYear();

  /** Public pages (sign-up, accepting an invitation) render full width, without the menus. */
  protected readonly bare = toSignal(
    this.router.events.pipe(
      filter((event) => event instanceof NavigationEnd),
      map(() => isPublicRoute(this.router.routerState.snapshot.root)),
    ),
    { initialValue: isPublicPath(window.location.pathname) },
  );

  /** The sidebar and top bar: signed in and not on a public page. */
  protected readonly chrome = computed(() => !!this.keycloak.authenticated && !this.bare());

  /** Menu groups filtered to what the signed-in user's permissions unlock. */
  protected readonly menuGroups = computed(() => {
    const granted = this.session.permissions();
    return NAVIGATION.map((group) => ({
      ...group,
      items: group.items.filter((item) => hasPermission(item.permissions, granted)),
    })).filter((group) => group.items.length > 0);
  });

  /** The inbox is the live source once loaded; /users/me's count until then. */
  protected readonly pendingCount = computed(() =>
    this.signing.success() ? this.signing.pendingCount() : this.session.pendingSignatureCount(),
  );

  constructor() {
    // Close the overlays whenever navigation completes.
    this.router.events
      .pipe(
        filter((event) => event instanceof NavigationEnd),
        takeUntilDestroyed(),
      )
      .subscribe(() => {
      this.mobileMenuOpen.set(false);
      this.accountMenuOpen.set(false);
    });
  }

  protected login(): void {
    this.keycloak.login({ redirectUri: window.location.href });
  }

  protected logout(): void {
    this.accountMenuOpen.set(false);
    this.keycloak.logout({ redirectUri: this.envLoader.env()?.redirectUri || window.location.origin });
  }

  protected toggleAccountMenu(event: Event): void {
    event.stopPropagation();
    this.accountMenuOpen.update((open) => !open);
  }

  @HostListener('document:click')
  @HostListener('document:keydown.escape')
  protected closeMenus(): void {
    this.accountMenuOpen.set(false);
  }
}
