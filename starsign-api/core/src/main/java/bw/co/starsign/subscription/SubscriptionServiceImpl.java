// license-header java merge-point
/**
 * This is only generated once! It will never be overwritten.
 * You can (and have to!) safely modify it by hand.
 * TEMPLATE:    SpringServiceImpl.vsl in andromda-spring cartridge
 * MODEL CLASS: AndroMDAModel::backend::bw.co.starsign::subscription::SubscriptionService
 * STEREOTYPE:  Service
 */
package bw.co.starsign.subscription;

import bw.co.starsign.audit.ActorType;
import bw.co.starsign.audit.AuditEventDTO;
import bw.co.starsign.audit.AuditEventType;
import bw.co.starsign.audit.AuditOutcome;
import bw.co.starsign.audit.AuditTrailService;
import bw.co.starsign.context.CallerContext;
import bw.co.starsign.identity.UserStatus;
import bw.co.starsign.organisation.Organisation;
import bw.co.starsign.organisation.OrganisationRepository;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Currency;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * @see bw.co.starsign.subscription.SubscriptionService
 */
@Service("subscriptionService")
@Transactional(propagation = Propagation.REQUIRED, readOnly = false)
public class SubscriptionServiceImpl
    extends SubscriptionServiceBase
{
    private static final Pattern CODE = Pattern.compile("^[A-Z0-9][A-Z0-9_-]{0,49}$");
    /** Statuses in which the organisation may use what its plan includes. */
    private static final Set<SubscriptionStatus> USABLE = EnumSet.of(SubscriptionStatus.TRIAL,
            SubscriptionStatus.ACTIVE, SubscriptionStatus.PAST_DUE, SubscriptionStatus.CANCELLED);
    /** Metrics whose limits are per calendar month; the others are running totals or features. */
    private static final Set<UsageMetric> MONTHLY = EnumSet.of(UsageMetric.DOCUMENTS_UPLOADED,
            UsageMetric.WORKFLOWS_INITIATED, UsageMetric.SMS_SENT, UsageMetric.SIGNATURES_COMPLETED,
            UsageMetric.EMAILS_SENT, UsageMetric.DIGITAL_SIGNATURES, UsageMetric.API_CALLS);
    /** Metrics recorded as deltas that may go down again (a document deleted, a user removed). */
    private static final Set<UsageMetric> RUNNING_TOTALS = EnumSet.of(UsageMetric.STORAGE_BYTES, UsageMetric.USERS);

    private final CallerContext caller;
    private final OrganisationRepository organisationRepository;

    public SubscriptionServiceImpl(
        @Lazy AuditTrailService auditTrailService,
        SubscriptionPlanRepository subscriptionPlanRepository,
        SubscriptionPlanMapper subscriptionPlanMapper,
        SubscriptionRepository subscriptionRepository,
        SubscriptionMapper subscriptionMapper,
        UsageRecordRepository usageRecordRepository,
        UsageRecordMapper usageRecordMapper,
        CallerContext caller,
        OrganisationRepository organisationRepository) {

        super(
            auditTrailService,
            subscriptionPlanRepository,
            subscriptionPlanMapper,
            subscriptionRepository,
            subscriptionMapper,
            usageRecordRepository,
            usageRecordMapper);
        this.caller = caller;
        this.organisationRepository = organisationRepository;
    }

    /**
     * @see bw.co.starsign.subscription.SubscriptionService#findPlans(Boolean)
     */
    @Override
    protected List<SubscriptionPlanDTO> handleFindPlans(Boolean includeInactive)
        throws Exception
    {
        // Inactive and private plans are for platform administrators; everyone else sees what is on offer.
        // The caller is only consulted when asked for more: registration calls this before anyone signs in.
        if (Boolean.TRUE.equals(includeInactive) && caller.has("subscriptions:administer"))
        {
            return subscriptionPlanMapper.toSubscriptionPlanDTOCollection(
                    subscriptionPlanRepository.findAllByOrderByMonthlyPriceAscNameAsc());
        }
        return subscriptionPlanMapper.toSubscriptionPlanDTOCollection(
                subscriptionPlanRepository.findByActiveTrueAndPublicPlanTrueOrderByMonthlyPriceAscNameAsc());
    }

    /**
     * @see bw.co.starsign.subscription.SubscriptionService#savePlan(@Valid SubscriptionPlanDTO)
     */
    @Override
    protected SubscriptionPlanDTO handleSavePlan(@Valid SubscriptionPlanDTO plan)
        throws Exception
    {
        if (!caller.has("subscriptions:administer"))
        {
            throw new SubscriptionServiceException("Only a platform administrator can change subscription plans");
        }
        checkPlan(plan);
        String code = plan.getCode().trim().toUpperCase(Locale.ROOT);

        SubscriptionPlan entity;
        if (StringUtils.isBlank(plan.getId()))
        {
            if (subscriptionPlanRepository.findByCodeIgnoreCase(code).isPresent())
            {
                throw new SubscriptionServiceException("There is already a plan with the code " + code);
            }
            entity = SubscriptionPlan.Factory.newInstance();
            entity.setCode(code);
            entity.setCreatedBy(caller.userId());
            entity.setCreatedAt(LocalDateTime.now());
        }
        else
        {
            entity = subscriptionPlanRepository.findById(uuid(plan.getId(), "plan"))
                    .orElseThrow(() -> SubscriptionServiceException.notFound("The plan does not exist"));
            // Subscriptions, invoices and the portal refer to plans by code
            if (!entity.getCode().equalsIgnoreCase(code))
            {
                throw new SubscriptionServiceException("The code of a plan cannot be changed");
            }
            entity.setModifiedBy(caller.userId());
            entity.setModifiedAt(LocalDateTime.now());
        }
        entity.setName(plan.getName().trim());
        entity.setDescription(StringUtils.trimToNull(plan.getDescription()));
        entity.setCurrency(plan.getCurrency().trim().toUpperCase(Locale.ROOT));
        entity.setMonthlyPrice(plan.getMonthlyPrice());
        entity.setAnnualPrice(plan.getAnnualPrice());
        entity.setMaxUsers(plan.getMaxUsers());
        entity.setMaxDocumentsPerMonth(plan.getMaxDocumentsPerMonth());
        entity.setMaxWorkflowsPerMonth(plan.getMaxWorkflowsPerMonth());
        entity.setMaxStorageBytes(plan.getMaxStorageBytes());
        entity.setMaxSmsPerMonth(plan.getMaxSmsPerMonth());
        entity.setDigitalSignaturesEnabled(Boolean.TRUE.equals(plan.getDigitalSignaturesEnabled()));
        entity.setTemplatesEnabled(Boolean.TRUE.equals(plan.getTemplatesEnabled()));
        entity.setApiAccessEnabled(Boolean.TRUE.equals(plan.getApiAccessEnabled()));
        entity.setTrialDays(plan.getTrialDays() == null ? 0 : plan.getTrialDays());
        entity.setActive(plan.getActive() == null || plan.getActive());
        entity.setPublicPlan(plan.getPublicPlan() == null || plan.getPublicPlan());

        SubscriptionPlan saved = subscriptionPlanRepository.save(entity);
        audit(null, "SubscriptionPlan", saved.getId().toString(), "Subscription plan " + saved.getCode() + " saved", true);
        return subscriptionPlanMapper.toSubscriptionPlanDTO(saved);
    }

    /**
     * @see bw.co.starsign.subscription.SubscriptionService#getSubscription(String)
     */
    @Override
    protected SubscriptionDTO handleGetSubscription(String organisationId)
        throws Exception
    {
        Organisation organisation = loadVisible(organisationId);
        Subscription subscription = current(organisation)
                .orElseThrow(() -> SubscriptionServiceException.notFound("The organisation has no subscription"));
        return toDTO(subscription);
    }

    /**
     * @see bw.co.starsign.subscription.SubscriptionService#startTrial(String, String)
     */
    @Override
    protected SubscriptionDTO handleStartTrial(String organisationId, String planCode)
        throws Exception
    {
        // Called by the registration, before anybody is signed in: no caller checks here
        Organisation organisation = loadOrganisation(organisationId);
        SubscriptionPlan plan = activePlan(planCode);
        current(organisation).filter(subscription -> isUsable(subscription.getStatus())).ifPresent(subscription -> {
            throw new SubscriptionServiceException("The organisation already has a subscription");
        });

        LocalDate today = LocalDate.now();
        Subscription subscription = Subscription.Factory.newInstance();
        subscription.setOrganisation(organisation);
        subscription.setPlan(plan);
        subscription.setBillingCycle(BillingCycle.MONTHLY);
        subscription.setStartDate(today);
        subscription.setAutoRenew(true);
        if (plan.getTrialDays() > 0)
        {
            subscription.setStatus(SubscriptionStatus.TRIAL);
            subscription.setTrialEndsAt(today.plusDays(plan.getTrialDays()));
            subscription.setCurrentPeriodStart(today);
            subscription.setCurrentPeriodEnd(today.plusDays(plan.getTrialDays()));
        }
        else
        {
            // A plan without a trial (a free plan) starts right away
            subscription.setStatus(SubscriptionStatus.ACTIVE);
            subscription.setCurrentPeriodStart(today);
            subscription.setCurrentPeriodEnd(periodEnd(today, BillingCycle.MONTHLY));
        }
        subscription.setCreatedBy("system");
        subscription.setCreatedAt(LocalDateTime.now());

        Subscription saved = subscriptionRepository.save(subscription);
        audit(organisation, "Subscription", saved.getId().toString(),
                "Subscription to " + plan.getCode() + " started as " + saved.getStatus(), false);
        return toDTO(saved);
    }

    /**
     * @see bw.co.starsign.subscription.SubscriptionService#changePlan(String, @Valid PlanChangeDTO)
     */
    @Override
    protected SubscriptionDTO handleChangePlan(String organisationId, @Valid PlanChangeDTO change)
        throws Exception
    {
        Organisation organisation = loadVisible(organisationId);
        if (StringUtils.isBlank(change.getPlanCode()))
        {
            throw new SubscriptionServiceException("Say which plan to change to");
        }
        SubscriptionPlan plan = activePlan(change.getPlanCode());
        if (!plan.isPublicPlan() && !caller.has("subscriptions:administer"))
        {
            throw SubscriptionServiceException.notFound("The plan " + plan.getCode() + " does not exist");
        }
        checkFits(organisation, plan);

        LocalDate today = LocalDate.now();
        Subscription subscription = current(organisation)
                .filter(existing -> existing.getStatus() != SubscriptionStatus.EXPIRED)
                .orElse(null);
        BillingCycle cycle = change.getBillingCycle();
        if (subscription == null || subscription.getStatus() == SubscriptionStatus.CANCELLED)
        {
            // Nothing in force (or ending): a new subscription starts today
            subscription = Subscription.Factory.newInstance();
            subscription.setOrganisation(organisation);
            subscription.setStatus(SubscriptionStatus.ACTIVE);
            subscription.setBillingCycle(cycle == null ? BillingCycle.MONTHLY : cycle);
            subscription.setStartDate(today);
            subscription.setCurrentPeriodStart(today);
            subscription.setCurrentPeriodEnd(periodEnd(today, subscription.getBillingCycle()));
            subscription.setAutoRenew(true);
            subscription.setCreatedBy(caller.userId());
            subscription.setCreatedAt(LocalDateTime.now());
        }
        else
        {
            if (subscription.getPlan().equals(plan) && (cycle == null || cycle == subscription.getBillingCycle()))
            {
                throw new SubscriptionServiceException("The organisation is already on the " + plan.getName() + " plan");
            }
            // A trial stays a trial until it ends; a new billing cycle starts a new period
            if (subscription.getStatus() != SubscriptionStatus.TRIAL && cycle != null
                    && cycle != subscription.getBillingCycle())
            {
                subscription.setBillingCycle(cycle);
                subscription.setCurrentPeriodStart(today);
                subscription.setCurrentPeriodEnd(periodEnd(today, cycle));
            }
            else if (cycle != null)
            {
                subscription.setBillingCycle(cycle);
            }
            subscription.setModifiedBy(caller.userId());
            subscription.setModifiedAt(LocalDateTime.now());
        }
        String previous = subscription.getPlan() == null ? null : subscription.getPlan().getCode();
        subscription.setPlan(plan);

        Subscription saved = subscriptionRepository.save(subscription);
        audit(organisation, "Subscription", saved.getId().toString(),
                previous == null ? "Subscribed to " + plan.getCode()
                        : "Plan changed from " + previous + " to " + plan.getCode(), true);
        return toDTO(saved);
    }

    /**
     * @see bw.co.starsign.subscription.SubscriptionService#cancel(String)
     */
    @Override
    protected SubscriptionDTO handleCancel(String organisationId)
        throws Exception
    {
        Organisation organisation = loadVisible(organisationId);
        Subscription subscription = current(organisation)
                .orElseThrow(() -> SubscriptionServiceException.notFound("The organisation has no subscription"));
        if (subscription.getStatus() == SubscriptionStatus.CANCELLED || subscription.getStatus() == SubscriptionStatus.EXPIRED)
        {
            throw new SubscriptionServiceException("The subscription has already ended or been cancelled");
        }
        // What has been paid for stays usable until the end of the period
        subscription.setStatus(SubscriptionStatus.CANCELLED);
        subscription.setCancelledAt(LocalDateTime.now());
        subscription.setAutoRenew(false);
        // A trial's period is the trial itself
        subscription.setEndDate(subscription.getCurrentPeriodEnd());
        subscription.setModifiedBy(caller.userId());
        subscription.setModifiedAt(LocalDateTime.now());

        Subscription saved = subscriptionRepository.save(subscription);
        audit(organisation, "Subscription", saved.getId().toString(),
                "Subscription cancelled; it ends on " + saved.getEndDate(), true);
        return toDTO(saved);
    }

    /**
     * @see bw.co.starsign.subscription.SubscriptionService#recordUsage(String, UsageMetric, long, String)
     */
    @Override
    protected void handleRecordUsage(String organisationId, UsageMetric metric, long quantity, String sourceReference)
        throws Exception
    {
        if (quantity == 0)
        {
            return;
        }
        if (quantity < 0 && !RUNNING_TOTALS.contains(metric))
        {
            throw new SubscriptionServiceException("Only storage and users can be given back: " + metric
                    + " usage cannot be negative");
        }
        // The source reference makes metering idempotent: a retried event is counted once
        String reference = sourceReference.trim();
        if (usageRecordRepository.existsBySourceReference(reference))
        {
            logger.debug("Usage {} was already recorded", reference);
            return;
        }
        Organisation organisation = loadOrganisation(organisationId);
        LocalDateTime now = LocalDateTime.now();
        LocalDate month = now.toLocalDate().withDayOfMonth(1);

        UsageRecord record = UsageRecord.Factory.newInstance();
        record.setOrganisation(organisation);
        record.setMetric(metric);
        record.setQuantity(quantity);
        record.setPeriodStart(month);
        record.setPeriodEnd(month.plusMonths(1).minusDays(1));
        record.setRecordedAt(now);
        record.setSourceReference(reference);
        record.setCreatedBy("system");
        record.setCreatedAt(now);
        usageRecordRepository.save(record);
    }

    /**
     * @see bw.co.starsign.subscription.SubscriptionService#getUsageSummary(String, LocalDate, LocalDate)
     */
    @Override
    protected UsageSummaryDTO handleGetUsageSummary(String organisationId, LocalDate from, LocalDate to)
        throws Exception
    {
        if (to.isBefore(from))
        {
            throw new SubscriptionServiceException("The period ends before it starts");
        }
        Organisation organisation = loadVisible(organisationId);
        SubscriptionPlan plan = current(organisation).map(Subscription::getPlan).orElse(null);
        // Monthly limits add up over the months the period touches
        long months = ChronoUnit.MONTHS.between(from.withDayOfMonth(1), to.withDayOfMonth(1)) + 1;

        List<UsageMetricValueDTO> metrics = new ArrayList<UsageMetricValueDTO>();
        for (UsageMetric metric : UsageMetric.values())
        {
            long used = used(organisation, metric, from.atStartOfDay(), to.plusDays(1).atStartOfDay());
            Long limit = plan == null ? null : limit(plan, metric);
            if (limit != null && MONTHLY.contains(metric))
            {
                limit = limit * months;
            }
            Double percentage = limit == null ? null
                    : limit == 0 ? (used > 0 ? 100.0 : 0.0) : Math.round(used * 10000.0 / limit) / 100.0;
            metrics.add(new UsageMetricValueDTO(metric, used, limit, percentage));
        }
        return new UsageSummaryDTO(organisation.getId().toString(), from, to, metrics);
    }

    /**
     * @see bw.co.starsign.subscription.SubscriptionService#checkLimit(String, UsageMetric, long)
     */
    @Override
    protected boolean handleCheckLimit(String organisationId, UsageMetric metric, long additionalQuantity)
        throws Exception
    {
        // Called by the other services before they do something metered; the caller was checked by them
        Organisation organisation = loadOrganisation(organisationId);
        Optional<Subscription> subscription = current(organisation);
        if (subscription.isEmpty() || !isUsable(subscription.get().getStatus()))
        {
            return false;
        }
        SubscriptionPlan plan = subscription.get().getPlan();
        if ((metric == UsageMetric.DIGITAL_SIGNATURES && !plan.isDigitalSignaturesEnabled())
                || (metric == UsageMetric.API_CALLS && !plan.isApiAccessEnabled()))
        {
            return false;
        }
        Long limit = limit(plan, metric);
        if (limit == null)
        {
            return true;
        }
        LocalDate month = LocalDate.now().withDayOfMonth(1);
        long used = used(organisation, metric, month.atStartOfDay(), month.plusMonths(1).atStartOfDay());
        return used + Math.max(additionalQuantity, 0) <= limit;
    }

    private Organisation loadOrganisation(String organisationId)
    {
        return organisationRepository.findById(uuid(organisationId, "organisation"))
                .filter(organisation -> organisation.getDeletedAt() == null)
                .orElseThrow(() -> SubscriptionServiceException.notFound("The organisation does not exist"));
    }

    /** The organisation, when the caller may see it: organisation users only see their own. */
    private Organisation loadVisible(String organisationId)
    {
        Organisation organisation = loadOrganisation(organisationId);
        caller.tenantId().ifPresent(tenantId -> {
            if (!tenantId.equals(organisation.getTenantId()))
            {
                throw SubscriptionServiceException.notFound("The organisation does not exist");
            }
        });
        return organisation;
    }

    private SubscriptionPlan activePlan(String planCode)
    {
        SubscriptionPlan plan = subscriptionPlanRepository.findByCodeIgnoreCase(planCode.trim())
                .orElseThrow(() -> SubscriptionServiceException.notFound("The plan " + planCode.trim() + " does not exist"));
        if (!plan.isActive())
        {
            throw new SubscriptionServiceException("The plan " + plan.getName() + " is no longer offered");
        }
        return plan;
    }

    /**
     * The organisation's latest subscription, brought up to date: trials and cancelled subscriptions end,
     * renewing ones move on to the period that contains today.
     */
    private Optional<Subscription> current(Organisation organisation)
    {
        Optional<Subscription> found =
                subscriptionRepository.findFirstByOrganisationIdOrderByStartDateDescCreatedAtDesc(organisation.getId());
        found.ifPresent(this::bringUpToDate);
        return found;
    }

    private void bringUpToDate(Subscription subscription)
    {
        LocalDate today = LocalDate.now();
        SubscriptionStatus before = subscription.getStatus();
        LocalDate periodEndBefore = subscription.getCurrentPeriodEnd();
        switch (before)
        {
            case TRIAL:
                // There is no payment integration: an unconverted trial simply ends
                if (subscription.getTrialEndsAt() != null && today.isAfter(subscription.getTrialEndsAt()))
                {
                    subscription.setStatus(SubscriptionStatus.EXPIRED);
                    subscription.setEndDate(subscription.getTrialEndsAt());
                }
                break;
            case ACTIVE:
            case PAST_DUE:
                if (today.isAfter(subscription.getCurrentPeriodEnd()))
                {
                    if (subscription.isAutoRenew())
                    {
                        while (today.isAfter(subscription.getCurrentPeriodEnd()))
                        {
                            LocalDate start = subscription.getCurrentPeriodEnd().plusDays(1);
                            subscription.setCurrentPeriodStart(start);
                            subscription.setCurrentPeriodEnd(periodEnd(start, subscription.getBillingCycle()));
                        }
                    }
                    else
                    {
                        subscription.setStatus(SubscriptionStatus.EXPIRED);
                        subscription.setEndDate(subscription.getCurrentPeriodEnd());
                    }
                }
                break;
            case CANCELLED:
                if (subscription.getEndDate() != null && today.isAfter(subscription.getEndDate()))
                {
                    subscription.setStatus(SubscriptionStatus.EXPIRED);
                }
                break;
            default:
                break;
        }
        if (subscription.getStatus() != before || !subscription.getCurrentPeriodEnd().equals(periodEndBefore))
        {
            subscription.setModifiedBy("system");
            subscription.setModifiedAt(LocalDateTime.now());
            subscriptionRepository.save(subscription);
        }
    }

    private static boolean isUsable(SubscriptionStatus status)
    {
        return USABLE.contains(status);
    }

    private static LocalDate periodEnd(LocalDate start, BillingCycle cycle)
    {
        return (cycle == BillingCycle.ANNUAL ? start.plusYears(1) : start.plusMonths(1)).minusDays(1);
    }

    /** The plan's limit for the metric; null when it is unlimited. */
    private static Long limit(SubscriptionPlan plan, UsageMetric metric)
    {
        switch (metric)
        {
            case USERS:
                return plan.getMaxUsers() == null ? null : plan.getMaxUsers().longValue();
            case DOCUMENTS_UPLOADED:
                return plan.getMaxDocumentsPerMonth() == null ? null : plan.getMaxDocumentsPerMonth().longValue();
            case WORKFLOWS_INITIATED:
                return plan.getMaxWorkflowsPerMonth() == null ? null : plan.getMaxWorkflowsPerMonth().longValue();
            case STORAGE_BYTES:
                return plan.getMaxStorageBytes();
            case SMS_SENT:
                return plan.getMaxSmsPerMonth() == null ? null : plan.getMaxSmsPerMonth().longValue();
            case DIGITAL_SIGNATURES:
                return plan.isDigitalSignaturesEnabled() ? null : 0L;
            case API_CALLS:
                return plan.isApiAccessEnabled() ? null : 0L;
            default:
                return null;
        }
    }

    /**
     * What the organisation used: users and storage are what it holds at the end of the period (users as they
     * are now), the other metrics what was recorded within the period.
     */
    private long used(Organisation organisation, UsageMetric metric, LocalDateTime from, LocalDateTime to)
    {
        switch (metric)
        {
            case USERS:
                return usageRecordRepository.countUsers(organisation.getId(), UserStatus.DEACTIVATED);
            case STORAGE_BYTES:
                return Math.max(0, usageRecordRepository.sumQuantityBefore(organisation.getId(), metric, to));
            default:
                return usageRecordRepository.sumQuantity(organisation.getId(), metric, from, to);
        }
    }

    /** A smaller plan must still hold what the organisation already has. */
    private void checkFits(Organisation organisation, SubscriptionPlan plan)
    {
        List<String> errors = new ArrayList<String>();
        LocalDateTime tomorrow = LocalDate.now().plusDays(1).atStartOfDay();
        long users = used(organisation, UsageMetric.USERS, null, tomorrow);
        if (plan.getMaxUsers() != null && users > plan.getMaxUsers())
        {
            errors.add("The organisation has " + users + " users and the plan allows " + plan.getMaxUsers()
                    + ": deactivate users first");
        }
        long storage = used(organisation, UsageMetric.STORAGE_BYTES, null, tomorrow);
        if (plan.getMaxStorageBytes() != null && storage > plan.getMaxStorageBytes())
        {
            errors.add("The organisation stores more than the plan allows: remove documents first");
        }
        if (!errors.isEmpty())
        {
            throw new SubscriptionServiceException(String.join("; ", errors));
        }
    }

    private static void checkPlan(SubscriptionPlanDTO plan)
    {
        List<String> errors = new ArrayList<String>();
        if (StringUtils.isBlank(plan.getCode()) || !CODE.matcher(plan.getCode().trim().toUpperCase(Locale.ROOT)).matches())
        {
            errors.add("The code is required: up to 50 letters, digits, '-' or '_'");
        }
        if (StringUtils.isBlank(plan.getName()))
        {
            errors.add("The name is required");
        }
        if (StringUtils.isBlank(plan.getCurrency()) || !validCurrency(plan.getCurrency()))
        {
            errors.add("The currency must be an ISO 4217 code such as BWP");
        }
        if (plan.getMonthlyPrice() == null || plan.getMonthlyPrice().compareTo(BigDecimal.ZERO) < 0)
        {
            errors.add("The monthly price is required and cannot be negative");
        }
        if (plan.getAnnualPrice() == null || plan.getAnnualPrice().compareTo(BigDecimal.ZERO) < 0)
        {
            errors.add("The annual price is required and cannot be negative");
        }
        if (negative(plan.getMaxUsers()) || negative(plan.getMaxDocumentsPerMonth())
                || negative(plan.getMaxWorkflowsPerMonth()) || negative(plan.getMaxSmsPerMonth())
                || (plan.getMaxStorageBytes() != null && plan.getMaxStorageBytes() < 0))
        {
            errors.add("Limits cannot be negative (leave a limit empty for unlimited)");
        }
        if (plan.getMaxUsers() != null && plan.getMaxUsers() < 1)
        {
            errors.add("A plan must allow at least one user");
        }
        if (plan.getTrialDays() != null && (plan.getTrialDays() < 0 || plan.getTrialDays() > 365))
        {
            errors.add("Trials last 0 to 365 days");
        }
        if (!errors.isEmpty())
        {
            throw new SubscriptionServiceException(String.join("; ", errors));
        }
    }

    private static boolean negative(Integer value)
    {
        return value != null && value < 0;
    }

    private static boolean validCurrency(String code)
    {
        try
        {
            Currency.getInstance(code.trim().toUpperCase(Locale.ROOT));
            return true;
        }
        catch (IllegalArgumentException e)
        {
            return false;
        }
    }

    private SubscriptionDTO toDTO(Subscription subscription)
    {
        SubscriptionDTO dto = subscriptionMapper.toSubscriptionDTO(subscription);
        dto.setOrganisationId(subscription.getOrganisation().getId().toString());
        return dto;
    }

    private static UUID uuid(String id, String what)
    {
        try
        {
            return UUID.fromString(id.trim());
        }
        catch (IllegalArgumentException e)
        {
            throw SubscriptionServiceException.notFound("'" + id + "' is not the id of an " + what);
        }
    }

    /**
     * Records a change to plans or subscriptions. {@code byCaller} is false for changes the system makes on its
     * own, such as the trial started during registration, when nobody is signed in.
     */
    private void audit(Organisation organisation, String entityType, String entityId, String details, boolean byCaller)
    {
        AuditEventDTO event = new AuditEventDTO();
        event.setChainKey(organisation == null ? "platform" : "organisation:" + organisation.getId());
        event.setEventType(AuditEventType.SETTINGS_CHANGED);
        event.setOccurredAt(LocalDateTime.now());
        event.setOrganisationId(organisation == null ? null : organisation.getId().toString());
        event.setActorType(ActorType.SYSTEM);
        if (byCaller)
        {
            event.setActorId(caller.userId());
            event.setActorType(caller.tenantId().isPresent() ? ActorType.ORGANISATION_USER : ActorType.PLATFORM_ADMIN);
        }
        event.setOutcome(AuditOutcome.SUCCESS);
        event.setEntityType(entityType);
        event.setEntityId(entityId);
        event.setDetails(details);
        auditTrailService.record(event);
    }
}
