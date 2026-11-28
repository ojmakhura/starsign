import { computed, signal } from '@angular/core';
import { BaseSearchCriteria } from '@models/common';
import { emptyPage, Page } from '@models/page';
import { apiErrorMessage } from '@store/app-state';
import { firstValueFrom, Observable } from 'rxjs';

/** Page sizes offered by the pager on the search pages. */
export const PAGE_SIZE_OPTIONS = [10, 25, 50, 100];

export const DEFAULT_PAGE_SIZE = 25;

/**
 * Zero-based page indexes for a numbered pager: a window of up to `size` pages centred on the
 * current page where possible.
 */
export function pageWindow(currentPage: number, totalPages: number, size = 5): number[] {
  const total = Math.max(totalPages, 1);
  const length = Math.min(size, total);
  const start = Math.min(Math.max(currentPage - Math.floor(length / 2), 0), total - length);
  return Array.from({ length }, (_, i) => start + i);
}

/** "Showing 11–20 of 42" for the results header. */
export function showingRecordsLabel(
  currentPage: number,
  pageSize: number,
  shown: number,
  total: number,
): string {
  if (!total || !shown) {
    return `Showing 0 of ${total}`;
  }
  const start = currentPage * pageSize + 1;
  return `Showing ${start}–${start + shown - 1} of ${total}`;
}

/**
 * The state of a paged search page: the criteria of the last search, the page shown, and loading
 * and error flags. `search` applies new criteria from the first page; `goTo` and `setPageSize` page
 * through the results of the criteria already applied. A response that arrives after a newer
 * request was sent is dropped.
 */
export class PagedSearch<C extends BaseSearchCriteria, T> {
  readonly result = signal<Page<T>>(emptyPage<T>(DEFAULT_PAGE_SIZE));
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  readonly pageSize = signal(DEFAULT_PAGE_SIZE);
  /** Whether a search ran yet; before it the empty page is no "no results". */
  readonly searched = signal(false);

  readonly items = computed(() => this.result().content);
  readonly pageNumber = computed(() => this.result().page.number);
  readonly totalElements = computed(() => this.result().page.totalElements);
  readonly totalPages = computed(() => this.result().page.totalPages);

  /** Criteria of the last search, without the page, used for paging and export. */
  readonly applied = signal<C | null>(null);
  private request = 0;

  constructor(
    private readonly fetch: (criteria: C) => Observable<Page<T>>,
    private readonly failure = 'The search failed.',
  ) {}

  /** Searches with new criteria, from the first page. */
  search(criteria: C): Promise<void> {
    this.applied.set(criteria);
    return this.load(0);
  }

  /** Shows another page of the last search. */
  goTo(page: number): Promise<void> {
    if (page < 0 || page >= Math.max(this.totalPages(), 1) || page === this.pageNumber()) {
      return Promise.resolve();
    }
    return this.load(page);
  }

  setPageSize(size: number): Promise<void> {
    this.pageSize.set(size);
    return this.load(0);
  }

  /** Runs the last search again on the page shown, e.g. after a change to one of its rows. */
  refresh(): Promise<void> {
    return this.load(this.pageNumber());
  }

  private async load(page: number): Promise<void> {
    const criteria = this.applied();
    if (!criteria) {
      return;
    }
    const request = ++this.request;
    this.loading.set(true);
    this.error.set(null);
    try {
      const result = await firstValueFrom(
        this.fetch({ ...criteria, pageNumber: page, pageSize: this.pageSize() }),
      );
      if (request === this.request) {
        this.result.set(result ?? emptyPage<T>(this.pageSize()));
        this.searched.set(true);
      }
    } catch (error) {
      if (request === this.request) {
        this.error.set(apiErrorMessage(error, this.failure));
      }
    } finally {
      if (request === this.request) {
        this.loading.set(false);
      }
    }
  }
}
