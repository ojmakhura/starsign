import { DecimalPipe } from '@angular/common';
import { Component, computed, input } from '@angular/core';

export interface ChartSeries {
  name: string;
  /** A CSS colour, normally a design token such as `var(--color-cyan)`. */
  color: string;
  values: readonly number[];
}

const WIDTH = 640;
const HEIGHT = 220;
const PAD = { top: 12, right: 12, bottom: 30, left: 44 };
const PLOT_W = WIDTH - PAD.left - PAD.right;
const PLOT_H = HEIGHT - PAD.top - PAD.bottom;
const MAX_X_LABELS = 8;

/** A 1-2-5 rounded maximum so the gridlines land on readable numbers. */
function niceMax(value: number): number {
  if (value <= 0) {
    return 1;
  }
  const magnitude = 10 ** Math.floor(Math.log10(value));
  const step = [1, 2, 2.5, 5, 10].find((s) => s * magnitude >= value) ?? 10;
  return step * magnitude;
}

/**
 * A small inline-SVG bar or line chart (no chart library). The chart is decorative for
 * screen readers beyond its summary label: every page that uses it also shows the numbers
 * in a table. Each mark carries a `<title>` so hovering shows its exact value.
 */
@Component({
  selector: 'app-trend-chart',
  imports: [DecimalPipe],
  template: `
    <figure class="m-0 flex flex-col gap-2">
      @if (series().length > 1) {
        <ul class="legend" aria-hidden="true">
          @for (s of series(); track s.name) {
            <li><span class="legend__swatch" [style.background]="s.color"></span>{{ s.name }}</li>
          }
        </ul>
      }
      <svg
        [attr.viewBox]="'0 0 ' + width + ' ' + height"
        class="chart"
        role="img"
        [attr.aria-label]="label()"
      >
        @for (tick of ticks(); track tick.value) {
          <line class="chart__grid" [attr.x1]="padLeft" [attr.x2]="width - padRight" [attr.y1]="tick.y" [attr.y2]="tick.y" />
          <text class="chart__axis" [attr.x]="padLeft - 6" [attr.y]="tick.y + 3" text-anchor="end">
            {{ tick.value | number: '1.0-1' }}
          </text>
        }
        @if (kind() === 'bar') {
          @for (bar of bars(); track bar.key) {
            <rect
              [attr.x]="bar.x"
              [attr.y]="bar.y"
              [attr.width]="bar.w"
              [attr.height]="bar.h"
              rx="2"
              [attr.fill]="bar.color"
            >
              <title>{{ bar.title }}</title>
            </rect>
          }
        } @else {
          @for (line of lines(); track line.name) {
            <polyline class="chart__line" [attr.points]="line.points" [attr.stroke]="line.color" />
            @for (dot of line.dots; track $index) {
              <circle [attr.cx]="dot.x" [attr.cy]="dot.y" r="4" [attr.fill]="line.color">
                <title>{{ dot.title }}</title>
              </circle>
            }
          }
        }
        @for (x of xLabels(); track x.index) {
          <text class="chart__axis" [attr.x]="x.x" [attr.y]="height - 10" text-anchor="middle">{{ x.text }}</text>
        }
      </svg>
      @if (empty()) {
        <figcaption class="text-ink-muted text-center">No data for this period.</figcaption>
      }
    </figure>
  `,
  styles: `
    .chart {
      width: 100%;
      height: auto;
      max-height: 260px;
      overflow: visible;
    }
    .chart__grid {
      stroke: var(--color-hairline);
      stroke-width: 1;
    }
    .chart__axis {
      fill: var(--color-ink-muted);
      font-family: var(--font-mono);
      font-size: 10px;
    }
    .chart__line {
      fill: none;
      stroke-width: 2;
      stroke-linejoin: round;
    }
    .legend {
      display: flex;
      flex-wrap: wrap;
      gap: 1rem;
      margin: 0;
      padding: 0;
      list-style: none;
      font-size: var(--text-body-sm);
      color: var(--color-ink-muted);
    }
    .legend li {
      display: inline-flex;
      align-items: center;
      gap: 0.375rem;
    }
    .legend__swatch {
      width: 10px;
      height: 10px;
      border-radius: 2px;
    }
  `,
})
export class TrendChart {
  readonly labels = input.required<readonly string[]>();
  readonly series = input.required<readonly ChartSeries[]>();
  readonly kind = input<'bar' | 'line'>('bar');
  /** Accessible summary, e.g. "Documents created and signed per day". */
  readonly label = input.required<string>();

  protected readonly width = WIDTH;
  protected readonly height = HEIGHT;
  protected readonly padLeft = PAD.left;
  protected readonly padRight = PAD.right;

  protected readonly empty = computed(
    () => !this.labels().length || this.series().every((s) => s.values.every((v) => !v)),
  );

  private readonly max = computed(() =>
    niceMax(Math.max(0, ...this.series().flatMap((s) => s.values.map((v) => v ?? 0)))),
  );

  private y(value: number): number {
    return PAD.top + PLOT_H - (Math.max(0, value) / this.max()) * PLOT_H;
  }

  protected readonly ticks = computed(() =>
    [0, 0.25, 0.5, 0.75, 1].map((f) => ({ value: this.max() * f, y: this.y(this.max() * f) })),
  );

  private readonly slot = computed(() => PLOT_W / Math.max(1, this.labels().length));

  protected readonly bars = computed(() => {
    const series = this.series();
    const slot = this.slot();
    const group = slot * 0.8;
    const gap = series.length > 1 ? 2 : 0;
    const w = Math.max(1, (group - gap * (series.length - 1)) / Math.max(1, series.length));
    return this.labels().flatMap((label, i) =>
      series.map((s, j) => {
        const value = s.values[i] ?? 0;
        const y = this.y(value);
        return {
          key: `${i}-${j}`,
          x: PAD.left + i * slot + (slot - group) / 2 + j * (w + gap),
          y,
          w,
          h: Math.max(0, PAD.top + PLOT_H - y),
          color: s.color,
          title: `${label} · ${s.name}: ${value}`,
        };
      }),
    );
  });

  protected readonly lines = computed(() => {
    const slot = this.slot();
    return this.series().map((s) => {
      const dots = this.labels().map((label, i) => ({
        x: PAD.left + i * slot + slot / 2,
        y: this.y(s.values[i] ?? 0),
        title: `${label} · ${s.name}: ${s.values[i] ?? 0}`,
      }));
      return {
        name: s.name,
        color: s.color,
        dots,
        points: dots.map((d) => `${d.x},${d.y}`).join(' '),
      };
    });
  });

  protected readonly xLabels = computed(() => {
    const labels = this.labels();
    const every = Math.max(1, Math.ceil(labels.length / MAX_X_LABELS));
    const slot = this.slot();
    return labels
      .map((text, index) => ({
        index,
        text: text.length > 12 ? `${text.slice(0, 11)}…` : text,
        x: PAD.left + index * slot + slot / 2,
      }))
      .filter((l) => l.index % every === 0);
  });
}
