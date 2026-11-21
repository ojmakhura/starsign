import { Component, inject } from '@angular/core';
import { LoaderState } from '@store/loader-state';

@Component({
  selector: 'app-loader',
  template: `
    @if (loader.isLoading()) {
      <div class="fixed top-0 inset-x-0 z-[70] h-0.5 overflow-hidden bg-cyan/10" role="progressbar" aria-label="Loading">
        <div class="h-full w-1/3 bg-cyan shadow-[0_0_12px_var(--color-cyan)] animate-[loader-slide_1.1s_ease-in-out_infinite]"></div>
      </div>
      <span class="sr-only" aria-live="polite">{{ loader.message() }}</span>
    }
  `,
  styles: `
    @keyframes loader-slide {
      from { transform: translateX(-100%); }
      to { transform: translateX(300%); }
    }
  `,
})
export class Loader {
  protected readonly loader = inject(LoaderState);
}
