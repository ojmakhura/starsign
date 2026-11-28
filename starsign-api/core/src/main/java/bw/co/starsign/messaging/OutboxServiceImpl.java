// license-header java merge-point
/**
 * This is only generated once! It will never be overwritten.
 * You can (and have to!) safely modify it by hand.
 * TEMPLATE:    SpringServiceImpl.vsl in andromda-spring cartridge
 * MODEL CLASS: AndroMDAModel::backend::bw.co.starsign::messaging::OutboxService
 * STEREOTYPE:  Service
 */
package bw.co.starsign.messaging;

import jakarta.validation.Valid;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Transactional outbox. Producers write events in their own transaction; the relay
 * ({@link OutboxRelay}) hands due events to the in-process {@link OutboxEventHandler}s, at most once
 * per handler, and retries failures with exponential backoff until they are dead-lettered.
 *
 * @see bw.co.starsign.messaging.OutboxService
 */
@Service("outboxService")
@Transactional(propagation = Propagation.REQUIRED, readOnly = false)
public class OutboxServiceImpl
    extends OutboxServiceBase
{
    private static final Set<OutboxStatus> DUE = EnumSet.of(OutboxStatus.PENDING, OutboxStatus.FAILED,
            OutboxStatus.PUBLISHING);
    /** How long a relay may hold an event before another relay assumes it crashed and takes it over. */
    private static final Duration LEASE = Duration.ofMinutes(5);
    private static final Duration FIRST_RETRY = Duration.ofSeconds(15);
    private static final Duration MAX_RETRY = Duration.ofHours(1);
    private static final int MAX_ATTEMPTS = 10;
    private static final int MAX_ERROR_LENGTH = 2000;
    private static final String SYSTEM = "system";

    private final ObjectProvider<OutboxEventHandler> handlers;
    private final TransactionTemplate transaction;
    private final ApplicationEventPublisher events;

    public OutboxServiceImpl(
        OutboxEventRepository outboxEventRepository,
        OutboxEventMapper outboxEventMapper,
        ProcessedMessageRepository processedMessageRepository,
        ProcessedMessageMapper processedMessageMapper,
        ObjectProvider<OutboxEventHandler> handlers,
        PlatformTransactionManager transactionManager,
        ApplicationEventPublisher events) {

        super(
            outboxEventRepository,
            outboxEventMapper,
            processedMessageRepository,
            processedMessageMapper);
        this.handlers = handlers;
        this.events = events;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * @see bw.co.starsign.messaging.OutboxService#enqueue(OutboxEventDTO)
     */
    @Override
    protected OutboxEventDTO handleEnqueue(@Valid OutboxEventDTO event)
        throws Exception
    {
        List<String> errors = new ArrayList<String>();
        if (StringUtils.isBlank(event.getAggregateType()))
        {
            errors.add("The aggregate type is required");
        }
        if (StringUtils.isBlank(event.getAggregateId()))
        {
            errors.add("The aggregate id is required");
        }
        if (StringUtils.isBlank(event.getEventType()))
        {
            errors.add("The event type is required");
        }
        if (StringUtils.isBlank(event.getRoutingKey()))
        {
            errors.add("The routing key is required");
        }
        if (StringUtils.isNotBlank(event.getPayload()) && !looksLikeJson(event.getPayload()))
        {
            errors.add("The payload must be a JSON object or array");
        }
        if (!errors.isEmpty())
        {
            throw new OutboxServiceException(String.join("; ", errors));
        }

        // The same business fact enqueued twice (e.g. a retried request) is published once
        String idempotencyKey = StringUtils.isNotBlank(event.getIdempotencyKey())
                ? event.getIdempotencyKey().trim()
                : UUID.randomUUID().toString();
        Optional<OutboxEvent> existing = outboxEventRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent())
        {
            return outboxEventMapper.toOutboxEventDTO(existing.get());
        }

        LocalDateTime now = LocalDateTime.now();
        OutboxEvent entity = OutboxEvent.Factory.newInstance();
        entity.setAggregateType(event.getAggregateType().trim());
        entity.setAggregateId(event.getAggregateId().trim());
        entity.setEventType(event.getEventType().trim());
        entity.setOrganisationId(StringUtils.trimToNull(event.getOrganisationId()));
        entity.setRoutingKey(event.getRoutingKey().trim());
        entity.setPayload(StringUtils.isBlank(event.getPayload()) ? "{}" : event.getPayload().trim());
        entity.setStatus(OutboxStatus.PENDING);
        entity.setAttempts(0);
        entity.setNextAttemptAt(now);
        entity.setIdempotencyKey(idempotencyKey);
        entity.setCorrelationId(StringUtils.trimToNull(event.getCorrelationId()));
        entity.setCreatedAt(now);
        entity.setCreatedBy(SYSTEM);
        OutboxEvent saved = outboxEventRepository.save(entity);
        // The relay picks this up once the caller's transaction commits (nothing happens on rollback)
        events.publishEvent(new OutboxEventEnqueued(String.valueOf(saved.getId())));
        return outboxEventMapper.toOutboxEventDTO(saved);
    }

    /**
     * @see bw.co.starsign.messaging.OutboxService#publishPending(int)
     */
    @Override
    protected int handlePublishPending(int batchSize)
        throws Exception
    {
        if (batchSize <= 0)
        {
            throw new OutboxServiceException("The batch size must be positive");
        }
        List<UUID> due = transaction.execute(status -> outboxEventRepository.findDueIds(DUE, LocalDateTime.now(),
                PageRequest.of(0, batchSize)));

        int published = 0;
        for (UUID id : due)
        {
            if (publish(id))
            {
                published++;
            }
        }
        return published;
    }

    /**
     * @see bw.co.starsign.messaging.OutboxService#markFailed(String, String)
     */
    @Override
    protected void handleMarkFailed(String eventId, String error)
        throws Exception
    {
        OutboxEvent event = outboxEventRepository.findById(uuid(eventId))
                .orElseThrow(() -> OutboxServiceException.notFound("The outbox event does not exist"));
        fail(event, error);
    }

    /**
     * @see bw.co.starsign.messaging.OutboxService#isProcessed(String, String)
     */
    @Override
    protected boolean handleIsProcessed(String messageId, String consumerName)
        throws Exception
    {
        return processedMessageRepository.existsByMessageIdAndConsumerName(messageId.trim(), consumerName.trim());
    }

    /**
     * @see bw.co.starsign.messaging.OutboxService#markProcessed(String, String, String)
     */
    @Override
    protected void handleMarkProcessed(String messageId, String consumerName, String outcome)
        throws Exception
    {
        if (processedMessageRepository.existsByMessageIdAndConsumerName(messageId.trim(), consumerName.trim()))
        {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        ProcessedMessage processed = ProcessedMessage.Factory.newInstance();
        processed.setMessageId(messageId.trim());
        processed.setConsumerName(consumerName.trim());
        processed.setProcessedAt(now);
        processed.setOutcome(StringUtils.abbreviate(StringUtils.trimToNull(outcome), 255));
        processed.setCreatedAt(now);
        processed.setCreatedBy(SYSTEM);
        processedMessageRepository.save(processed);
    }

    /**
     * @see bw.co.starsign.messaging.OutboxService#purgePublished(LocalDateTime)
     */
    @Override
    protected int handlePurgePublished(LocalDateTime olderThan)
        throws Exception
    {
        return outboxEventRepository.deleteByStatusAndPublishedAtBefore(OutboxStatus.PUBLISHED, olderThan);
    }

    /**
     * Claims, hands out and settles one event; false when another relay had it or a handler failed.
     */
    private boolean publish(UUID id)
    {
        Integer claimed = transaction.execute(status -> {
            LocalDateTime now = LocalDateTime.now();
            return outboxEventRepository.claim(id, DUE, OutboxStatus.PUBLISHING, now, now.plus(LEASE));
        });
        if (claimed == null || claimed == 0)
        {
            return false;
        }
        OutboxEventDTO event = transaction.execute(status ->
                outboxEventRepository.findById(id).map(outboxEventMapper::toOutboxEventDTO).orElse(null));
        if (event == null)
        {
            return false;
        }

        try
        {
            for (OutboxEventHandler handler : handlers.orderedStream().toList())
            {
                if (!handler.accepts(event.getRoutingKey()))
                {
                    continue;
                }
                // The handler's work and its processed marker commit together, so a retry skips finished handlers
                transaction.executeWithoutResult(status -> {
                    if (!processedMessageRepository.existsByMessageIdAndConsumerName(event.getIdempotencyKey(),
                            handler.consumerName()))
                    {
                        handler.handle(event);
                        markProcessedNow(event.getIdempotencyKey(), handler.consumerName(), "OK");
                    }
                });
            }
        }
        catch (RuntimeException e)
        {
            logger.warn("Outbox event {} ({} -> {}) failed: {}", id, event.getEventType(), event.getRoutingKey(),
                    e.toString());
            transaction.executeWithoutResult(status ->
                    outboxEventRepository.findById(id).ifPresent(entity -> fail(entity, e.toString())));
            return false;
        }

        transaction.executeWithoutResult(status -> outboxEventRepository.findById(id).ifPresent(entity -> {
            LocalDateTime now = LocalDateTime.now();
            entity.setStatus(OutboxStatus.PUBLISHED);
            entity.setPublishedAt(now);
            entity.setNextAttemptAt(null);
            entity.setLastError(null);
            entity.setModifiedAt(now);
            entity.setModifiedBy(SYSTEM);
            outboxEventRepository.save(entity);
        }));
        return true;
    }

    private void markProcessedNow(String messageId, String consumerName, String outcome)
    {
        LocalDateTime now = LocalDateTime.now();
        ProcessedMessage processed = ProcessedMessage.Factory.newInstance();
        processed.setMessageId(messageId);
        processed.setConsumerName(consumerName);
        processed.setProcessedAt(now);
        processed.setOutcome(outcome);
        processed.setCreatedAt(now);
        processed.setCreatedBy(SYSTEM);
        processedMessageRepository.save(processed);
    }

    /** Schedules a retry with exponential backoff, or dead-letters the event after the last attempt. */
    private void fail(OutboxEvent event, String error)
    {
        LocalDateTime now = LocalDateTime.now();
        event.setLastError(StringUtils.abbreviate(StringUtils.defaultIfBlank(error, "Unknown error"),
                MAX_ERROR_LENGTH));
        if (event.getAttempts() >= MAX_ATTEMPTS)
        {
            event.setStatus(OutboxStatus.DEAD_LETTERED);
            event.setNextAttemptAt(null);
            logger.error("Outbox event {} ({}) is dead-lettered after {} attempts: {}", event.getId(),
                    event.getEventType(), event.getAttempts(), event.getLastError());
        }
        else
        {
            event.setStatus(OutboxStatus.FAILED);
            event.setNextAttemptAt(now.plus(backoff(event.getAttempts())));
        }
        event.setModifiedAt(now);
        event.setModifiedBy(SYSTEM);
        outboxEventRepository.save(event);
    }

    private static Duration backoff(int attempts)
    {
        Duration delay = FIRST_RETRY.multipliedBy(1L << Math.min(Math.max(attempts - 1, 0), 16));
        return delay.compareTo(MAX_RETRY) > 0 ? MAX_RETRY : delay;
    }

    private static boolean looksLikeJson(String payload)
    {
        String value = payload.trim();
        return (value.startsWith("{") && value.endsWith("}")) || (value.startsWith("[") && value.endsWith("]"));
    }

    private static UUID uuid(String id)
    {
        try
        {
            return UUID.fromString(id.trim());
        }
        catch (IllegalArgumentException e)
        {
            throw OutboxServiceException.notFound("'" + id + "' is not the id of an outbox event");
        }
    }
}
