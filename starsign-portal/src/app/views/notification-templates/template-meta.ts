import { NotificationChannel, NotificationEventType } from '@models/notification';

export interface EventMeta {
  label: string;
  description: string;
  /** Placeholders the API puts into the model for this event ({{name}}). */
  variables: string[];
}

const COMMON = ['recipientName', 'organisationName', 'appName', 'emailLogoUrl'];

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
  // The API sends the uploaded e-mail logo, else the StarSign one the portal serves
  emailLogoUrl: `${globalThis.location?.origin ?? ''}/brand/email-logo.png`,
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

/** True when an e-mail body is a whole HTML document, which the API sends without its layout. */
export function isEmailDocument(html: string): boolean {
  return /<\s*(html|body)[\s>]/i.test(html);
}

/**
 * The default e-mail layout around a rendered body, for previews: the same markup as the API's
 * EmailLayout.wrap (starsign-api core, bw.co.starsign.notification.EmailLayout). Change both together.
 */
export function emailLayout(body: string, subject: string, model: Record<string, unknown>): string {
  if (isEmailDocument(body)) {
    return body;
  }
  const text = (key: string) => (typeof model[key] === 'string' ? (model[key] as string).trim() : '');
  const appName = text('appName') || 'StarSign';
  const organisation = text('organisationName');
  const logo = text('emailLogoUrl');
  const footer = text('emailFooter');
  const supportEmail = text('supportEmail');
  const supportUrl = text('supportUrl');
  const isDefaultLogo = logo.endsWith('/brand/email-logo.png');
  const link = 'color:#0273b1;text-decoration:underline;';

  const logoHtml = /^https?:\/\//i.test(logo)
    ? isDefaultLogo
      ? `<img src="${escapeHtml(logo)}" width="200" height="56" alt="${escapeHtml(appName)}" style="display:block;width:200px;height:56px;border:0;outline:none;">`
      : `<img src="${escapeHtml(logo)}" height="56" alt="${escapeHtml(appName)}" style="display:block;height:56px;width:auto;max-width:280px;border:0;outline:none;">`
    : `<span style="font:700 22px/28px Arial,Helvetica,sans-serif;color:#0f172a;">${escapeHtml(appName)}</span>`;

  const support = [
    supportEmail
      ? `Questions? <a href="mailto:${escapeHtml(supportEmail)}" style="${link}">${escapeHtml(supportEmail)}</a>`
      : '',
    /^https?:\/\//i.test(supportUrl) ? `<a href="${escapeHtml(supportUrl)}" style="${link}">Help centre</a>` : '',
  ]
    .filter(Boolean)
    .join(' &middot; ');

  return (
    '<!doctype html>\n<html lang="en"><head><meta charset="utf-8">' +
    '<meta name="viewport" content="width=device-width, initial-scale=1">' +
    '<meta name="color-scheme" content="light"><meta name="supported-color-schemes" content="light">' +
    `<title>${escapeHtml(subject)}</title></head>` +
    '<body style="margin:0;padding:0;background:#f4f7fb;">' +
    '<table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="background:#f4f7fb;" bgcolor="#f4f7fb"><tr><td align="center" style="padding:32px 12px;">' +
    '<table role="presentation" width="600" cellpadding="0" cellspacing="0" border="0" style="width:100%;max-width:600px;">' +
    '<tr><td bgcolor="#ffffff" style="background:#ffffff;padding:24px 32px 20px;border-radius:8px 8px 0 0;border:1px solid #e2e8f0;border-bottom:3px solid #0284c7;">' +
    logoHtml +
    '</td></tr>' +
    '<tr><td bgcolor="#ffffff" style="background:#ffffff;padding:28px 32px 32px;border:1px solid #e2e8f0;border-top:0;border-radius:0 0 8px 8px;font:15px/1.6 Arial,Helvetica,sans-serif;color:#1e293b;">' +
    body +
    '</td></tr>' +
    '<tr><td style="padding:20px 32px 0;font:12px/1.6 Arial,Helvetica,sans-serif;color:#64748b;" align="center">' +
    (footer ? `<p style="margin:0 0 8px;">${escapeHtml(footer).replaceAll('\n', '<br>')}</p>` : '') +
    (support ? `<p style="margin:0 0 8px;">${support}</p>` : '') +
    `<p style="margin:0;">Sent by ${escapeHtml(appName)}${organisation ? ` on behalf of ${escapeHtml(organisation)}` : ''}</p>` +
    '</td></tr></table></td></tr></table></body></html>'
  );
}
