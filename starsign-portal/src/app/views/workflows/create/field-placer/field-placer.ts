import {
  Component,
  computed,
  DestroyRef,
  effect,
  inject,
  input,
  model,
  signal,
  untracked,
} from '@angular/core';
import { DocumentPageDTO } from '@models/document';
import { FieldType } from '@models/workflow';
import { DocumentApi } from '@services/document/document-api';
import { apiErrorMessage } from '@store/app-state';
import { firstValueFrom } from 'rxjs';
import {
  FIELD_KINDS,
  fieldKind,
  FieldKind,
  PlacedField,
  PlacerRecipient,
  viewedSize,
} from './placed-field';

const DRAG_TYPE = 'application/x-starsign-field';
const MIN_SIZE = 0.01;

interface Gesture {
  key: string;
  mode: 'move' | 'resize';
  pointerId: number;
  startX: number;
  startY: number;
  rect: DOMRect;
  origin: PlacedField;
}

/**
 * Lays the document's pages out and lets the sender drop signature and data fields
 * onto them for each recipient, like a desktop e-signature tool: drag a field from the
 * palette (or pick it and click the page), drag to move, pull the corner to resize,
 * Delete to remove, arrow keys to nudge.
 */
@Component({
  selector: 'app-field-placer',
  templateUrl: './field-placer.html',
  styles: `
    .placer-page {
      position: relative;
      background: #fff;
      box-shadow: 0 8px 24px rgb(0 0 0 / 35%);
      user-select: none;
      touch-action: none;
    }
    .placer-page img {
      display: block;
      width: 100%;
      height: auto;
      pointer-events: none;
    }
    .placer-page--armed {
      cursor: crosshair;
    }
    .placed {
      position: absolute;
      box-sizing: border-box;
      border: 2px solid var(--field-colour);
      background: color-mix(in srgb, var(--field-colour) 14%, transparent);
      color: var(--field-colour);
      border-radius: 4px;
      cursor: move;
      overflow: hidden;
      display: flex;
      align-items: center;
      gap: 0.25rem;
      padding: 0 0.3rem;
      font: 600 11px/1.1 var(--font-body, system-ui);
      white-space: nowrap;
    }
    .placed:focus-visible,
    .placed--selected {
      outline: 2px solid var(--field-colour);
      outline-offset: 2px;
      background: color-mix(in srgb, var(--field-colour) 24%, transparent);
    }
    .placed__handle {
      position: absolute;
      right: -1px;
      bottom: -1px;
      width: 10px;
      height: 10px;
      background: var(--field-colour);
      cursor: nwse-resize;
    }
    .swatch {
      width: 0.75rem;
      height: 0.75rem;
      border-radius: 999px;
      flex: none;
    }
  `,
})
export class FieldPlacer {
  readonly documentId = input.required<string>();
  readonly recipients = input.required<PlacerRecipient[]>();
  readonly fields = model<PlacedField[]>([]);

  private readonly documentApi = inject(DocumentApi);

