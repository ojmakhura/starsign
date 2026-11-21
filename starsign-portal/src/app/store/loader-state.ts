import { computed, Injectable, signal } from '@angular/core';

/**
 * Global busy indicator. Pages register a loading signal with `track`; the overlay
 * shows while any registered signal is true.
 */
@Injectable({ providedIn: 'root' })
export class LoaderState {
  readonly message = signal('Loading ...');
  private readonly sources = signal<ReadonlyArray<() => boolean>>([]);
  readonly isLoading = computed(() => this.sources().some((source) => source()));

  /** Shows the loader while `loading()` is true; returns a function that stops tracking it. */
  track(loading: () => boolean, message?: string): () => void {
    if (message) {
      this.message.set(message);
    }
    this.sources.update((sources) => [...sources, loading]);
    return () => this.sources.update((sources) => sources.filter((source) => source !== loading));
  }
}
