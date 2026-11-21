import { NotificationChannel, NotificationEventType } from '@models/notification';

export interface EventMeta {
  label: string;
  description: string;
  /** Placeholders the API puts into the model for this event ({{name}}). */
  variables: string[];
}

const COMMON = ['recipientName', 'organisationName', 'appName'];

export const EVENT_META: Record<NotificationEventType, EventMeta> = {
  [NotificationEventType.SIGNING_INVITATION]: {
    label: 'Signing invitation',
    description: 'Sent to a signatory when a document is sent to them to sign.',
    variables: [...COMMON, 'workflowTitle', 'documentTitle', 'link', 'message', 'expiresAt', 'ownerName'],
  },
  [NotificationEventType.SIGNING_REMINDER]: {
    label: 'Signing reminder',
    description: 'Reminds a signatory who has not signed yet.',
    variables: [...COMMON, 'workflowTitle', 'link', 'expiresAt', 'reminderCount'],
  },
  [NotificationEventType.SIGNATURE_COMPLETED]: {
    label: 'Signature completed',
    description: 'Tells the workflow owner that a signatory has signed.',
    variables: [...COMMON, 'workflowTitle', 'signatoryName', 'link'],
  },
  [NotificationEventType.NEXT_SIGNATORY_ACTIVATED]: {
    label: 'Next signatory\'s turn',
    description: 'Sent in sequential workflows when it is the next signatory\'s turn.',
    variables: [...COMMON, 'workflowTitle', 'link', 'expiresAt'],
  },
  [NotificationEventType.DOCUMENT_DECLINED]: {
    label: 'Document declined',
    description: 'Tells the owner (and others) that a signatory declined.',
    variables: [...COMMON, 'workflowTitle', 'signatoryName', 'reason', 'link'],
  },
  [NotificationEventType.WORKFLOW_COMPLETED]: {
    label: 'Workflow completed',
    description: 'Everyone has signed.',
    variables: [...COMMON, 'workflowTitle', 'workflowReference', 'link'],
  },
  [NotificationEventType.WORKFLOW_CANCELLED]: {
    label: 'Workflow cancelled',
    description: 'The sender cancelled the workflow; signatories no longer need to sign.',
    variables: [...COMMON, 'workflowTitle', 'reason'],
  },
  [NotificationEventType.WORKFLOW_EXPIRING]: {
    label: 'Workflow expiring',
    description: 'The workflow expires soon.',
    variables: [...COMMON, 'workflowTitle', 'expiresAt', 'deadline', 'link'],
  },
  [NotificationEventType.WORKFLOW_EXPIRED]: {
    label: 'Workflow expired',
    description: 'The workflow expired before everyone signed.',
    variables: [...COMMON, 'workflowTitle', 'expiresAt'],
  },
  [NotificationEventType.USER_INVITATION]: {
    label: 'User invitation',
    description: 'Invites a colleague to join the organisation.',
    variables: [...COMMON, 'firstName', 'lastName', 'invitedByName', 'roles', 'link', 'acceptUrl', 'message', 'expiresAt'],
  },
  [NotificationEventType.VERIFICATION_CODE]: {
    label: 'Verification code',
    description: 'The one-time code a signatory types to prove who they are.',
    variables: [...COMMON, 'code'],
  },
  [NotificationEventType.ESCALATION]: {
    label: 'Escalation',
    description: 'Tells the owner that a signatory keeps ignoring reminders.',
    variables: [...COMMON, 'workflowTitle', 'signatoryName', 'reminderCount', 'link'],
  },
  [NotificationEventType.COMPLETED_COPY]: {
    label: 'Completed copy',
    description: 'Sends signatories their copy of the completed document.',
    variables: [...COMMON, 'workflowTitle', 'link'],
  },
  [NotificationEventType.ORGANISATION_STATUS]: {
    label: 'Organisation status',
    description: 'The organisation was activated, suspended or closed.',
    variables: [...COMMON, 'status', 'reason'],
  },
};

export const EVENT_TYPES = Object.values(NotificationEventType);

export const CHANNEL_META: Record<NotificationChannel, { label: string; icon: string }> = {
  [NotificationChannel.EMAIL]: { label: 'Email', icon: 'bi-envelope' },
  [NotificationChannel.SMS]: { label: 'SMS', icon: 'bi-phone' },
  [NotificationChannel.IN_APP]: { label: 'In-app', icon: 'bi-bell' },
};

export const CHANNELS = Object.values(NotificationChannel);

/** Sample values for previews. */
export const SAMPLE_MODEL: Record<string, string> = {
  recipientName: 'Neo Kgosi',
  organisationName: 'Acme Holdings (Pty) Ltd',
  appName: 'StarSign',
  workflowTitle: 'Service agreement 2026',
  workflowReference: 'WF-2026-0142',
  documentTitle: 'Service agreement.pdf',
  link: 'https://sign.example.co.bw/s/AbC123',
  acceptUrl: 'https://portal.example.co.bw/invitations/AbC123',
  message: 'Please sign before the end of the week.',
  expiresAt: '30 Nov 2026, 17:00',
  deadline: '30 Nov 2026',
  ownerName: 'Lesego Molefe',
  signatoryName: 'Kabo Dube',
  reason: 'The payment terms need to change.',
  reminderCount: '3',
  firstName: 'Neo',
  lastName: 'Kgosi',
  invitedByName: 'Lesego Molefe',
  roles: 'Document manager',
  code: '482913',
  status: 'ACTIVE',
};

const PLACEHOLDER = /\{\{\s*([A-Za-z0-9_.-]+)\s*\}\}/g;

function escapeHtml(text: string): string {
  return text
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;');
}

/** Same as NotificationTemplates.render on the API: {{name}} placeholders, values escaped in HTML. */
export function renderTemplate(template: string, model: Record<string, unknown>, html: boolean): string {
  return template.replace(PLACEHOLDER, (_, name: string) => {
    const value = name.split('.').reduce<unknown>(
      (current, part) => (current && typeof current === 'object' ? (current as Record<string, unknown>)[part] : undefined),
      model,
    );
    const text = value === null || value === undefined ? '' : String(value);
    return html ? escapeHtml(text) : text;
  });
}

/** Placeholder names used in a template. */
export function placeholdersOf(template: string): string[] {
  return [...new Set([...template.matchAll(PLACEHOLDER)].map((match) => match[1]))];
}

/** Default of app.novu.workflow-prefix on the API; the real prefix is server configuration. */
export const NOVU_WORKFLOW_PREFIX = 'starsign-';

/**
 * The Novu workflow StarSign triggers for an event: the prefix plus the event type in kebab case,
 * e.g. SIGNING_INVITATION → starsign-signing-invitation (with the default prefix).
 */
export function novuWorkflowId(eventType: NotificationEventType | null | undefined): string | null {
  return eventType ? NOVU_WORKFLOW_PREFIX + eventType.toLowerCase().replaceAll('_', '-') : null;
}

/** Payload fields StarSign sends to the Novu workflow, per channel. */
export const NOVU_PAYLOAD: Partial<Record<NotificationChannel, string[]>> = {
  [NotificationChannel.EMAIL]: ['subject', 'body', 'text', 'from', 'eventType', 'notificationId', 'recipientName', 'link', 'replyTo', 'senderName'],
  [NotificationChannel.SMS]: ['body', 'senderId'],
};
