import { Component, computed, DestroyRef, effect, inject, input, resource, signal, untracked } from '@angular/core';
import {
  applyEach,
  email,
  form,
  FormField,
  FormRoot,
  hidden,
  maxLength,
  pattern,
  required,
  schema,
  validate,
} from '@angular/forms/signals';
import { Router, RouterLink } from '@angular/router';
import { DocumentListDTO, DocumentStatus } from '@models/document';
import { UserStatus } from '@models/identity';
import { VerificationMethod } from '@models/signing';
import { SigningTemplateDTO, TemplateInstantiationDTO, TemplateStatus } from '@models/template';
import { SignatoryType } from '@models/workflow';
import { DocumentApi } from '@services/document/document-api';
import { OrganisationApi } from '@services/organisation/organisation-api';
import { TemplateApi } from '@services/template/template-api';
import { FieldErrors } from '@shared/field-errors/field-errors';
import { apiErrorMessage } from '@store/app-state';
import { LoaderState } from '@store/loader-state';
import { SessionStore } from '@store/session.store';
import { toast } from 'ngx-sonner';
import { firstValueFrom } from 'rxjs';
import { toDateInput } from '../../documents/search-dates';
import { highestFieldPage, signatoryTypeLabel, verificationLabel } from '../template-meta';

/** Who fills one signatory placeholder of the template. */
interface BindingRow {
  roleId: string;
  roleName: string;
  stageNumber: number;
  signatoryType: SignatoryType;
  verificationMethod: VerificationMethod | '';
  mandatory: boolean;
  /** Leave an optional role unfilled (its fields are dropped). */
  skip: boolean;
  internalUserId: string;
  fullName: string;
  emailAddress: string;
  phoneNumber: string;
  organisationName: string;
  designation: string;
}

interface UseModel {
  documentId: string;
  title: string;
  message: string;
  expiresAt: string;
  initiateImmediately: boolean;
  bindings: BindingRow[];
}

/** Documents a new workflow can be created for (the API refuses any other status). */
const ELIGIBLE = [DocumentStatus.DRAFT, DocumentStatus.DECLINED, DocumentStatus.CANCELLED];
const E164 = /^\+[1-9]\d{6,14}$/;
const MAX_EXPIRY_DAYS = 365;
const addDays = (days: number) => toDateInput(new Date(Date.now() + days * 86_400_000));

/** Applies an active template to a document: choose the document and fill each role. */
@Component({
  selector: 'app-template-instantiate',
  imports: [FieldErrors, FormField, FormRoot, RouterLink],
  templateUrl: './template-instantiate.html',
})
export class TemplateInstantiate {
  readonly id = input.required<string>();

  private readonly templateApi = inject(TemplateApi);
  private readonly documentApi = inject(DocumentApi);
  private readonly organisationApi = inject(OrganisationApi);
  private readonly session = inject(SessionStore);
  private readonly router = inject(Router);

  /** One key per visit, so a retried request cannot create a second workflow. */
  private readonly idempotencyKey = crypto.randomUUID();

  protected readonly template = resource({
    params: () => this.id(),
    loader: ({ params }) => firstValueFrom(this.templateApi.findById(params)),
  });
  protected readonly tpl = computed<SigningTemplateDTO | null>(() =>
    this.template.hasValue() ? this.template.value() : null,
  );
  protected readonly loadError = computed(() => {
    const error = this.template.error() as (Error & { cause?: unknown }) | undefined;
    return error ? apiErrorMessage(error.cause ?? error, 'The template could not be loaded.') : null;
  });
  protected readonly inactive = computed(
    () => !!this.tpl() && this.tpl()?.status !== TemplateStatus.ACTIVE,
  );
  protected readonly minPages = computed(() => highestFieldPage(this.tpl()));

  protected readonly documents = resource({
    loader: () =>
      firstValueFrom(this.documentApi.search({ statuses: ELIGIBLE, includeArchived: false, pageSize: 200 })),
  });
  protected readonly documentList = computed<DocumentListDTO[]>(() =>
    this.documents.hasValue() ? this.documents.value() : [],
  );
  protected readonly selectedDocument = computed(
    () => this.documentList().find((doc) => doc.id === this.model().documentId) ?? null,
  );

