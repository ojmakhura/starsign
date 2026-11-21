// license-header java merge-point
/**
 * This is only generated once! It will never be overwritten.
 * You can (and have to!) safely modify it by hand.
 * TEMPLATE:    SpringServiceImpl.vsl in andromda-spring cartridge
 * MODEL CLASS: AndroMDAModel::backend::bw.co.starsign::workflow::SigningWorkflowService
 * STEREOTYPE:  Service
 */
package bw.co.starsign.workflow;

import bw.co.starsign.audit.ActorType;
import bw.co.starsign.audit.AuditEventDTO;
import bw.co.starsign.audit.AuditEventType;
import bw.co.starsign.audit.AuditTrailService;
import bw.co.starsign.common.Auditable;
import bw.co.starsign.common.SortDirection;
import bw.co.starsign.context.CallerContext;
import bw.co.starsign.document.ArtifactType;
import bw.co.starsign.document.Document;
import bw.co.starsign.document.DocumentArtifact;
import bw.co.starsign.document.DocumentMapper;
import bw.co.starsign.document.DocumentPage;
import bw.co.starsign.document.DocumentRepository;
import bw.co.starsign.document.DocumentStatus;
import bw.co.starsign.document.DocumentVersion;
import bw.co.starsign.identity.OrganisationUser;
import bw.co.starsign.identity.OrganisationUserRepository;
import bw.co.starsign.identity.UserStatus;
import bw.co.starsign.messaging.OutboxService;
import bw.co.starsign.notification.NotificationEventType;
import bw.co.starsign.notification.SigningReminderDTO;
import bw.co.starsign.organisation.Organisation;
import bw.co.starsign.organisation.OrganisationSigningPolicy;
import bw.co.starsign.signing.InvitationStatus;
import bw.co.starsign.signing.SignatoryInvitationDTO;
import bw.co.starsign.signing.SignatureAssuranceLevel;
import bw.co.starsign.signing.SignatureCaptureMethod;
import bw.co.starsign.signing.SigningEvent;
import bw.co.starsign.signing.SigningEventType;
import bw.co.starsign.signing.SigningInvitationService;
import bw.co.starsign.signing.VerificationMethod;
import bw.co.starsign.subscription.SubscriptionService;
import bw.co.starsign.subscription.UsageMetric;
import bw.co.starsign.template.SigningTemplate;
import bw.co.starsign.template.SigningTemplateField;
import bw.co.starsign.template.SigningTemplateRepository;
import bw.co.starsign.template.SigningTemplateSignatory;
import bw.co.starsign.template.TemplateInstantiationDTO;
import bw.co.starsign.template.TemplateRoleBindingDTO;
import bw.co.starsign.template.TemplateStatus;
import bw.co.starsign.workflow.WorkflowSupport.Actor;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import jakarta.validation.Valid;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * @see bw.co.starsign.workflow.SigningWorkflowService
 */
