import { Injector, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { form } from '@angular/forms/signals';
import { VerificationMethod } from '@models/signing';
import {
  newDraft,
  newSignatory,
  toCreateWorkflow,
  WorkflowDraft,
  workflowDraftSchema,
} from './workflow-draft';

describe('workflowDraftSchema', () => {
  const create = (patch: Partial<WorkflowDraft> = {}) => {
    const model = signal<WorkflowDraft>({ ...newDraft('doc-1'), title: 'NDA', ...patch });
    const tree = form(model, workflowDraftSchema, { injector: TestBed.inject(Injector) });
    return { model, tree };
  };
  const person = (emailAddress: string) => ({
    ...newSignatory(),
    fullName: 'Signer',
    emailAddress,
  });

  it('is valid with one complete signatory', () => {
    const { tree } = create({ signatories: [person('a@example.com')] });
    expect(tree().valid()).toBe(true);
  });

  it('flags duplicate signatory emails on the second occurrence', () => {
    const { tree } = create({ signatories: [person('a@example.com'), person('A@example.com ')] });
    expect(tree.signatories[0].emailAddress().valid()).toBe(true);
    expect(
      tree.signatories[1]
        .emailAddress()
        .errors()
        .map((e) => e.kind),
    ).toContain('duplicateEmail');
  });

  it('requires a mobile number only when SMS is used', () => {
    const { tree, model } = create({ signatories: [person('a@example.com')] });
    expect(tree.signatories[0].phoneNumber().valid()).toBe(true);
    model.update((d) => ({ ...d, verificationMethod: VerificationMethod.SMS_OTP }));
    expect(
      tree.signatories[0]
        .phoneNumber()
        .errors()
        .map((e) => e.kind),
    ).toContain('required');
  });

  it('disables "decline ends workflow" when declining is not allowed', () => {
    const { tree, model } = create();
    expect(tree.declineTerminatesWorkflow().disabled()).toBe(false);
    model.update((d) => ({ ...d, allowDecline: false }));
    expect(tree.declineTerminatesWorkflow().disabled()).toBe(true);
    expect(toCreateWorkflow(model()).declineTerminatesWorkflow).toBe(false);
  });

  it('rejects an expiry date in the past and no signature method', () => {
    const { tree } = create({ expiresAt: '2000-01-01', captureDrawn: false, captureTyped: false });
    expect(
      tree
        .expiresAt()
        .errors()
        .map((e) => e.kind),
    ).toContain('expiry');
    expect(
      tree
        .captureTyped()
        .errors()
        .map((e) => e.kind),
    ).toContain('capture');
  });
});
