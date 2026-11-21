import { Component, computed, input, model } from '@angular/core';
import { PermissionDTO } from '@models/identity';
import { compareActions, groupPermissions, humanise, PermissionGroup } from '../permission-groups';

/**
 * Permissions as a matrix: one row per resource, one column per action. Editable mode
 * shows checkboxes bound to `selected` (permission codes), with a toggle per resource.
 */
@Component({
  selector: 'app-permission-matrix',
  templateUrl: './permission-matrix.html',
})
export class PermissionMatrix {
  /** The permissions to show (the catalogue, or a role's own permissions when read-only). */
  readonly permissions = input.required<readonly PermissionDTO[]>();
  readonly selected = model<string[]>([]);
  readonly editable = input(false);
  readonly label = input('Permissions');

  protected readonly groups = computed(() => groupPermissions(this.permissions()));
  protected readonly actions = computed(() =>
    [...new Set(this.groups().flatMap((group) => [...group.actions.keys()]))].sort(compareActions),
  );
  private readonly selectedSet = computed(() => new Set(this.selected()));

  protected readonly humanise = humanise;

  protected isSelected(code: string | null | undefined): boolean {
    return !!code && this.selectedSet().has(code);
  }

  protected toggle(code: string | null | undefined, chosen: boolean): void {
    if (!code) {
      return;
    }
    this.selected.update((codes) =>
      chosen ? [...new Set([...codes, code])] : codes.filter((existing) => existing !== code),
    );
  }

  protected groupState(group: PermissionGroup): 'all' | 'some' | 'none' {
    const codes = this.codes(group);
    const count = codes.filter((code) => this.selectedSet().has(code)).length;
    return count === 0 ? 'none' : count === codes.length ? 'all' : 'some';
  }

  protected toggleGroup(group: PermissionGroup, chosen: boolean): void {
    const codes = this.codes(group);
    this.selected.update((current) =>
      chosen
        ? [...new Set([...current, ...codes])]
        : current.filter((code) => !codes.includes(code)),
    );
  }

  private codes(group: PermissionGroup): string[] {
    return [...group.actions.values()].map((permission) => permission.code!);
  }
}
