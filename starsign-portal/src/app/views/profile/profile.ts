import { DatePipe } from '@angular/common';
import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { hasPermission } from '@auth/page-permissions';
import { CurrentUserDTO, OrganisationUserDTO } from '@models/identity';
import { EnvLoaderService } from '@services/env-loader.service';
import { UserApi } from '@services/identity/user-api';
import { StatusChip } from '@shared/status-chip/status-chip';
import { apiErrorMessage } from '@store/app-state';
import { SessionStore } from '@store/session.store';
import { firstValueFrom } from 'rxjs';
import { groupCodes } from '../roles/permission-groups';
import { UserProfileForm } from '../users/profile-form/user-profile-form';

/**
 * The signed-in user's own account (GET /users/me): profile, organisation, roles and
 * permissions. The API has no self-service profile update: PATCH /users/{id} needs
 * users:edit, so the form is editable only for holders of it; everyone else changes
 * their name and password on the Keycloak account page.
 */
@Component({
  selector: 'app-profile',
  imports: [DatePipe, RouterLink, StatusChip, UserProfileForm],
  templateUrl: './profile.html',
})
export class Profile implements OnInit {
  private readonly userApi = inject(UserApi);
  private readonly session = inject(SessionStore);
  protected readonly accountUrl = inject(EnvLoaderService).accountUrl;

  protected readonly current = signal<CurrentUserDTO | null>(null);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);

  /** Falls back to the session's copy until (or if) the fresh one fails to load. */
  protected readonly me = computed(() => this.current() ?? this.session.currentUser());
  protected readonly user = computed(() => this.me()?.user ?? null);
  protected readonly displayName = computed(() => {
    const user = this.user();
    return [user?.firstName, user?.lastName].filter(Boolean).join(' ') || this.session.displayName();
  });
  protected readonly permissions = computed(() =>
    (this.me()?.permissions ?? []).map((permission) => permission.replace(/^SCOPE_/, '')),
  );
  protected readonly permissionGroups = computed(() => groupCodes(this.permissions()));
  /** Platform administrators have no StarSign profile (no id) to update. */
  protected readonly canEdit = computed(
    () => !!this.user()?.id && hasPermission(['users:edit'], this.session.permissions()),
  );

  ngOnInit(): void {
    void this.load();
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    this.error.set(null);
    try {
      this.current.set(await firstValueFrom(this.userApi.getCurrentUser()));
    } catch (error) {
      this.error.set(apiErrorMessage(error, 'Unable to load your account.'));
    } finally {
      this.loading.set(false);
    }
  }

  protected onSaved(user: OrganisationUserDTO): void {
    this.current.update((current) => (current ? { ...current, user } : current));
    // The shell shows the name from the session.
    this.session.loadCurrentUser();
  }
}
