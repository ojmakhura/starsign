import {
  afterNextRender,
  Component,
  ElementRef,
  input,
  model,
  viewChild,
} from '@angular/core';
import { FormValueControl } from '@angular/forms/signals';

/**
 * Drawn-signature capture bound to a signal-form field as a PNG data URL:
 * `<app-signature-pad [formField]="form.signatureImage" />`. An empty pad is ''.
 */
@Component({
  selector: 'app-signature-pad',
  template: `
    <!-- White like paper: the ink is black, as it is stamped on the document -->
    <div class="sign-here bg-white" [class.opacity-50]="disabled()">
      <span class="sign-here__label">Sign here <i class="bi bi-arrow-down-right" aria-hidden="true"></i></span>
      <canvas
        #canvas
        class="block w-full h-44 touch-none cursor-crosshair"
        role="img"
        aria-label="Signature drawing area"
        (pointerdown)="start($event)"
        (pointermove)="draw($event)"
        (pointerup)="end()"
        (pointerleave)="end()"
      ></canvas>
    </div>
    <div class="mt-2 flex items-center justify-between">
      <span class="mono text-ink-muted">{{ value() ? 'SIGNATURE CAPTURED' : 'DRAW WITH MOUSE, PEN OR FINGER' }}</span>
      <button type="button" class="btn btn--ghost" (click)="clear()" [disabled]="disabled() || !value()">
        <i class="bi bi-eraser" aria-hidden="true"></i> Clear
      </button>
    </div>
  `,
})
export class SignaturePad implements FormValueControl<string> {
  readonly value = model('');
  readonly touched = model(false);
  readonly disabled = input(false);

  private readonly canvas = viewChild.required<ElementRef<HTMLCanvasElement>>('canvas');
  private context: CanvasRenderingContext2D | null = null;
  private drawing = false;

  constructor() {
    afterNextRender(() => {
      const canvas = this.canvas().nativeElement;
      const ratio = window.devicePixelRatio || 1;
      canvas.width = canvas.offsetWidth * ratio;
      canvas.height = canvas.offsetHeight * ratio;
      this.context = canvas.getContext('2d');
      if (this.context) {
        this.context.scale(ratio, ratio);
        this.context.lineWidth = 2.2;
        this.context.lineCap = 'round';
        this.context.lineJoin = 'round';
        // Black ink, the colour signatures are stamped in (the server makes any signature image black too)
        this.context.strokeStyle = '#000000';
      }
    });
  }

  protected start(event: PointerEvent): void {
    if (this.disabled() || !this.context) {
      return;
    }
    this.drawing = true;
    (event.target as HTMLElement).setPointerCapture(event.pointerId);
    this.context.beginPath();
    this.context.moveTo(event.offsetX, event.offsetY);
  }

  protected draw(event: PointerEvent): void {
    if (!this.drawing || !this.context) {
      return;
    }
    this.context.lineTo(event.offsetX, event.offsetY);
    this.context.stroke();
  }

  protected end(): void {
    if (!this.drawing) {
      return;
    }
    this.drawing = false;
    this.touched.set(true);
    this.value.set(this.canvas().nativeElement.toDataURL('image/png'));
  }

  protected clear(): void {
    const canvas = this.canvas().nativeElement;
    this.context?.clearRect(0, 0, canvas.width, canvas.height);
    this.value.set('');
    this.touched.set(true);
  }
}
