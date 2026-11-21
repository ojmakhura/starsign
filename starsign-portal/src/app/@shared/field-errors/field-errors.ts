import { Component, computed, input } from '@angular/core';
import { FieldTree } from '@angular/forms/signals';

/**
 * Lists a signal-form field's validation errors once the field is touched (or the
 * form was submitted, which marks every field touched).
 */
@Component({
  selector: 'app-field-errors',
  template: `
    @if (visible()) {
      <ul class="field-errors" role="alert">
        @for (error of field()().errors(); track error.kind + $index) {
          <li>{{ error.message ?? fallback(error.kind) }}</li>
        }
      </ul>
    }
  `,
})
export class FieldErrors {
  readonly field = input.required<FieldTree<unknown>>();
  protected readonly visible = computed(() => {
    const state = this.field()();
    return state.touched() && state.invalid();
  });

  protected fallback(kind: string): string {
    switch (kind) {
      case 'required':
        return 'This field is required.';
      case 'email':
        return 'Enter a valid email address.';
      case 'minLength':
        return 'Too short.';
      case 'maxLength':
        return 'Too long.';
      case 'pattern':
        return 'The format is not valid.';
      default:
        return 'This value is not valid.';
    }
  }
}
