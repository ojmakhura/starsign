import { DatePipe, DecimalPipe } from '@angular/common';
import { httpResource } from '@angular/common/http';
import { Component, computed, inject, input, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { Can } from '@auth/can.directive';
import { DocumentDTO, DocumentIntegrityReportDTO, DocumentStatus } from '@models/document';
import { DocumentApi } from '@services/document/document-api';
import { saveFile } from '@shared/save-file';
import { StatusChip } from '@shared/status-chip/status-chip';
import { apiErrorMessage } from '@store/app-state';
import { DocumentApiStore } from '@store/document/document-api.store';
import { injectLocalStore } from '@store/local-store';
import { LoaderState } from '@store/loader-state';
import { StoreRequest } from '@store/store-request';
import { toast } from 'ngx-sonner';
import { firstValueFrom } from 'rxjs';

@Component({
  selector: 'app-document-details',
  imports: [Can, DatePipe, DecimalPipe, RouterLink, StatusChip],
  templateUrl: './document-details.html',
})
export class DocumentDetails {
  readonly id = input.required<string>();

  private readonly documentApi = inject(DocumentApi);
  private readonly router = inject(Router);

  protected readonly document = httpResource<DocumentDTO>(() => `/documents/${this.id()}`);
  protected readonly doc = computed(() =>
    this.document.hasValue() ? this.document.value() : null,
  );

  /** Integrity is checked on demand; the report resets when the document changes. */
  private readonly verifyRequested = signal<string | null>(null);
  protected readonly integrity = httpResource<DocumentIntegrityReportDTO>(() =>
    this.verifyRequested() === this.id() ? `/documents/${this.id()}/integrity` : undefined,
  );
  protected readonly integrityPassed = computed(() => {
    const report = this.integrity.hasValue() ? this.integrity.value() : null;
    return report ? /PASS|VALID|OK/i.test(report.overallResult ?? '') : null;
  });

  /** Archive/delete go through a private store instance and a StoreRequest. */
  private readonly actions = injectLocalStore(DocumentApiStore);
  protected readonly request = new StoreRequest<'archive' | 'remove'>(this.actions, {
    done: {
      archive: () => {
        toast.success('Document archived');
        this.document.reload();
      },
      remove: () => {
        toast.success('Document deleted');
        this.router.navigate(['/documents']);
      },
    },
  });

  protected readonly canStartWorkflow = computed(() => {
    const status = this.doc()?.status;
    return (
      !this.doc()?.activeWorkflowId &&
      (status === DocumentStatus.UPLOADED ||
        status === DocumentStatus.DRAFT ||
        status === DocumentStatus.READY_FOR_SIGNING)
    );
  });
  protected readonly sealed = computed(() => this.doc()?.status === DocumentStatus.COMPLETED);

  constructor() {
    inject(LoaderState).track(
      () => this.document.isLoading() || this.request.busy(),
      'Loading document…',
    );
  }

  protected verify(): void {
    if (this.verifyRequested() === this.id()) {
      this.integrity.reload();
    } else {
      this.verifyRequested.set(this.id());
    }
  }

  protected archive(): void {
    this.request.run('archive', () => this.actions.archive({ id: this.id() }));
  }

  protected remove(): void {
    if (confirm(`Delete "${this.doc()?.title}"? This cannot be undone.`)) {
      this.request.run('remove', () => this.actions.remove({ id: this.id() }));
    }
  }

  protected async download(): Promise<void> {
    try {
      const blob = await firstValueFrom(this.documentApi.download(this.id()));
      saveFile(
        blob,
        this.doc()?.originalFileName || `${this.doc()?.referenceNumber || 'document'}.pdf`,
      );
    } catch (error) {
      toast.error('Download failed', { description: apiErrorMessage(error) });
    }
  }
}