  protected readonly kinds = FIELD_KINDS;
  protected readonly FieldType = FieldType;
  protected readonly pages = signal<DocumentPageDTO[]>([]);
  protected readonly images = signal<Record<number, string>>({});
  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);

  /** Recipient new fields are assigned to. */
  protected readonly activeRecipientKey = signal<string | null>(null);
  /** Field type picked in the palette, placed on the next click on a page. */
  protected readonly armed = signal<FieldType | null>(null);
  protected readonly selectedKey = signal<string | null>(null);

  protected readonly activeRecipient = computed(() => {
    const list = this.recipients();
    return list.find((r) => r.key === this.activeRecipientKey()) ?? list[0] ?? null;
  });
  protected readonly selected = computed(
    () => this.fields().find((field) => field.key === this.selectedKey()) ?? null,
  );
  protected readonly colours = computed(() => {
    const map = new Map<string, PlacerRecipient>();
    this.recipients().forEach((r) => map.set(r.key, r));
    return map;
  });
  /** Per recipient: how many fields, and whether they have somewhere to sign. */
  protected readonly coverage = computed(() =>
    this.recipients().map((recipient) => {
      const mine = this.fields().filter((f) => f.recipientKey === recipient.key);
      return {
        recipient,
        count: mine.length,
        signs: mine.some((f) => f.type === FieldType.SIGNATURE),
      };
    }),
  );

  private gesture: Gesture | null = null;
  private objectUrls: string[] = [];

  constructor() {
    inject(DestroyRef).onDestroy(() => this.revokeImages());

    effect(() => {
      const documentId = this.documentId();
      untracked(() => void this.loadPages(documentId));
    });

    // Fields of a removed recipient go with them; keep the active recipient valid.
    effect(() => {
      const keys = new Set(this.recipients().map((r) => r.key));
      untracked(() => {
        if (this.fields().some((f) => !keys.has(f.recipientKey))) {
          this.fields.update((list) => list.filter((f) => keys.has(f.recipientKey)));
        }
        if (!this.activeRecipientKey() || !keys.has(this.activeRecipientKey()!)) {
          this.activeRecipientKey.set(this.recipients()[0]?.key ?? null);
        }
      });
    });
  }

  protected colourOf(recipientKey: string): string {
    return this.colours().get(recipientKey)?.color ?? '#64748b';
  }

  protected nameOf(recipientKey: string): string {
    return this.colours().get(recipientKey)?.name || 'Recipient';
  }

  protected kindOf(type: FieldType): FieldKind {
    return fieldKind(type);
  }

  protected fieldsOn(pageNumber: number): PlacedField[] {
    return this.fields().filter((field) => field.page === pageNumber);
  }

  protected arm(type: FieldType): void {
    this.armed.update((current) => (current === type ? null : type));
  }

  protected onDragStart(event: DragEvent, type: FieldType): void {
    event.dataTransfer?.setData(DRAG_TYPE, type);
    event.dataTransfer?.setData('text/plain', type);
    if (event.dataTransfer) {
      event.dataTransfer.effectAllowed = 'copy';
    }
  }

  protected onDragOver(event: DragEvent): void {
    if (event.dataTransfer?.types.includes(DRAG_TYPE)) {
      event.preventDefault();
      event.dataTransfer.dropEffect = 'copy';
    }
  }

  protected onDrop(event: DragEvent, page: DocumentPageDTO): void {
    const type = event.dataTransfer?.getData(DRAG_TYPE) as FieldType | undefined;
    if (!type) {
      return;
    }
    event.preventDefault();
    this.place(type, page, event.currentTarget as HTMLElement, event.clientX, event.clientY);
  }

  protected onPageClick(event: MouseEvent, page: DocumentPageDTO): void {
    const type = this.armed();
    if (type) {
      this.place(type, page, event.currentTarget as HTMLElement, event.clientX, event.clientY);
      this.armed.set(null);
    } else {
      this.selectedKey.set(null);
    }
  }

  protected startGesture(event: PointerEvent, field: PlacedField, mode: 'move' | 'resize'): void {
    event.stopPropagation();
    event.preventDefault();
    const pageElement = (event.currentTarget as HTMLElement).closest('.placer-page') as HTMLElement;
    (event.currentTarget as HTMLElement).setPointerCapture(event.pointerId);
    this.selectedKey.set(field.key);
    this.gesture = {
      key: field.key,
      mode,
      pointerId: event.pointerId,
      startX: event.clientX,
      startY: event.clientY,
      rect: pageElement.getBoundingClientRect(),
      origin: { ...field },
    };
  }

  protected moveGesture(event: PointerEvent): void {
    const g = this.gesture;
    if (!g || g.pointerId !== event.pointerId) {
      return;
    }
    const dx = (event.clientX - g.startX) / g.rect.width;
    const dy = (event.clientY - g.startY) / g.rect.height;
    const o = g.origin;
    if (g.mode === 'move') {
      this.patch(g.key, {
        x: clamp(o.x + dx, 0, 1 - o.w),
        y: clamp(o.y + dy, 0, 1 - o.h),
      });
    } else {
      this.patch(g.key, {
        w: clamp(o.w + dx, MIN_SIZE, 1 - o.x),
        h: clamp(o.h + dy, MIN_SIZE, 1 - o.y),
      });
    }
  }

  protected endGesture(event: PointerEvent): void {
    if (this.gesture?.pointerId === event.pointerId) {
      this.gesture = null;
    }
  }

  protected onFieldKey(event: KeyboardEvent, field: PlacedField): void {
    const step = event.shiftKey ? 0.02 : 0.004;
    const moves: Record<string, [number, number]> = {
      ArrowLeft: [-step, 0],
      ArrowRight: [step, 0],
      ArrowUp: [0, -step],
      ArrowDown: [0, step],
    };
    if (event.key === 'Delete' || event.key === 'Backspace') {
      event.preventDefault();
      this.remove(field.key);
    } else if (moves[event.key]) {
      event.preventDefault();
      const [dx, dy] = moves[event.key];
      this.patch(field.key, {
        x: clamp(field.x + dx, 0, 1 - field.w),
        y: clamp(field.y + dy, 0, 1 - field.h),
      });
    }
  }

  protected select(field: PlacedField, event: Event): void {
    event.stopPropagation();
    this.selectedKey.set(field.key);
  }

  protected remove(key: string): void {
    this.fields.update((list) => list.filter((field) => field.key !== key));
    if (this.selectedKey() === key) {
      this.selectedKey.set(null);
    }
  }

  protected assign(key: string, recipientKey: string): void {
    this.patch(key, { recipientKey });
  }

  protected setRequired(key: string, required: boolean): void {
    this.patch(key, { required });
  }

  protected setLabel(key: string, label: string): void {
    this.patch(key, { label });
  }

  protected duplicateToAllPages(field: PlacedField): void {
    const copies = this.pages()
      .map((p) => p.pageNumber ?? 0)
      .filter((n) => n > 0 && n !== field.page)
      .map((page) => ({ ...field, key: crypto.randomUUID(), page }));
    this.fields.update((list) => [...list, ...copies]);
  }

  private place(type: FieldType, page: DocumentPageDTO, pageElement: HTMLElement, clientX: number, clientY: number): void {
    const recipient = this.activeRecipient();
    if (!recipient || !page.pageNumber) {
      return;
    }
    const rect = pageElement.getBoundingClientRect();
    const size = viewedSize(page);
    const kind = fieldKind(type);
    const w = Math.min(kind.width / size.width, 1);
    const h = Math.min(kind.height / size.height, 1);
    // Centre the field on the pointer, kept inside the page.
    const x = clamp((clientX - rect.left) / rect.width - w / 2, 0, 1 - w);
    const y = clamp((clientY - rect.top) / rect.height - h / 2, 0, 1 - h);
    const field: PlacedField = {
      key: crypto.randomUUID(),
      recipientKey: recipient.key,
      type,
      page: page.pageNumber,
      x,
      y,
      w,
      h,
      required: true,
      label: '',
    };
    this.fields.update((list) => [...list, field]);
    this.selectedKey.set(field.key);
  }

  private patch(key: string, changes: Partial<PlacedField>): void {
    this.fields.update((list) => list.map((f) => (f.key === key ? { ...f, ...changes } : f)));
  }

  private async loadPages(documentId: string): Promise<void> {
    this.revokeImages();
    this.pages.set([]);
    this.error.set(null);
    if (!documentId) {
      return;
    }
    this.loading.set(true);
    try {
      const pages = (await firstValueFrom(this.documentApi.getPages(documentId)))
        .filter((p) => !!p.pageNumber)
        .sort((a, b) => a.pageNumber! - b.pageNumber!);
      if (documentId !== this.documentId()) {
        return;
      }
      this.pages.set(pages);
      // Fetch the page images one after another so a long document doesn't flood the API.
      for (const page of pages) {
        const blob = await firstValueFrom(this.documentApi.getPreview(documentId, page.pageNumber!));
        if (documentId !== this.documentId()) {
          return;
        }
        const url = URL.createObjectURL(blob);
        this.objectUrls.push(url);
        this.images.update((map) => ({ ...map, [page.pageNumber!]: url }));
      }
    } catch (error) {
      this.error.set(apiErrorMessage(error, 'The document pages could not be loaded.'));
    } finally {
      this.loading.set(false);
    }
  }

  private revokeImages(): void {
    this.objectUrls.forEach((url) => URL.revokeObjectURL(url));
    this.objectUrls = [];
    this.images.set({});
  }
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(Math.max(value, min), Math.max(min, max));
}
