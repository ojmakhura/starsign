import { PAGE_PERMISSIONS, PermissionRequirement } from '@auth/page-permissions';

export interface NavItem {
  label: string;
  icon: string;
  link: string;
  exact?: boolean;
  permissions: PermissionRequirement;
  /** Shows the pending-signature count beside the item. */
  badge?: 'pending';
}

export interface NavGroup {
  label: string;
  items: NavItem[];
}

export const NAVIGATION: NavGroup[] = [
  {
    label: 'Command',
    items: [
      { label: 'Dashboard', icon: 'bi-grid-1x2', link: '/', exact: true, permissions: PAGE_PERMISSIONS.dashboard },
      { label: 'Signing Inbox', icon: 'bi-pen', link: '/signing', permissions: PAGE_PERMISSIONS.signing, badge: 'pending' },
    ],
  },
  {
    label: 'Documents',
    items: [
      { label: 'Document Vault', icon: 'bi-file-earmark-lock2', link: '/documents', exact: true, permissions: PAGE_PERMISSIONS.documents },
      { label: 'Upload PDF', icon: 'bi-cloud-arrow-up', link: '/documents/upload', permissions: PAGE_PERMISSIONS.documentUpload },
    ],
  },
  {
    label: 'Workflows',
    items: [
      { label: 'Signing Workflows', icon: 'bi-diagram-3', link: '/workflows', exact: true, permissions: PAGE_PERMISSIONS.workflows },
      { label: 'New Workflow', icon: 'bi-send-plus', link: '/workflows/new', permissions: PAGE_PERMISSIONS.workflowCreate },
      { label: 'Templates', icon: 'bi-files', link: '/templates', permissions: PAGE_PERMISSIONS.templates },
    ],
  },
  {
    label: 'Organisation',
    items: [
      { label: 'Users', icon: 'bi-people', link: '/users', exact: true, permissions: PAGE_PERMISSIONS.users },
      { label: 'Invite User', icon: 'bi-person-plus', link: '/users/invite', permissions: PAGE_PERMISSIONS.userInvite },
      { label: 'Roles', icon: 'bi-person-lock', link: '/roles', permissions: PAGE_PERMISSIONS.roles },
      { label: 'Settings', icon: 'bi-building-gear', link: '/organisation', permissions: PAGE_PERMISSIONS.organisation },
      { label: 'Email Templates', icon: 'bi-envelope-paper', link: '/notification-templates', permissions: PAGE_PERMISSIONS.notificationTemplates },
      { label: 'Subscription', icon: 'bi-credit-card', link: '/subscription', permissions: PAGE_PERMISSIONS.subscription },
    ],
  },
  {
    label: 'Insight',
    items: [
      { label: 'Reports', icon: 'bi-bar-chart-line', link: '/reports', permissions: PAGE_PERMISSIONS.reports },
      { label: 'Audit Trail', icon: 'bi-journal-check', link: '/audit', permissions: PAGE_PERMISSIONS.audit },
    ],
  },
  {
    label: 'Trust',
    items: [
      { label: 'Verify Document', icon: 'bi-patch-check', link: '/verify', permissions: PAGE_PERMISSIONS.verify },
    ],
  },
  {
    label: 'Platform',
    items: [
      { label: 'Organisations', icon: 'bi-buildings', link: '/platform/organisations', permissions: PAGE_PERMISSIONS.platformOrganisations },
      { label: 'Statistics', icon: 'bi-graph-up', link: '/platform/statistics', permissions: PAGE_PERMISSIONS.platformStatistics },
      { label: 'Plans', icon: 'bi-tags', link: '/platform/plans', permissions: PAGE_PERMISSIONS.platformPlans },
      { label: 'Configuration', icon: 'bi-sliders', link: '/platform/configuration', permissions: PAGE_PERMISSIONS.platformConfiguration },
      { label: 'Notification Providers', icon: 'bi-broadcast', link: '/platform/notification-providers', permissions: PAGE_PERMISSIONS.platformProviders },
      { label: 'Storage', icon: 'bi-hdd-stack', link: '/platform/storage', permissions: PAGE_PERMISSIONS.platformStorage },
      { label: 'Platform Branding', icon: 'bi-palette', link: '/platform/branding', permissions: PAGE_PERMISSIONS.platformBranding },
    ],
  },
];
