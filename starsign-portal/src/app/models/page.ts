/**
 * One page of a paged search: Spring Data's Page, serialised VIA_DTO by the API
 * (`{ content, page: { size, number, totalElements, totalPages } }`).
 * Hand-written: scripts/generate-api.py imports it for endpoints that return Page<T>.
 */
export interface Page<T> {
  content: T[];
  page: PageMetadata;
}

export interface PageMetadata {
  /** The largest number of items a page holds. */
  size: number;
  /** The zero-based number of this page. */
  number: number;
  /** The number of items the search matches, over all pages. */
  totalElements: number;
  /** The number of pages the search's items fill. */
  totalPages: number;
}

/** An empty first page, for state before the first search. */
export function emptyPage<T>(size = 0): Page<T> {
  return { content: [], page: { size, number: 0, totalElements: 0, totalPages: 0 } };
}
