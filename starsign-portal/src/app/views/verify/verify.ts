import { DatePipe } from '@angular/common';
import { Component, computed, signal } from '@angular/core';
import { inject } from '@angular/core';
import { form, FormRoot, FormField, validate } from '@angular/forms/signals';
import { DocumentIntegrityReportDTO } from '@models/document';
import { DocumentApi } from '@services/document/document-api';
import { FieldErrors } from '@shared/field-errors/field-errors';
import { FileDrop } from '@shared/file-drop/file-drop';
import { apiErrorMessage } from '@store/app-state';
import { firstValueFrom } from 'rxjs';

/** Checks any PDF against StarSign's tamper-evident records. */
@Component({
  selector: 'app-verify',
  imports: [DatePipe, FieldErrors, FileDrop, FormField, FormRoot],
  template: `
    <div class="page max-w-4xl">
      <header class="page-header">
        <div>
          <span class="eyebrow">Trust centre</span>
          <h1 class="page-title mt-1">Verify a signed document</h1>
          <p class="page-subtitle">
            Drop any PDF to check its hash, signatures and audit chain against StarSign's sealed
            record. The file is only hashed, never stored.
          </p>
        </div>
      </header>

      <form class="card flex flex-col gap-4" [formRoot]="verifyForm" aria-label="Verify document">
        <app-file-drop
          [formField]="verifyForm.file"
          prompt="Drop the signed PDF to verify"
          hint="PDF · checked against the audit chain"
        />
        <app-field-errors [field]="verifyForm.file" />
        @if (error()) {
          <div class="alert alert--danger" role="alert">
            <i class="bi bi-exclamation-triangle" aria-hidden="true"></i>{{ error() }}
          </div>
        }
        <button
          type="submit"
          class="btn btn--verify self-end min-h-11"
          [disabled]="verifyForm().submitting()"
        >
          @if (verifyForm().submitting()) {
            <span class="spinner" aria-hidden="true"></span> Verifying…
          } @else {
            <i class="bi bi-patch-check" aria-hidden="true"></i> Verify integrity
          }
        </button>
      </form>

      @if (report(); as r) {
        <section class="stage p-6" aria-live="polite">
          <div class="flex flex-wrap items-center gap-4">
            @if (passed()) {
              <img src="/brand/seal.svg" alt="Valid digital seal" class="h-24 w-24 flex-none" />
              <span class="seal"
                ><i class="bi bi-patch-check-fill" aria-hidden="true"></i
                ><span class="mono">VERIFIED · TAMPER-EVIDENT SEAL INTACT</span></span
              >
            } @else {
              <span class="chip chip--voided">{{ r.overallResult || 'NOT VERIFIED' }}</span>
            }
            <span class="mono ml-auto text-ink-muted">{{
              r.verifiedAt | date: 'dd-MM-yyyy HH:mm:ss'
            }}</span>
          </div>
          <dl class="mt-6 grid gap-4 md:grid-cols-2">
            <div>
              <dt class="eyebrow">Reference</dt>
              <dd class="m-0 mono">{{ r.referenceNumber || '—' }}</dd>
            </div>
            <div>
              <dt class="eyebrow">Assurance level</dt>
              <dd class="m-0 mono">{{ r.assuranceLevel || '—' }}</dd>
            </div>
            <div>
              <dt class="eyebrow">Audit chain</dt>
              <dd class="m-0" [class]="r.auditChainValid ? 'text-gold' : 'text-crimson'">
                {{ r.auditChainValid ? 'Intact' : 'Broken or unknown' }}
              </dd>
            </div>
            <div>
              <dt class="eyebrow">Digital signatures</dt>
              <dd class="m-0">
                {{ r.signatureCount ?? 0 }} · {{ r.digitalSignaturesValid ? 'valid' : 'not valid' }}
              </dd>
            </div>
            <div class="md:col-span-2">
              <dt class="eyebrow">Original SHA-256</dt>
              <dd class="m-0 hash">{{ r.originalSha256 || '—' }}</dd>
            </div>
            <div class="md:col-span-2">
              <dt class="eyebrow">Sealed SHA-256</dt>
              <dd class="m-0 hash">{{ r.finalSha256 || '—' }}</dd>
            </div>
          </dl>
          @for (message of r.messages ?? []; track $index) {
            <p class="m-0 mt-3 text-body-sm text-ink-muted">{{ message }}</p>
          }
        </section>
      }
    </div>
  `,
})
export class Verify {
  private readonly documentApi = inject(DocumentApi);

  private readonly model = signal<{ file: File | null }>({ file: null });
  protected readonly report = signal<DocumentIntegrityReportDTO | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly passed = computed(() =>
    /PASS|VALID|OK/i.test(this.report()?.overallResult ?? ''),
  );

  protected readonly verifyForm = form(
    this.model,
    (path) => {
      validate(path.file, ({ value }) =>
        !value()
          ? { kind: 'required', message: 'Choose the PDF to verify.' }
          : value()!.name.toLowerCase().endsWith('.pdf')
            ? null
            : { kind: 'fileType', message: 'Only PDF documents can be verified.' },
      );
    },
    {
      submission: {
        action: async () => {
          this.error.set(null);
          this.report.set(null);
          try {
            this.report.set(
              await firstValueFrom(this.documentApi.verifyFile(this.model().file as File)),
            );
          } catch (error) {
            this.error.set(apiErrorMessage(error, 'Verification failed.'));
          }
          return undefined;
        },
      },
    },
  );
}
