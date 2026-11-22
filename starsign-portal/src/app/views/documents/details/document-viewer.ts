import { Component, DestroyRef, effect, inject, input, signal, untracked } from '@angular/core';
import { DomSanitizer, SafeResourceUrl } from '@angular/platform-browser';
import { DocumentApi } from '@services/document/document-api';
import { apiErrorMessage } from '@store/app-state';
import { firstValueFrom } from 'rxjs';

type ViewMode = 'pdf' | 'pages';

/** Most documents are short; this only bounds the page-by-page loop. */
const MAX_PAGES = 500;

/**
 * Shows the document as it is stored now: after signing that is the sealed PDF with the stamped signatures and
 * the signing certificate. "PDF" embeds the file in the browser's own viewer (exact rendering, text selection,
 * the reader's signature panel where it has one). On phones, whose browsers mostly cannot show a PDF inside a page,
 * it starts on "Pages" (server-rendered page images) and offers the switch; desktops only get the PDF.
 */
@Component({
  selector: 'app-document-viewer',
  template: `
    <div class="flex flex-col gap-3">
      <div class="flex flex-wrap items-center gap-2">
        @if (phone()) {
          <div class="segmented" role="radiogroup" aria-label="How to show the document">
            <label
              ><input
                type="radio"
                name="viewer-mode"
                [checked]="mode() === 'pdf'"
                (change)="setMode('pdf')"
              />PDF</label
            >
            <label
              ><input
                type="radio"
                name="viewer-mode"
                [checked]="mode() === 'pages'"
                (change)="setMode('pages')"
              />Pages</label
            >
          </div>
        }
        @if (pdfUrl()) {
          <a class="btn btn--ghost ml-auto" [href]="rawUrl" target="_blank" rel="noopener">
            <i class="bi bi-box-arrow-up-right" aria-hidden="true"></i> Open in new tab
          </a>
        }
      </div>

      @if (error()) {
        <div class="alert alert--danger" role="alert">
          <i class="bi bi-exclamation-triangle" aria-hidden="true"></i>{{ error() }}
        </div>
      }

      @if (mode() === 'pdf') {
        @if (pdfUrl(); as url) {
          <iframe
            class="block h-[78vh] min-h-[480px] w-full rounded-[var(--radius-panel)] border-0 bg-white"
            [src]="url"
            [title]="title() + ' (PDF)'"
          ></iframe>
        } @else if (!error()) {
          <p class="m-0 p-10 text-center text-ink-muted" role="status">
            <span class="spinner" aria-hidden="true"></span> Loading the document…
          </p>
        }
      } @else {
        <div class="flex flex-col items-center gap-5">
          @for (src of pages(); track $index; let i = $index) {
            <figure class="m-0 flex w-full max-w-[820px] flex-col gap-2">
              <img
                class="block w-full rounded bg-white shadow-lg"
                [src]="src"
                [alt]="'Page ' + (i + 1) + ' of ' + title()"
              />
              <figcaption class="text-center mono text-ink-muted">Page {{ i + 1 }}</figcaption>
            </figure>
          }
          @if (loadingPages()) {
            <p class="m-0 text-ink-muted" role="status">
              <span class="spinner" aria-hidden="true"></span> Loading page
              {{ pages().length + 1 }}…
            </p>
          }
        </div>
      }
    </div>
  `,
})
export class DocumentViewer {
  readonly documentId = input.required<string>();
  readonly title = input<string>('Document');
  /** Changes when the stored file does (new version, completion), so the viewer reloads. */
  readonly revision = input<unknown>(null);

  private readonly documentApi = inject(DocumentApi);
  private readonly sanitizer = inject(DomSanitizer);

  private readonly phoneQuery =
    typeof window !== 'undefined' ? window.matchMedia?.('(max-width: 767px)') : undefined;
  /** Phone-sized screen: the only place the PDF/Pages switch is shown. */
  protected readonly phone = signal(!!this.phoneQuery?.matches);
  /** Phones rarely render a PDF inside a page: start them on page images. */
  protected readonly mode = signal<ViewMode>(this.phone() ? 'pages' : 'pdf');
  protected readonly pdfUrl = signal<SafeResourceUrl | null>(null);
  protected rawUrl = '';
  protected readonly pages = signal<string[]>([]);
  protected readonly loadingPages = signal(false);
  protected readonly error = signal<string | null>(null);

  private objectUrls: string[] = [];
  /** Bumped on every reload, so a slower earlier load cannot overwrite a newer one. */
  private generation = 0;

  constructor() {
    inject(DestroyRef).onDestroy(() => this.reset());

    // A window widened past phone size loses the switch, so it goes back to the PDF
    const onResize = (event: MediaQueryListEvent) => {
      this.phone.set(event.matches);
      if (!event.matches && this.mode() === 'pages') {
        this.setMode('pdf');
      }
    };
    this.phoneQuery?.addEventListener('change', onResize);
    inject(DestroyRef).onDestroy(() => this.phoneQuery?.removeEventListener('change', onResize));
    effect(() => {
      const id = this.documentId();
      this.revision();
      untracked(() => {
        this.reset();
        const run = ++this.generation;
        if (this.mode() === 'pdf') {
          void this.loadPdf(id, run);
        } else {
          void this.loadPages(id, run);
        }
      });
    });
  }

  protected setMode(mode: ViewMode): void {
    this.mode.set(mode);
    this.error.set(null);
    const id = this.documentId();
    if (mode === 'pdf' && !this.pdfUrl()) {
      void this.loadPdf(id, this.generation);
    } else if (mode === 'pages' && !this.pages().length && !this.loadingPages()) {
      void this.loadPages(id, this.generation);
    }
  }

  private async loadPdf(id: string, run: number): Promise<void> {
    try {
      // The current version for viewing (documents:view): unlike /download it works for unsigned drafts and is
      // not recorded as a download. After completion the current version is the sealed copy.
      const blob = await firstValueFrom(this.documentApi.streamForPreparation(id));
      if (run !== this.generation) {
        return;
      }
      const url = this.track(URL.createObjectURL(new Blob([blob], { type: 'application/pdf' })));
      this.rawUrl = url;
      this.pdfUrl.set(this.sanitizer.bypassSecurityTrustResourceUrl(url));
    } catch (error) {
      if (run === this.generation) {
        this.error.set(apiErrorMessage(error, 'The document could not be loaded.'));
      }
    }
  }

  /**
   * Fetches page images one after another until the API reports no further page: the stored page count of a
   * signed version may not include the signing certificate pages appended at completion.
   */
  private async loadPages(id: string, run: number): Promise<void> {
    this.loadingPages.set(true);
    try {
      for (let page = 1; page <= MAX_PAGES; page++) {
        let blob: Blob;
        try {
          blob = await firstValueFrom(this.documentApi.getPreview(id, page));
        } catch (error) {
          if (page === 1 && run === this.generation) {
            this.error.set(apiErrorMessage(error, 'The pages could not be loaded.'));
          }
          return; // past the last page
        }
        if (run !== this.generation) {
          return;
        }
        const url = this.track(URL.createObjectURL(blob));
        this.pages.update((list) => [...list, url]);
      }
    } finally {
      if (run === this.generation) {
        this.loadingPages.set(false);
      }
    }
  }

  private track(url: string): string {
    this.objectUrls.push(url);
    return url;
  }

  private reset(): void {
    this.objectUrls.forEach((url) => URL.revokeObjectURL(url));
    this.objectUrls = [];
    this.pdfUrl.set(null);
    this.rawUrl = '';
    this.pages.set([]);
    this.loadingPages.set(false);
    this.error.set(null);
  }
}
