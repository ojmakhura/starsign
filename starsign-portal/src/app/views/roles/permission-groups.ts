import { PermissionDTO } from '@models/identity';

/** One resource ("documents") and its permissions keyed by action ("view", "list-own"). */
export interface PermissionGroup {
  resource: string;
  label: string;
  actions: Map<string, PermissionDTO>;
}

/** Common actions first, the rest alphabetically. */
const ACTION_ORDER = ['view', 'list', 'edit', 'delete', 'manage'];

/** "documents:view-own" → ["documents", "view-own"]; codes without ":" fall back to the module. */
export function splitCode(code: string, module?: string | null): [string, string] {
  const separator = code.indexOf(':');
  return separator > 0
    ? [code.slice(0, separator), code.slice(separator + 1)]
    : [module || 'other', code];
}

export function humanise(value: string): string {
  const words = value.replace(/[-_]+/g, ' ').trim();
  return words.charAt(0).toUpperCase() + words.slice(1);
}

export function compareActions(a: string, b: string): number {
  const base = (action: string) => action.replace(/-own$/, '');
  const rank = (action: string) => {
    const index = ACTION_ORDER.indexOf(base(action));
    return index < 0 ? ACTION_ORDER.length : index;
  };
  return rank(a) - rank(b) || base(a).localeCompare(base(b)) || a.localeCompare(b);
}

/** Groups permissions by resource, resources and actions sorted. */
export function groupPermissions(permissions: readonly PermissionDTO[]): PermissionGroup[] {
  const groups = new Map<string, PermissionGroup>();
  for (const permission of permissions) {
    if (!permission.code) {
      continue;
    }
    const [resource, action] = splitCode(permission.code, permission.module);
    let group = groups.get(resource);
    if (!group) {
      group = { resource, label: humanise(resource), actions: new Map() };
      groups.set(resource, group);
    }
    group.actions.set(action, permission);
  }
  for (const group of groups.values()) {
    group.actions = new Map([...group.actions].sort(([a], [b]) => compareActions(a, b)));
  }
  return [...groups.values()].sort((a, b) => a.label.localeCompare(b.label));
}

/** Groups plain permission codes (e.g. a user's effective permissions) by resource. */
export function groupCodes(codes: readonly string[]): { resource: string; label: string; actions: string[] }[] {
  return groupPermissions(codes.map((code) => ({ code }))).map((group) => ({
    resource: group.resource,
    label: group.label,
    actions: [...group.actions.keys()],
  }));
}
