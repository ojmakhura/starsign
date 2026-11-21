import { DatePipe } from '@angular/common';
import { Component, computed, inject, OnInit, signal } from '@angular/core';
import {
  applyWhenValue,
  email,
  form,
  FormField,
  FormRoot,
  maxLength,
  min,
  pattern,
  required,
  validate,
} from '@angular/forms/signals';
import { RouterLink } from '@angular/router';
import { Can } from '@auth/can.directive';
import {
  NotificationChannel,
  NotificationEventType,
  NotificationProviderConfigDTO,
} from '@models/notification';
import { NotificationProviderApi } from '@services/notification/notification-provider-api';
import { FieldErrors } from '@shared/field-errors/field-errors';
import { apiErrorMessage } from '@store/app-state';
import { toast } from 'ngx-sonner';
import { firstValueFrom } from 'rxjs';
import { humanise } from '../platform-format';

/** Where credentials are kept: a scheme and a key, never the secret itself (NotificationProviderServiceImpl.SECRET_REF). */
const SECRET_REF = /^(env|vault|secret|k8s|aws-sm|novu):[A-Za-z0-9_./-]{1,200}$/;
const PHONE = /^\+?[0-9]{7,15}$/;

type DeliveryChannel = NotificationChannel.EMAIL | NotificationChannel.SMS;

interface ProviderModel {
  name: string;
  channel: DeliveryChannel;
  providerType: string;
  endpointUrl: string;
  credentialsSecretRef: string;
  senderAddress: string;
  active: boolean;
  priority: number | null;
  rateLimitPerMinute: number | null;
}

interface TestModel {
  recipientAddress: string;
}

type TestResult = { provider: string; recipient: string } & (
  | { outcome: 'sent' }
  | { outcome: 'disabled' }
  | { outcome: 'failed'; message: string }
);

/** "SIGNING_INVITATION" → "signing-invitation" (Novu workflow ids are the prefix + this). */
const kebab = (value: string) => value.toLowerCase().replaceAll('_', '-');

/**
 * Email and SMS provider configurations (/admin/notification-providers). All delivery
 * goes through Novu; a configuration names where its credentials are kept.
 */
@Component({
  selector: 'app-platform-providers',
  imports: [Can, DatePipe, FieldErrors, FormField, FormRoot, RouterLink],
  templateUrl: './platform-providers.html',
})
export class PlatformProviders implements OnInit {
  private readonly providerApi = inject(NotificationProviderApi);

  protected readonly humanise = humanise;
  protected readonly channels: DeliveryChannel[] = [NotificationChannel.EMAIL, NotificationChannel.SMS];
  protected readonly exampleWorkflows = [
    NotificationEventType.SIGNING_INVITATION,
    NotificationEventType.SIGNING_REMINDER,
    NotificationEventType.WORKFLOW_COMPLETED,
  ].map((event) => `starsign-${kebab(event)}`);

