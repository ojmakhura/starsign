import { DestroyRef, inject, Injector, Type } from '@angular/core';

/**
 * Creates a private instance of a root signal store for the calling component,
 * destroyed along with it, so lookups neither overwrite the shared store's data
 * nor share its loading/success flags. Must be called in an injection context.
 */
export function injectLocalStore<T>(store: Type<T>): T {
  const injector = Injector.create({ providers: [store], parent: inject(Injector) });
  inject(DestroyRef).onDestroy(() => injector.destroy());
  return injector.get(store);
}
