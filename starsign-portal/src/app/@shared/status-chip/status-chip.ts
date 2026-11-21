import { Component, computed, input } from '@angular/core';
import { statusLabel, statusTone } from './status-meta';

@Component({
  selector: 'app-status-chip',
  template: `<span class="chip" [class]="'chip--' + tone()">{{ label() }}</span>`,
})
export class StatusChip {
  readonly status = input.required<string | null | undefined>();
  protected readonly tone = computed(() => statusTone(this.status()));
  protected readonly label = computed(() => statusLabel(this.status()));
}
