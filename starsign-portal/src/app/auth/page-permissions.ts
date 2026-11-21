/**
 * Permission scopes (from SessionStore.permissions, e.g. "documents:view") that unlock
 * each page. A user needs any one of a page's entries; an entry that is itself a list
 * needs all of its scopes. An empty list means every signed-in portal user.
 *
 * Owner-scoped roles (ORGANISATION_USER, SIGNING_OFFICER) hold "<scope>-own" on
 * documents and workflows; that satisfies the plain scope here, since the API limits
 * them to their own records. See keycloak/ROLE_MATRIX.md.
 */
export type PermissionRequirement = readonly (string | readonly string[])[];

export const PAGE_PERMISSIONS = {
  dashboard: [],
  verify: [],
  signing: ['signing:sign'],
  documents: ['documents:list'],
  documentUpload: ['documents:edit'],
  documentDelete: ['documents:delete'],
  workflows: ['workflows:list'],
  workflowCreate: ['workflows:edit'],
  workflowInitiate: ['workflows:initiate'],
  workflowCancel: ['workflows:cancel'],
  reports: ['reports:view'],
  users: ['users:list'],
  userInvite: ['invitations:manage'],
  roles: ['roles:list'],
  organisation: ['organisations:view'],
  branding: ['branding:configure'],
  notificationTemplates: ['notification-templates:list'],
  templates: ['templates:list'],
  audit: ['audit:list'],
  subscription: ['subscriptions:view'],
  notifications: [],
  profile: [],
  platform: [
    'organisations:administer',
    'admin-configuration:list',
    'admin-notification-providers:list',
    'admin-storage:list',
    'branding:administer',
    'reports:administer',
    'subscriptions:administer',
  ],
  platformOrganisations: ['organisations:administer'],
  platformConfiguration: ['admin-configuration:list'],
  platformProviders: ['admin-notification-providers:list'],
  platformStorage: ['admin-storage:list'],
  platformBranding: ['branding:administer'],
  platformPlans: ['subscriptions:administer'],
  platformStatistics: ['reports:administer'],
} satisfies Record<string, PermissionRequirement>;

const holds = (granted: readonly string[], permission: string) =>
  granted.includes(permission) || granted.includes(`${permission}-own`);

/** Whether `granted` satisfies any entry of `required` (or nothing is required). */
export function hasPermission(
  required: PermissionRequirement | null | undefined,
  granted: readonly string[],
): boolean {
  return (
    !required?.length ||
    required.some((entry) =>
      typeof entry === 'string'
        ? holds(granted, entry)
        : entry.every((permission) => holds(granted, permission)),
    )
  );
}