@Service("signingWorkflowService")
@Transactional(propagation = Propagation.REQUIRED, readOnly = false)
public class SigningWorkflowServiceImpl
    extends SigningWorkflowServiceBase
{
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final String[] COLOURS = {
        "#2563EB", "#DC2626", "#059669", "#D97706", "#7C3AED", "#DB2777", "#0891B2", "#65A30D" };
    private static final Set<WorkflowStatus> OPEN = EnumSet.of(WorkflowStatus.DRAFT, WorkflowStatus.READY,
            WorkflowStatus.IN_PROGRESS, WorkflowStatus.COMPLETING, WorkflowStatus.EXPIRED, WorkflowStatus.FAILED);
    private static final Set<Integer> ROTATIONS = Set.of(0, 90, 180, 270);
    private static final int DEFAULT_EXPIRY_DAYS = 30;
    private static final int DEFAULT_INVITATION_VALIDITY_HOURS = 72;
    private static final int DEFAULT_REMINDER_INTERVAL_HOURS = 48;
    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 200;
    private static final double EPSILON = 1e-6;
    // Used when the page metadata of the document is missing: an A4 page in points
    private static final double A4_WIDTH = 595.28;
    private static final double A4_HEIGHT = 841.89;
    private static final Map<String, String> SORT_FIELDS = Map.of(
            "createdAt", "createdAt", "title", "title", "referenceNumber", "referenceNumber", "status", "status",
            "expiresAt", "expiresAt", "initiatedAt", "initiatedAt", "completedAt", "completedAt");
    /** Signing events that the signing service also records as audit events: shown once in the timeline. */
    private static final Set<SigningEventType> AUDITED_SIGNING_EVENTS = EnumSet.of(
            SigningEventType.INVITATION_SENT, SigningEventType.INVITATION_OPENED, SigningEventType.ACCESS_GRANTED,
            SigningEventType.ACCESS_DENIED, SigningEventType.DOCUMENT_VIEWED, SigningEventType.VERIFICATION_PASSED,
            SigningEventType.VERIFICATION_FAILED, SigningEventType.SIGNATURE_CAPTURED,
            SigningEventType.SIGNATURE_SUBMITTED, SigningEventType.SIGNATURE_DECLINED,
            SigningEventType.STAGE_COMPLETED, SigningEventType.STAGE_ACTIVATED, SigningEventType.INVITATION_REVOKED);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final CallerContext caller;
    private final OrganisationUserRepository organisationUserRepository;
    private final SigningTemplateRepository signingTemplateRepository;
    private final WorkflowSupport support;
    private final WorkflowViews views;

    public SigningWorkflowServiceImpl(
        @Lazy WorkflowEngineService workflowEngineService,
        @Lazy SigningInvitationService signingInvitationService,
        @Lazy AuditTrailService auditTrailService,
        @Lazy OutboxService outboxService,
        @Lazy SubscriptionService subscriptionService,
        SigningWorkflowRepository signingWorkflowRepository,
        SigningWorkflowMapper signingWorkflowMapper,
        SigningStageRepository signingStageRepository,
        SigningStageMapper signingStageMapper,
        SignatoryRepository signatoryRepository,
        SignatoryMapper signatoryMapper,
        SigningFieldRepository signingFieldRepository,
        SigningFieldMapper signingFieldMapper,
        SigningAssignmentRepository signingAssignmentRepository,
        SigningAssignmentMapper signingAssignmentMapper,
        WorkflowConfigurationChangeRepository workflowConfigurationChangeRepository,
        WorkflowConfigurationChangeMapper workflowConfigurationChangeMapper,
        DocumentRepository documentRepository,
        DocumentMapper documentMapper,
        CallerContext caller,
        OrganisationUserRepository organisationUserRepository,
        SigningTemplateRepository signingTemplateRepository,
        WorkflowSupport support,
        WorkflowViews views) {

        super(
            workflowEngineService,
            signingInvitationService,
            auditTrailService,
            outboxService,
            subscriptionService,
            signingWorkflowRepository,
            signingWorkflowMapper,
            signingStageRepository,
            signingStageMapper,
            signatoryRepository,
            signatoryMapper,
            signingFieldRepository,
            signingFieldMapper,
            signingAssignmentRepository,
            signingAssignmentMapper,
            workflowConfigurationChangeRepository,
            workflowConfigurationChangeMapper,
            documentRepository,
            documentMapper);
        this.caller = caller;
        this.organisationUserRepository = organisationUserRepository;
        this.signingTemplateRepository = signingTemplateRepository;
        this.support = support;
        this.views = views;
    }

    /**
     * @see bw.co.starsign.workflow.SigningWorkflowService#create(@Valid CreateWorkflowDTO)
     */
    @Override
    protected SigningWorkflowDTO handleCreate(@Valid CreateWorkflowDTO workflow)
        throws Exception
    {
        SigningTemplate template = null;
        if (StringUtils.isNotBlank(workflow.getTemplateId()))
        {
            template = activeTemplate(workflow.getTemplateId());
        }
        SigningWorkflow entity = newWorkflow(workflow, template);
        if (template != null)
        {
            // Without role bindings only the stages of the template can be taken over: the signatories are
            // added afterwards (or the workflow is created with TemplateService.instantiate)
            int number = 1;
            for (int templateStage : template.getSignatories().stream()
                    .map(SigningTemplateSignatory::getStageNumber).distinct().sorted().toList())
            {
                entity.getStages().add(newStage(entity, number++, "Stage " + templateStage));
            }
        }
        created(entity, template == null ? "Workflow created"
                : "Workflow created from the template '" + template.getName() + "'");
        return views.workflow(entity);
    }

    /**
     * @see bw.co.starsign.workflow.SigningWorkflowService#findById(String)
     */
    @Override
    protected SigningWorkflowDTO handleFindById(String id)
        throws Exception
    {
        return views.workflow(load(id));
    }

    /**
     * @see bw.co.starsign.workflow.SigningWorkflowService#search(@Valid SigningWorkflowSearchCriteria)
     */
    @Override
    protected List<SigningWorkflowListDTO> handleSearch(@Valid SigningWorkflowSearchCriteria criteria)
        throws Exception
    {
        List<Specification<SigningWorkflow>> specs = new ArrayList<Specification<SigningWorkflow>>();
        caller.tenantId().ifPresent(tenant ->
                specs.add((root, query, cb) -> cb.equal(root.get("organisation").get("tenantId"), tenant)));
        if (caller.ownRecordsOnly("workflows:list"))
        {
            String userId = caller.userId();
            specs.add((root, query, cb) -> {
                Subquery<UUID> signs = query.subquery(UUID.class);
                Root<Signatory> signatory = signs.from(Signatory.class);
                signs.select(signatory.get("workflow").get("id"))
                        .where(cb.equal(signatory.get("internalUser").get("keycloakUserId"), userId));
                return cb.or(cb.equal(root.get("owner").get("keycloakUserId"), userId), root.get("id").in(signs));
            });
        }
        if (StringUtils.isNotBlank(criteria.getText()))
        {
            String text = "%" + criteria.getText().trim().toUpperCase() + "%";
            specs.add((root, query, cb) -> cb.or(
                    cb.like(cb.upper(root.get("title")), text),
                    cb.like(cb.upper(root.get("referenceNumber")), text),
                    cb.like(cb.upper(root.get("document").get("title")), text)));
        }
        if (StringUtils.isNotBlank(criteria.getReferenceNumber()))
        {
            String reference = criteria.getReferenceNumber().trim().toUpperCase();
            specs.add((root, query, cb) -> cb.equal(cb.upper(root.get("referenceNumber")), reference));
        }
        if (criteria.getStatuses() != null && !criteria.getStatuses().isEmpty())
        {
            List<WorkflowStatus> statuses = criteria.getStatuses();
            specs.add((root, query, cb) -> root.get("status").in(statuses));
        }
        if (StringUtils.isNotBlank(criteria.getDocumentId()))
        {
            UUID documentId = criterion(criteria.getDocumentId(), "document");
            specs.add((root, query, cb) -> cb.equal(root.get("document").get("id"), documentId));
        }
        if (StringUtils.isNotBlank(criteria.getOwnerId()))
        {
            UUID ownerId = criterion(criteria.getOwnerId(), "owner");
            specs.add((root, query, cb) -> cb.equal(root.get("owner").get("id"), ownerId));
        }
        if (StringUtils.isNotBlank(criteria.getSignatoryEmail()))
        {
            String email = criteria.getSignatoryEmail().trim().toLowerCase();
            specs.add((root, query, cb) -> {
                Subquery<UUID> signs = query.subquery(UUID.class);
                Root<Signatory> signatory = signs.from(Signatory.class);
                signs.select(signatory.get("workflow").get("id"))
                        .where(cb.equal(cb.lower(signatory.get("emailAddress")), email));
                return root.get("id").in(signs);
            });
        }
        between(specs, "createdAt", criteria.getCreatedFrom(), criteria.getCreatedTo());
        between(specs, "completedAt", criteria.getCompletedFrom(), criteria.getCompletedTo());
        if (criteria.getExpiringBefore() != null)
        {
            LocalDateTime before = criteria.getExpiringBefore();
            specs.add((root, query, cb) -> cb.lessThan(root.get("expiresAt"), before));
        }

        String sortField = SORT_FIELDS.getOrDefault(StringUtils.trimToEmpty(criteria.getSortField()), "createdAt");
        Sort.Direction direction = criteria.getSortDirection() == SortDirection.ASC
                || (criteria.getSortDirection() == null && criteria.getSortField() != null)
                ? Sort.Direction.ASC : Sort.Direction.DESC;
        int page = criteria.getPageNumber() == null ? 0 : Math.max(0, criteria.getPageNumber());
        int size = criteria.getPageSize() == null ? DEFAULT_PAGE_SIZE
                : Math.max(1, Math.min(MAX_PAGE_SIZE, criteria.getPageSize()));
        return signingWorkflowRepository
                .findAll(Specification.allOf(specs), PageRequest.of(page, size, Sort.by(direction, sortField)))
                .stream()
                .map(views::listItem)
                .toList();
    }

    /**
     * @see bw.co.starsign.workflow.SigningWorkflowService#update(String, @Valid SigningWorkflowDTO)
     */
    @Override
    protected SigningWorkflowDTO handleUpdate(String id, @Valid SigningWorkflowDTO workflow)
        throws Exception
    {
        SigningWorkflow entity = load(id);
        requireEditable(entity);
        String before = WorkflowSupport.snapshot(entity);
        OrganisationSigningPolicy policy = entity.getOrganisation().getSigningPolicy();
        List<String> errors = new ArrayList<String>();

        if (workflow.getTitle() != null)
        {
            if (StringUtils.isBlank(workflow.getTitle()))
            {
                errors.add("The title cannot be empty");
            }
            else
            {
                entity.setTitle(workflow.getTitle().trim());
            }
        }
        if (workflow.getMessage() != null)
        {
            entity.setMessage(StringUtils.trimToNull(workflow.getMessage()));
        }
        if (workflow.getExecutionMode() != null)
        {
            entity.setExecutionMode(workflow.getExecutionMode());
        }
        if (workflow.getExpiresAt() != null)
        {
            if (!workflow.getExpiresAt().isAfter(LocalDateTime.now()))
            {
                errors.add("The expiry date must be in the future");
            }
            entity.setExpiresAt(workflow.getExpiresAt());
        }
        SignatureAssuranceLevel level = workflow.getAssuranceLevel() == null
                ? entity.getAssuranceLevel() : workflow.getAssuranceLevel();
        VerificationMethod method = workflow.getVerificationMethod() == null
                ? entity.getVerificationMethod() : workflow.getVerificationMethod();
        checkAssurance(level, method, policy, errors);
        entity.setAssuranceLevel(level);
        entity.setVerificationMethod(method);
        if (workflow.getSingleUseInvitations() != null)
        {
            entity.setSingleUseInvitations(workflow.getSingleUseInvitations());
        }
        if (workflow.getInvitationValidityHours() != null)
        {
            if (workflow.getInvitationValidityHours() < 1)
            {
                errors.add("Invitations must be valid for at least one hour");
            }
            entity.setInvitationValidityHours(workflow.getInvitationValidityHours());
        }
        if (workflow.getAllowDecline() != null)
        {
            if (workflow.getAllowDecline() && policy != null && !policy.isAllowSignatoryDecline())
            {
                errors.add("The organisation does not allow signatories to decline");
            }
            entity.setAllowDecline(workflow.getAllowDecline());
        }
        if (workflow.getDeclineTerminatesWorkflow() != null)
        {
            entity.setDeclineTerminatesWorkflow(workflow.getDeclineTerminatesWorkflow());
        }
        if (workflow.getRequireDeclineReason() != null)
        {
            entity.setRequireDeclineReason(workflow.getRequireDeclineReason());
        }
        if (workflow.getSendCompletedCopy() != null)
        {
            entity.setSendCompletedCopy(workflow.getSendCompletedCopy());
        }
        if (workflow.getAllowedCaptureMethods() != null)
        {
            entity.setAllowedCaptureMethods(captureMethods(workflow.getAllowedCaptureMethods(), policy, errors));
        }
        if (workflow.getReminderIntervalHours() != null)
        {
            if (workflow.getReminderIntervalHours() < 0)
            {
                errors.add("The reminder interval cannot be negative");
            }
            entity.setReminderIntervalHours(workflow.getReminderIntervalHours());
        }
        if (!entity.isAllowDecline())
        {
            // Signatories cannot be allowed more than the workflow allows
            entity.getSignatories().forEach(signatory -> signatory.setCanDecline(false));
        }
        failIfAny(errors);

        touched(entity, WorkflowChangeType.UPDATED, "Workflow settings changed", before, null);
        return views.workflow(signingWorkflowRepository.save(entity));
    }

    /**
     * @see bw.co.starsign.workflow.SigningWorkflowService#addStage(String, @Valid SigningStageDTO)
     */
    @Override
    protected SigningStageDTO handleAddStage(String id, @Valid SigningStageDTO stage)
        throws Exception
    {
        SigningWorkflow workflow = load(id);
        requireEditable(workflow);
        String before = WorkflowSupport.snapshot(workflow);

        List<SigningStage> ordered = new ArrayList<SigningStage>(
                workflow.getStages().stream().sorted(WorkflowViews.STAGE_ORDER).toList());
        int position = stage.getStageNumber() == null || stage.getStageNumber() < 1
                || stage.getStageNumber() > ordered.size() + 1
                ? ordered.size() + 1 : stage.getStageNumber();
        SigningStage entity = newStage(workflow, position, StringUtils.defaultIfBlank(stage.getName(), "Stage " + position));
        applyStage(workflow, entity, stage);
        workflow.getStages().add(entity);
        ordered.add(position - 1, entity);
        renumber(workflow, ordered);

        if (stage.getSignatories() != null)
        {
            for (SignatoryDTO signatory : stage.getSignatories())
            {
                addSignatoryTo(workflow, signatory, entity);
            }
        }
        touched(workflow, WorkflowChangeType.STAGE_ADDED,
                "Stage " + entity.getStageNumber() + " (" + entity.getName() + ") added", before, null);
        signingWorkflowRepository.save(workflow);
        return views.stage(entity);
    }

    /**
     * @see bw.co.starsign.workflow.SigningWorkflowService#updateStage(String, String, @Valid SigningStageDTO)
     */
    @Override
    protected SigningStageDTO handleUpdateStage(String id, String stageId, @Valid SigningStageDTO stage)
        throws Exception
    {
        SigningWorkflow workflow = load(id);
        requireEditable(workflow);
        String before = WorkflowSupport.snapshot(workflow);
        SigningStage entity = stage(workflow, stageId);
        if (stage.getName() != null)
        {
            if (StringUtils.isBlank(stage.getName()))
            {
                throw new SigningWorkflowServiceException("The name of the stage cannot be empty");
            }
            entity.setName(stage.getName().trim());
        }
        applyStage(workflow, entity, stage);
        stamp(entity, false);

        if (stage.getStageNumber() != null && stage.getStageNumber() != entity.getStageNumber())
        {
            List<SigningStage> ordered = new ArrayList<SigningStage>(
                    workflow.getStages().stream().sorted(WorkflowViews.STAGE_ORDER).toList());
            ordered.remove(entity);
            int position = Math.max(1, Math.min(ordered.size() + 1, stage.getStageNumber()));
            ordered.add(position - 1, entity);
            renumber(workflow, ordered);
        }
        touched(workflow, WorkflowChangeType.STAGE_UPDATED,
                "Stage " + entity.getStageNumber() + " (" + entity.getName() + ") changed", before, null);
        signingWorkflowRepository.save(workflow);
        return views.stage(entity);
    }

    /**
     * @see bw.co.starsign.workflow.SigningWorkflowService#removeStage(String, String)
     */
    @Override
    protected boolean handleRemoveStage(String id, String stageId)
        throws Exception
    {
        SigningWorkflow workflow = load(id);
        requireEditable(workflow);
        String before = WorkflowSupport.snapshot(workflow);
        SigningStage stage = stage(workflow, stageId);
        List<Signatory> members = WorkflowSupport.members(stage);

        // Rows go in dependency order (fields, then signatories, then the stage), each flushed on its own
        removeFieldsOf(workflow, members);
        workflow.getSignatories().removeIf(signatory -> members.contains(signatory));
        signingWorkflowRepository.saveAndFlush(workflow);
        workflow.getStages().removeIf(s -> s == stage);
        signingWorkflowRepository.saveAndFlush(workflow);
        renumber(workflow, workflow.getStages().stream().sorted(WorkflowViews.STAGE_ORDER).toList());

        touched(workflow, WorkflowChangeType.STAGE_REMOVED, "Stage '" + stage.getName() + "' removed with "
                + members.size() + " signator" + (members.size() == 1 ? "y" : "ies"), before, null);
        signingWorkflowRepository.save(workflow);
        return true;
    }

    /**
     * @see bw.co.starsign.workflow.SigningWorkflowService#reorderStages(String, @Valid ReorderRequestDTO)
     */
    @Override
    protected List<SigningStageDTO> handleReorderStages(String id, @Valid ReorderRequestDTO order)
        throws Exception
    {
        SigningWorkflow workflow = load(id);
        requireEditable(workflow);
        String before = WorkflowSupport.snapshot(workflow);
        List<String> ids = order.getOrderedIds() == null ? List.of() : order.getOrderedIds();
        if (ids.size() != workflow.getStages().size() || new HashSet<String>(ids).size() != ids.size())
        {
            throw new SigningWorkflowServiceException("List every stage of the workflow exactly once");
        }
        List<SigningStage> ordered = new ArrayList<SigningStage>();
        for (String stageId : ids)
        {
            ordered.add(stage(workflow, stageId));
        }
        renumber(workflow, ordered);
        touched(workflow, WorkflowChangeType.STAGE_UPDATED, "Stages reordered", before, null);
        signingWorkflowRepository.save(workflow);
        return views.stages(workflow);
    }

    /**
     * @see bw.co.starsign.workflow.SigningWorkflowService#addSignatory(String, @Valid SignatoryDTO)
     */
    @Override
    protected SignatoryDTO handleAddSignatory(String id, @Valid SignatoryDTO signatory)
        throws Exception
    {
        SigningWorkflow workflow = load(id);
        requireEditable(workflow);
        String before = WorkflowSupport.snapshot(workflow);
        Signatory entity = addSignatoryTo(workflow, signatory, null);
        touched(workflow, WorkflowChangeType.SIGNATORY_ADDED, entity.getFullName() + " (" + entity.getEmailAddress()
                + ") added to stage " + entity.getStage().getStageNumber(), before, null);
        signingWorkflowRepository.saveAndFlush(workflow);
        return views.signatory(entity);
    }

    /**
     * @see bw.co.starsign.workflow.SigningWorkflowService#updateSignatory(String, String, @Valid SignatoryDTO)
     */
    @Override
    protected SignatoryDTO handleUpdateSignatory(String id, String signatoryId, @Valid SignatoryDTO signatory)
        throws Exception
    {
        SigningWorkflow workflow = load(id);
        requireEditable(workflow);
        String before = WorkflowSupport.snapshot(workflow);
        Signatory entity = signatory(workflow, signatoryId);
        SigningStage target = stageOf(workflow, signatory, entity.getStage());
        if (target != entity.getStage())
        {
            entity.setStage(target);
            if (signatory.getSigningOrder() == null)
            {
                entity.setSigningOrder(nextOrder(workflow, target, entity));
            }
        }
        applySignatory(workflow, entity, signatory, false);
        stamp(entity, false);
        touched(workflow, WorkflowChangeType.SIGNATORY_UPDATED, entity.getFullName() + " changed", before, null);
        signingWorkflowRepository.save(workflow);
        return views.signatory(entity);
    }

    /**
     * @see bw.co.starsign.workflow.SigningWorkflowService#removeSignatory(String, String)
     */
    @Override
    protected boolean handleRemoveSignatory(String id, String signatoryId)
        throws Exception
    {
        SigningWorkflow workflow = load(id);
        if (workflow.getStatus() == WorkflowStatus.IN_PROGRESS)
        {
            throw new SigningWorkflowServiceException(
                    "Signatories cannot be removed once the workflow is sent: amend the workflow to replace one");
        }
        requireEditable(workflow);
        String before = WorkflowSupport.snapshot(workflow);
        Signatory signatory = signatory(workflow, signatoryId);
        removeFieldsOf(workflow, List.of(signatory));
        workflow.getSignatories().removeIf(s -> s == signatory);
        touched(workflow, WorkflowChangeType.SIGNATORY_REMOVED, signatory.getFullName() + " ("
                + signatory.getEmailAddress() + ") removed", before, null);
        signingWorkflowRepository.save(workflow);
        return true;
    }

    /**
     * @see bw.co.starsign.workflow.SigningWorkflowService#reorderSignatories(String, @Valid ReorderRequestDTO)
     */
    @Override
    protected List<SignatoryDTO> handleReorderSignatories(String id, @Valid ReorderRequestDTO order)
        throws Exception
    {
        SigningWorkflow workflow = load(id);
        requireEditable(workflow);
        String before = WorkflowSupport.snapshot(workflow);
        List<String> ids = order.getOrderedIds() == null ? List.of() : order.getOrderedIds();
        Map<Signatory, Integer> position = new HashMap<Signatory, Integer>();
        for (String signatoryId : ids)
        {
            Signatory signatory = signatory(workflow, signatoryId);
            if (position.put(signatory, position.size()) != null)
            {
                throw new SigningWorkflowServiceException("Every signatory can be listed only once");
            }
        }
        // The order applies within each stage; signatories that are not listed keep their place after the
        // listed ones
        for (SigningStage stage : workflow.getStages())
        {
            List<Signatory> members = new ArrayList<Signatory>(WorkflowSupport.members(stage));
            members.sort(Comparator.comparingInt((Signatory s) -> position.getOrDefault(s, Integer.MAX_VALUE))
                    .thenComparingInt(Signatory::getSigningOrder));
            for (int i = 0; i < members.size(); i++)
            {
                members.get(i).setSigningOrder(i + 1);
            }
        }
        touched(workflow, WorkflowChangeType.SIGNATORIES_REORDERED, "Signing order changed", before, null);
        signingWorkflowRepository.save(workflow);
        return views.signatories(workflow);
    }

    /**
     * @see bw.co.starsign.workflow.SigningWorkflowService#getFields(String)
     */
    @Override
    protected List<SigningFieldDTO> handleGetFields(String id)
        throws Exception
    {
        return views.fields(load(id));
    }

    /**
     * @see bw.co.starsign.workflow.SigningWorkflowService#saveFields(String, @Valid List<SigningFieldDTO>)
     */
    @Override
    protected List<SigningFieldDTO> handleSaveFields(String id, @Valid List<SigningFieldDTO> fields)
        throws Exception
    {
        SigningWorkflow workflow = load(id);
        requireEditable(workflow);
        String before = WorkflowSupport.snapshot(workflow);
        DocumentVersion version = workflow.getSourceVersion();
        int pageCount = pageCount(workflow.getDocument(), version);

        Map<String, SigningField> existing = new HashMap<String, SigningField>();
        workflow.getFields().forEach(field -> existing.put(field.getId().toString(), field));
        List<String> errors = new ArrayList<String>();
        Set<String> kept = new HashSet<String>();
        for (int i = 0; i < fields.size(); i++)
        {
            SigningFieldDTO field = fields.get(i);
            String prefix = "Field " + (i + 1) + (StringUtils.isBlank(field.getLabel()) ? "" : " '" + field.getLabel() + "'")
                    + ": ";
            checkField(workflow, field, pageCount, prefix, errors);
            if (StringUtils.isNotBlank(field.getId()))
            {
                if (!existing.containsKey(field.getId().trim()))
                {
                    errors.add(prefix + "it is not a field of this workflow");
                }
                else if (!kept.add(field.getId().trim()))
                {
                    errors.add(prefix + "it is listed more than once");
                }
            }
        }
        failIfAny(errors);

        // The list replaces the layout: fields that are not in it are removed first
        workflow.getFields().removeIf(field -> !kept.contains(field.getId().toString()));
        signingWorkflowRepository.saveAndFlush(workflow);
        for (int i = 0; i < fields.size(); i++)
        {
            SigningFieldDTO dto = fields.get(i);
            SigningField field = StringUtils.isBlank(dto.getId()) ? null : existing.get(dto.getId().trim());
            if (field == null)
            {
                field = SigningField.Factory.newInstance();
                field.setWorkflow(workflow);
                stamp(field, true);
                workflow.getFields().add(field);
            }
            else
            {
                stamp(field, false);
            }
            applyField(field, dto, signatory(workflow, dto.getSignatoryId()), page(version, dto.getPageNumber()), i + 1);
        }
        touched(workflow, WorkflowChangeType.FIELDS_CHANGED, fields.size() + " field" + (fields.size() == 1 ? "" : "s")
                + " placed", before, null);
        signingWorkflowRepository.saveAndFlush(workflow);
        return views.fields(workflow);
    }

    /**
     * @see bw.co.starsign.workflow.SigningWorkflowService#removeField(String, String)
     */
    @Override
    protected boolean handleRemoveField(String id, String fieldId)
        throws Exception
    {
        SigningWorkflow workflow = load(id);
        requireEditable(workflow);
        String before = WorkflowSupport.snapshot(workflow);
        SigningField field = workflow.getFields().stream()
                .filter(f -> f.getId().toString().equalsIgnoreCase(StringUtils.trimToEmpty(fieldId)))
                .findFirst()
                .orElseThrow(() -> SigningWorkflowServiceException.notFound("The field does not exist"));
        workflow.getFields().removeIf(f -> f == field);
        touched(workflow, WorkflowChangeType.FIELDS_CHANGED, field.getFieldType() + " field on page "
                + field.getPageNumber() + " removed", before, null);
        signingWorkflowRepository.save(workflow);
        return true;
    }

    /**
     * @see bw.co.starsign.workflow.SigningWorkflowService#validate(String)
     */
    @Override
    protected WorkflowValidationResultDTO handleValidate(String id)
        throws Exception
    {
        SigningWorkflow workflow = load(id);
        WorkflowValidationResultDTO result = check(workflow);
        readiness(workflow, result);
        signingWorkflowRepository.save(workflow);
        return result;
    }

    /**
     * @see bw.co.starsign.workflow.SigningWorkflowService#initiate(String, @Valid WorkflowInitiationDTO)
     */
    @Override
    protected SigningWorkflowDTO handleInitiate(String id, @Valid WorkflowInitiationDTO initiation)
        throws Exception
    {
        return views.workflow(initiate(lock(id), initiation));
    }

    /**
     * @see bw.co.starsign.workflow.SigningWorkflowService#cancel(String, @Valid WorkflowCancellationDTO)
     */
    @Override
    protected SigningWorkflowDTO handleCancel(String id, @Valid WorkflowCancellationDTO cancellation)
        throws Exception
    {
        SigningWorkflow workflow = lock(id);
        WorkflowStatus status = workflow.getStatus();
        if (status != WorkflowStatus.DRAFT && status != WorkflowStatus.READY && status != WorkflowStatus.IN_PROGRESS)
        {
            throw new SigningWorkflowServiceException("A " + text(status) + " workflow cannot be cancelled");
        }
        String reason = StringUtils.trimToNull(cancellation.getReason());
        if (status == WorkflowStatus.IN_PROGRESS && reason == null)
        {
            throw new SigningWorkflowServiceException("Say why the workflow is cancelled: the signatories are told");
        }
        String before = WorkflowSupport.snapshot(workflow);
        LocalDateTime now = LocalDateTime.now();

        List<Signatory> involved = workflow.getSignatories().stream()
                .filter(s -> WorkflowSupport.ACTIVE_SIGNATORY.contains(s.getStatus())
                        || s.getStatus() == SignatoryStatus.SIGNED)
                .toList();
        for (SigningStage stage : workflow.getStages())
        {
            if (stage.getStatus() == StageStatus.ACTIVE || stage.getStatus() == StageStatus.PENDING)
            {
                stage.setStatus(StageStatus.CANCELLED);
            }
        }
        for (Signatory signatory : workflow.getSignatories())
        {
            if (signatory.getStatus() == SignatoryStatus.PENDING
                    || WorkflowSupport.ACTIVE_SIGNATORY.contains(signatory.getStatus()))
            {
                signatory.setStatus(SignatoryStatus.CANCELLED);
            }
        }
        workflow.setStatus(WorkflowStatus.CANCELLED);
        workflow.setCancelledAt(now);
        workflow.setCancelledBy(caller.userId());
        workflow.setCancellationReason(reason);
        stamp(workflow, false);

        if (status == WorkflowStatus.IN_PROGRESS)
        {
            support.revokeInvitations(workflow, "The workflow was cancelled");
            support.cancelReminders(workflow);
            support.documentStatus(workflow, DocumentStatus.CANCELLED);
            if (!Boolean.FALSE.equals(cancellation.getNotifySignatories()))
            {
                Map<String, Object> model = support.model(workflow);
                model.put("reason", reason);
                involved.forEach(signatory ->
                        support.notifySignatory(signatory, NotificationEventType.WORKFLOW_CANCELLED, model));
            }
        }
        else
        {
            // A workflow that was never sent gives the document back for a new one
            support.documentStatus(workflow, DocumentStatus.DRAFT);
        }
        Actor actor = actor();
        support.changed(workflow, WorkflowChangeType.CANCELLED, "Workflow cancelled", before, reason, actor.id());
        support.audit(workflow, AuditEventType.WORKFLOW_CANCELLED, actor, null,
                reason == null ? "Workflow cancelled before it was sent" : "Workflow cancelled: " + reason);
        support.publish(workflow, "WORKFLOW_CANCELLED", reason == null ? Map.of() : Map.of("reason", reason));
        return views.workflow(signingWorkflowRepository.save(workflow));
    }

    /**
     * @see bw.co.starsign.workflow.SigningWorkflowService#renew(String, @Valid WorkflowRenewalDTO)
     */
    @Override
    protected SigningWorkflowDTO handleRenew(String id, @Valid WorkflowRenewalDTO renewal)
        throws Exception
    {
        SigningWorkflow workflow = lock(id);
        if (workflow.getStatus() != WorkflowStatus.EXPIRED)
        {
            throw new SigningWorkflowServiceException("Only an expired workflow can be renewed");
        }
        LocalDateTime now = LocalDateTime.now();
        if (renewal.getNewExpiresAt() == null || !renewal.getNewExpiresAt().isAfter(now))
        {
            throw new SigningWorkflowServiceException("Give the new expiry date: it must be in the future");
        }
        String before = WorkflowSupport.snapshot(workflow);
        workflow.setExpiresAt(renewal.getNewExpiresAt());
        workflow.setRenewedAt(now);
        workflow.setRenewalCount(workflow.getRenewalCount() + 1);
        workflow.setConfigurationVersion(workflow.getConfigurationVersion() + 1);
        workflow.setStatus(WorkflowStatus.IN_PROGRESS);
        stamp(workflow, false);

        // Deadlines that have passed would expire the workflow again straight away
        for (SigningStage stage : workflow.getStages())
        {
            if (stage.getStatus() == StageStatus.EXPIRED)
            {
                stage.setStatus(StageStatus.ACTIVE);
            }
            if (stage.getDueAt() != null && !stage.getDueAt().isAfter(now))
            {
                stage.setDueAt(null);
            }
        }
        boolean reissue = !Boolean.FALSE.equals(renewal.getReissueInvitations());
        for (Signatory signatory : workflow.getSignatories())
        {
            if (signatory.getDeadline() != null && !signatory.getDeadline().isAfter(now))
            {
                signatory.setDeadline(null);
            }
            if (signatory.getStatus() == SignatoryStatus.EXPIRED)
            {
                support.invite(signatory, reissue);
            }
        }
        Actor actor = actor();
        if (workflow.getStages().stream().noneMatch(stage -> stage.getStatus() == StageStatus.ACTIVE))
        {
            support.activateNextStage(workflow, reissue, actor);
        }
        support.documentSigningStatus(workflow);
        support.changed(workflow, WorkflowChangeType.RENEWED, "Renewed until " + workflow.getExpiresAt(), before,
                renewal.getReason(), actor.id());
        support.audit(workflow, AuditEventType.WORKFLOW_RENEWED, actor, null, "Renewed until " + workflow.getExpiresAt()
                + (StringUtils.isBlank(renewal.getReason()) ? "" : ": " + renewal.getReason().trim()));
        support.publish(workflow, "WORKFLOW_RENEWED", Map.of("expiresAt", workflow.getExpiresAt().toString()));
        return views.workflow(signingWorkflowRepository.save(workflow));
    }

    /**
     * @see bw.co.starsign.workflow.SigningWorkflowService#amend(String, @Valid WorkflowAmendmentDTO)
     */
    @Override
    protected SigningWorkflowDTO handleAmend(String id, @Valid WorkflowAmendmentDTO amendment)
        throws Exception
    {
        SigningWorkflow workflow = lock(id);
        if (workflow.getStatus() == WorkflowStatus.DRAFT || workflow.getStatus() == WorkflowStatus.READY)
        {
            throw new SigningWorkflowServiceException("The workflow has not been sent yet: change the signatory directly");
        }
        if (workflow.getStatus() != WorkflowStatus.IN_PROGRESS && workflow.getStatus() != WorkflowStatus.EXPIRED)
        {
            throw new SigningWorkflowServiceException("A " + text(workflow.getStatus()) + " workflow cannot be amended");
        }
        List<String> errors = new ArrayList<String>();
        if (StringUtils.isBlank(amendment.getReason()))
        {
            errors.add("Say why the workflow is amended");
        }
        if (StringUtils.isBlank(amendment.getSignatoryId()))
        {
            errors.add("Say which signatory is replaced");
        }
        if (amendment.getReplacement() == null)
        {
            errors.add("Give the details of the replacing signatory");
        }
        failIfAny(errors);
        Signatory replaced = signatory(workflow, amendment.getSignatoryId());
        if (replaced.getStatus() == SignatoryStatus.SIGNED)
        {
            throw new SigningWorkflowServiceException(replaced.getFullName() + " has signed and cannot be replaced");
        }
        if (replaced.getStatus() == SignatoryStatus.DECLINED || replaced.getStatus() == SignatoryStatus.CANCELLED
                || replaced.getStatus() == SignatoryStatus.SKIPPED)
        {
            throw new SigningWorkflowServiceException(replaced.getFullName() + " is no longer part of the workflow");
        }
        String before = WorkflowSupport.snapshot(workflow);
        SignatoryStatus replacedStatus = replaced.getStatus();
        // The replaced signatory leaves before the replacement joins, so the replacement may use the same
        // stage, order and even e-mail address
        replaced.setStatus(SignatoryStatus.CANCELLED);
        stamp(replaced, false);

        SignatoryDTO details = new SignatoryDTO(amendment.getReplacement());
        details.setStageId(replaced.getStage().getId().toString());
        details.setStageNumber(null);
        details.setSigningOrder(details.getSigningOrder() == null ? replaced.getSigningOrder() : details.getSigningOrder());
        details.setMandatory(details.getMandatory() == null ? replaced.isMandatory() : details.getMandatory());
        details.setCanDecline(details.getCanDecline() == null ? replaced.isCanDecline() : details.getCanDecline());
        details.setDeclineTerminatesWorkflow(details.getDeclineTerminatesWorkflow() == null
                ? replaced.isDeclineTerminatesWorkflow() : details.getDeclineTerminatesWorkflow());
        details.setDeadline(details.getDeadline() == null ? replaced.getDeadline() : details.getDeadline());
        details.setReceiveCompletedCopy(details.getReceiveCompletedCopy() == null
                ? replaced.isReceiveCompletedCopy() : details.getReceiveCompletedCopy());
        details.setColour(StringUtils.defaultIfBlank(details.getColour(), replaced.getColour()));
        Signatory replacement = addSignatoryTo(workflow, details, null);
        for (SigningField field : workflow.getFields())
        {
            if (field.getAssignment() != null && field.getAssignment().getSignatory() == replaced)
            {
                field.getAssignment().setSignatory(replacement);
            }
        }
        workflow.setConfigurationVersion(workflow.getConfigurationVersion() + 1);
        stamp(workflow, false);
        signingWorkflowRepository.saveAndFlush(workflow);

        for (SignatoryInvitationDTO invitation : signingInvitationService.findBySignatory(replaced.getId().toString()))
        {
            if (invitation.getStatus() != InvitationStatus.CONSUMED && invitation.getStatus() != InvitationStatus.EXPIRED
                    && invitation.getStatus() != InvitationStatus.REVOKED && invitation.getStatus() != InvitationStatus.FAILED)
            {
                signingInvitationService.revoke(invitation.getId(), "The signatory was replaced");
            }
        }
        if (replaced.getInvitationSentAt() != null)
        {
            support.cancelReminders(replaced);
        }
        if (WorkflowSupport.ACTIVE_SIGNATORY.contains(replacedStatus) && workflow.getStatus() == WorkflowStatus.IN_PROGRESS)
        {
            support.invite(replacement, true);
        }
        else if (replacedStatus == SignatoryStatus.EXPIRED)
        {
            // Invited again when the workflow is renewed
            replacement.setStatus(SignatoryStatus.EXPIRED);
        }

        Actor actor = actor();
        String description = replaced.getFullName() + " (" + replaced.getEmailAddress() + ") replaced by "
                + replacement.getFullName() + " (" + replacement.getEmailAddress() + ")";
        support.changed(workflow, WorkflowChangeType.AMENDED, description, before, amendment.getReason(), actor.id());
        support.audit(workflow, AuditEventType.WORKFLOW_AMENDED, actor, replaced,
                description + ": " + amendment.getReason().trim());
        support.publish(workflow, "WORKFLOW_AMENDED", Map.of("replacedSignatoryId", replaced.getId().toString(),
                "replacementSignatoryId", replacement.getId().toString()));
        return views.workflow(signingWorkflowRepository.save(workflow));
    }

    /**
     * @see bw.co.starsign.workflow.SigningWorkflowService#resendInvitation(String, String)
     */
    @Override
    protected void handleResendInvitation(String id, String signatoryId)
        throws Exception
    {
        SigningWorkflow workflow = load(id);
        if (workflow.getStatus() != WorkflowStatus.IN_PROGRESS)
        {
            throw new SigningWorkflowServiceException("Invitations are only sent while the workflow is in progress");
        }
        Signatory signatory = signatory(workflow, signatoryId);
        if (!WorkflowSupport.ACTIVE_SIGNATORY.contains(signatory.getStatus()))
        {
            throw new SigningWorkflowServiceException(signatory.getStatus() == SignatoryStatus.PENDING
                    ? "It is not yet " + signatory.getFullName() + "'s turn to sign"
                    : signatory.getFullName() + " has nothing left to sign (" + text(signatory.getStatus()) + ")");
        }
        support.invite(signatory, true);
        support.audit(workflow, AuditEventType.SIGNING_INVITATION_SENT, actor(), signatory,
                "Invitation sent again to " + signatory.getEmailAddress());
        signingWorkflowRepository.save(workflow);
    }

    /**
     * @see bw.co.starsign.workflow.SigningWorkflowService#sendReminder(String, String)
     */
    @Override
    protected SigningReminderDTO handleSendReminder(String id, String signatoryId)
        throws Exception
    {
        SigningWorkflow workflow = load(id);
        if (workflow.getStatus() != WorkflowStatus.IN_PROGRESS)
        {
            throw new SigningWorkflowServiceException("Reminders are only sent while the workflow is in progress");
        }
        Signatory signatory = signatory(workflow, signatoryId);
        if (!WorkflowSupport.ACTIVE_SIGNATORY.contains(signatory.getStatus()))
        {
            throw new SigningWorkflowServiceException(signatory.getStatus() == SignatoryStatus.PENDING
                    ? "It is not yet " + signatory.getFullName() + "'s turn to sign"
                    : signatory.getFullName() + " has nothing left to sign (" + text(signatory.getStatus()) + ")");
        }
        return support.remind(signatory);
    }

    /**
     * @see bw.co.starsign.workflow.SigningWorkflowService#getTimeline(String)
     */
    @Override
    protected List<WorkflowTimelineEntryDTO> handleGetTimeline(String id)
        throws Exception
    {
        SigningWorkflow workflow = load(id);
        Map<String, Signatory> signatories = new HashMap<String, Signatory>();
        workflow.getSignatories().forEach(s -> signatories.put(s.getId().toString(), s));

        List<WorkflowTimelineEntryDTO> timeline = new ArrayList<WorkflowTimelineEntryDTO>();
        for (AuditEventDTO event : auditTrailService.findByWorkflow(workflow.getId().toString()))
        {
            WorkflowTimelineEntryDTO entry = new WorkflowTimelineEntryDTO();
            entry.setTimestamp(event.getOccurredAt());
            entry.setEventType(event.getEventType() == null ? null : event.getEventType().name());
            entry.setActorName(event.getActorDisplayName());
            entry.setActorType(event.getActorType() == null ? null : event.getActorType().name());
            Signatory signatory = event.getSignatoryId() == null ? null : signatories.get(event.getSignatoryId());
            if (signatory != null)
            {
                entry.setSignatoryName(signatory.getFullName());
                entry.setStageNumber(signatory.getStage().getStageNumber());
            }
            entry.setDescription(StringUtils.defaultIfBlank(bw.co.starsign.signing.SigningEventRecorder.detailsText(event.getDetails()), text(event.getEventType())));
            timeline.add(entry);
        }
        // Signing events add what the audit trail does not repeat (deliveries, field progress, expiries)
        for (SigningEvent event : workflow.getEvents())
        {
            if (AUDITED_SIGNING_EVENTS.contains(event.getEventType()))
            {
                continue;
            }
            WorkflowTimelineEntryDTO entry = new WorkflowTimelineEntryDTO();
            entry.setTimestamp(event.getOccurredAt());
            entry.setEventType(event.getEventType().name());
            Signatory signatory = event.getSignatory();
            entry.setActorName(signatory == null ? "StarSign" : signatory.getFullName());
            entry.setActorType(signatory == null ? ActorType.SYSTEM.name() : ActorType.SIGNATORY.name());
            entry.setSignatoryName(signatory == null ? null : signatory.getFullName());
            entry.setStageNumber(event.getStage() != null ? Integer.valueOf(event.getStage().getStageNumber())
                    : signatory == null ? null : Integer.valueOf(signatory.getStage().getStageNumber()));
            entry.setDescription(StringUtils.defaultIfBlank(bw.co.starsign.signing.SigningEventRecorder.detailsText(event.getDetails()), text(event.getEventType())));
            timeline.add(entry);
        }
        timeline.sort(Comparator.comparing(WorkflowTimelineEntryDTO::getTimestamp,
                Comparator.nullsFirst(Comparator.naturalOrder())));
        return timeline;
    }

    /**
     * @see bw.co.starsign.workflow.SigningWorkflowService#getConfigurationHistory(String)
     */
    @Override
    protected List<WorkflowConfigurationChangeDTO> handleGetConfigurationHistory(String id)
        throws Exception
    {
        return views.changes(load(id));
    }

    /**
     * @see bw.co.starsign.workflow.SigningWorkflowService#createFromTemplate(String, @Valid TemplateInstantiationDTO)
     */
    @Override
    protected SigningWorkflowDTO handleCreateFromTemplate(String templateId, @Valid TemplateInstantiationDTO instantiation)
        throws Exception
    {
        SigningTemplate template = activeTemplate(templateId);
        String key = StringUtils.trimToNull(instantiation.getIdempotencyKey());
        if (key != null)
        {
            SigningWorkflow earlier = signingWorkflowRepository
                    .findFirstByOrganisationIdAndInitiationIdempotencyKey(template.getOrganisation().getId(), key)
                    .orElse(null);
            if (earlier != null)
            {
                return views.workflow(earlier);
            }
        }

        List<String> errors = new ArrayList<String>();
        Map<SigningTemplateSignatory, SignatoryDTO> bound = new LinkedHashMap<SigningTemplateSignatory, SignatoryDTO>();
        for (TemplateRoleBindingDTO binding : instantiation.getBindings() == null
                ? List.<TemplateRoleBindingDTO>of() : instantiation.getBindings())
        {
            SigningTemplateSignatory role = template.getSignatories().stream()
                    .filter(r -> r.getId().toString().equalsIgnoreCase(StringUtils.trimToEmpty(binding.getSignatoryRoleId())))
                    .findFirst()
                    .orElse(null);
            if (role == null)
            {
                errors.add("'" + binding.getSignatoryRoleId() + "' is not a role of the template");
            }
            else if (binding.getSignatory() == null)
            {
                errors.add("Say who signs as " + role.getRoleName());
            }
            else if (bound.put(role, binding.getSignatory()) != null)
            {
                errors.add("The role " + role.getRoleName() + " is bound more than once");
            }
        }
        template.getSignatories().stream()
                .filter(role -> role.isMandatory() && !bound.containsKey(role))
                .forEach(role -> errors.add("Say who signs as " + role.getRoleName()));
        failIfAny(errors);

        CreateWorkflowDTO create = new CreateWorkflowDTO();
        create.setDocumentId(instantiation.getDocumentId());
        create.setTitle(StringUtils.defaultIfBlank(instantiation.getTitle(), template.getName()));
        create.setMessage(StringUtils.defaultIfBlank(instantiation.getMessage(), template.getMessage()));
        create.setExecutionMode(template.getExecutionMode());
        create.setExpiresAt(instantiation.getExpiresAt() != null ? instantiation.getExpiresAt()
                : template.getDefaultExpiryDays() > 0 ? LocalDateTime.now().plusDays(template.getDefaultExpiryDays()) : null);
        create.setAssuranceLevel(template.getAssuranceLevel());
        create.setVerificationMethod(template.getVerificationMethod());
        create.setTemplateId(template.getId().toString());
        SigningWorkflow workflow = newWorkflow(create, template);

        // Page-count rule: the document must have every page the template places a field on
        DocumentVersion version = workflow.getSourceVersion();
        int pageCount = pageCount(workflow.getDocument(), version);
        int highestPage = template.getFields().stream().mapToInt(SigningTemplateField::getPageNumber).max().orElse(0);
        if (highestPage > pageCount)
        {
            throw new SigningWorkflowServiceException("The document has " + pageCount + " page" + (pageCount == 1 ? "" : "s")
                    + " but the template places fields on page " + highestPage);
        }

        // Stages are numbered 1..n over the template stages that have someone to sign
        Map<Integer, SigningStage> stages = new HashMap<Integer, SigningStage>();
        int number = 1;
        for (int templateStage : bound.keySet().stream().map(SigningTemplateSignatory::getStageNumber).distinct().sorted().toList())
        {
            SigningStage stage = newStage(workflow, number, "Stage " + number);
            workflow.getStages().add(stage);
            stages.put(templateStage, stage);
            number++;
        }
        Map<SigningTemplateSignatory, Signatory> signatories = new HashMap<SigningTemplateSignatory, Signatory>();
        List<SigningTemplateSignatory> roles = bound.keySet().stream()
                .sorted(Comparator.comparingInt(SigningTemplateSignatory::getStageNumber)
                        .thenComparingInt(SigningTemplateSignatory::getSigningOrder))
                .toList();
        for (SigningTemplateSignatory role : roles)
        {
            SignatoryDTO details = new SignatoryDTO(bound.get(role));
            details.setStageId(null);
            details.setStageNumber(null);
            details.setSignatoryType(details.getSignatoryType() == null ? role.getSignatoryType() : details.getSignatoryType());
            details.setSigningOrder(role.getSigningOrder());
            details.setMandatory(role.isMandatory());
            details.setCanDecline(details.getCanDecline() == null ? role.isCanDecline() && workflow.isAllowDecline()
                    : details.getCanDecline());
            if (details.getVerificationMethod() == null && details.getSignatoryType() == role.getSignatoryType())
            {
                details.setVerificationMethod(role.getVerificationMethod());
            }
            details.setColour(StringUtils.defaultIfBlank(details.getColour(), role.getColour()));
            details.setDesignation(StringUtils.defaultIfBlank(details.getDesignation(), role.getRoleName()));
            signatories.put(role, addSignatoryTo(workflow, details, stages.get(role.getStageNumber())));
        }
        for (SigningTemplateField templateField : template.getFields())
        {
            Signatory signatory = signatories.get(templateField.getSignatoryRole());
            if (signatory == null)
            {
                continue; // an optional role nobody was bound to
            }
            SigningFieldDTO dto = new SigningFieldDTO();
            dto.setFieldType(templateField.getFieldType());
            dto.setLabel(templateField.getLabel());
            dto.setPageNumber(templateField.getPageNumber());
            dto.setXRatio(templateField.getXRatio());
            dto.setYRatio(templateField.getYRatio());
            dto.setWidthRatio(templateField.getWidthRatio());
            dto.setHeightRatio(templateField.getHeightRatio());
            dto.setRequired(templateField.isRequired());
            dto.setReadOnly(templateField.isReadOnly());
            dto.setPlaceholder(templateField.getPlaceholder());
            dto.setFormatPattern(templateField.getFormatPattern());
            dto.setValidationRegex(templateField.getValidationRegex());
            dto.setOptions(WorkflowViews.options(templateField.getOptionsJson()));
            dto.setTabOrder(templateField.getTabOrder());
            SigningField field = SigningField.Factory.newInstance();
            field.setWorkflow(workflow);
            stamp(field, true);
            applyField(field, dto, signatory, page(version, templateField.getPageNumber()), templateField.getTabOrder());
            workflow.getFields().add(field);
        }
        created(workflow, "Workflow created from the template '" + template.getName() + "' (version "
                + template.getTemplateVersion() + ")");

        if (Boolean.TRUE.equals(instantiation.getInitiateImmediately()))
        {
            WorkflowInitiationDTO initiation = new WorkflowInitiationDTO();
            initiation.setIdempotencyKey(key);
            initiation.setSendInvitations(Boolean.TRUE);
            initiate(workflow, initiation);
        }
        return views.workflow(workflow);
    }

    // ---------------------------------------------------------------- creating

    /**
     * A DRAFT workflow for the document, with the organisation's signing policy filling in whatever the
     * request leaves out; saved, and the document moved to IN_PREPARATION.
     */
    private SigningWorkflow newWorkflow(CreateWorkflowDTO request, SigningTemplate template)
    {
        if (StringUtils.isBlank(request.getDocumentId()))
        {
            throw new SigningWorkflowServiceException("Say which document is to be signed");
        }
        Document document = documentRepository.findById(uuid(request.getDocumentId(), "document"))
                .filter(d -> d.getDeletedAt() == null)
                .orElseThrow(() -> SigningWorkflowServiceException.notFound("The document does not exist"));
        checkTenant(document.getOrganisation(), "The document does not exist");
        Organisation organisation = document.getOrganisation();
        if (template != null && !template.getOrganisation().getId().equals(organisation.getId()))
        {
            throw new SigningWorkflowServiceException("The template belongs to another organisation");
        }
        switch (document.getStatus())
        {
            case DRAFT, DECLINED, CANCELLED:
                break;
            case UPLOADED:
                throw new SigningWorkflowServiceException(
                        "The document is still being checked: it can be sent for signing once it is ready");
            case IN_PREPARATION, READY_FOR_SIGNING:
                throw new SigningWorkflowServiceException("The document already has a workflow being prepared");
            default:
                throw new SigningWorkflowServiceException("A " + text(document.getStatus())
                        + " document cannot be sent for signing again");
        }
        signingWorkflowRepository.findByDocumentId(document.getId()).stream()
                .filter(other -> OPEN.contains(other.getStatus()))
                .findFirst()
                .ifPresent(other -> {
                    throw new SigningWorkflowServiceException("The document already has the open workflow "
                            + other.getReferenceNumber());
                });
        OrganisationUser owner = organisationUserRepository.findByKeycloakUserId(caller.userId())
                .filter(user -> user.getOrganisation().getId().equals(organisation.getId()))
                .orElseThrow(() -> new SigningWorkflowServiceException(
                        "Only users of the document's organisation can send it for signing"));
        DocumentVersion version = document.getVersions().stream()
                .filter(v -> v.getVersionNumber() == document.getCurrentVersionNumber())
                .findFirst()
                .orElseThrow(() -> new SigningWorkflowServiceException("The document has no current version to sign"));

        OrganisationSigningPolicy policy = organisation.getSigningPolicy();
        List<String> errors = new ArrayList<String>();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = request.getExpiresAt() != null ? request.getExpiresAt()
                : now.plusDays(policy != null && policy.getDefaultExpiryDays() > 0 ? policy.getDefaultExpiryDays()
                        : DEFAULT_EXPIRY_DAYS);
        if (!expiresAt.isAfter(now))
        {
            errors.add("The expiry date must be in the future");
        }
        SignatureAssuranceLevel minimum = policy == null || policy.getMinimumAssuranceLevel() == null
                ? SignatureAssuranceLevel.ELECTRONIC_BASIC : policy.getMinimumAssuranceLevel();
        SignatureAssuranceLevel level = request.getAssuranceLevel() != null ? request.getAssuranceLevel() : minimum;
        VerificationMethod method = request.getVerificationMethod() != null ? request.getVerificationMethod()
                : policy != null && policy.getDefaultVerificationMethod() != null ? policy.getDefaultVerificationMethod()
                : VerificationMethod.EMAIL_LINK;
        if (request.getVerificationMethod() == null && method == VerificationMethod.EMAIL_LINK
                && level.ordinal() >= SignatureAssuranceLevel.ELECTRONIC_VERIFIED.ordinal())
        {
            // A verified signature needs more than possession of the e-mail: default to a one-time code
            method = VerificationMethod.EMAIL_OTP;
        }
        checkAssurance(level, method, policy, errors);
        if (request.getReminderIntervalHours() != null && request.getReminderIntervalHours() < 0)
        {
            errors.add("The reminder interval cannot be negative");
        }
        if (Boolean.TRUE.equals(request.getAllowDecline()) && policy != null && !policy.isAllowSignatoryDecline())
        {
            errors.add("The organisation does not allow signatories to decline");
        }
        String captureMethods = captureMethods(request.getAllowedCaptureMethods(), policy, errors);
        failIfAny(errors);

        SigningWorkflow workflow = SigningWorkflow.Factory.newInstance();
        stamp(workflow, true);
        workflow.setOrganisation(organisation);
        workflow.setDocument(document);
        workflow.setSourceVersion(version);
        workflow.setOwner(owner);
        workflow.setTemplate(template);
        workflow.setReferenceNumber(referenceNumber(organisation));
        workflow.setTitle(StringUtils.defaultIfBlank(StringUtils.trimToNull(request.getTitle()), document.getTitle()));
        workflow.setMessage(StringUtils.trimToNull(request.getMessage()));
        workflow.setStatus(WorkflowStatus.DRAFT);
        workflow.setExecutionMode(request.getExecutionMode() != null ? request.getExecutionMode()
                : policy != null && policy.getDefaultExecutionMode() != null ? policy.getDefaultExecutionMode()
                : ExecutionMode.SEQUENTIAL);
        workflow.setExpiresAt(expiresAt);
        workflow.setAssuranceLevel(level);
        workflow.setVerificationMethod(method);
        workflow.setSingleUseInvitations(request.getSingleUseInvitations() != null ? request.getSingleUseInvitations()
                : policy == null || policy.isSingleUseInvitationLinks());
        workflow.setInvitationValidityHours(policy != null && policy.getInvitationValidityHours() > 0
                ? policy.getInvitationValidityHours() : DEFAULT_INVITATION_VALIDITY_HOURS);
        workflow.setAllowDecline(request.getAllowDecline() != null ? request.getAllowDecline()
                : policy == null || policy.isAllowSignatoryDecline());
        workflow.setDeclineTerminatesWorkflow(request.getDeclineTerminatesWorkflow() != null
                ? request.getDeclineTerminatesWorkflow() : policy == null || policy.isDeclineTerminatesWorkflow());
        workflow.setRequireDeclineReason(policy == null || policy.isRequireDeclineReason());
        workflow.setSendCompletedCopy(request.getSendCompletedCopy() != null ? request.getSendCompletedCopy()
                : policy == null || policy.isSendCompletedCopyToSignatories());
        workflow.setAllowedCaptureMethods(captureMethods);
        workflow.setReminderIntervalHours(request.getReminderIntervalHours() != null ? request.getReminderIntervalHours()
                : DEFAULT_REMINDER_INTERVAL_HOURS);
        workflow.setRenewalCount(0);
        workflow.setCurrentStageNumber(0);
        workflow.setConfigurationLocked(false);
        workflow.setConfigurationVersion(1);
        workflow.setLastProcessedEventSequence(0);
        return signingWorkflowRepository.save(workflow);
    }

    /** Records the creation of a workflow that has been assembled. */
    private void created(SigningWorkflow workflow, String description)
    {
        Actor actor = actor();
        support.documentStatus(workflow, DocumentStatus.IN_PREPARATION);
        support.changed(workflow, WorkflowChangeType.CREATED, description, null, null, actor.id());
        support.audit(workflow, AuditEventType.WORKFLOW_CREATED, actor, null, description);
        support.publish(workflow, "WORKFLOW_CREATED", Map.of());
        signingWorkflowRepository.saveAndFlush(workflow);
    }

    private SigningTemplate activeTemplate(String templateId)
    {
        SigningTemplate template = signingTemplateRepository.findById(uuid(templateId, "template"))
                .orElseThrow(() -> SigningWorkflowServiceException.notFound("The template does not exist"));
        checkTenant(template.getOrganisation(), "The template does not exist");
        if (template.getStatus() != TemplateStatus.ACTIVE)
        {
            throw new SigningWorkflowServiceException("Only an active template can be used: '" + template.getName()
                    + "' is " + text(template.getStatus()));
        }
        return template;
    }

    private String referenceNumber(Organisation organisation)
    {
        String prefix = "WF-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "-";
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        for (int attempt = 0; attempt < 20; attempt++)
        {
            StringBuilder reference = new StringBuilder(prefix);
            for (int i = 0; i < 6; i++)
            {
                reference.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
            }
            if (!signingWorkflowRepository.existsByOrganisationIdAndReferenceNumber(organisation.getId(), reference.toString()))
            {
                return reference.toString();
            }
        }
        throw new IllegalStateException("No free workflow reference number found for organisation " + organisation.getId());
    }

    // ---------------------------------------------------------------- initiation and validation

    private SigningWorkflow initiate(SigningWorkflow workflow, WorkflowInitiationDTO initiation)
    {
        String key = StringUtils.trimToNull(initiation.getIdempotencyKey());
        if (key != null && key.equals(workflow.getInitiationIdempotencyKey()))
        {
            return workflow; // the same request again
        }
        if (workflow.getStatus() != WorkflowStatus.DRAFT && workflow.getStatus() != WorkflowStatus.READY)
        {
            throw new SigningWorkflowServiceException(workflow.getStatus() == WorkflowStatus.IN_PROGRESS
                    ? "The workflow has already been sent for signing"
                    : "A " + text(workflow.getStatus()) + " workflow cannot be sent for signing");
        }
        WorkflowValidationResultDTO result = check(workflow);
        if (!result.getValid())
        {
            throw new SigningWorkflowServiceException("The workflow cannot be sent yet: " + String.join("; ",
                    result.getIssues().stream().filter(issue -> "ERROR".equals(issue.getSeverity()))
                            .map(WorkflowValidationIssueDTO::getMessage).toList()));
        }
        String before = WorkflowSupport.snapshot(workflow);
        Actor actor = actor();
        DocumentVersion version = workflow.getSourceVersion();
        workflow.setStatus(WorkflowStatus.IN_PROGRESS);
        workflow.setInitiatedAt(LocalDateTime.now());
        workflow.setInitiatedBy(actor.id());
        workflow.setInitiationIdempotencyKey(key);
        workflow.setConfigurationLocked(true);
        workflow.setConfigurationVersion(workflow.getConfigurationVersion() + 1);
        workflow.setSourceDocumentSha256(version.getSha256());
        workflow.setSourceArtifact(sourceArtifact(version));
        stamp(workflow, false);

        support.documentStatus(workflow, DocumentStatus.PENDING_SIGNATURES);
        support.changed(workflow, WorkflowChangeType.INITIATED, "Sent for signing", before, null, actor.id());
        support.audit(workflow, AuditEventType.WORKFLOW_INITIATED, actor, null, "Sent for signing to "
                + workflow.getSignatories().size() + " signator" + (workflow.getSignatories().size() == 1 ? "y" : "ies")
                + " in " + workflow.getStages().size() + " stage" + (workflow.getStages().size() == 1 ? "" : "s"));
        support.publish(workflow, "WORKFLOW_INITIATED", Map.of("sourceDocumentSha256",
                StringUtils.defaultString(workflow.getSourceDocumentSha256())));
        subscriptionService.recordUsage(workflow.getOrganisation().getId().toString(), UsageMetric.WORKFLOWS_INITIATED, 1,
                workflow.getId().toString());
        signingWorkflowRepository.saveAndFlush(workflow);
        support.activateNextStage(workflow, !Boolean.FALSE.equals(initiation.getSendInvitations()), actor);
        return signingWorkflowRepository.save(workflow);
    }

    /** The artifact of the version whose content is signed: the one with the version's hash, else the original. */
    private static DocumentArtifact sourceArtifact(DocumentVersion version)
    {
        return version.getArtifacts().stream()
                .filter(artifact -> StringUtils.equalsIgnoreCase(artifact.getSha256(), version.getSha256()))
                .findFirst()
                .or(() -> version.getArtifacts().stream()
                        .filter(artifact -> artifact.getArtifactType() == ArtifactType.ORIGINAL)
                        .findFirst())
                .orElse(null);
    }

    /** DRAFT <-> READY, and the document IN_PREPARATION <-> READY_FOR_SIGNING, after a validation. */
    private void readiness(SigningWorkflow workflow, WorkflowValidationResultDTO result)
    {
        if (workflow.getStatus() == WorkflowStatus.DRAFT && result.getValid())
        {
            workflow.setStatus(WorkflowStatus.READY);
            support.documentStatus(workflow, DocumentStatus.READY_FOR_SIGNING);
            support.changed(workflow, WorkflowChangeType.VALIDATED, "Validated: ready to send", null, null,
                    caller.userId());
        }
        else if (workflow.getStatus() == WorkflowStatus.READY && !result.getValid())
        {
            workflow.setStatus(WorkflowStatus.DRAFT);
            support.documentStatus(workflow, DocumentStatus.IN_PREPARATION);
            support.changed(workflow, WorkflowChangeType.VALIDATED, "Validated: no longer ready to send", null, null,
                    caller.userId());
        }
    }

    /**
     * The checks of WorkflowValidationResultDTO. Errors block initiation, warnings are worth a look.
     */
    private WorkflowValidationResultDTO check(SigningWorkflow workflow)
    {
        List<WorkflowValidationIssueDTO> issues = new ArrayList<WorkflowValidationIssueDTO>();
        LocalDateTime now = LocalDateTime.now();
        boolean preparing = workflow.getStatus() == WorkflowStatus.DRAFT || workflow.getStatus() == WorkflowStatus.READY;

        if (workflow.getStages().isEmpty())
        {
            issues.add(issue("ERROR", "NO_STAGES", "Add at least one stage with signatories", null, null, null));
        }
        if (workflow.getExpiresAt() == null || !workflow.getExpiresAt().isAfter(now))
        {
            issues.add(issue("ERROR", "EXPIRY_PASSED", "The expiry date must be in the future", null, null, null));
        }
        else if (workflow.getExpiresAt().isBefore(now.plusDays(1)))
        {
            issues.add(issue("WARNING", "EXPIRY_SOON", "The workflow expires within a day of being sent", null, null, null));
        }
        for (SigningStage stage : workflow.getStages().stream().sorted(WorkflowViews.STAGE_ORDER).toList())
        {
            String stageId = stage.getId() == null ? null : stage.getId().toString();
            List<Signatory> members = WorkflowSupport.members(stage);
            String name = "Stage " + stage.getStageNumber() + " (" + stage.getName() + ")";
            if (members.isEmpty())
            {
                issues.add(issue("ERROR", "EMPTY_STAGE", name + " has no signatories", null, null, stageId));
            }
            if (stage.getCompletionRule() == StageCompletionRule.MINIMUM_COUNT
                    && (stage.getMinimumSignatures() == null || stage.getMinimumSignatures() > members.size()))
            {
                issues.add(issue("ERROR", "MINIMUM_NOT_REACHABLE", name + " needs " + stage.getMinimumSignatures()
                        + " signatures but has " + members.size() + " signatories", null, null, stageId));
            }
            if (stage.getCompletionRule() == StageCompletionRule.ALL_REQUIRED && !members.isEmpty()
                    && members.stream().noneMatch(Signatory::isMandatory))
            {
                issues.add(issue("ERROR", "NO_MANDATORY_SIGNATORY", name + " completes when all mandatory signatories "
                        + "have signed, but none is mandatory", null, null, stageId));
            }
            if (stage.getDueAt() != null && workflow.getExpiresAt() != null && stage.getDueAt().isAfter(workflow.getExpiresAt()))
            {
                issues.add(issue("ERROR", "STAGE_DUE_AFTER_EXPIRY", name + " is due after the workflow expires", null, null,
                        stageId));
            }
            Set<String> emails = new HashSet<String>();
            for (Signatory signatory : members)
            {
                if (!emails.add(StringUtils.lowerCase(signatory.getEmailAddress())))
                {
                    issues.add(issue("ERROR", "DUPLICATE_EMAIL", signatory.getEmailAddress() + " signs more than once in "
                            + name, id(signatory), null, stageId));
                }
            }
        }
        for (Signatory signatory : workflow.getSignatories())
        {
            List<SigningField> fields = workflow.getFields().stream()
                    .filter(f -> f.getAssignment() != null && f.getAssignment().getSignatory() == signatory)
                    .toList();
            if (signatory.isMandatory() && fields.stream()
                    .noneMatch(f -> f.getFieldType() == FieldType.SIGNATURE && f.isRequired()))
            {
                issues.add(issue("ERROR", "NO_SIGNATURE_FIELD", signatory.getFullName()
                        + " has no required signature field", id(signatory), null, null));
            }
            else if (fields.isEmpty())
            {
                issues.add(issue("WARNING", "NO_FIELDS", signatory.getFullName() + " has no fields to complete",
                        id(signatory), null, null));
            }
            if (signatory.getDeadline() != null && workflow.getExpiresAt() != null
                    && signatory.getDeadline().isAfter(workflow.getExpiresAt()))
            {
                issues.add(issue("ERROR", "DEADLINE_AFTER_EXPIRY", signatory.getFullName()
                        + "'s deadline is after the workflow expires", id(signatory), null, null));
            }
            if (signatory.getDeadline() != null && !signatory.getDeadline().isAfter(now) && preparing)
            {
                issues.add(issue("ERROR", "DEADLINE_PASSED", signatory.getFullName() + "'s deadline has passed",
                        id(signatory), null, null));
            }
            if ((signatory.getVerificationMethod() == VerificationMethod.SMS_OTP || signatory.isNotifyBySms())
                    && StringUtils.isBlank(signatory.getPhoneNumber()))
            {
                issues.add(issue("ERROR", "NO_PHONE_NUMBER", signatory.getFullName()
                        + " needs a phone number for SMS", id(signatory), null, null));
            }
            if (signatory.getSignatoryType() == SignatoryType.INTERNAL_USER && signatory.getInternalUser() != null
                    && signatory.getInternalUser().getStatus() != UserStatus.ACTIVE)
            {
                issues.add(issue("WARNING", "INTERNAL_USER_INACTIVE", signatory.getFullName()
                        + "'s user account is not active", id(signatory), null, null));
            }
        }
        int pageCount = pageCount(workflow.getDocument(), workflow.getSourceVersion());
        for (SigningField field : workflow.getFields())
        {
            if (field.getPageNumber() < 1 || field.getPageNumber() > pageCount || field.getXRatio() < 0 || field.getYRatio() < 0
                    || field.getWidthRatio() <= 0 || field.getHeightRatio() <= 0
                    || field.getXRatio() + field.getWidthRatio() > 1 + EPSILON
                    || field.getYRatio() + field.getHeightRatio() > 1 + EPSILON)
            {
                issues.add(issue("ERROR", "FIELD_OUT_OF_BOUNDS", "A " + text(field.getFieldType()) + " field on page "
                        + field.getPageNumber() + " is outside the page", null, id(field), null));
            }
        }
        if (preparing)
        {
            DocumentStatus status = workflow.getDocument().getStatus();
            if (status != DocumentStatus.IN_PREPARATION && status != DocumentStatus.READY_FOR_SIGNING)
            {
                issues.add(issue("ERROR", "DOCUMENT_STATUS", "The document is " + text(status)
                        + " and cannot be sent for signing", null, null, null));
            }
            if (!subscriptionService.checkLimit(workflow.getOrganisation().getId().toString(),
                    UsageMetric.WORKFLOWS_INITIATED, 1))
            {
                issues.add(issue("ERROR", "PLAN_LIMIT", "The organisation's plan allows no more workflows in this period",
                        null, null, null));
            }
        }

        WorkflowValidationResultDTO result = new WorkflowValidationResultDTO();
        result.setIssues(issues);
        result.setValid(issues.stream().noneMatch(issue -> "ERROR".equals(issue.getSeverity())));
        return result;
    }

    private static WorkflowValidationIssueDTO issue(String severity, String code, String message, String signatoryId,
            String fieldId, String stageId)
    {
        WorkflowValidationIssueDTO issue = new WorkflowValidationIssueDTO();
        issue.setSeverity(severity);
        issue.setCode(code);
        issue.setMessage(message);
        issue.setSignatoryId(signatoryId);
        issue.setFieldId(fieldId);
        issue.setStageId(stageId);
        return issue;
    }

    // ---------------------------------------------------------------- stages

    private SigningStage newStage(SigningWorkflow workflow, int number, String name)
    {
        SigningStage stage = SigningStage.Factory.newInstance();
        stamp(stage, true);
        stage.setWorkflow(workflow);
        stage.setStageNumber(number);
        stage.setName(name);
        stage.setCompletionRule(StageCompletionRule.ALL_REQUIRED);
        stage.setStatus(StageStatus.PENDING);
        return stage;
    }

    private static void applyStage(SigningWorkflow workflow, SigningStage stage, SigningStageDTO dto)
    {
        List<String> errors = new ArrayList<String>();
        if (dto.getCompletionRule() != null)
        {
            stage.setCompletionRule(dto.getCompletionRule());
        }
        if (stage.getCompletionRule() == StageCompletionRule.MINIMUM_COUNT)
        {
            Integer minimum = dto.getMinimumSignatures() != null ? dto.getMinimumSignatures() : stage.getMinimumSignatures();
            if (minimum == null || minimum < 1)
            {
                errors.add("Say how many signatures complete the stage");
            }
            stage.setMinimumSignatures(minimum);
        }
        else
        {
            stage.setMinimumSignatures(null);
        }
        stage.setDueAt(dto.getDueAt());
        if (dto.getDueAt() != null)
        {
            if (!dto.getDueAt().isAfter(LocalDateTime.now()))
            {
                errors.add("The stage must be due in the future");
            }
            if (workflow.getExpiresAt() != null && dto.getDueAt().isAfter(workflow.getExpiresAt()))
            {
                errors.add("The stage cannot be due after the workflow expires");
            }
        }
        failIfAny(errors);
    }

    /**
     * Gives the stages the numbers 1..n in the given order. Stage numbers are unique per workflow and rows are
     * updated one at a time, so the stages pass through numbers nobody uses first.
     */
    private void renumber(SigningWorkflow workflow, List<SigningStage> ordered)
    {
        for (int i = 0; i < ordered.size(); i++)
        {
            ordered.get(i).setStageNumber(100000 + i);
        }
        signingWorkflowRepository.saveAndFlush(workflow);
        for (int i = 0; i < ordered.size(); i++)
        {
            ordered.get(i).setStageNumber(i + 1);
        }
        signingWorkflowRepository.saveAndFlush(workflow);
    }

    private static SigningStage stage(SigningWorkflow workflow, String stageId)
    {
        return workflow.getStages().stream()
                .filter(stage -> stage.getId().toString().equalsIgnoreCase(StringUtils.trimToEmpty(stageId)))
                .findFirst()
                .orElseThrow(() -> SigningWorkflowServiceException.notFound("The stage does not exist"));
    }

    /** The stage a signatory DTO names (by id or number), else the given default. */
    private SigningStage stageOf(SigningWorkflow workflow, SignatoryDTO dto, SigningStage otherwise)
    {
        if (StringUtils.isNotBlank(dto.getStageId()))
        {
            return stage(workflow, dto.getStageId());
        }
        if (dto.getStageNumber() != null)
        {
            SigningStage stage = WorkflowSupport.stage(workflow, dto.getStageNumber());
            if (stage == null)
            {
                throw new SigningWorkflowServiceException("The workflow has no stage " + dto.getStageNumber());
            }
            return stage;
        }
        if (otherwise != null)
        {
            return otherwise;
        }
        // Signatories go to the last stage; the first signatory of a workflow creates stage 1
        return workflow.getStages().stream().max(WorkflowViews.STAGE_ORDER).orElseGet(() -> {
            SigningStage first = newStage(workflow, 1, "Stage 1");
            workflow.getStages().add(first);
            return first;
        });
    }

    // ---------------------------------------------------------------- signatories

    /** The ACTIVE, not deleted user of the workflow's organisation with this e-mail address (ignoring case), if any. */
    private OrganisationUser activeUserByEmail(SigningWorkflow workflow, String email)
    {
        String address = StringUtils.trimToNull(email);
        if (address == null || workflow.getOrganisation() == null)
        {
            return null;
        }
        return organisationUserRepository.findByOrganisation_IdAndEmailAddressIgnoreCaseAndStatusAndDeletedAtIsNull(
                        workflow.getOrganisation().getId(), address, UserStatus.ACTIVE).stream()
                .filter(user -> StringUtils.isNotBlank(user.getKeycloakUserId()))
                .findFirst()
                .orElse(null);
    }

    private Signatory addSignatoryTo(SigningWorkflow workflow, SignatoryDTO dto, SigningStage stage)
    {
        Signatory signatory = Signatory.Factory.newInstance();
        stamp(signatory, true);
        signatory.setWorkflow(workflow);
        signatory.setStatus(SignatoryStatus.PENDING);
        signatory.setReminderCount(0);
        signatory.setStage(stage != null ? stage : stageOf(workflow, dto, null));
        applySignatory(workflow, signatory, dto, true);
        workflow.getSignatories().add(signatory);
        return signatory;
    }

    /**
     * Copies the signatory details, with the workflow settings as defaults for a new signatory and the stored
     * values as defaults when changing one.
     * <p>
     * Existing accounts are matched by e-mail, like other e-signature products do: when no internalUserId is
     * given and the signatory's e-mail address belongs (ignoring case) to an ACTIVE, not deleted user of the
     * workflow's organisation, the signatory becomes an INTERNAL_USER linked to that user. They then sign in the
     * portal with their own account (INTERNAL_ACCOUNT), see the request in their signing inbox and are notified
     * with a portal link instead of a one-time token link.
     * <ul>
     * <li>No type, or EXTERNAL_PERSON (which clients send by default for "someone by e-mail"), is upgraded: an
     * explicit EXTERNAL_PERSON with a colleague's address is almost always the sender not knowing they have an
     * account, and signing with the account is stronger than any link verification. The verification method is
     * then INTERNAL_ACCOUNT whatever the client asked for, since the asked-for method was meant for an outsider.</li>
     * <li>INTERNAL_USER without internalUserId is resolved by e-mail too (the client's verification method, if
     * any, is kept).</li>
     * <li>EXTERNAL_ORGANISATION_REPRESENTATIVE is never upgraded: the sender says the person signs for another
     * organisation.</li>
     * <li>When changing a signatory, matching only happens when the change carries an e-mail address or a type,
     * so editing an unrelated setting does not change how someone signs.</li>
     * </ul>
     */
    private void applySignatory(SigningWorkflow workflow, Signatory signatory, SignatoryDTO dto, boolean creating)
    {
        List<String> errors = new ArrayList<String>();
        SignatoryType type = dto.getSignatoryType() != null ? dto.getSignatoryType()
                : !creating ? signatory.getSignatoryType()
                : StringUtils.isNotBlank(dto.getInternalUserId()) ? SignatoryType.INTERNAL_USER : SignatoryType.EXTERNAL_PERSON;

        // An existing account of the organisation with the signatory's e-mail address
        OrganisationUser matched = null;
        boolean upgraded = false;
        boolean identityGiven = creating || dto.getSignatoryType() != null || StringUtils.isNotBlank(dto.getEmailAddress());
        if (identityGiven && StringUtils.isBlank(dto.getInternalUserId())
                && (type == SignatoryType.EXTERNAL_PERSON
                        || (type == SignatoryType.INTERNAL_USER && (creating || signatory.getInternalUser() == null
                                || StringUtils.isNotBlank(dto.getEmailAddress())))))
        {
            matched = activeUserByEmail(workflow, StringUtils.isNotBlank(dto.getEmailAddress())
                    ? dto.getEmailAddress() : signatory.getEmailAddress());
            if (matched != null && type == SignatoryType.EXTERNAL_PERSON)
            {
                type = SignatoryType.INTERNAL_USER;
                upgraded = true;
            }
        }
        signatory.setSignatoryType(type);

        OrganisationUser user = null;
        if (type == SignatoryType.INTERNAL_USER)
        {
            if (matched != null)
            {
                user = matched;
            }
            else if (StringUtils.isNotBlank(dto.getInternalUserId()))
            {
                user = organisationUserRepository.findById(uuid(dto.getInternalUserId(), "user"))
                        .filter(u -> u.getOrganisation().getId().equals(workflow.getOrganisation().getId())
                                && u.getDeletedAt() == null)
                        .orElse(null);
                if (user == null)
                {
                    errors.add("An internal signatory must be a user of the organisation");
                }
                else if (user.getStatus() == UserStatus.DEACTIVATED || user.getStatus() == UserStatus.LOCKED)
                {
                    errors.add(WorkflowViews.name(user) + "'s account is " + text(user.getStatus()));
                }
            }
            else
            {
                user = signatory.getInternalUser();
                if (user == null)
                {
                    errors.add("Say which user of the organisation signs");
                }
            }
        }
        signatory.setInternalUser(user);

        String fullName = StringUtils.trimToNull(dto.getFullName());
        if (fullName == null)
        {
            fullName = user != null ? WorkflowViews.name(user) : signatory.getFullName();
        }
        String email = StringUtils.trimToNull(dto.getEmailAddress());
        if (email == null)
        {
            email = user != null ? user.getEmailAddress() : signatory.getEmailAddress();
        }
        if (StringUtils.isBlank(fullName))
        {
            errors.add("The signatory's full name is required");
        }
        if (email == null || !EMAIL.matcher(email).matches())
        {
            errors.add("A valid e-mail address is required for " + StringUtils.defaultIfBlank(fullName, "the signatory"));
        }
        signatory.setFullName(fullName);
        signatory.setEmailAddress(email == null ? null : email.toLowerCase());
        if (dto.getPhoneNumber() != null || creating)
        {
            signatory.setPhoneNumber(StringUtils.trimToNull(dto.getPhoneNumber()));
        }
        if (dto.getOrganisationName() != null || creating)
        {
            signatory.setOrganisationName(StringUtils.trimToNull(dto.getOrganisationName()));
        }
        if (dto.getDesignation() != null || creating)
        {
            signatory.setDesignation(StringUtils.trimToNull(dto.getDesignation()));
        }
        if (type == SignatoryType.EXTERNAL_ORGANISATION_REPRESENTATIVE && signatory.getOrganisationName() == null)
        {
            errors.add("Say which organisation " + StringUtils.defaultIfBlank(fullName, "the signatory") + " represents");
        }

        VerificationMethod method = upgraded ? VerificationMethod.INTERNAL_ACCOUNT
                : dto.getVerificationMethod() != null ? dto.getVerificationMethod()
                : !creating && (signatory.getVerificationMethod() != VerificationMethod.INTERNAL_ACCOUNT
                        || type == SignatoryType.INTERNAL_USER) ? signatory.getVerificationMethod()
                : type == SignatoryType.INTERNAL_USER ? VerificationMethod.INTERNAL_ACCOUNT
                : workflow.getVerificationMethod();
        if (method == VerificationMethod.INTERNAL_ACCOUNT && type != SignatoryType.INTERNAL_USER)
        {
            errors.add("Only users of the organisation can sign with their own account");
        }
        if (method == VerificationMethod.EMAIL_LINK
                && workflow.getAssuranceLevel().ordinal() >= SignatureAssuranceLevel.ELECTRONIC_VERIFIED.ordinal())
        {
            errors.add("The workflow needs verified signatures: an e-mail link is not enough to verify "
                    + StringUtils.defaultIfBlank(fullName, "the signatory"));
        }
        signatory.setVerificationMethod(method);

        signatory.setMandatory(dto.getMandatory() != null ? dto.getMandatory() : !creating ? signatory.isMandatory() : true);
        boolean canDecline = dto.getCanDecline() != null ? dto.getCanDecline()
                : !creating ? signatory.isCanDecline() : workflow.isAllowDecline();
        if (canDecline && !workflow.isAllowDecline())
        {
            errors.add("The workflow does not allow signatories to decline");
        }
        signatory.setCanDecline(canDecline);
        signatory.setDeclineTerminatesWorkflow(dto.getDeclineTerminatesWorkflow() != null ? dto.getDeclineTerminatesWorkflow()
                : !creating ? signatory.isDeclineTerminatesWorkflow() : workflow.isDeclineTerminatesWorkflow());
        if (dto.getDeadline() != null || creating)
        {
            signatory.setDeadline(dto.getDeadline());
        }
        if (dto.getDeadline() != null)
        {
            if (!dto.getDeadline().isAfter(LocalDateTime.now()))
            {
                errors.add("The deadline must be in the future");
            }
            else if (workflow.getExpiresAt() != null && dto.getDeadline().isAfter(workflow.getExpiresAt()))
            {
                errors.add("The deadline cannot be after the workflow expires");
            }
        }
        signatory.setNotifyByEmail(dto.getNotifyByEmail() != null ? dto.getNotifyByEmail()
                : !creating ? signatory.isNotifyByEmail() : true);
        signatory.setNotifyBySms(dto.getNotifyBySms() != null ? dto.getNotifyBySms()
                : !creating ? signatory.isNotifyBySms() : method == VerificationMethod.SMS_OTP);
        if ((signatory.isNotifyBySms() || method == VerificationMethod.SMS_OTP) && signatory.getPhoneNumber() == null)
        {
            errors.add("A phone number is required to reach " + StringUtils.defaultIfBlank(fullName, "the signatory")
                    + " by SMS");
        }
        signatory.setReceiveCompletedCopy(dto.getReceiveCompletedCopy() != null ? dto.getReceiveCompletedCopy()
                : !creating ? signatory.isReceiveCompletedCopy() : workflow.isSendCompletedCopy());
        signatory.setColour(StringUtils.isNotBlank(dto.getColour()) ? dto.getColour().trim()
                : signatory.getColour() != null ? signatory.getColour()
                : COLOURS[workflow.getSignatories().size() % COLOURS.length]);
        if (dto.getLocale() != null || creating)
        {
            signatory.setLocale(StringUtils.trimToNull(dto.getLocale()));
        }
        if (dto.getSigningOrder() != null)
        {
            if (dto.getSigningOrder() < 1)
            {
                errors.add("The signing order starts at 1");
            }
            signatory.setSigningOrder(dto.getSigningOrder());
        }
        else if (creating)
        {
            signatory.setSigningOrder(nextOrder(workflow, signatory.getStage(), signatory));
        }

        String address = signatory.getEmailAddress();
        boolean duplicate = address != null && workflow.getSignatories().stream()
                .anyMatch(other -> other != signatory && other.getStage() == signatory.getStage()
                        && other.getStatus() != SignatoryStatus.CANCELLED
                        && address.equalsIgnoreCase(other.getEmailAddress()));
        if (duplicate)
        {
            errors.add(address + " already signs in stage " + signatory.getStage().getStageNumber());
        }
        failIfAny(errors);
    }

    private static int nextOrder(SigningWorkflow workflow, SigningStage stage, Signatory signatory)
    {
        return workflow.getSignatories().stream()
                .filter(other -> other != signatory && other.getStage() == stage)
                .mapToInt(Signatory::getSigningOrder)
                .max()
                .orElse(0) + 1;
    }

    private static Signatory signatory(SigningWorkflow workflow, String signatoryId)
    {
        return workflow.getSignatories().stream()
                .filter(s -> s.getId() != null && s.getId().toString().equalsIgnoreCase(StringUtils.trimToEmpty(signatoryId)))
                .findFirst()
                .orElseThrow(() -> SigningWorkflowServiceException.notFound("The signatory does not exist"));
    }

    /** Removes the fields assigned to the signatories and flushes, so the signatories can go next. */
    private void removeFieldsOf(SigningWorkflow workflow, List<Signatory> signatories)
    {
        workflow.getFields().removeIf(field -> field.getAssignment() != null
                && signatories.contains(field.getAssignment().getSignatory()));
        signingWorkflowRepository.saveAndFlush(workflow);
    }

    // ---------------------------------------------------------------- fields

    private static void checkField(SigningWorkflow workflow, SigningFieldDTO field, int pageCount, String prefix,
            List<String> errors)
    {
        if (field.getFieldType() == null)
        {
            errors.add(prefix + "say what kind of field it is");
        }
        if (StringUtils.isBlank(field.getSignatoryId()) || workflow.getSignatories().stream()
                .noneMatch(s -> s.getId().toString().equalsIgnoreCase(field.getSignatoryId().trim())))
        {
            errors.add(prefix + "assign it to a signatory of the workflow");
        }
        if (field.getPageNumber() == null || field.getPageNumber() < 1 || field.getPageNumber() > pageCount)
        {
            errors.add(prefix + "the page must be between 1 and " + pageCount);
        }
        if (field.getXRatio() == null || field.getYRatio() == null || field.getWidthRatio() == null
                || field.getHeightRatio() == null)
        {
            errors.add(prefix + "give its position and size");
        }
        else if (field.getXRatio() < 0 || field.getYRatio() < 0 || field.getWidthRatio() <= 0 || field.getHeightRatio() <= 0
                || field.getXRatio() + field.getWidthRatio() > 1 + EPSILON
                || field.getYRatio() + field.getHeightRatio() > 1 + EPSILON)
        {
            errors.add(prefix + "it must lie within the page");
        }
        if (field.getPageRotation() != null && !ROTATIONS.contains(field.getPageRotation()))
        {
            errors.add(prefix + "the page rotation must be 0, 90, 180 or 270 degrees");
        }
        if ((field.getPageWidthPoints() != null && field.getPageWidthPoints() <= 0)
                || (field.getPageHeightPoints() != null && field.getPageHeightPoints() <= 0))
        {
            errors.add(prefix + "the page size must be positive");
        }
        if (field.getFieldType() == FieldType.RADIO && (StringUtils.isBlank(field.getRadioGroup())
                || field.getOptions() == null || field.getOptions().isEmpty()))
        {
            errors.add(prefix + "a radio button needs a group and options");
        }
        if (field.getMaxLength() != null && field.getMaxLength() < 1)
        {
            errors.add(prefix + "the maximum length must be at least 1");
        }
        if (field.getFontSize() != null && field.getFontSize() <= 0)
        {
            errors.add(prefix + "the font size must be positive");
        }
        if (StringUtils.isNotBlank(field.getValidationRegex()))
        {
            try
            {
                Pattern.compile(field.getValidationRegex());
            }
            catch (PatternSyntaxException e)
            {
                errors.add(prefix + "the validation pattern is not a valid regular expression");
            }
        }
    }

    /** Copies a checked field, taking the page geometry from the document where the request leaves it out. */
    private void applyField(SigningField field, SigningFieldDTO dto, Signatory signatory, DocumentPage page, int tabOrder)
    {
        field.setFieldType(dto.getFieldType());
        field.setLabel(StringUtils.trimToNull(dto.getLabel()));
        field.setPageNumber(dto.getPageNumber());
        field.setXRatio(dto.getXRatio());
        field.setYRatio(dto.getYRatio());
        field.setWidthRatio(dto.getWidthRatio());
        field.setHeightRatio(dto.getHeightRatio());
        field.setPageWidthPoints(dto.getPageWidthPoints() != null ? dto.getPageWidthPoints()
                : page != null ? page.getWidthPoints() : A4_WIDTH);
        field.setPageHeightPoints(dto.getPageHeightPoints() != null ? dto.getPageHeightPoints()
                : page != null ? page.getHeightPoints() : A4_HEIGHT);
        field.setPageRotation(dto.getPageRotation() != null ? dto.getPageRotation() : page != null ? page.getRotation() : 0);
        // Signatures are always required; other fields when the preparer says so
        field.setRequired(dto.getFieldType() == FieldType.SIGNATURE || Boolean.TRUE.equals(dto.getRequired()));
        field.setReadOnly(Boolean.TRUE.equals(dto.getReadOnly()));
        field.setPlaceholder(StringUtils.trimToNull(dto.getPlaceholder()));
        field.setDefaultValue(dto.getDefaultValue());
        field.setFormatPattern(StringUtils.trimToNull(dto.getFormatPattern()));
        field.setValidationRegex(StringUtils.trimToNull(dto.getValidationRegex()));
        field.setMaxLength(dto.getMaxLength());
        field.setFontSize(dto.getFontSize());
        field.setRadioGroup(StringUtils.trimToNull(dto.getRadioGroup()));
        field.setOptionsJson(WorkflowViews.options(dto.getOptions()));
        field.setTabOrder(dto.getTabOrder() != null ? dto.getTabOrder() : tabOrder);

        SigningAssignment assignment = field.getAssignment();
        if (assignment == null)
        {
            assignment = SigningAssignment.Factory.newInstance();
            stamp(assignment, true);
            assignment.setAssignedAt(LocalDateTime.now());
            assignment.setCompleted(false);
            assignment.setField(field);
            field.setAssignment(assignment);
        }
        else if (assignment.getSignatory() != signatory)
        {
            assignment.setAssignedAt(LocalDateTime.now());
            stamp(assignment, false);
        }
        assignment.setSignatory(signatory);
        assignment.setRequired(field.isRequired());
    }

    private static int pageCount(Document document, DocumentVersion version)
    {
        return version != null && version.getPageCount() > 0 ? version.getPageCount() : document.getPageCount();
    }

    private static DocumentPage page(DocumentVersion version, Integer pageNumber)
    {
        if (version == null || pageNumber == null)
        {
            return null;
        }
        return version.getPages().stream().filter(p -> p.getPageNumber() == pageNumber).findFirst().orElse(null);
    }

    // ---------------------------------------------------------------- policy

    /**
     * The organisation's signing policy sets the floor for the assurance level; certificate-based levels need
     * digital signatures enabled, and verified levels a verification stronger than the e-mail link.
     */
    private static void checkAssurance(SignatureAssuranceLevel level, VerificationMethod method,
            OrganisationSigningPolicy policy, List<String> errors)
    {
        SignatureAssuranceLevel minimum = policy == null || policy.getMinimumAssuranceLevel() == null
                ? SignatureAssuranceLevel.ELECTRONIC_BASIC : policy.getMinimumAssuranceLevel();
        if (level.ordinal() < minimum.ordinal())
        {
            errors.add("The organisation requires at least " + text(minimum) + " signatures");
        }
        if ((level == SignatureAssuranceLevel.ADVANCED_DIGITAL || level == SignatureAssuranceLevel.QUALIFIED)
                && (policy == null || !policy.isDigitalSignatureEnabled()))
        {
            errors.add("Digital signatures are not enabled for the organisation");
        }
        if (level.ordinal() >= SignatureAssuranceLevel.ELECTRONIC_VERIFIED.ordinal() && method == VerificationMethod.EMAIL_LINK)
        {
            errors.add("Verified signatures need a one-time code or an identity provider, not just the e-mail link");
        }
    }

    /** The requested capture methods (all the policy allows when none are given), stored as text. */
    private static String captureMethods(List<SignatureCaptureMethod> requested, OrganisationSigningPolicy policy,
            List<String> errors)
    {
        List<SignatureCaptureMethod> allowed = new ArrayList<SignatureCaptureMethod>();
        if (policy == null || policy.isAllowDrawnSignature())
        {
            allowed.add(SignatureCaptureMethod.DRAWN);
        }
        if (policy == null || policy.isAllowTypedSignature())
        {
            allowed.add(SignatureCaptureMethod.TYPED);
        }
        if (policy == null || policy.isAllowUploadedSignatureImage())
        {
            allowed.add(SignatureCaptureMethod.UPLOADED_IMAGE);
        }
        List<SignatureCaptureMethod> methods = requested == null || requested.isEmpty() ? allowed : requested;
        for (SignatureCaptureMethod method : methods)
        {
            if (!allowed.contains(method))
            {
                errors.add("The organisation does not allow " + text(method) + " signatures");
            }
        }
        if (methods.isEmpty())
        {
            errors.add("At least one way of capturing a signature must be allowed");
        }
        return WorkflowViews.captureMethods(methods);
    }

    // ---------------------------------------------------------------- helpers

    private SigningWorkflow load(String id)
    {
        SigningWorkflow workflow = signingWorkflowRepository.findById(uuid(id, "workflow"))
                .orElseThrow(() -> SigningWorkflowServiceException.notFound("The signing workflow does not exist"));
        checkTenant(workflow.getOrganisation(), "The signing workflow does not exist");
        return workflow;
    }

    /** The workflow, locked against concurrent transitions until the transaction ends. */
    private SigningWorkflow lock(String id)
    {
        SigningWorkflow workflow = signingWorkflowRepository.findForUpdate(uuid(id, "workflow"))
                .orElseThrow(() -> SigningWorkflowServiceException.notFound("The signing workflow does not exist"));
        checkTenant(workflow.getOrganisation(), "The signing workflow does not exist");
        return workflow;
    }

    /** Records of another tenant are reported as missing; platform administrators have no tenant and see all. */
    private void checkTenant(Organisation organisation, String message)
    {
        caller.tenantId()
                .filter(tenant -> !tenant.equals(organisation.getTenantId()))
                .ifPresent(tenant -> {
                    throw SigningWorkflowServiceException.notFound(message);
                });
    }

    private static void requireEditable(SigningWorkflow workflow)
    {
        if (workflow.isConfigurationLocked() || (workflow.getStatus() != WorkflowStatus.DRAFT
                && workflow.getStatus() != WorkflowStatus.READY))
        {
            throw new SigningWorkflowServiceException(workflow.getStatus() == WorkflowStatus.IN_PROGRESS
                    ? "The workflow has been sent: its configuration is locked (amend it to replace a signatory)"
                    : "A " + text(workflow.getStatus()) + " workflow can no longer be changed");
        }
    }

    /**
     * After a configuration change: a new configuration version, and a READY workflow needs validating again.
     */
    private void touched(SigningWorkflow workflow, WorkflowChangeType type, String description, String before, String reason)
    {
        Actor actor = actor();
        workflow.setConfigurationVersion(workflow.getConfigurationVersion() + 1);
        stamp(workflow, false);
        if (workflow.getStatus() == WorkflowStatus.READY)
        {
            workflow.setStatus(WorkflowStatus.DRAFT);
            support.documentStatus(workflow, DocumentStatus.IN_PREPARATION);
        }
        support.changed(workflow, type, description, before, reason, actor.id());
        support.audit(workflow, AuditEventType.WORKFLOW_UPDATED, actor, null, description);
    }

    private Actor actor()
    {
        String userId = caller.userId();
        String name = organisationUserRepository.findByKeycloakUserId(userId).map(WorkflowViews::name).orElse(userId);
        return new Actor(caller.tenantId().isPresent() ? ActorType.ORGANISATION_USER : ActorType.PLATFORM_ADMIN, userId,
                name);
    }

    private void stamp(Auditable entity, boolean creating)
    {
        LocalDateTime now = LocalDateTime.now();
        if (creating)
        {
            entity.setCreatedAt(now);
            entity.setCreatedBy(caller.userId());
        }
        else
        {
            entity.setModifiedAt(now);
            entity.setModifiedBy(caller.userId());
        }
    }

    private static void between(List<Specification<SigningWorkflow>> specs, String attribute, LocalDate from, LocalDate to)
    {
        if (from != null)
        {
            LocalDateTime start = from.atStartOfDay();
            specs.add((root, query, cb) -> cb.greaterThanOrEqualTo(root.get(attribute), start));
        }
        if (to != null)
        {
            LocalDateTime end = to.plusDays(1).atStartOfDay();
            specs.add((root, query, cb) -> cb.lessThan(root.get(attribute), end));
        }
    }

    private static void failIfAny(List<String> errors)
    {
        if (!errors.isEmpty())
        {
            throw new SigningWorkflowServiceException(String.join("; ", errors));
        }
    }

    private static UUID uuid(String id, String what)
    {
        try
        {
            return UUID.fromString(id.trim());
        }
        catch (IllegalArgumentException | NullPointerException e)
        {
            throw SigningWorkflowServiceException.notFound("'" + id + "' is not the id of a " + what);
        }
    }

    private static UUID criterion(String id, String what)
    {
        try
        {
            return UUID.fromString(id.trim());
        }
        catch (IllegalArgumentException e)
        {
            throw new SigningWorkflowServiceException("'" + id + "' is not the id of a " + what);
        }
    }

    private static String id(Object entity)
    {
        UUID id = entity instanceof Signatory s ? s.getId() : entity instanceof SigningField f ? f.getId() : null;
        return id == null ? null : id.toString();
    }

    /** An enum value as words, e.g. IN_PROGRESS -> "in progress". */
    private static String text(Enum<?> value)
    {
        return value == null ? "unknown" : value.name().toLowerCase().replace('_', ' ');
    }

    // ---------------------------------------------------------------- transaction boundaries

    /*
     * The generated base class declares the public operations; @Transactional on this class does not reach
     * methods it inherits, so each operation is redeclared here to run in one transaction.
     */

    @Override
    @Transactional
    public SigningWorkflowDTO create(final @Valid CreateWorkflowDTO workflow)
    {
        return super.create(workflow);
    }

    @Override
    @Transactional(readOnly = true)
    public SigningWorkflowDTO findById(final String id)
    {
        return super.findById(id);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SigningWorkflowListDTO> search(final @Valid SigningWorkflowSearchCriteria criteria)
    {
        return super.search(criteria);
    }

    @Override
    @Transactional
    public SigningWorkflowDTO update(final String id, final @Valid SigningWorkflowDTO workflow)
    {
        return super.update(id, workflow);
    }

    @Override
    @Transactional
    public SigningStageDTO addStage(final String id, final @Valid SigningStageDTO stage)
    {
        return super.addStage(id, stage);
    }

    @Override
    @Transactional
    public SigningStageDTO updateStage(final String id, final String stageId, final @Valid SigningStageDTO stage)
    {
        return super.updateStage(id, stageId, stage);
    }

    @Override
    @Transactional
    public boolean removeStage(final String id, final String stageId)
    {
        return super.removeStage(id, stageId);
    }

    @Override
    @Transactional
    public List<SigningStageDTO> reorderStages(final String id, final @Valid ReorderRequestDTO order)
    {
        return super.reorderStages(id, order);
    }

    @Override
    @Transactional
    public SignatoryDTO addSignatory(final String id, final @Valid SignatoryDTO signatory)
    {
        return super.addSignatory(id, signatory);
    }

    @Override
    @Transactional
    public SignatoryDTO updateSignatory(final String id, final String signatoryId, final @Valid SignatoryDTO signatory)
    {
        return super.updateSignatory(id, signatoryId, signatory);
    }

    @Override
    @Transactional
    public boolean removeSignatory(final String id, final String signatoryId)
    {
        return super.removeSignatory(id, signatoryId);
    }

    @Override
    @Transactional
    public List<SignatoryDTO> reorderSignatories(final String id, final @Valid ReorderRequestDTO order)
    {
        return super.reorderSignatories(id, order);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SigningFieldDTO> getFields(final String id)
    {
        return super.getFields(id);
    }

    @Override
    @Transactional
    public List<SigningFieldDTO> saveFields(final String id, final @Valid List<SigningFieldDTO> fields)
    {
        return super.saveFields(id, fields);
    }

    @Override
    @Transactional
    public boolean removeField(final String id, final String fieldId)
    {
        return super.removeField(id, fieldId);
    }

    @Override
    @Transactional
    public WorkflowValidationResultDTO validate(final String id)
    {
        return super.validate(id);
    }

    @Override
    @Transactional
    public SigningWorkflowDTO initiate(final String id, final @Valid WorkflowInitiationDTO initiation)
    {
        return super.initiate(id, initiation);
    }

    @Override
    @Transactional
    public SigningWorkflowDTO cancel(final String id, final @Valid WorkflowCancellationDTO cancellation)
    {
        return super.cancel(id, cancellation);
    }

    @Override
    @Transactional
    public SigningWorkflowDTO renew(final String id, final @Valid WorkflowRenewalDTO renewal)
    {
        return super.renew(id, renewal);
    }

    @Override
    @Transactional
    public SigningWorkflowDTO amend(final String id, final @Valid WorkflowAmendmentDTO amendment)
    {
        return super.amend(id, amendment);
    }

    @Override
    @Transactional
    public void resendInvitation(final String id, final String signatoryId)
    {
        super.resendInvitation(id, signatoryId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<WorkflowTimelineEntryDTO> getTimeline(final String id)
    {
        return super.getTimeline(id);
    }

    @Override
    @Transactional(readOnly = true)
    public List<WorkflowConfigurationChangeDTO> getConfigurationHistory(final String id)
    {
        return super.getConfigurationHistory(id);
    }

    @Override
    @Transactional
    public SigningWorkflowDTO createFromTemplate(final String templateId, final @Valid TemplateInstantiationDTO instantiation)
    {
        return super.createFromTemplate(templateId, instantiation);
    }
}
