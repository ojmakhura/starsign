import { computed, effect, Signal, signal, untracked } from '@angular/core';

/** The parts of an API signal store a StoreRequest watches. */
export interface RequestStore {
  loading: Signal<boolean>;
  success: Signal<boolean>;
  messages: Signal<string[]>;
}

export interface StoreRequestHandlers<A extends string> {
  /** Runs after an action succeeds. */
  done?: Partial<Record<A, () => void>>;
  /** Fallback error text per action, used when the store has no message. */
  failed?: Partial<Record<A, string>>;
  /** Runs after an action fails. */
  onError?: (action: A, message: string) => void;
}

/**
 * Tracks the requests a component sends through an API signal store: which action
 * is in flight, whether it is still running, and the error it ended with. Calling
 * `run` starts an action; once the store stops loading, the matching `done`
 * handler runs, or `error` is set from the store's message.
 *
 * Must be created in an injection context.
 */
export class StoreRequest<A extends string> {
  readonly pending = signal<A | null>(null);
  readonly error = signal<string | null>(null);
  readonly busy = computed(() => this.store.loading() && this.pending() !== null);

  constructor(
    private readonly store: RequestStore,
    private readonly handlers: StoreRequestHandlers<A> = {},
  ) {
    effect(() => {
      const action = this.pending();
      if (this.store.loading() || !action) {
        return;
      }
      untracked(() => this.finish(action));
    });
  }

  /** True while `action` is the request in flight. */
  isRunning(action: A): Signal<boolean> {
    return computed(() => this.busy() && this.pending() === action);
  }

  run(action: A, call: () => void): void {
    this.error.set(null);
    this.pending.set(action);
    call();
  }

  private finish(action: A): void {
    this.pending.set(null);
    if (!this.store.success()) {
      const message =
        this.store.messages()[0] ?? this.handlers.failed?.[action] ?? `Failed to ${action}.`;
      this.error.set(message);
      this.handlers.onError?.(action, message);
      return;
    }
    this.handlers.done?.[action]?.();
  }
}
