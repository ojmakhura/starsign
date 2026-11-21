import { Component, computed, inject, signal } from '@angular/core';
import {
  form,
  FormField,
  FormRoot,
  maxLength,
  minLength,
  pattern,
  required,
  validate,
} from '@angular/forms/signals';
import { Router, RouterLink } from '@angular/router';
import { DocumentUploadDTO } from '@models/document';
import { DocumentApi } from '@services/document/document-api';
import { FieldErrors } from '@shared/field-errors/field-errors';
import { FileDrop } from '@shared/file-drop/file-drop';
import { apiErrorMessage, apiFieldErrors } from '@store/app-state';
import { toast } from 'ngx-sonner';
import { firstValueFrom } from 'rxjs';
import { toDateInput } from '../search-dates';

interface UploadModel {
  file: File | null;
  title: string;
  referenceNumber: string;
  description: string;
  retentionUntil: string;
  tagInput: string;
  tags: string[];
}

/** Default organisation limit; the API enforces the organisation's signing policy. */
const MAX_UPLOAD_MB = 25;

@Component({
  selector: 'app-document-upload',
  imports: [FieldErrors, FileDrop, FormField, FormRoot, RouterLink],
  templateUrl: './document-upload.html',
})
export class DocumentUpload {
  private readonly documentApi = inject(DocumentApi);
  private readonly router = inject(Router);

  protected readonly maxUploadMb = MAX_UPLOAD_MB;
  protected readonly today = toDateInput(new Date());

  protected readonly model = signal<UploadModel>({
    file: null,
    title: '',
    referenceNumber: '',
    description: '',
    retentionUntil: '',
    tagInput: '',
    tags: [],
  });

  protected readonly uploadForm = form(
    this.model,
    (path) => {
      validate(path.file, ({ value }) => {
        const file = value();
        if (!file) {
          return { kind: 'required', message: 'Choose the PDF to upload.' };
        }
        if (file.type !== 'application/pdf' && !file.name.toLowerCase().endsWith('.pdf')) {
          return { kind: 'fileType', message: 'Only PDF documents can be signed.' };
        }
        if (file.size > MAX_UPLOAD_MB * 1_048_576) {
          return { kind: 'fileSize', message: `The file is larger than ${MAX_UPLOAD_MB} MB.` };
        }
        return file.size === 0 ? { kind: 'fileEmpty', message: 'The file is empty.' } : null;
      });

      required(path.title, { message: 'Give the document a title.' });
      minLength(path.title, 3, { message: 'The title needs at least 3 characters.' });
      maxLength(path.title, 200);

      pattern(path.referenceNumber, /^[A-Za-z0-9][A-Za-z0-9\-_/.]*$/, {
        message: 'Use letters, digits and - _ / . only.',
      });
      maxLength(path.referenceNumber, 64);
      maxLength(path.description, 2000, { message: 'Keep the description under 2000 characters.' });

      validate(path.retentionUntil, ({ value }) =>
        value() && value() <= this.today
          ? { kind: 'retention', message: 'Retention must end in the future.' }
          : null,
      );
      validate(path.tags, ({ value }) =>
        value().length > 10 ? { kind: 'tags', message: 'Use at most 10 tags.' } : null,
      );
    },
    {
      submission: {
        action: async (field) => {
          const model = this.model();
          try {
            const document = await firstValueFrom(
              this.documentApi.upload(model.file as File, this.toDetails(model)),
            );
            toast.success('Document uploaded', {
              description: `${document.title} is in the vault and ready for a signing workflow.`,
            });
            this.router.navigate(['/documents', document.id]);
            return undefined;
          } catch (error) {
            // Map server-side field errors back onto the matching form fields.
            const fieldErrors = apiFieldErrors(error);
            const mapped = (['title', 'referenceNumber', 'description', 'retentionUntil'] as const)
              .filter((key) => fieldErrors[key])
              .map((key) => ({ kind: 'server', message: fieldErrors[key], fieldTree: field[key] }));
            return mapped.length
              ? mapped
              : { kind: 'server', message: apiErrorMessage(error, 'The upload failed.') };
          }
        },
      },
    },
  );

  /** Form-level errors returned by the submission action. */
  protected readonly serverError = computed(
    () =>
      this.uploadForm()
        .errors()
        .find((error) => error.kind === 'server')?.message ?? null,
  );

  /** Prefills the title from the chosen file name while the title is untouched. */
  protected readonly suggestedTitle = computed(() => {
    const name = this.model().file?.name;
    return name
      ? name
          .replace(/\.pdf$/i, '')
          .replace(/[_-]+/g, ' ')
          .trim()
      : '';
  });

  protected useSuggestedTitle(): void {
    this.uploadForm.title().value.set(this.suggestedTitle());
  }

  protected addTag(event?: Event): void {
    event?.preventDefault();
    const tag = this.model().tagInput.trim().toLowerCase();
    this.model.update((m) => ({
      ...m,
      tagInput: '',
      tags: tag && !m.tags.includes(tag) ? [...m.tags, tag] : m.tags,
    }));
  }

  protected removeTag(tag: string): void {
    this.model.update((m) => ({ ...m, tags: m.tags.filter((t) => t !== tag) }));
  }

  private toDetails(model: UploadModel): DocumentUploadDTO {
    return {
      title: model.title.trim(),
      referenceNumber: model.referenceNumber.trim() || null,
      description: model.description.trim() || null,
      retentionUntil: model.retentionUntil || null,
      // The API stores tags as one comma-separated string.
      tags: model.tags.join(', ') || null,
    };
  }
}