  protected readonly providers = signal<NotificationProviderConfigDTO[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly busyId = signal<string | null>(null);

  protected readonly groups = computed(() =>
    this.channels.map((channel) => ({
      channel,
      providers: this.providers()
        .filter((p) => p.channel === channel)
        .sort((a, b) => (a.priority ?? 100) - (b.priority ?? 100)),
    })),
  );

  // ---- Editor ----
  protected readonly editing = signal<NotificationProviderConfigDTO | null>(null);
  protected readonly editorOpen = signal(false);
  protected readonly model = signal<ProviderModel>(this.emptyProvider());
  protected readonly providerForm = form(
    this.model,
    (path) => {
      required(path.name, { message: 'Give the provider a name.' });
      maxLength(path.name, 100);
      required(path.providerType, { message: 'Give the provider type, e.g. novu, smtp or twilio.' });
      maxLength(path.providerType, 50);
      pattern(path.endpointUrl, /^https?:\/\/\S+$/, { message: 'An http or https URL.' });
      required(path.credentialsSecretRef, {
        message: 'Say where the credentials are kept, e.g. env:NOVU_SECRET_KEY.',
      });
      validate(path.credentialsSecretRef, ({ value }) =>
        !value().trim() || SECRET_REF.test(value().trim())
          ? null
          : {
              kind: 'secretRef',
              message:
                'A reference such as env:NOVU_SECRET_KEY or vault:secret/starsign/sms (env, vault, secret, k8s, aws-sm or novu) — never the secret itself.',
            },
      );
      applyWhenValue(path, (m) => m.channel === NotificationChannel.EMAIL, (p) => {
        email(p.senderAddress, { message: 'The sender of an email provider must be an email address.' });
      });
      min(path.priority, 0, { message: 'The priority cannot be negative.' });
      min(path.rateLimitPerMinute, 1, { message: 'The rate limit must be positive, or empty for none.' });
    },
    {
      submission: {
        action: async () => {
          const m = this.model();
          const editing = this.editing();
          try {
            const saved = await firstValueFrom(
              this.providerApi.saveProvider({
                ...(editing ?? {}),
                name: m.name.trim(),
                channel: m.channel,
                providerType: m.providerType.trim().toLowerCase(),
                endpointUrl: m.endpointUrl.trim() || null,
                credentialsSecretRef: m.credentialsSecretRef.trim(),
                senderAddress: m.senderAddress.trim() || null,
                active: m.active,
                priority: m.priority,
                rateLimitPerMinute: m.rateLimitPerMinute,
              }),
            );
            toast.success(editing ? 'Provider saved' : 'Provider added', {
              description: saved.name ?? undefined,
            });
            this.editorOpen.set(false);
            await this.load();
            return undefined;
          } catch (error) {
            return { kind: 'server', message: apiErrorMessage(error, 'The provider could not be saved.') };
          }
        },
      },
    },
  );
  protected readonly serverError = computed(
    () =>
      this.providerForm()
        .errors()
        .find((error) => error.kind === 'server')?.message ?? null,
  );

  // ---- Test dialog ----
  protected readonly testing = signal<NotificationProviderConfigDTO | null>(null);
  protected readonly testResult = signal<TestResult | null>(null);
  protected readonly testModel = signal<TestModel>({ recipientAddress: '' });
  protected readonly testForm = form(
    this.testModel,
    (path) => {
      required(path.recipientAddress, { message: 'Give the address to send the test to.' });
      validate(path.recipientAddress, ({ value }) => {
        const v = value().trim();
        if (!v) {
          return null;
        }
        if (this.testing()?.channel === NotificationChannel.SMS) {
          return PHONE.test(v.replace(/[\s()-]/g, ''))
            ? null
            : { kind: 'phone', message: 'A phone number, e.g. +26771234567.' };
        }
        return /^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(v)
          ? null
          : { kind: 'email', message: 'Enter a valid email address.' };
      });
    },
    {
      submission: {
        action: async () => {
          const provider = this.testing();
          if (!provider?.id) {
            return undefined;
          }
          const recipient = this.testModel().recipientAddress.trim();
          const base = { provider: provider.name ?? '', recipient };
          try {
            const sent = await firstValueFrom(this.providerApi.testProvider(provider.id, recipient));
            this.testResult.set(sent ? { ...base, outcome: 'sent' } : { ...base, outcome: 'disabled' });
          } catch (error) {
            this.testResult.set({
              ...base,
              outcome: 'failed',
              message: apiErrorMessage(error, 'The test message could not be sent.'),
            });
          }
          return undefined;
        },
      },
    },
  );

  ngOnInit(): void {
    void this.load();
  }

  protected async load(): Promise<void> {
    this.loading.set(true);
    this.error.set(null);
    try {
      this.providers.set(await firstValueFrom(this.providerApi.findProviders()));
    } catch (error) {
      this.error.set(apiErrorMessage(error, 'Unable to load the notification providers.'));
    } finally {
      this.loading.set(false);
    }
  }

  protected create(channel: DeliveryChannel = NotificationChannel.EMAIL): void {
    this.editing.set(null);
    this.model.set({ ...this.emptyProvider(), channel });
    this.providerForm().reset();
    this.editorOpen.set(true);
  }

  protected edit(provider: NotificationProviderConfigDTO): void {
    this.editing.set(provider);
    this.model.set({
      name: provider.name ?? '',
      channel: provider.channel === NotificationChannel.SMS ? NotificationChannel.SMS : NotificationChannel.EMAIL,
      providerType: provider.providerType ?? '',
      endpointUrl: provider.endpointUrl ?? '',
      credentialsSecretRef: provider.credentialsSecretRef ?? '',
      senderAddress: provider.senderAddress ?? '',
      active: provider.active ?? true,
      priority: provider.priority ?? 100,
      rateLimitPerMinute: provider.rateLimitPerMinute ?? null,
    });
    this.providerForm().reset();
    this.editorOpen.set(true);
  }

  protected openTest(provider: NotificationProviderConfigDTO): void {
    this.testing.set(provider);
    this.testResult.set(null);
    this.testModel.set({ recipientAddress: '' });
    this.testForm().reset();
  }

  protected closeTest(): void {
    this.testing.set(null);
  }

  protected async remove(provider: NotificationProviderConfigDTO): Promise<void> {
    if (!provider.id || !confirm(`Remove the ${provider.channel} provider ${provider.name}? This cannot be undone.`)) {
      return;
    }
    this.busyId.set(provider.id);
    try {
      await firstValueFrom(this.providerApi.removeProvider(provider.id));
      toast.success('Provider removed', { description: provider.name ?? undefined });
      if (this.editing()?.id === provider.id) {
        this.editorOpen.set(false);
      }
      await this.load();
    } catch (error) {
      toast.error(apiErrorMessage(error, 'The provider could not be removed.'));
    } finally {
      this.busyId.set(null);
    }
  }

  private emptyProvider(): ProviderModel {
    return {
      name: '',
      channel: NotificationChannel.EMAIL,
      providerType: 'novu',
      endpointUrl: '',
      credentialsSecretRef: '',
      senderAddress: '',
      active: true,
      priority: 100,
      rateLimitPerMinute: null,
    };
  }
}
