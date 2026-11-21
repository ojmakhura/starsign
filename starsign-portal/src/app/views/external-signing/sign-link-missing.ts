import { Component } from '@angular/core';

/** /sign without a token: explains where the signing link comes from. */
@Component({
  selector: 'app-sign-link-missing',
  template: `
    <div class="login-screen">
      <section class="login-card stage flex flex-col gap-4" aria-labelledby="sign-missing-title">
        <div class="flex items-center gap-3">
          <img src="/mark.svg" alt="" class="h-9 w-9" />
          <span class="font-display text-headline-sm font-bold">StarSign</span>
        </div>
        <span class="eyebrow mt-4">Signing portal</span>
        <h1 id="sign-missing-title" class="m-0 text-headline-md font-bold">
          Open your signing link
        </h1>
        <p class="m-0 text-ink-muted">
          To review and sign a document, open the link in the signing invitation you received by
          email. The link is personal to you, so make sure you use the whole link.
        </p>
        <div class="alert alert--info">
          <i class="bi bi-envelope-open" aria-hidden="true"></i>
          Can't find the email? Check your spam folder, or ask the sender to send the invitation
          again.
        </div>
        <a class="btn btn--ghost btn--block" href="/signing">
          <i class="bi bi-person-circle" aria-hidden="true"></i> I have a StarSign account
        </a>
      </section>
    </div>
  `,
})
export class SignLinkMissing {}
