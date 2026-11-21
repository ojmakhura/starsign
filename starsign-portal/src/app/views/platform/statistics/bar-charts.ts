import { Component, computed, input, signal } from '@angular/core';

export interface BarDatum {
  label: string;
  value: number;
  /** Formatted value for labels and the hover read-out; defaults to a localised number. */
  display?: string;
}

const display = (d: BarDatum) => d.display ?? d.value.toLocaleString();

/** Path of a bar with 4px rounded data-end (top), square at the baseline. */
function verticalBar(x: number, y: number, w: number, h: number): string {
  if (h <= 0) {
    return '';
  }
  const r = Math.min(4, w / 2, h);
  return `M${x},${y + h}V${y + r}Q${x},${y} ${x + r},${y}H${x + w - r}Q${x + w},${y} ${x + w},${y + r}V${y + h}Z`;
}

/** Path of a bar with 4px rounded data-end (right), square at the baseline. */
function horizontalBar(x: number, y: number, w: number, h: number): string {
  if (w <= 0) {
    return '';
  }
  const r = Math.min(4, h / 2, w);
  return `M${x},${y}H${x + w - r}Q${x + w},${y} ${x + w},${y + r}V${y + h - r}Q${x + w},${y + h} ${x + w - r},${y + h}H${x}Z`;
}

/** A "nice" axis maximum (1, 2, 2.5 or 5 × 10^n) at or above `value`. */
function niceMax(value: number): number {
  if (value <= 0) {
    return 1;
  }
  const power = 10 ** Math.floor(Math.log10(value));
  for (const step of [1, 2, 2.5, 5, 10]) {
    if (step * power >= value) {
      return step * power;
    }
  }
  return 10 * power;
}

/**
 * Single-series column chart (inline SVG, no dependencies): one hue, thin recessive
 * grid, hover read-out, values available to screen readers as a list.
 */
@Component({
  selector: 'app-column-chart',
  styles: `
    :host { display: block; }
    .bar { fill: var(--color-cyan); opacity: 0.85; transition: opacity 0.15s; }
    .bar--active { opacity: 1; }
    .dim .bar:not(.bar--active) { opacity: 0.35; }
    .grid { stroke: var(--color-hairline, rgb(255 255 255 / 0.08)); stroke-width: 1; }
    .axis-label { fill: var(--color-ink-muted); font: 10px var(--font-mono); }
    .hit { fill: transparent; cursor: default; }
  `,
  template: `
    <div class="mb-1 flex items-baseline justify-between gap-2">
      <span class="text-body-sm text-ink">{{ title() }}</span>
      <span class="mono text-ink-muted" aria-live="polite">
        @if (hovered(); as d) {
          {{ d.label }} · <span class="text-ink">{{ display(d) }}</span>
        } @else {
          {{ summary() }}
        }
      </span>
    </div>
    <svg
      [attr.viewBox]="'0 0 ' + width + ' ' + height"
      class="block w-full"
      [class.dim]="hoveredIndex() !== null"
      role="img"
      [attr.aria-label]="ariaLabel()"
      (mouseleave)="hoveredIndex.set(null)"
    >
      @for (tick of ticks(); track tick.y) {
        <line class="grid" [attr.x1]="padLeft" [attr.x2]="width" [attr.y1]="tick.y" [attr.y2]="tick.y" />
        <text class="axis-label" x="0" [attr.y]="tick.y + 3">{{ tick.label }}</text>
      }
      @for (bar of bars(); track bar.label; let i = $index) {
        <path class="bar" [class.bar--active]="hoveredIndex() === i" [attr.d]="bar.path" />
        <rect
          class="hit"
          [attr.x]="bar.slotX"
          y="0"
          [attr.width]="bar.slotW"
          [attr.height]="plotBottom"
          (mouseenter)="hoveredIndex.set(i)"
        />
        <text class="axis-label" [attr.x]="bar.cx" [attr.y]="height - 2" text-anchor="middle">
          {{ bar.label }}
        </text>
      }
    </svg>
  `,
})
export class ColumnChart {
  readonly title = input.required<string>();
  readonly data = input.required<readonly BarDatum[]>();
  /** Text shown when nothing is hovered, e.g. the total. */
  readonly summary = input('');

  protected readonly width = 320;
  protected readonly height = 150;
  protected readonly padLeft = 34;
  protected readonly plotBottom = 132;
  private readonly plotTop = 6;

