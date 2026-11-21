// license-header java merge-point
/**
 * This is only generated once! It will never be overwritten.
 * You can (and have to!) safely modify it by hand.
 * TEMPLATE:    SpringServiceImpl.vsl in andromda-spring cartridge
 * MODEL CLASS: AndroMDAModel::backend::bw.co.starsign::reporting::ReportingService
 * STEREOTYPE:  Service
 */
package bw.co.starsign.reporting;

import bw.co.starsign.audit.AuditEvent;
import bw.co.starsign.audit.AuditEventDTO;
import bw.co.starsign.audit.AuditEventMapper;
import bw.co.starsign.audit.AuditEventRepository;
import bw.co.starsign.context.CallerContext;
import bw.co.starsign.document.DocumentListDTO;
import bw.co.starsign.document.DocumentMapper;
import bw.co.starsign.document.DocumentRepository;
import bw.co.starsign.document.DocumentStatus;
import bw.co.starsign.identity.OrganisationUserMapper;
import bw.co.starsign.identity.OrganisationUserRepository;
import bw.co.starsign.identity.UserStatus;
import bw.co.starsign.notification.NotificationStatus;
import bw.co.starsign.organisation.OrganisationMapper;
import bw.co.starsign.organisation.OrganisationRepository;
import bw.co.starsign.organisation.OrganisationStatus;
import bw.co.starsign.storage.StorageService;
import bw.co.starsign.storage.StorageStatus;
import bw.co.starsign.storage.StorageUsageDTO;
import bw.co.starsign.workflow.ExecutionMode;
import bw.co.starsign.workflow.SignatoryMapper;
import bw.co.starsign.workflow.SignatoryRepository;
import bw.co.starsign.workflow.SignatoryStatus;
import bw.co.starsign.workflow.SigningWorkflowListDTO;
import bw.co.starsign.workflow.SigningWorkflowMapper;
import bw.co.starsign.workflow.SigningWorkflowRepository;
import bw.co.starsign.workflow.WorkflowStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;
import jakarta.validation.Valid;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Dashboards and reports, computed with aggregate JPQL over the other packages' entities (read-only).
 * <p>
 * Tenant isolation: organisation users only get their own organisation's figures; owner-scoped users
 * ({@code documents:list-own}) only their own documents and workflows. Platform administrators get
 * aggregated figures only, never document titles, people or audit details.
 * <p>
 * Period reports follow a cohort view: "created" counts documents uploaded in the period, "sent" the
 * workflows initiated in it, and signed/pending/declined/expired/cancelled what has become of those
 * workflows since.
 *
 * @see bw.co.starsign.reporting.ReportingService
 */
