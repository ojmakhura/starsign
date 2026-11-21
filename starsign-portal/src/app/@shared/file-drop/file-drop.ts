import { Component, computed, input, model, signal } from '@angular/core';
import { FormValueControl, ValidationError } from '@angular/forms/signals';

/**
 * Drag-and-drop file picker that binds straight to a signal-form field:
 * `<app-file-drop [formField]="form.file" accept="application/pdf" />`.
 * Implements FormValueControl<File | null>, so required/validate rules on the field
 * apply and its touched/invalid/disabled state flows back in.
 */
@Component({
  selector: 'app-file-drop',
  template: `
    <label
      class="flex flex-col items-center justify-center gap-2 rounded-[var(--radius-panel)] border border-dashed px-6 py-10 text-center transition-colors cursor-pointer"
      [class]="
        dragging()
          ? 'border-cyan bg-cyan/10'
          : showInvalid()
            ? 'border-crimson bg-crimson/5'
            : 'border-hairline-strong bg-obsidian hover:border-hairline-cyan'
      "
      [class.opacity-50]="disabled()"
      (dragover)="onDragOver($event)"
      (dragleave)="dragging.set(false)"
      (drop)="onDrop($event)"
    >
      <input
        class="sr-only"
        type="file"
        [accept]="accept()"
        [disabled]="disabled()"
        (change)="onPick($event)"
        (blur)="touched.set(true)"
      />
      @if (value(); as file) {
        <i class="bi bi-file-earmark-pdf text-3xl text-cyan" aria-hidden="true"></i>
        <span class="font-medium text-ink">{{ file.name }}</span>
        <span class="mono text-ink-muted">{{ sizeLabel() }} · {{ file.type || 'unknown type' }}</span>
        <button type="button" class="btn btn--ghost mt-1" (click)="clear($event)">
          <i class="bi bi-x-lg" aria-hidden="true"></i> Remove
        </button>
      } @else {
        <i class="bi bi-cloud-arrow-up text-3xl text-cyan" aria-hidden="true"></i>
        <span class="font-medium text-ink">{{ prompt() }}</span>
        <span class="text-body-sm text-ink-muted">{{ hint() }}</span>
      }
    </label>
  `,
})
export class FileDrop implements FormValueControl<File | null> {
  readonly value = model<File | null>(null);
  readonly touched = model(false);
  readonly disabled = input(false);
  readonly invalid = input(false);
  readonly errors = input<readonly ValidationError[]>([]);

  readonly accept = input('application/pdf');
  readonly prompt = input('Drop a PDF here or click to browse');
  readonly hint = input('PDF only');

  protected readonly dragging = signal(false);
  protected readonly showInvalid = computed(() => this.touched() && this.invalid());
  protected readonly sizeLabel = computed(() => {
    const bytes = this.value()?.size ?? 0;
    return bytes >= 1_048_576 ? `${(bytes / 1_048_576).toFixed(2)} MB` : `${Math.ceil(bytes / 1024)} KB`;
  });

  protected onPick(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.select(input.files?.[0] ?? null);
    input.value = '';
  }

  protected onDragOver(event: DragEvent): void {
    event.preventDefault();
    if (!this.disabled()) {
      this.dragging.set(true);
    }
  }

  protected onDrop(event: DragEvent): void {
    event.preventDefault();
    this.dragging.set(false);
    if (!this.disabled()) {
      this.select(event.dataTransfer?.files?.[0] ?? null);
    }
  }

  protected clear(event: Event): void {
    event.preventDefault();
    this.select(null);
  }

  private select(file: File | null): void {
    this.value.set(file);
    this.touched.set(true);
  }
}