  protected readonly display = display;
  protected readonly hoveredIndex = signal<number | null>(null);
  protected readonly hovered = computed(() => {
    const index = this.hoveredIndex();
    return index === null ? null : (this.data()[index] ?? null);
  });

  private readonly max = computed(() => niceMax(Math.max(0, ...this.data().map((d) => d.value))));

  protected readonly ticks = computed(() => {
    const max = this.max();
    return [0, 0.5, 1].map((f) => ({
      y: this.plotBottom - f * (this.plotBottom - this.plotTop),
      label: compact(max * f),
    }));
  });

  protected readonly bars = computed(() => {
    const data = this.data();
    const plotW = this.width - this.padLeft;
    const slotW = data.length ? plotW / data.length : plotW;
    const barW = Math.max(4, Math.min(28, slotW * 0.55));
    const scale = (this.plotBottom - this.plotTop) / this.max();
    return data.map((d, i) => {
      const slotX = this.padLeft + i * slotW;
      const h = Math.max(0, d.value * scale);
      const x = slotX + (slotW - barW) / 2;
      return {
        label: d.label,
        slotX,
        slotW,
        cx: slotX + slotW / 2,
        path: verticalBar(x, this.plotBottom - h, barW, h),
      };
    });
  });

  protected readonly ariaLabel = computed(
    () => `${this.title()}: ` + this.data().map((d) => `${d.label} ${display(d)}`).join(', '),
  );
}

/**
 * Single-series horizontal bar chart with direct value labels (category names on the
 * left, values at the bar ends), inline SVG.
 */
@Component({
  selector: 'app-bar-list-chart',
  styles: `
    :host { display: block; }
    .bar { fill: var(--color-cyan); opacity: 0.85; }
    .bar--active { opacity: 1; }
    .track { fill: rgb(255 255 255 / 0.04); }
    .label { fill: var(--color-ink-muted); font: 12px var(--font-sans); }
    .value { fill: var(--color-ink); font: 12px var(--font-mono); }
  `,
  template: `
    <svg
      [attr.viewBox]="'0 0 ' + width + ' ' + height()"
      class="block w-full"
      role="img"
      [attr.aria-label]="ariaLabel()"
    >
      @for (row of rows(); track row.label; let i = $index) {
        <g (mouseenter)="hoveredIndex.set(i)" (mouseleave)="hoveredIndex.set(null)">
          <title>{{ row.label }}: {{ row.display }}</title>
          <text class="label" x="0" [attr.y]="row.y + barHeight / 2 + 4">{{ row.label }}</text>
          <rect class="track" [attr.x]="labelWidth" [attr.y]="row.y" [attr.width]="plotWidth" [attr.height]="barHeight" rx="2" />
          <path class="bar" [class.bar--active]="hoveredIndex() === i" [attr.d]="row.path" />
          <text class="value" [attr.x]="labelWidth + row.w + 6" [attr.y]="row.y + barHeight / 2 + 4">
            {{ row.display }}
          </text>
        </g>
      }
    </svg>
  `,
})
export class BarListChart {
  readonly data = input.required<readonly BarDatum[]>();
  readonly label = input('Chart');

  protected readonly width = 480;
  protected readonly labelWidth = 150;
  protected readonly plotWidth = 270;
  protected readonly barHeight = 16;
  private readonly gap = 12;

  protected readonly hoveredIndex = signal<number | null>(null);
  protected readonly height = computed(() =>
    Math.max(1, this.data().length * (this.barHeight + this.gap) - this.gap),
  );

  protected readonly rows = computed(() => {
    const max = Math.max(1, ...this.data().map((d) => d.value));
    return this.data().map((d, i) => {
      const y = i * (this.barHeight + this.gap);
      const w = (d.value / max) * this.plotWidth;
      return {
        label: d.label,
        display: display(d),
        y,
        w,
        path: horizontalBar(this.labelWidth, y, w, this.barHeight),
      };
    });
  });

  protected readonly ariaLabel = computed(
    () => `${this.label()}: ` + this.data().map((d) => `${d.label} ${display(d)}`).join(', '),
  );
}

/** 1234 → "1.2k" for axis labels. */
function compact(value: number): string {
  return new Intl.NumberFormat(undefined, { notation: 'compact', maximumFractionDigits: 1 }).format(value);
}
