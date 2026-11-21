import { DatePipe } from '@angular/common';
import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { RouterLink } from '@angular/router';
import { hasPermission } from '@auth/page-permissions';
import { SortDirection } from '@models/common';
import { OrganisationListDTO } from '@models/organisation';
import {
  ReconciliationIssueType,
  ReconciliationResolution,
  ReconciliationRunDTO,
  StorageReconciliationIssueDTO,
} from '@models/storage';
import { OrganisationApi } from '@services/organisation/organisation-api';
import { StorageAdminApi } from '@services/storage/storage-admin-api';
import { apiErrorMessage } from '@store/app-state';
import { SessionStore } from '@store/session.store';
import { toast } from 'ngx-sonner';
import { firstValueFrom } from 'rxjs';
import { humanise } from '../platform-format';

/** StorageReconciliationServiceImpl's value for the platform's own files. */
const PLATFORM = 'platform';

/** Resolutions StorageReconciliationServiceImpl.resolveIssue accepts per issue type. */
const RESOLUTIONS: Record<ReconciliationIssueType, ReconciliationResolution[]> = {
  [ReconciliationIssueType.ORPHANED_OBJECT]: [ReconciliationResolution.OBJECT_DELETED, ReconciliationResolution.IGNORED],
  [ReconciliationIssueType.MISSING_OBJECT]: [
    ReconciliationResolution.RE_UPLOADED,
    ReconciliationResolution.REFERENCE_MARKED_FAILED,
    ReconciliationResolution.IGNORED,
  ],
  [ReconciliationIssueType.CHECKSUM_MISMATCH]: [
    ReconciliationResolution.RE_UPLOADED,
    ReconciliationResolution.OBJECT_DELETED,
    ReconciliationResolution.REFERENCE_MARKED_FAILED,
    ReconciliationResolution.IGNORED,
  ],
  [ReconciliationIssueType.STALE_PENDING_UPLOAD]: [
    ReconciliationResolution.RE_UPLOADED,
    ReconciliationResolution.REFERENCE_MARKED_FAILED,
    ReconciliationResolution.IGNORED,
  ],
  [ReconciliationIssueType.FAILED_DELETION]: [ReconciliationResolution.OBJECT_DELETED, ReconciliationResolution.IGNORED],
};

const RESOLUTION_LABELS: Record<ReconciliationResolution, string> = {
  [ReconciliationResolution.OPEN]: 'Open',
  [ReconciliationResolution.RE_UPLOADED]: 'Recovered (object present again)',
  [ReconciliationResolution.OBJECT_DELETED]: 'Delete the stored object',
  [ReconciliationResolution.REFERENCE_MARKED_FAILED]: 'Mark the reference failed',
  [ReconciliationResolution.IGNORED]: 'Ignore',
};

const ISSUE_HINTS: Record<ReconciliationIssueType, string> = {
  [ReconciliationIssueType.ORPHANED_OBJECT]: 'An object in the bucket that no record refers to.',
  [ReconciliationIssueType.MISSING_OBJECT]: 'A record whose object is missing from the bucket.',
  [ReconciliationIssueType.CHECKSUM_MISMATCH]: 'The stored object does not match its recorded checksum.',
  [ReconciliationIssueType.STALE_PENDING_UPLOAD]: 'An upload that never finished.',
  [ReconciliationIssueType.FAILED_DELETION]: 'An object that retention could not delete.',
};

/**
 * SeaweedFS storage administration (/admin/storage): reconciliation issues and how they
 * were resolved, reconciliation runs per organisation, and retrying failed uploads.
 */
@Component({
  selector: 'app-platform-storage',
  imports: [DatePipe, RouterLink],
  templateUrl: './platform-storage.html',
})
export class PlatformStorage {
  private readonly storageApi = inject(StorageAdminApi);
  private readonly organisationApi = inject(OrganisationApi);
  private readonly session = inject(SessionStore);

  protected readonly humanise = humanise;
  protected readonly resolutionLabels = RESOLUTION_LABELS;
  protected readonly issueHints = ISSUE_HINTS;
  protected readonly platform = PLATFORM;
  protected readonly filters = Object.values(ReconciliationResolution);

  protected readonly canEdit = computed(() =>
    hasPermission(['admin-storage:edit'], this.session.permissions()),
  );
  private readonly canListOrganisations = computed(() =>
    hasPermission(['organisations:administer'], this.session.permissions()),
  );

