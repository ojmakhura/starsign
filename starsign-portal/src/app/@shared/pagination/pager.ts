import { Component, computed, input, output } from '@angular/core';
import { PAGE_SIZE_OPTIONS, pageWindow, showingRecordsLabel } from './pagination';

/**
 * The footer of a paged results table: what is shown, rows per page, and a numbered pager.
 * Page numbers are zero-based, as the API's.
 */
@Component({
  selector: 'app-pager',
  template: `
    <nav
      class="flex flex-wrap items-center justify-between gap-3 border-t border-hairline px-5 py-3"
      aria-label="Pages"
    >
      <div class="flex flex-wrap items-center gap-4">
        <span class="mono text-ink-muted" aria-live="polite">{{ showing() }}</span>
        <label class="flex items-center gap-2 text-ink-muted">
          Rows
          <select
            class="field-control w-auto py-1"
            [value]="pageSize()"
            [disabled]="disabled()"
            (change)="sizeChange.emit(+$any($event.target).value)"
          >
            @for (size of sizeOptions; track size) {
              <option [value]="size" [selected]="size === pageSize()">{{ size }}</option>
            }
          </select>
        </label>
      </div>
      <div class="flex items-center gap-1">
        <button
          type="button"
          class="btn btn--ghost btn--icon"
          aria-label="Previous page"
          [disabled]="disabled() || pageNumber() === 0"
          (click)="pageChange.emit(pageNumber() - 1)"
        >
          <i class="bi bi-chevron-left" aria-hidden="true"></i>
        </button>
        @for (page of pages(); track page) {
          <button
            type="button"
            class="btn btn--icon mono"
            [class.btn--primary]="page === pageNumber()"
            [class.btn--ghost]="page !== pageNumber()"
            [attr.aria-current]="page === pageNumber() ? 'page' : null"
            [attr.aria-label]="'Page ' + (page + 1)"
            [disabled]="disabled()"
            (click)="pageChange.emit(page)"
          >
            {{ page + 1 }}
          </button>
        }
        <button
          type="button"
          class="btn btn--ghost btn--icon"
          aria-label="Next page"
          [disabled]="disabled() || pageNumber() >= totalPages() - 1"
          (click)="pageChange.emit(pageNumber() + 1)"
        >
          <i class="bi bi-chevron-right" aria-hidden="true"></i>
        </button>
      </div>
    </nav>
  `,
})
export class Pager {
  readonly pageNumber = input.required<number>();
  readonly pageSize = input.required<number>();
  readonly totalElements = input.required<number>();
  readonly totalPages = input.required<number>();
  /** The number of rows on the page shown. */
  readonly shown = input.required<number>();
  readonly disabled = input(false);

  readonly pageChange = output<number>();
  readonly sizeChange = output<number>();

  protected readonly sizeOptions = PAGE_SIZE_OPTIONS;
  protected readonly pages = computed(() => pageWindow(this.pageNumber(), this.totalPages()));
  protected readonly showing = computed(() =>
    showingRecordsLabel(this.pageNumber(), this.pageSize(), this.shown(), this.totalElements()),
  );
}