  /** Users of the organisation, loaded only when a role is for an internal user. */
  protected readonly users = resource({
    params: () => {
      const organisationId = this.session.organisationId();
      const needed = this.tpl()?.signatories?.some((r) => r.signatoryType === SignatoryType.INTERNAL_USER);
      return needed && organisationId ? organisationId : undefined;
    },
    loader: ({ params }) => firstValueFrom(this.organisationApi.findUsers(params)),
  });
  protected readonly activeUsers = computed(() =>
    (this.users.hasValue() ? this.users.value() : []).filter(
      (user) => user.status !== UserStatus.DEACTIVATED && user.status !== UserStatus.LOCKED,
    ),
  );

  protected readonly model = signal<UseModel>({
    documentId: '',
    title: '',
    message: '',
    expiresAt: addDays(30),
    initiateImmediately: true,
    bindings: [],
  });

  protected readonly useForm = form(
    this.model,
    schema<UseModel>((path) => {
      required(path.documentId, { message: 'Choose the document to be signed.' });
      validate(path.documentId, ({ value }) => {
        const doc = this.documentList().find((d) => d.id === value());
        const pages = this.minPages();
        return doc && pages && (doc.pageCount ?? 0) < pages
          ? {
              kind: 'pages',
              message: `The template places fields on page ${pages}; this document has ${doc.pageCount ?? 0}.`,
            }
          : null;
      });
      maxLength(path.title, 200);
      maxLength(path.message, 2000, { message: 'Keep the message under 2000 characters.' });
      required(path.expiresAt, { message: 'Set when the signing request expires.' });
      validate(path.expiresAt, ({ value }) => {
        if (!value()) {
          return null;
        }
        if (value() <= toDateInput(new Date())) {
          return { kind: 'expiry', message: 'The expiry date must be in the future.' };
        }
        return value() > addDays(MAX_EXPIRY_DAYS)
          ? { kind: 'expiry', message: `Requests can stay open for at most ${MAX_EXPIRY_DAYS} days.` }
          : null;
      });

      applyEach(path.bindings, (row) => {
        const isInternal = (type: SignatoryType) => type === SignatoryType.INTERNAL_USER;

        hidden(row.internalUserId, ({ valueOf }) => !isInternal(valueOf(row.signatoryType)));
        required(row.internalUserId, {
          when: ({ valueOf }) => !valueOf(row.skip) && isInternal(valueOf(row.signatoryType)),
          message: 'Choose the user who signs.',
        });

        hidden(row.fullName, ({ valueOf }) => isInternal(valueOf(row.signatoryType)));
        hidden(row.emailAddress, ({ valueOf }) => isInternal(valueOf(row.signatoryType)));
        required(row.fullName, {
          when: ({ valueOf }) => !valueOf(row.skip) && !isInternal(valueOf(row.signatoryType)),
          message: 'Name is required.',
        });
        maxLength(row.fullName, 120);
        required(row.emailAddress, {
          when: ({ valueOf }) => !valueOf(row.skip) && !isInternal(valueOf(row.signatoryType)),
          message: 'Email is required.',
        });
        email(row.emailAddress, { message: 'Enter a valid email address.' });

        required(row.phoneNumber, {
          when: ({ valueOf }) =>
            !valueOf(row.skip) && valueOf(row.verificationMethod) === VerificationMethod.SMS_OTP,
          message: 'A mobile number is needed for SMS verification.',
        });
        pattern(row.phoneNumber, E164, { message: 'Use international format, e.g. +26771234567.' });

        hidden(
          row.organisationName,
          ({ valueOf }) =>
            valueOf(row.signatoryType) !== SignatoryType.EXTERNAL_ORGANISATION_REPRESENTATIVE,
        );
        required(row.organisationName, {
          when: ({ valueOf }) =>
            !valueOf(row.skip) &&
            valueOf(row.signatoryType) === SignatoryType.EXTERNAL_ORGANISATION_REPRESENTATIVE,
          message: 'Which organisation do they represent?',
        });
        hidden(row.skip, ({ valueOf }) => valueOf(row.mandatory));
      });
    }),
    { submission: { action: () => this.submit() } },
  );

