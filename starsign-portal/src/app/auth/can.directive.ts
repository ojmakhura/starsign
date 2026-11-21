import { Directive, effect, inject, input, TemplateRef, ViewContainerRef } from '@angular/core';
import { SessionStore } from '@store/session.store';
import { hasPermission } from './page-permissions';

/**
 * Renders the element only when the signed-in user holds one of the given permission
 * scopes, e.g. `<button *appCan="'workflows:cancel'">`. Updates when permissions change.
 */
@Directive({ selector: '[appCan]' })
export class Can {
  readonly appCan = input.required<string | readonly string[]>();

  private readonly session = inject(SessionStore);
  private readonly template = inject(TemplateRef<unknown>);
  private readonly container = inject(ViewContainerRef);
  private shown = false;

  constructor() {
    effect(() => {
      const required = this.appCan();
      const allowed = hasPermission(
        typeof required === 'string' ? [required] : required,
        this.session.permissions(),
      );
      if (allowed && !this.shown) {
        this.container.createEmbeddedView(this.template);
      } else if (!allowed && this.shown) {
        this.container.clear();
      }
      this.shown = allowed;
    });
  }
}
