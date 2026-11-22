import { DatePipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { FormField, form } from '@angular/forms/signals';
import { RouterLink } from '@angular/router';
import { InternalSigningApiStore } from '@store/signing/internal-signing-api.store';

type InboxSort = 'deadline' | 'requested';

@Component({
  selector: 'app-signing-inbox',
  imports: [DatePipe, FormField, RouterLink],
  template: `
    <div class="page">
      <header class="page-header">
        <div>
          <nav class="breadcrumb" aria-label="Breadcrumb">
            <a routerLink="/">Command</a><i class="bi bi-chevron-right" aria-hidden="true"></i
            ><span class="breadcrumb__current">Signing inbox</span>
          </nav>
          <h1 class="page-title">Signing inbox</h1>
          <p class="page-subtitle">Documents routed to you for signature, in signing order.</p>
        </div>
        <button
          type="button"
          class="btn btn--secondary"
          (click)="store.getPendingForCurrentUser()"
          [disabled]="store.loading()"
        >
          <i class="bi bi-arrow-clockwise" aria-hidden="true"></i> Refresh
        </button>
      </header>

      <form class="glass flex flex-wrap items-end gap-4 p-4" (submit)="$event.preventDefault()">
        <div class="field min-w-60 flex-1">
          <label for="inbox-query">Search</label>
          <input
            id="inbox-query"
            class="field-control"
            type="search"
            placeholder="Title, document or sender"
            [formField]="filterForm.query"
          />
        </div>
        <div class="field">
          <span class="field__label">Sort by</span>
          <div class="segmented" role="radiogroup" aria-label="Sort by">
            <label
              ><input type="radio" value="deadline" [formField]="filterForm.sort" />Deadline</label
            >
            <label
              ><input
                type="radio"
                value="requested"
                [formField]="filterForm.sort"
              />Requested</label
            >
          </div>
        </div>
        <label class="check pb-2"
          ><input type="checkbox" [formField]="filterForm.dueSoonOnly" /> Due within 48h</label
        >
      </form>

      @if (store.error()) {
        <div class="alert alert--danger" role="alert">{{ store.messages()[0] }}</div>
      }

      <section class="flex flex-col gap-3" aria-label="Pending signatures">
        @for (item of visible(); track item.signatoryId) {
          <article class="card flex flex-wrap items-center gap-5">
            <span
              class="grid h-12 w-12 place-items-center rounded-full border border-hairline-cyan bg-cyan/10 text-xl text-cyan"
              ><i class="bi bi-pen" aria-hidden="true"></i
            ></span>
            <div class="min-w-0 flex-1">
              <h2 class="m-0 truncate font-display text-headline-sm">{{ item.workflowTitle }}</h2>
              <p class="m-0 text-ink-muted">
                {{ item.documentTitle }} · from {{ item.requestedBy || 'unknown sender' }}
              </p>
              <p class="m-0 mt-1 mono text-ink-muted">
                REQUESTED {{ item.requestedAt | date: 'dd-MM-yyyy HH:mm' }} ·
                {{ item.fieldCount ?? 0 }} FIELDS
              </p>
            </div>
            @if (item.deadline) {
              <span
                class="chip"
                [class]="isDueSoon(item.deadline) ? 'chip--voided' : 'chip--pending'"
                >Due {{ item.deadline | date: 'dd-MM-yyyy HH:mm' }}</span
              >
            }
            <a
              class="btn btn--primary"
              [routerLink]="[
                '/signing',
                'workflows',
                item.workflowId,
                'signatories',
                item.signatoryId,
              ]"
              >Review &amp; sign <i class="bi bi-arrow-right" aria-hidden="true"></i
            ></a>
          </article>
        } @empty {
          <div class="card empty-state">
            <i class="bi bi-inbox" aria-hidden="true"></i>
            <span>{{
              store.loading()
                ? 'Checking your inbox…'
                : store.dataList().length
                  ? 'No requests match these filters.'
                  : 'You are all caught up.'
            }}</span>
          </div>
        }
      </section>
    </div>
  `,
})
export class SigningInbox {
  protected readonly store = inject(InternalSigningApiStore);

  private readonly filter = signal({
    query: '',
    sort: 'deadline' as InboxSort,
    dueSoonOnly: false,
  });
  protected readonly filterForm = form(this.filter);

  /** Pure derivation from the store and the filter form — no subscriptions. */
  protected readonly visible = computed(() => {
    const { query, sort, dueSoonOnly } = this.filter();
    const term = query.trim().toLowerCase();
    const dueSoon = new Set(this.store.dueSoon());
    const time = (value?: string | null) =>
      value ? new Date(value).getTime() : Number.MAX_SAFE_INTEGER;
    return this.store
      .dataList()
      .filter((item) => !dueSoonOnly || dueSoon.has(item))
      .filter(
        (item) =>
          !term ||
          [item.workflowTitle, item.documentTitle, item.requestedBy].some((value) =>
            value?.toLowerCase().includes(term),
          ),
      )
      .toSorted((a, b) =>
        sort === 'deadline'
          ? time(a.deadline) - time(b.deadline)
          : time(b.requestedAt) - time(a.requestedAt),
      );
  });

  constructor() {
    this.store.getPendingForCurrentUser();
  }

  protected isDueSoon(deadline: string): boolean {
    return new Date(deadline).getTime() - Date.now() < 48 * 3600_000;
  }
}