@Service("reportingService")
public class ReportingServiceImpl
    extends ReportingServiceBase
{
    private static final int RECENT_DOCUMENTS = 5;
    private static final int RECENT_AUDIT_EVENTS = 10;
    private static final int EXPIRING_SOON = 10;
    private static final int EXPIRING_WITHIN_DAYS = 7;
    private static final int DASHBOARD_DURATION_DAYS = 90;
    private static final int MAX_ROWS = 1000;
    private static final long MAX_PERIODS = 1500;

    private static final Set<WorkflowStatus> PENDING_WORKFLOW = Set.of(
            WorkflowStatus.IN_PROGRESS, WorkflowStatus.COMPLETING, WorkflowStatus.FAILED);
    private static final Set<SignatoryStatus> OPEN_SIGNATORY = Set.of(
            SignatoryStatus.INVITED, SignatoryStatus.VIEWED, SignatoryStatus.VERIFIED);
    private static final Set<StorageStatus> OCCUPYING_STORAGE = Set.of(
            StorageStatus.STORED, StorageStatus.VERIFIED, StorageStatus.PENDING_DELETION, StorageStatus.ORPHANED);
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final CallerContext caller;
    private final TransactionTemplate readOnly;

    @PersistenceContext
    private EntityManager entityManager;

    public ReportingServiceImpl(
        @Lazy StorageService storageService,
        DocumentRepository documentRepository,
        DocumentMapper documentMapper,
        SigningWorkflowRepository signingWorkflowRepository,
        SigningWorkflowMapper signingWorkflowMapper,
        SignatoryRepository signatoryRepository,
        SignatoryMapper signatoryMapper,
        AuditEventRepository auditEventRepository,
        AuditEventMapper auditEventMapper,
        OrganisationUserRepository organisationUserRepository,
        OrganisationUserMapper organisationUserMapper,
        OrganisationRepository organisationRepository,
        OrganisationMapper organisationMapper,
        CallerContext caller,
        PlatformTransactionManager transactionManager) {

        super(
            storageService,
            documentRepository,
            documentMapper,
            signingWorkflowRepository,
            signingWorkflowMapper,
            signatoryRepository,
            signatoryMapper,
            auditEventRepository,
            auditEventMapper,
            organisationUserRepository,
            organisationUserMapper,
            organisationRepository,
            organisationMapper);
        this.caller = caller;
        // The public methods live on the generated base class, out of reach of a class-level @Transactional
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        this.readOnly.setReadOnly(true);
    }

    /**
     * @see bw.co.starsign.reporting.ReportingService#getDashboard(String)
     */
    @Override
    protected DashboardSummaryDTO handleGetDashboard(String organisationId)
        throws Exception
    {
        Scope scope = scope(organisationId, null);
        return readOnly.execute(status -> {
            DashboardSummaryDTO dashboard = new DashboardSummaryDTO();
            dashboard.setOrganisationId(scope.organisationId().toString());

            Map<DocumentStatus, Long> documents = new EnumMap<>(DocumentStatus.class);
            query("select d.status, count(d) from Document d left join d.owner o"
                    + " where d.organisation.id = :organisation and d.deletedAt is null" + scope.ownerClause("o")
                    + " group by d.status", scope)
                    .getResultList()
                    .forEach(row -> documents.put((DocumentStatus) row[0], number(row[1])));
            dashboard.setTotalDocuments(documents.values().stream().mapToLong(Long::longValue).sum());
            dashboard.setAwaitingSignatures(documents.getOrDefault(DocumentStatus.PENDING_SIGNATURES, 0L));
            dashboard.setPartiallySigned(documents.getOrDefault(DocumentStatus.PARTIALLY_SIGNED, 0L));
            dashboard.setCompleted(documents.getOrDefault(DocumentStatus.COMPLETED, 0L));
            dashboard.setDeclined(documents.getOrDefault(DocumentStatus.DECLINED, 0L));
            dashboard.setExpired(documents.getOrDefault(DocumentStatus.EXPIRED, 0L));

            dashboard.setCancelledWorkflows(single(aggregate("select count(w) from SigningWorkflow w left join w.owner o"
                    + " where w.organisation.id = :organisation and w.status = :status" + scope.ownerClause("o"), scope)
                    .setParameter("status", WorkflowStatus.CANCELLED)));

            if (scope.userId() != null)
            {
                dashboard.setPendingActionsForMe(single(entityManager.createQuery(
                        "select count(s) from Signatory s join s.workflow w join s.internalUser u"
                        + " where w.organisation.id = :organisation and u.keycloakUserId = :me"
                        + " and s.status in :open and w.status = :inProgress")
                        .setParameter("organisation", scope.organisationId())
                        .setParameter("me", scope.userId())
                        .setParameter("open", OPEN_SIGNATORY)
                        .setParameter("inProgress", WorkflowStatus.IN_PROGRESS)));
            }
            else
            {
                dashboard.setPendingActionsForMe(0L);
            }

            List<Double> durations = hours(query("select w.initiatedAt, w.completedAt from SigningWorkflow w left join w.owner o"
                    + " where w.organisation.id = :organisation and w.status = :completed"
                    + " and w.initiatedAt is not null and w.completedAt >= :since" + scope.ownerClause("o"), scope)
                    .setParameter("completed", WorkflowStatus.COMPLETED)
                    .setParameter("since", LocalDateTime.now().minusDays(DASHBOARD_DURATION_DAYS))
                    .getResultList());
            dashboard.setAverageSigningDurationHours(average(durations));

            if (scope.platformAdministrator())
            {
                // Aggregates only: no titles, people or audit details of the tenant
                dashboard.setRecentDocuments(new ArrayList<>());
                dashboard.setRecentAuditEvents(new ArrayList<>());
                dashboard.setExpiringSoons(new ArrayList<>());
            }
            else
            {
                dashboard.setRecentDocuments(recentDocuments(scope));
                dashboard.setRecentAuditEvents(recentAuditEvents(scope));
                dashboard.setExpiringSoons(expiringSoon(scope));
            }
            return dashboard;
        });
    }

    /**
     * @see bw.co.starsign.reporting.ReportingService#documentVolume(@Valid ReportCriteria)
     */
    @Override
    protected List<DocumentVolumeRowDTO> handleDocumentVolume(@Valid ReportCriteria criteria)
        throws Exception
    {
        Scope scope = scope(criteria.getOrganisationId(), criteria.getOwnerId());
        Period period = period(criteria);
        return readOnly.execute(status -> volume(scope, period));
    }

    /**
     * Rates are fractions between 0 and 1 of the workflows sent in the period; durations are in hours.
     *
     * @see bw.co.starsign.reporting.ReportingService#signingPerformance(@Valid ReportCriteria)
     */
    @Override
    protected SigningPerformanceDTO handleSigningPerformance(@Valid ReportCriteria criteria)
        throws Exception
    {
        Scope scope = scope(criteria.getOrganisationId(), criteria.getOwnerId());
        Period period = period(criteria);
        return readOnly.execute(status -> {
            SigningPerformanceDTO performance = new SigningPerformanceDTO();
            List<DocumentVolumeRowDTO> rows = volume(scope, period);
            long sent = rows.stream().mapToLong(DocumentVolumeRowDTO::getSent).sum();
            performance.setCompletionRate(rate(rows.stream().mapToLong(DocumentVolumeRowDTO::getSigned).sum(), sent));
            performance.setDeclineRate(rate(rows.stream().mapToLong(DocumentVolumeRowDTO::getDeclined).sum(), sent));
            performance.setExpiryRate(rate(rows.stream().mapToLong(DocumentVolumeRowDTO::getExpired).sum(), sent));

            List<Double> durations = hours(query("select w.initiatedAt, w.completedAt from SigningWorkflow w left join w.owner o"
                    + " where w.organisation.id = :organisation and w.status = :completed and w.completedAt is not null"
                    + " and w.initiatedAt >= :start and w.initiatedAt < :end" + scope.ownerClause("o"), scope)
                    .setParameter("completed", WorkflowStatus.COMPLETED)
                    .setParameter("start", period.start())
                    .setParameter("end", period.end())
                    .getResultList());
            performance.setAverageSigningDurationHours(average(durations));
            performance.setMedianSigningDurationHours(median(durations));

            List<Double> firstViews = hours(query("select s.invitationSentAt, s.firstAccessedAt from Signatory s"
                    + " join s.workflow w left join w.owner o"
                    + " where w.organisation.id = :organisation and s.firstAccessedAt is not null"
                    + " and s.invitationSentAt >= :start and s.invitationSentAt < :end" + scope.ownerClause("o"), scope)
                    .setParameter("start", period.start())
                    .setParameter("end", period.end())
                    .getResultList());
            performance.setAverageTimeToFirstViewHours(average(firstViews));
            performance.setRows(rows);
            return performance;
        });
    }

    /**
     * @see bw.co.starsign.reporting.ReportingService#signatoryActivity(@Valid ReportCriteria)
     */
    @Override
    protected List<SignatoryActivityRowDTO> handleSignatoryActivity(@Valid ReportCriteria criteria)
        throws Exception
    {
        Scope scope = scope(criteria.getOrganisationId(), criteria.getOwnerId());
        scope.requireTenantView("Signatory activity names people; platform administrators get aggregated reports only");
        Period period = period(criteria);
        return readOnly.execute(status -> {
            Map<String, SignatoryActivityRowDTO> rows = new LinkedHashMap<>();
            query("select lower(s.emailAddress), max(s.fullName), count(s),"
                    + " sum(case when s.status = bw.co.starsign.workflow.SignatoryStatus.SIGNED then 1 else 0 end),"
                    + " sum(case when s.status = bw.co.starsign.workflow.SignatoryStatus.DECLINED then 1 else 0 end)"
                    + " from Signatory s join s.workflow w left join w.owner o"
                    + " where w.organisation.id = :organisation and s.invitationSentAt >= :start and s.invitationSentAt < :end"
                    + scope.ownerClause("o")
                    + " group by lower(s.emailAddress) order by count(s) desc", scope)
                    .setParameter("start", period.start())
                    .setParameter("end", period.end())
                    .setMaxResults(MAX_ROWS)
                    .getResultList()
                    .forEach(row -> {
                        SignatoryActivityRowDTO activity = new SignatoryActivityRowDTO();
                        activity.setSignatoryEmail((String) row[0]);
                        activity.setSignatoryName((String) row[1]);
                        activity.setInvited(number(row[2]));
                        activity.setSigned(number(row[3]));
                        activity.setDeclined(number(row[4]));
                        rows.put((String) row[0], activity);
                    });

            Map<String, List<Double>> responses = new HashMap<>();
            query("select lower(s.emailAddress), s.invitationSentAt, coalesce(s.signedAt, s.declinedAt) from Signatory s"
                    + " join s.workflow w left join w.owner o"
                    + " where w.organisation.id = :organisation and s.invitationSentAt >= :start and s.invitationSentAt < :end"
                    + " and (s.signedAt is not null or s.declinedAt is not null)" + scope.ownerClause("o"), scope)
                    .setParameter("start", period.start())
                    .setParameter("end", period.end())
                    .getResultList()
                    .forEach(row -> {
                        Double hours = hoursBetween(row[1], row[2]);
                        if (hours != null)
                        {
                            responses.computeIfAbsent((String) row[0], email -> new ArrayList<>()).add(hours);
                        }
                    });
            rows.forEach((email, activity) -> activity.setAverageResponseHours(average(responses.get(email))));
            return new ArrayList<>(rows.values());
        });
    }

    /**
     * @see bw.co.starsign.reporting.ReportingService#userActivity(@Valid ReportCriteria)
     */
    @Override
    protected List<UserActivityRowDTO> handleUserActivity(@Valid ReportCriteria criteria)
        throws Exception
    {
        Scope scope = scope(criteria.getOrganisationId(), criteria.getOwnerId());
        scope.requireTenantView("User activity names people; platform administrators get aggregated reports only");
        Period period = period(criteria);
        return readOnly.execute(status -> {
            Map<UUID, UserActivityRowDTO> rows = new LinkedHashMap<>();
            Map<String, UUID> byKeycloakId = new HashMap<>();
            query("select o.id, o.keycloakUserId, o.firstName, o.lastName, o.username, o.lastLoginAt from OrganisationUser o"
                    + " where o.organisation.id = :organisation and o.deletedAt is null" + scope.ownerClause("o")
                    + " order by o.firstName, o.lastName", scope)
                    .setMaxResults(MAX_ROWS)
                    .getResultList()
                    .forEach(row -> {
                        UserActivityRowDTO activity = new UserActivityRowDTO();
                        activity.setUserId(row[0].toString());
                        String name = StringUtils.trimToNull(StringUtils.defaultString((String) row[2]) + " "
                                + StringUtils.defaultString((String) row[3]));
                        activity.setUserName(name != null ? name : (String) row[4]);
                        activity.setDocumentsUploaded(0L);
                        activity.setWorkflowsInitiated(0L);
                        activity.setWorkflowsCompleted(0L);
                        activity.setLastActivityAt((LocalDateTime) row[5]);
                        rows.put((UUID) row[0], activity);
                        if (row[1] != null)
                        {
                            byKeycloakId.put((String) row[1], (UUID) row[0]);
                        }
                    });
            if (rows.isEmpty())
            {
                return new ArrayList<>();
            }

            countPerOwner("select o.id, count(d) from Document d join d.owner o where d.organisation.id = :organisation"
                    + " and d.createdAt >= :start and d.createdAt < :end and o.id in :users group by o.id", scope, period, rows.keySet())
                    .forEach((user, count) -> rows.get(user).setDocumentsUploaded(count));
            countPerOwner("select o.id, count(w) from SigningWorkflow w join w.owner o where w.organisation.id = :organisation"
                    + " and w.initiatedAt >= :start and w.initiatedAt < :end and o.id in :users group by o.id", scope, period, rows.keySet())
                    .forEach((user, count) -> rows.get(user).setWorkflowsInitiated(count));
            countPerOwner("select o.id, count(w) from SigningWorkflow w join w.owner o where w.organisation.id = :organisation"
                    + " and w.status = bw.co.starsign.workflow.WorkflowStatus.COMPLETED"
                    + " and w.completedAt >= :start and w.completedAt < :end and o.id in :users group by o.id", scope, period, rows.keySet())
                    .forEach((user, count) -> rows.get(user).setWorkflowsCompleted(count));

            String tenant = scope.tenantId();
            if (tenant != null && !byKeycloakId.isEmpty())
            {
                entityManager.createQuery("select a.actorId, max(a.occurredAt) from AuditEvent a"
                        + " where a.chainKey = :tenant and a.actorId in :actors group by a.actorId", Object[].class)
                        .setParameter("tenant", tenant)
                        .setParameter("actors", byKeycloakId.keySet())
                        .getResultList()
                        .forEach(row -> {
                            UserActivityRowDTO activity = rows.get(byKeycloakId.get((String) row[0]));
                            LocalDateTime last = (LocalDateTime) row[1];
                            if (activity != null && last != null
                                    && (activity.getLastActivityAt() == null || last.isAfter(activity.getLastActivityAt())))
                            {
                                activity.setLastActivityAt(last);
                            }
                        });
            }
            return new ArrayList<>(rows.values());
        });
    }

    /**
     * @see bw.co.starsign.reporting.ReportingService#storageUtilisation(String)
     */
    @Override
    protected StorageUsageDTO handleStorageUtilisation(String organisationId)
        throws Exception
    {
        Scope scope = scope(organisationId, null);
        return storageService.getUsage(scope.organisationId().toString());
    }

    /**
     * @see bw.co.starsign.reporting.ReportingService#platformStatistics(LocalDate, LocalDate)
     */
    @Override
    protected PlatformStatisticsDTO handlePlatformStatistics(LocalDate from, LocalDate to)
        throws Exception
    {
        if (callerTenant().isPresent())
        {
            throw new ReportingServiceException("Platform statistics are only available to platform administrators");
        }
        if (from.isAfter(to))
        {
            throw new ReportingServiceException("The start of the period cannot be after its end");
        }
        LocalDateTime start = from.atStartOfDay();
        LocalDateTime end = to.plusDays(1).atStartOfDay();
        return readOnly.execute(status -> {
            PlatformStatisticsDTO statistics = new PlatformStatisticsDTO();
            Map<String, Long> organisations = new LinkedHashMap<>();
            for (OrganisationStatus organisationStatus : OrganisationStatus.values())
            {
                organisations.put(organisationStatus.name(), 0L);
            }
            entityManager.createQuery("select o.status, count(o) from Organisation o where o.deletedAt is null group by o.status",
                    Object[].class)
                    .getResultList()
                    .forEach(row -> organisations.put(((OrganisationStatus) row[0]).name(), number(row[1])));
            statistics.setOrganisationsByStatus(organisations);

            statistics.setActiveUsers(single(entityManager.createQuery(
                    "select count(u) from OrganisationUser u where u.status = :active and u.deletedAt is null")
                    .setParameter("active", UserStatus.ACTIVE)));
            statistics.setDocumentsUploaded(countBetween("select count(d) from Document d where d.createdAt >= :start and d.createdAt < :end", start, end));
            statistics.setWorkflowsInitiated(countBetween(
                    "select count(w) from SigningWorkflow w where w.initiatedAt >= :start and w.initiatedAt < :end", start, end));
            statistics.setWorkflowsCompleted(countBetween("select count(w) from SigningWorkflow w"
                    + " where w.status = bw.co.starsign.workflow.WorkflowStatus.COMPLETED"
                    + " and w.completedAt >= :start and w.completedAt < :end", start, end));
            statistics.setSignaturesCompleted(countBetween(
                    "select count(s) from Signatory s where s.signedAt >= :start and s.signedAt < :end", start, end));
            statistics.setStorageBytes(single(entityManager.createQuery(
                    "select coalesce(sum(r.contentLength), 0) from DocumentStorageReference r where r.status in :occupying")
                    .setParameter("occupying", OCCUPYING_STORAGE)));
            statistics.setNotificationsFailed(single(entityManager.createQuery(
                    "select count(n) from Notification n where n.status = :failed"
                    + " and coalesce(n.failedAt, n.createdAt) >= :start and coalesce(n.failedAt, n.createdAt) < :end")
                    .setParameter("failed", NotificationStatus.FAILED)
                    .setParameter("start", start)
                    .setParameter("end", end)));
            return statistics;
        });
    }

    /**
     * Renders the report as CSV or PDF (the formats of {@link ReportFormat}).
     *
     * @see bw.co.starsign.reporting.ReportingService#export(ReportType, ReportFormat, @Valid ReportCriteria)
     */
    @Override
    protected Resource handleExport(ReportType reportType, ReportFormat format, @Valid ReportCriteria criteria)
        throws Exception
    {
        ReportCriteria report = criteria == null ? new ReportCriteria() : criteria;
        ReportTable table = switch (reportType)
        {
            case DOCUMENT_VOLUME -> volumeTable("Document volume", handleDocumentVolume(report));
            case SIGNING_PERFORMANCE -> performanceTable(handleSigningPerformance(report));
            case SIGNATORY_ACTIVITY -> signatoryTable(handleSignatoryActivity(report));
            case USER_ACTIVITY -> userTable(handleUserActivity(report));
            case STORAGE_UTILISATION -> storageTable(handleStorageUtilisation(report.getOrganisationId()));
            case PLATFORM_STATISTICS -> platformTable(report);
        };

        byte[] content;
        String extension;
        if (format == ReportFormat.PDF)
        {
            try
            {
                content = table.toPdf();
            }
            catch (IOException e)
            {
                throw new ReportingServiceException("The PDF report could not be produced", e);
            }
            extension = "pdf";
        }
        else
        {
            content = table.toCsv();
            extension = "csv";
        }
        String fileName = reportType.name().toLowerCase().replace('_', '-') + "-" + FILE_STAMP.format(LocalDateTime.now()) + "." + extension;
        return new ByteArrayResource(content)
        {
            @Override
            public String getFilename()
            {
                return fileName;
            }
        };
    }

    // ------------------------------------------------------------------ dashboard parts

    private List<DocumentListDTO> recentDocuments(Scope scope)
    {
        List<DocumentListDTO> documents = new ArrayList<>();
        query("select d.id, d.referenceNumber, d.title, d.status, o.firstName, o.lastName, d.pageCount, d.createdAt, d.completedAt"
                + " from Document d left join d.owner o"
                + " where d.organisation.id = :organisation and d.deletedAt is null" + scope.ownerClause("o")
                + " order by d.createdAt desc", scope)
                .setMaxResults(RECENT_DOCUMENTS)
                .getResultList()
                .forEach(row -> {
                    DocumentListDTO document = new DocumentListDTO();
                    document.setId(row[0].toString());
                    document.setReferenceNumber((String) row[1]);
                    document.setTitle((String) row[2]);
                    document.setStatus((DocumentStatus) row[3]);
                    document.setOwnerName(name(row[4], row[5]));
                    document.setPageCount(row[6] == null ? null : ((Number) row[6]).intValue());
                    document.setCreatedAt((LocalDateTime) row[7]);
                    document.setCompletedAt((LocalDateTime) row[8]);
                    document.setSignatoryCount(0);
                    document.setSignedCount(0);
                    documents.add(document);
                });
        if (!documents.isEmpty())
        {
            Map<String, DocumentListDTO> byId = new HashMap<>();
            documents.forEach(document -> byId.put(document.getId(), document));
            entityManager.createQuery("select w.document.id, count(s),"
                    + " sum(case when s.status = bw.co.starsign.workflow.SignatoryStatus.SIGNED then 1 else 0 end)"
                    + " from Signatory s join s.workflow w where w.document.id in :documents"
                    + " and w.status <> bw.co.starsign.workflow.WorkflowStatus.CANCELLED group by w.document.id", Object[].class)
                    .setParameter("documents", ids(byId.keySet()))
                    .getResultList()
                    .forEach(row -> {
                        DocumentListDTO document = byId.get(row[0].toString());
                        document.setSignatoryCount((int) number(row[1]));
                        document.setSignedCount((int) number(row[2]));
                    });
        }
        return documents;
    }

    private List<AuditEventDTO> recentAuditEvents(Scope scope)
    {
        if (scope.tenantId() == null)
        {
            return new ArrayList<>();
        }
        TypedQuery<AuditEvent> events = entityManager.createQuery("select a from AuditEvent a where a.chainKey = :tenant"
                + (scope.ownerId() != null ? " and a.actorId = :owner" : "")
                + " order by a.occurredAt desc, a.sequenceNumber desc", AuditEvent.class)
                .setParameter("tenant", scope.tenantId())
                .setMaxResults(RECENT_AUDIT_EVENTS);
        if (scope.ownerId() != null)
        {
            events.setParameter("owner", scope.ownerId());
        }
        return auditEventMapper.toAuditEventDTOCollection(events.getResultList());
    }

    private List<SigningWorkflowListDTO> expiringSoon(Scope scope)
    {
        LocalDateTime now = LocalDateTime.now();
        List<SigningWorkflowListDTO> workflows = new ArrayList<>();
        query("select w.id, w.referenceNumber, w.title, d.title, w.status, w.executionMode, o.firstName, o.lastName,"
                + " w.initiatedAt, w.expiresAt, w.completedAt, w.currentStageNumber"
                + " from SigningWorkflow w left join w.document d left join w.owner o"
                + " where w.organisation.id = :organisation and w.status = :inProgress"
                + " and w.expiresAt >= :now and w.expiresAt < :soon" + scope.ownerClause("o")
                + " order by w.expiresAt", scope)
                .setParameter("inProgress", WorkflowStatus.IN_PROGRESS)
                .setParameter("now", now)
                .setParameter("soon", now.plusDays(EXPIRING_WITHIN_DAYS))
                .setMaxResults(EXPIRING_SOON)
                .getResultList()
                .forEach(row -> {
                    SigningWorkflowListDTO workflow = new SigningWorkflowListDTO();
                    workflow.setId(row[0].toString());
                    workflow.setReferenceNumber((String) row[1]);
                    workflow.setTitle((String) row[2]);
                    workflow.setDocumentTitle((String) row[3]);
                    workflow.setStatus((WorkflowStatus) row[4]);
                    workflow.setExecutionMode((ExecutionMode) row[5]);
                    workflow.setOwnerName(name(row[6], row[7]));
                    workflow.setInitiatedAt((LocalDateTime) row[8]);
                    workflow.setExpiresAt((LocalDateTime) row[9]);
                    workflow.setCompletedAt((LocalDateTime) row[10]);
                    workflow.setCurrentStageNumber(row[11] == null ? null : ((Number) row[11]).intValue());
                    workflow.setSignatoryCount(0);
                    workflow.setSignedCount(0);
                    workflow.setStageCount(0);
                    workflows.add(workflow);
                });
        if (!workflows.isEmpty())
        {
            Map<String, SigningWorkflowListDTO> byId = new HashMap<>();
            workflows.forEach(workflow -> byId.put(workflow.getId(), workflow));
            entityManager.createQuery("select s.workflow.id, count(s),"
                    + " sum(case when s.status = bw.co.starsign.workflow.SignatoryStatus.SIGNED then 1 else 0 end)"
                    + " from Signatory s where s.workflow.id in :workflows group by s.workflow.id", Object[].class)
                    .setParameter("workflows", ids(byId.keySet()))
                    .getResultList()
                    .forEach(row -> {
                        SigningWorkflowListDTO workflow = byId.get(row[0].toString());
                        workflow.setSignatoryCount((int) number(row[1]));
                        workflow.setSignedCount((int) number(row[2]));
                    });
            entityManager.createQuery("select st.workflow.id, count(st) from SigningStage st"
                    + " where st.workflow.id in :workflows group by st.workflow.id", Object[].class)
                    .setParameter("workflows", ids(byId.keySet()))
                    .getResultList()
                    .forEach(row -> byId.get(row[0].toString()).setStageCount((int) number(row[1])));
        }
        return workflows;
    }

    // ------------------------------------------------------------------ period reports

    /**
     * Counts per day in the database, folded into the requested grouping here; every period of the range
     * gets a row, also when nothing happened in it.
     */
    private List<DocumentVolumeRowDTO> volume(Scope scope, Period period)
    {
        Map<String, DocumentVolumeRowDTO> rows = new LinkedHashMap<>();
        for (LocalDate day = period.from(); !day.isAfter(period.to()); day = next(day, period.grouping()))
        {
            String key = label(day, period.grouping());
            DocumentVolumeRowDTO row = new DocumentVolumeRowDTO();
            row.setPeriod(key);
            row.setCreated(0L);
            row.setSent(0L);
            row.setSigned(0L);
            row.setPending(0L);
            row.setDeclined(0L);
            row.setExpired(0L);
            row.setCancelled(0L);
            rows.put(key, row);
        }

        query("select extract(year from d.createdAt), extract(month from d.createdAt), extract(day from d.createdAt), count(d)"
                + " from Document d left join d.owner o"
                + " where d.organisation.id = :organisation and d.createdAt >= :start and d.createdAt < :end" + scope.ownerClause("o")
                + " group by extract(year from d.createdAt), extract(month from d.createdAt), extract(day from d.createdAt)", scope)
                .setParameter("start", period.start())
                .setParameter("end", period.end())
                .getResultList()
                .forEach(row -> {
                    DocumentVolumeRowDTO volume = rows.get(label(day(row), period.grouping()));
                    if (volume != null)
                    {
                        volume.setCreated(volume.getCreated() + number(row[3]));
                    }
                });

        query("select extract(year from w.initiatedAt), extract(month from w.initiatedAt), extract(day from w.initiatedAt),"
                + " w.status, count(w) from SigningWorkflow w left join w.owner o"
                + " where w.organisation.id = :organisation and w.initiatedAt >= :start and w.initiatedAt < :end" + scope.ownerClause("o")
                + " group by extract(year from w.initiatedAt), extract(month from w.initiatedAt), extract(day from w.initiatedAt), w.status", scope)
                .setParameter("start", period.start())
                .setParameter("end", period.end())
                .getResultList()
                .forEach(row -> {
                    DocumentVolumeRowDTO volume = rows.get(label(day(row), period.grouping()));
                    if (volume == null)
                    {
                        return;
                    }
                    long count = number(row[4]);
                    WorkflowStatus workflowStatus = (WorkflowStatus) row[3];
                    volume.setSent(volume.getSent() + count);
                    if (workflowStatus == WorkflowStatus.COMPLETED)
                    {
                        volume.setSigned(volume.getSigned() + count);
                    }
                    else if (PENDING_WORKFLOW.contains(workflowStatus))
                    {
                        volume.setPending(volume.getPending() + count);
                    }
                    else if (workflowStatus == WorkflowStatus.DECLINED)
                    {
                        volume.setDeclined(volume.getDeclined() + count);
                    }
                    else if (workflowStatus == WorkflowStatus.EXPIRED)
                    {
                        volume.setExpired(volume.getExpired() + count);
                    }
                    else if (workflowStatus == WorkflowStatus.CANCELLED)
                    {
                        volume.setCancelled(volume.getCancelled() + count);
                    }
                });
        return new ArrayList<>(rows.values());
    }

    private Map<UUID, Long> countPerOwner(String jpql, Scope scope, Period period, Collection<UUID> users)
    {
        Map<UUID, Long> counts = new HashMap<>();
        entityManager.createQuery(jpql, Object[].class)
                .setParameter("organisation", scope.organisationId())
                .setParameter("start", period.start())
                .setParameter("end", period.end())
                .setParameter("users", users)
                .getResultList()
                .forEach(row -> counts.put((UUID) row[0], number(row[1])));
        return counts;
    }

    private Period period(ReportCriteria criteria)
    {
        ReportGrouping grouping = criteria.getGrouping() == null ? ReportGrouping.DAY : criteria.getGrouping();
        LocalDate to = criteria.getTo() == null ? LocalDate.now() : criteria.getTo();
        LocalDate from = criteria.getFrom() != null ? criteria.getFrom()
                : switch (grouping)
                {
                    case DAY -> to.minusDays(29);
                    case WEEK -> to.minusWeeks(11);
                    case MONTH -> to.minusMonths(11).withDayOfMonth(1);
                };
        if (from.isAfter(to))
        {
            throw new ReportingServiceException("The start of the period cannot be after its end");
        }
        long periods = switch (grouping)
        {
            case DAY -> ChronoUnit.DAYS.between(from, to);
            case WEEK -> ChronoUnit.WEEKS.between(from, to);
            case MONTH -> ChronoUnit.MONTHS.between(from, to);
        };
        if (periods > MAX_PERIODS)
        {
            throw new ReportingServiceException("The period is too long for this grouping; group by week or month instead");
        }
        // Weeks and months start on their first day so the first row covers a whole period
        LocalDate start = switch (grouping)
        {
            case DAY -> from;
            case WEEK -> from.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case MONTH -> from.withDayOfMonth(1);
        };
        return new Period(start, to, grouping);
    }

    private static LocalDate next(LocalDate day, ReportGrouping grouping)
    {
        return switch (grouping)
        {
            case DAY -> day.plusDays(1);
            case WEEK -> day.plusWeeks(1);
            case MONTH -> day.plusMonths(1);
        };
    }

    private static String label(LocalDate day, ReportGrouping grouping)
    {
        return switch (grouping)
        {
            case DAY -> day.toString();
            case WEEK -> String.format("%d-W%02d", day.get(IsoFields.WEEK_BASED_YEAR), day.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
            case MONTH -> MONTH.format(day);
        };
    }

    private static LocalDate day(Object[] row)
    {
        return LocalDate.of(((Number) row[0]).intValue(), ((Number) row[1]).intValue(), ((Number) row[2]).intValue());
    }

    // ------------------------------------------------------------------ export tables

    private static ReportTable volumeTable(String title, List<DocumentVolumeRowDTO> rows)
    {
        ReportTable table = new ReportTable(title, "Period", "Created", "Sent", "Signed", "Pending", "Declined", "Expired", "Cancelled");
        rows.forEach(row -> table.row(row.getPeriod(), row.getCreated(), row.getSent(), row.getSigned(), row.getPending(),
                row.getDeclined(), row.getExpired(), row.getCancelled()));
        return table;
    }

    private static ReportTable performanceTable(SigningPerformanceDTO performance)
    {
        ReportTable table = volumeTable("Signing performance", performance.getRows());
        table.summary("Completion rate", percent(performance.getCompletionRate()));
        table.summary("Decline rate", percent(performance.getDeclineRate()));
        table.summary("Expiry rate", percent(performance.getExpiryRate()));
        table.summary("Average signing time (hours)", performance.getAverageSigningDurationHours());
        table.summary("Median signing time (hours)", performance.getMedianSigningDurationHours());
        table.summary("Average time to first view (hours)", performance.getAverageTimeToFirstViewHours());
        return table;
    }

    private static ReportTable signatoryTable(List<SignatoryActivityRowDTO> rows)
    {
        ReportTable table = new ReportTable("Signatory activity", "Email", "Name", "Invited", "Signed", "Declined", "Average response (hours)");
        rows.forEach(row -> table.row(row.getSignatoryEmail(), row.getSignatoryName(), row.getInvited(), row.getSigned(),
                row.getDeclined(), row.getAverageResponseHours()));
        return table;
    }

    private static ReportTable userTable(List<UserActivityRowDTO> rows)
    {
        ReportTable table = new ReportTable("User activity", "User", "User id", "Documents uploaded", "Workflows initiated",
                "Workflows completed", "Last activity");
        rows.forEach(row -> table.row(row.getUserName(), row.getUserId(), row.getDocumentsUploaded(), row.getWorkflowsInitiated(),
                row.getWorkflowsCompleted(), row.getLastActivityAt()));
        return table;
    }

    private static ReportTable storageTable(StorageUsageDTO usage)
    {
        return new ReportTable("Storage utilisation")
                .summary("Organisation", usage.getOrganisationId())
                .summary("Objects", usage.getObjectCount())
                .summary("Total bytes", usage.getTotalBytes())
                .summary("Originals bytes", usage.getOriginalsBytes())
                .summary("Artifacts bytes", usage.getArtifactsBytes())
                .summary("Preview bytes", usage.getPreviewBytes())
                .summary("Evidence bytes", usage.getEvidenceBytes());
    }

    private ReportTable platformTable(ReportCriteria criteria) throws Exception
    {
        LocalDate to = criteria.getTo() == null ? LocalDate.now() : criteria.getTo();
        LocalDate from = criteria.getFrom() == null ? to.minusDays(29) : criteria.getFrom();
        PlatformStatisticsDTO statistics = handlePlatformStatistics(from, to);
        ReportTable table = new ReportTable("Platform statistics " + from + " to " + to)
                .summary("Active users", statistics.getActiveUsers())
                .summary("Documents uploaded", statistics.getDocumentsUploaded())
                .summary("Workflows initiated", statistics.getWorkflowsInitiated())
                .summary("Workflows completed", statistics.getWorkflowsCompleted())
                .summary("Signatures completed", statistics.getSignaturesCompleted())
                .summary("Storage bytes", statistics.getStorageBytes())
                .summary("Notifications failed", statistics.getNotificationsFailed());
        Map<?, ?> organisations = statistics.getOrganisationsByStatus();
        if (organisations != null)
        {
            organisations.forEach((organisationStatus, count) -> table.summary("Organisations " + organisationStatus, count));
        }
        return table;
    }

    // ------------------------------------------------------------------ scope and helpers

    /**
     * Whose figures the caller may see. Organisation users: their own organisation only (and only their
     * own records when they hold just the -own permissions). Platform administrators and the system: the
     * organisation asked for.
     */
    private Scope scope(String requestedOrganisationId, String requestedOwnerId)
    {
        Optional<String> tenant = callerTenant();
        boolean signedIn = signedIn();
        String organisationId;
        if (tenant.isPresent())
        {
            organisationId = caller.organisationId()
                    .orElseThrow(() -> new ReportingServiceException("Your account is not linked to an organisation"));
            if (StringUtils.isNotBlank(requestedOrganisationId) && !organisationId.equalsIgnoreCase(requestedOrganisationId.trim()))
            {
                throw new ReportingServiceException("You can only see reports of your own organisation");
            }
        }
        else if (StringUtils.isNotBlank(requestedOrganisationId))
        {
            organisationId = requestedOrganisationId.trim();
        }
        else
        {
            throw new ReportingServiceException("Say which organisation the report is for");
        }
        UUID organisation = uuid(organisationId, "organisation");
        String tenantId = readOnly.execute(status -> entityManager.createQuery(
                "select o.tenantId from Organisation o where o.id = :organisation", String.class)
                .setParameter("organisation", organisation)
                .getResultStream()
                .findFirst()
                .orElseThrow(() -> ReportingServiceException.notFound("The organisation does not exist")));

        String userId = signedIn ? caller.userId() : null;
        String ownerId = null;
        if (signedIn && tenant.isPresent() && caller.ownRecordsOnly("documents:list"))
        {
            ownerId = userId;
        }
        else if (StringUtils.isNotBlank(requestedOwnerId))
        {
            ownerId = ownerKeycloakId(requestedOwnerId.trim(), organisation);
        }
        return new Scope(organisation, tenantId, userId, ownerId, signedIn && tenant.isEmpty());
    }

    /** ownerId may be an organisation user id or a Keycloak user id; reports filter on the Keycloak id. */
    private String ownerKeycloakId(String ownerId, UUID organisation)
    {
        UUID id = uuidOrNull(ownerId);
        if (id == null)
        {
            return ownerId;
        }
        return readOnly.execute(status -> entityManager.createQuery(
                "select u.keycloakUserId from OrganisationUser u where u.id = :id and u.organisation.id = :organisation", String.class)
                .setParameter("id", id)
                .setParameter("organisation", organisation)
                .getResultStream()
                .findFirst()
                .orElse(ownerId));
    }

    private Optional<String> callerTenant()
    {
        return signedIn() ? caller.tenantId() : Optional.empty();
    }

    /**
     * Reports are also produced where nobody is signed in (scheduled exports); those run as the system.
     */
    private boolean signedIn()
    {
        try
        {
            return StringUtils.isNotBlank(caller.userId());
        }
        catch (RuntimeException notSignedIn)
        {
            logger.trace("Reporting as the system: {}", notSignedIn.getMessage());
            return false;
        }
    }

    /** Rows of a multi-column query, bound to the organisation (and owner) of the scope. */
    private TypedQuery<Object[]> query(String jpql, Scope scope)
    {
        return bind(entityManager.createQuery(jpql, Object[].class), scope);
    }

    /** A single aggregate value, bound to the organisation (and owner) of the scope. */
    private Query aggregate(String jpql, Scope scope)
    {
        return bind(entityManager.createQuery(jpql), scope);
    }

    private static <Q extends Query> Q bind(Q query, Scope scope)
    {
        query.setParameter("organisation", scope.organisationId());
        if (scope.ownerId() != null)
        {
            query.setParameter("owner", scope.ownerId());
        }
        return query;
    }

    private long countBetween(String jpql, LocalDateTime start, LocalDateTime end)
    {
        return single(entityManager.createQuery(jpql).setParameter("start", start).setParameter("end", end));
    }

    private static long single(Query query)
    {
        return number(query.getSingleResult());
    }

    private static long number(Object value)
    {
        return value == null ? 0L : ((Number) value).longValue();
    }

    private static List<Double> hours(List<Object[]> pairs)
    {
        List<Double> hours = new ArrayList<>();
        for (Object[] pair : pairs)
        {
            Double value = hoursBetween(pair[0], pair[1]);
            if (value != null)
            {
                hours.add(value);
            }
        }
        return hours;
    }

    private static Double hoursBetween(Object start, Object end)
    {
        if (!(start instanceof LocalDateTime from) || !(end instanceof LocalDateTime to) || to.isBefore(from))
        {
            return null;
        }
        return Duration.between(from, to).toSeconds() / 3600d;
    }

    private static Double average(List<Double> values)
    {
        if (values == null || values.isEmpty())
        {
            return null;
        }
        return round(values.stream().mapToDouble(Double::doubleValue).average().orElse(0));
    }

    private static Double median(List<Double> values)
    {
        if (values.isEmpty())
        {
            return null;
        }
        List<Double> sorted = values.stream().sorted(Comparator.naturalOrder()).toList();
        int middle = sorted.size() / 2;
        return round(sorted.size() % 2 == 1 ? sorted.get(middle) : (sorted.get(middle - 1) + sorted.get(middle)) / 2);
    }

    private static Double rate(long part, long total)
    {
        return total == 0 ? 0d : BigDecimal.valueOf(part).divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP).doubleValue();
    }

    private static String percent(Double fraction)
    {
        return fraction == null ? null : BigDecimal.valueOf(fraction * 100).setScale(1, RoundingMode.HALF_UP) + "%";
    }

    private static Double round(double value)
    {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private static String name(Object firstName, Object lastName)
    {
        return StringUtils.trimToNull(StringUtils.defaultString((String) firstName) + " " + StringUtils.defaultString((String) lastName));
    }

    private static List<UUID> ids(Collection<String> ids)
    {
        return ids.stream().map(UUID::fromString).toList();
    }

    private static UUID uuid(String id, String what)
    {
        UUID uuid = uuidOrNull(id);
        if (uuid == null)
        {
            throw ReportingServiceException.notFound("'" + id + "' is not the id of an " + what);
        }
        return uuid;
    }

    private static UUID uuidOrNull(String id)
    {
        try
        {
            return id == null ? null : UUID.fromString(id.trim());
        }
        catch (IllegalArgumentException e)
        {
            return null;
        }
    }

    private record Period(LocalDate from, LocalDate to, ReportGrouping grouping)
    {
        LocalDateTime start()
        {
            return from.atStartOfDay();
        }

        LocalDateTime end()
        {
            return to.plusDays(1).atStartOfDay();
        }
    }

    private record Scope(UUID organisationId, String tenantId, String userId, String ownerId, boolean platformAdministrator)
    {
        /** JPQL restricting to the owner's records; {@code alias} is the joined OrganisationUser. */
        String ownerClause(String alias)
        {
            return ownerId == null ? "" : " and " + alias + ".keycloakUserId = :owner";
        }

        void requireTenantView(String message)
        {
            if (platformAdministrator)
            {
                throw new ReportingServiceException(message);
            }
        }
    }
}