  protected readonly formError = computed(
    () =>
      this.useForm()
        .errors()
        .find((error) => error.kind === 'server')?.message ?? null,
  );

  protected readonly SignatoryType = SignatoryType;
  protected readonly signatoryTypeLabel = signatoryTypeLabel;
  protected readonly verificationLabel = verificationLabel;

  constructor() {
    inject(DestroyRef).onDestroy(
      inject(LoaderState).track(() => this.template.isLoading(), 'Loading template…'),
    );

    // One placeholder row per role, in stage and signing order; defaults from the template.
    effect(() => {
      const template = this.tpl();
      if (!template) {
        return;
      }
      untracked(() =>
        this.model.update((m) => ({
          ...m,
          message: template.message ?? '',
          expiresAt: addDays(Math.min(MAX_EXPIRY_DAYS, template.defaultExpiryDays || 30)),
          bindings: [...(template.signatories ?? [])]
            .sort(
              (a, b) =>
                (a.stageNumber ?? 1) - (b.stageNumber ?? 1) ||
                (a.signingOrder ?? 0) - (b.signingOrder ?? 0),
            )
            .map((role) => ({
              roleId: role.id ?? '',
              roleName: role.roleName ?? '',
              stageNumber: role.stageNumber ?? 1,
              signatoryType: role.signatoryType ?? SignatoryType.EXTERNAL_PERSON,
              verificationMethod: role.verificationMethod ?? template.verificationMethod ?? '',
              mandatory: role.mandatory !== false,
              skip: false,
              internalUserId: '',
              fullName: '',
              emailAddress: '',
              phoneNumber: '',
              organisationName: '',
              designation: role.roleName ?? '',
            })),
        })),
      );
    });

    // Default the title to "<template> · <document>" while it is untouched.
    effect(() => {
      const doc = this.selectedDocument();
      const template = this.tpl();
      untracked(() => {
        const title = this.useForm.title();
        if (doc && template && !title.dirty()) {
          title.value.set(`${template.name}: ${doc.title}`);
        }
      });
    });
  }

  private async submit() {
    const m = this.model();
    const body: TemplateInstantiationDTO = {
      documentId: m.documentId,
      title: m.title.trim() || null,
      message: m.message.trim() || null,
      expiresAt: new Date(`${m.expiresAt}T23:59:59`).toISOString(),
      initiateImmediately: m.initiateImmediately,
      idempotencyKey: this.idempotencyKey,
      bindings: m.bindings
        .filter((row) => !row.skip)
        .map((row) => {
          const internal = row.signatoryType === SignatoryType.INTERNAL_USER;
          return {
            signatoryRoleId: row.roleId,
            signatory: {
              signatoryType: row.signatoryType,
              internalUserId: internal ? row.internalUserId : null,
              fullName: internal ? null : row.fullName.trim(),
              emailAddress: internal ? null : row.emailAddress.trim().toLowerCase(),
              phoneNumber: row.phoneNumber.trim() || null,
              organisationName:
                row.signatoryType === SignatoryType.EXTERNAL_ORGANISATION_REPRESENTATIVE
                  ? row.organisationName.trim()
                  : null,
              designation: row.designation.trim() || null,
              notifyByEmail: true,
            },
          };
        }),
    };
    try {
      const workflow = await firstValueFrom(this.templateApi.instantiate(this.id(), body));
      toast.success(m.initiateImmediately ? 'Sent for signing' : 'Draft workflow created', {
        description: workflow.title ?? undefined,
      });
      this.router.navigate(['/workflows', workflow.id]);
      return undefined;
    } catch (error) {
      return {
        kind: 'server',
        message: apiErrorMessage(error, 'The workflow could not be created from the template.'),
      };
    }
  }
}