  protected readonly resolution = signal<ReconciliationResolution>(ReconciliationResolution.OPEN);
  protected readonly issues = signal<StorageReconciliationIssueDTO[]>([]);
  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly busyId = signal<string | null>(null);
  /** Chosen resolution per open issue id. */
  protected readonly choice = signal<Record<string, ReconciliationResolution>>({});

  protected readonly organisations = signal<OrganisationListDTO[]>([]);
  protected readonly target = signal<string>(PLATFORM);
  protected readonly running = signal(false);
  protected readonly lastRun = signal<{ target: string; run: ReconciliationRunDTO } | null>(null);
  protected readonly retrying = signal(false);
  protected readonly retried = signal<number | null>(null);
  protected readonly listOrganisations = this.canListOrganisations;

  constructor() {
    effect(() => {
      const resolution = this.resolution();
      untracked(() => void this.loadIssues(resolution));
    });
    if (this.canEdit() && this.canListOrganisations()) {
      void this.loadOrganisations();
    }
  }

  protected options(issue: StorageReconciliationIssueDTO): ReconciliationResolution[] {
    return issue.issueType ? RESOLUTIONS[issue.issueType] : [ReconciliationResolution.IGNORED];
  }

  protected chosen(issue: StorageReconciliationIssueDTO): ReconciliationResolution {
    return this.choice()[issue.id ?? ''] ?? this.options(issue)[0];
  }

  protected choose(issue: StorageReconciliationIssueDTO, resolution: ReconciliationResolution): void {
    this.choice.update((c) => ({ ...c, [issue.id ?? '']: resolution }));
  }

  protected async resolve(issue: StorageReconciliationIssueDTO): Promise<void> {
    const resolution = this.chosen(issue);
    if (!issue.id) {
      return;
    }
    if (
      resolution === ReconciliationResolution.OBJECT_DELETED &&
      !confirm(`Delete the stored object ${issue.objectId ?? ''}? This cannot be undone.`)
    ) {
      return;
    }
    this.busyId.set(issue.id);
    try {
      await firstValueFrom(this.storageApi.resolveIssue(issue.id, resolution));
      toast.success('Issue resolved', { description: RESOLUTION_LABELS[resolution] });
      await this.loadIssues(this.resolution());
    } catch (error) {
      toast.error(apiErrorMessage(error, 'The issue could not be resolved.'));
    } finally {
      this.busyId.set(null);
    }
  }

  protected async runReconciliation(): Promise<void> {
    const target = this.target().trim();
    if (!target) {
      toast.error('Choose what to reconcile.');
      return;
    }
    this.running.set(true);
    try {
      const run = await firstValueFrom(this.storageApi.runReconciliation(target));
      this.lastRun.set({ target: this.targetName(target), run });
      toast.success('Reconciliation finished', {
        description: `${run.issuesDetected ?? 0} issues detected, ${run.issuesResolved ?? 0} resolved.`,
      });
      await this.loadIssues(this.resolution());
    } catch (error) {
      toast.error(apiErrorMessage(error, 'The reconciliation failed.'));
    } finally {
      this.running.set(false);
    }
  }

  protected async retryFailed(): Promise<void> {
    this.retrying.set(true);
    try {
      const count = await firstValueFrom(this.storageApi.retryFailedUploads());
      this.retried.set(count);
      toast.success(count === 1 ? '1 upload recovered' : `${count} uploads recovered`);
      await this.loadIssues(this.resolution());
    } catch (error) {
      toast.error(apiErrorMessage(error, 'Failed uploads could not be retried.'));
    } finally {
      this.retrying.set(false);
    }
  }

  private targetName(target: string): string {
    if (target === PLATFORM) {
      return 'the platform files';
    }
    return this.organisations().find((o) => o.id === target)?.name ?? target;
  }

  private async loadIssues(resolution: ReconciliationResolution): Promise<void> {
    this.loading.set(true);
    this.error.set(null);
    try {
      this.issues.set(await firstValueFrom(this.storageApi.findIssues(resolution)));
    } catch (error) {
      this.issues.set([]);
      this.error.set(apiErrorMessage(error, 'Unable to load the storage issues.'));
    } finally {
      this.loading.set(false);
    }
  }

  private async loadOrganisations(): Promise<void> {
    try {
      const organisations = await firstValueFrom(
        this.organisationApi.search({ pageSize: 200, sortField: 'name', sortDirection: SortDirection.ASC }),
      );
      this.organisations.set(organisations);
    } catch {
      // The id can still be typed in.
    }
  }
}
