import { BaseSearchCriteria } from '@models/common';
import { emptyPage, Page } from '@models/page';
import { Observable, of, Subject } from 'rxjs';
import { describe, expect, it } from 'vitest';
import { PagedSearch, pageWindow, showingRecordsLabel } from './pagination';

describe('pageWindow', () => {
  it('centres the window on the current page', () => {
    expect(pageWindow(5, 20)).toEqual([3, 4, 5, 6, 7]);
  });

  it('keeps the window inside the pages', () => {
    expect(pageWindow(0, 20)).toEqual([0, 1, 2, 3, 4]);
    expect(pageWindow(19, 20)).toEqual([15, 16, 17, 18, 19]);
    expect(pageWindow(1, 3)).toEqual([0, 1, 2]);
  });

  it('shows one page when there are none', () => {
    expect(pageWindow(0, 0)).toEqual([0]);
  });
});

describe('showingRecordsLabel', () => {
  it('shows the range of the page', () => {
    expect(showingRecordsLabel(1, 10, 10, 42)).toBe('Showing 11–20 of 42');
    expect(showingRecordsLabel(4, 10, 2, 42)).toBe('Showing 41–42 of 42');
  });

  it('shows nothing for an empty result', () => {
    expect(showingRecordsLabel(0, 10, 0, 0)).toBe('Showing 0 of 0');
  });
});

interface Criteria extends BaseSearchCriteria {
  text?: string;
}

describe('PagedSearch', () => {
  const page = (number: number, content: string[], totalElements = 30): Page<string> => ({
    content,
    page: { number, size: 10, totalElements, totalPages: Math.ceil(totalElements / 10) },
  });

  it('searches from the first page and pages with the applied criteria', async () => {
    const sent: Criteria[] = [];
    const search = new PagedSearch<Criteria, string>((criteria) => {
      sent.push(criteria);
      return of(page(criteria.pageNumber ?? 0, ['a']));
    });

    await search.search({ text: 'nda' });
    await search.goTo(2);

    expect(sent).toEqual([
      { text: 'nda', pageNumber: 0, pageSize: 25 },
      { text: 'nda', pageNumber: 2, pageSize: 25 },
    ]);
    expect(search.pageNumber()).toBe(2);
    expect(search.totalElements()).toBe(30);
  });

  it('drops a response that a newer search replaced', async () => {
    const responses: Subject<Page<string>>[] = [];
    const search = new PagedSearch<Criteria, string>(() => {
      const response = new Subject<Page<string>>();
      responses.push(response);
      return response as Observable<Page<string>>;
    });

    const first = search.search({ text: 'old' });
    const second = search.search({ text: 'new' });
    responses[1].next(page(0, ['new']));
    responses[1].complete();
    responses[0].next(page(0, ['old']));
    responses[0].complete();
    await Promise.all([first, second]);

    expect(search.items()).toEqual(['new']);
    expect(search.loading()).toBe(false);
  });

  it('starts empty', () => {
    const search = new PagedSearch<Criteria, string>(() => of(emptyPage<string>()));
    expect(search.items()).toEqual([]);
    expect(search.searched()).toBe(false);
  });
});
