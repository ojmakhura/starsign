// license-header java merge-point
/**
 * This is only generated once! It will never be overwritten.
 * You can (and have to!) safely modify it by hand.
 * TEMPLATE:    SpringServiceImpl.vsl in andromda-spring cartridge
 * MODEL CLASS: AndroMDAModel::backend::bw.co.starsign::template::SigningTemplateService
 * STEREOTYPE:  Service
 */
package bw.co.starsign.template;

import bw.co.starsign.audit.ActorType;
import bw.co.starsign.audit.AuditEventDTO;
import bw.co.starsign.audit.AuditEventType;
import bw.co.starsign.audit.AuditOutcome;
import bw.co.starsign.audit.AuditTrailService;
import bw.co.starsign.common.Auditable;
import bw.co.starsign.common.SortDirection;
import bw.co.starsign.context.CallerContext;
import bw.co.starsign.document.Document;
import bw.co.starsign.document.DocumentRepository;
import bw.co.starsign.organisation.Organisation;
import bw.co.starsign.organisation.OrganisationRepository;
import bw.co.starsign.organisation.OrganisationSigningPolicy;
import bw.co.starsign.signing.SignatureAssuranceLevel;
import bw.co.starsign.signing.VerificationMethod;
import bw.co.starsign.workflow.ExecutionMode;
import bw.co.starsign.workflow.FieldType;
import bw.co.starsign.workflow.Signatory;
import bw.co.starsign.workflow.SignatoryStatus;
import bw.co.starsign.workflow.SignatoryType;
import bw.co.starsign.workflow.SigningField;
import bw.co.starsign.workflow.SigningWorkflow;
import bw.co.starsign.workflow.SigningWorkflowDTO;
import bw.co.starsign.workflow.SigningWorkflowRepository;
import bw.co.starsign.workflow.SigningWorkflowService;
import bw.co.starsign.workflow.SigningWorkflowServiceException;
import bw.co.starsign.workflow.WorkflowStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
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
 * @see bw.co.starsign.template.SigningTemplateService
 */
@Service("signingTemplateService")
@Transactional(propagation = Propagation.REQUIRED, readOnly = false)
public class SigningTemplateServiceImpl
    extends SigningTemplateServiceBase
{
    private static final String[] COLOURS = {
        "#2563EB", "#DC2626", "#059669", "#D97706", "#7C3AED", "#DB2777", "#0891B2", "#65A30D" };
    private static final int DEFAULT_EXPIRY_DAYS = 30;
    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 200;
    private static final double EPSILON = 1e-6;
    private static final Map<String, String> SORT_FIELDS = Map.of(
            "name", "name", "status", "status", "createdAt", "createdAt", "modifiedAt", "modifiedAt");
    private static final Comparator<SigningTemplateSignatory> ROLE_ORDER = Comparator
            .comparingInt(SigningTemplateSignatory::getStageNumber)
            .thenComparingInt(SigningTemplateSignatory::getSigningOrder);
    private static final Comparator<SigningTemplateField> FIELD_ORDER = Comparator
            .comparingInt(SigningTemplateField::getPageNumber)
            .thenComparingInt(SigningTemplateField::getTabOrder);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final CallerContext caller;
    private final OrganisationRepository organisationRepository;
    private final DocumentRepository documentRepository;
    private final SigningWorkflowRepository signingWorkflowRepository;

    public SigningTemplateServiceImpl(
        @Lazy SigningWorkflowService signingWorkflowService,
        @Lazy AuditTrailService auditTrailService,
        SigningTemplateRepository signingTemplateRepository,
        SigningTemplateMapper signingTemplateMapper,
        SigningTemplateSignatoryRepository signingTemplateSignatoryRepository,
        SigningTemplateSignatoryMapper signingTemplateSignatoryMapper,
        SigningTemplateFieldRepository signingTemplateFieldRepository,
        SigningTemplateFieldMapper signingTemplateFieldMapper,
        CallerContext caller,
        OrganisationRepository organisationRepository,
        DocumentRepository documentRepository,
        SigningWorkflowRepository signingWorkflowRepository) {

        super(
            signingWorkflowService,
            auditTrailService,
            signingTemplateRepository,
            signingTemplateMapper,
            signingTemplateSignatoryRepository,
            signingTemplateSignatoryMapper,
            signingTemplateFieldRepository,
            signingTemplateFieldMapper);
        this.caller = caller;
        this.organisationRepository = organisationRepository;
        this.documentRepository = documentRepository;
        this.signingWorkflowRepository = signingWorkflowRepository;
    }

    /**
     * @see bw.co.starsign.template.SigningTemplateService#create(@Valid SigningTemplateDTO)
     */
    @Override
    protected SigningTemplateDTO handleCreate(@Valid SigningTemplateDTO template)
        throws Exception
    {
        String tenant = caller.tenantId()
                .orElseThrow(() -> new SigningTemplateServiceException("Templates belong to an organisation: sign in as one of its users"));
        Organisation organisation = organisationRepository.findByTenantId(tenant)
                .orElseThrow(() -> SigningTemplateServiceException.notFound("The organisation does not exist"));
        SigningTemplate entity = SigningTemplate.Factory.newInstance();
        stamp(entity, true);
        entity.setOrganisation(organisation);
        entity.setStatus(TemplateStatus.DRAFT);
        entity.setTemplateVersion(1);
        apply(entity, template, true);
        SigningTemplate saved = persist(entity);
        audit(saved, "Template '" + saved.getName() + "' created");
        return dto(saved);
    }

    /**
     * @see bw.co.starsign.template.SigningTemplateService#findById(String)
     */
    @Override
    protected SigningTemplateDTO handleFindById(String id)
        throws Exception
    {
        return dto(load(id));
    }

    /**
     * @see bw.co.starsign.template.SigningTemplateService#search(@Valid SigningTemplateSearchCriteria)
     */
    @Override
    protected List<SigningTemplateDTO> handleSearch(@Valid SigningTemplateSearchCriteria criteria)
        throws Exception
    {
        List<Specification<SigningTemplate>> specs = new ArrayList<Specification<SigningTemplate>>();
        caller.tenantId().ifPresent(tenant ->
                specs.add((root, query, cb) -> cb.equal(root.get("organisation").get("tenantId"), tenant)));
        if (StringUtils.isNotBlank(criteria.getName()))
        {
            String name = "%" + criteria.getName().trim().toUpperCase() + "%";
            specs.add((root, query, cb) -> cb.like(cb.upper(root.get("name")), name));
        }
        if (criteria.getStatus() != null)
        {
            TemplateStatus status = criteria.getStatus();
            specs.add((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        String sortField = SORT_FIELDS.getOrDefault(StringUtils.trimToEmpty(criteria.getSortField()), "name");
        Sort.Direction direction = criteria.getSortDirection() == SortDirection.DESC ? Sort.Direction.DESC : Sort.Direction.ASC;
        int page = criteria.getPageNumber() == null ? 0 : Math.max(0, criteria.getPageNumber());
        int size = criteria.getPageSize() == null ? DEFAULT_PAGE_SIZE
                : Math.max(1, Math.min(MAX_PAGE_SIZE, criteria.getPageSize()));
        return signingTemplateRepository
                .findAll(Specification.allOf(specs), PageRequest.of(page, size, Sort.by(direction, sortField)))
                .stream()
                .map(this::dto)
                .toList();
    }

    /**
     * @see bw.co.starsign.template.SigningTemplateService#update(String, @Valid SigningTemplateDTO)
     */
    @Override
    protected SigningTemplateDTO handleUpdate(String id, @Valid SigningTemplateDTO template)
        throws Exception
    {
        SigningTemplate entity = load(id);
        if (entity.getStatus() == TemplateStatus.ARCHIVED)
        {
            throw new SigningTemplateServiceException("An archived template cannot be changed");
        }
        apply(entity, template, false);
        // Workflows made from earlier versions keep their own copy of the layout
        entity.setTemplateVersion(entity.getTemplateVersion() + 1);
        stamp(entity, false);
        if (entity.getStatus() == TemplateStatus.ACTIVE)
        {
            checkUsable(entity);
        }
        SigningTemplate saved = persist(entity);
        audit(saved, "Template '" + saved.getName() + "' changed (version " + saved.getTemplateVersion() + ")");
        return dto(saved);
    }

    /**
     * @see bw.co.starsign.template.SigningTemplateService#activate(String)
     */
    @Override
    protected SigningTemplateDTO handleActivate(String id)
        throws Exception
    {
        SigningTemplate entity = load(id);
        if (entity.getStatus() != TemplateStatus.ACTIVE)
        {
            checkUsable(entity);
            entity.setStatus(TemplateStatus.ACTIVE);
            stamp(entity, false);
            audit(entity, "Template '" + entity.getName() + "' activated");
        }
        return dto(signingTemplateRepository.save(entity));
    }

    /**
     * @see bw.co.starsign.template.SigningTemplateService#archive(String)
     */
    @Override
    protected SigningTemplateDTO handleArchive(String id)
        throws Exception
    {
        SigningTemplate entity = load(id);
        if (entity.getStatus() != TemplateStatus.ARCHIVED)
        {
            entity.setStatus(TemplateStatus.ARCHIVED);
            stamp(entity, false);
            audit(entity, "Template '" + entity.getName() + "' archived");
        }
        return dto(signingTemplateRepository.save(entity));
    }

    /**
     * @see bw.co.starsign.template.SigningTemplateService#instantiate(String, @Valid TemplateInstantiationDTO)
     */
    @Override
    protected SigningWorkflowDTO handleInstantiate(String id, @Valid TemplateInstantiationDTO instantiation)
        throws Exception
    {
        load(id);
        try
        {
            return signingWorkflowService.createFromTemplate(id, instantiation);
        }
        catch (SigningWorkflowServiceException e)
        {
            // A broken rule reaches the caller as this service's own (400/404); failures keep their cause
            if (e.getCause() == null)
            {
                throw new SigningTemplateServiceException(e.getMessage(), e.isNotFound());
            }
            throw e;
        }
    }

    /**
     * @see bw.co.starsign.template.SigningTemplateService#createFromWorkflow(String, String)
     */
    @Override
    protected SigningTemplateDTO handleCreateFromWorkflow(String workflowId, String name)
        throws Exception
    {
        SigningWorkflow workflow = signingWorkflowRepository.findById(uuid(workflowId, "workflow"))
                .orElseThrow(() -> SigningTemplateServiceException.notFound("The signing workflow does not exist"));
        checkTenant(workflow.getOrganisation(), "The signing workflow does not exist");

        SigningTemplate template = SigningTemplate.Factory.newInstance();
        stamp(template, true);
        template.setOrganisation(workflow.getOrganisation());
        template.setStatus(TemplateStatus.DRAFT);
        template.setTemplateVersion(1);
        String templateName = StringUtils.defaultIfBlank(StringUtils.trimToNull(name), workflow.getTitle());
        checkName(template, templateName);
        template.setName(templateName);
        template.setDescription("Created from workflow " + workflow.getReferenceNumber());
        template.setExecutionMode(workflow.getExecutionMode());
        template.setMessage(workflow.getMessage());
        LocalDateTime start = workflow.getInitiatedAt() != null ? workflow.getInitiatedAt() : workflow.getCreatedAt();
        long days = start == null || workflow.getExpiresAt() == null ? DEFAULT_EXPIRY_DAYS
                : Duration.between(start, workflow.getExpiresAt()).toDays();
        template.setDefaultExpiryDays((int) Math.max(1, days));
        template.setAssuranceLevel(workflow.getAssuranceLevel());
        template.setVerificationMethod(workflow.getVerificationMethod());
        template.setBaseDocument(workflow.getDocument());
        template.setPageCount(workflow.getSourceVersion() != null && workflow.getSourceVersion().getPageCount() > 0
                ? workflow.getSourceVersion().getPageCount() : workflow.getDocument().getPageCount());

        // One role per signatory who is part of the workflow; replaced signatories are left out
        Map<Signatory, SigningTemplateSignatory> roles = new HashMap<Signatory, SigningTemplateSignatory>();
        Set<String> roleNames = new HashSet<String>();
        List<Signatory> signatories = workflow.getSignatories().stream()
                .filter(s -> s.getStatus() != SignatoryStatus.CANCELLED || workflow.getStatus() == WorkflowStatus.CANCELLED)
                .sorted(Comparator.comparingInt((Signatory s) -> s.getStage().getStageNumber())
                        .thenComparingInt(Signatory::getSigningOrder))
                .toList();
        for (Signatory signatory : signatories)
        {
            String roleName = StringUtils.trimToNull(signatory.getDesignation());
            if (roleName == null || !roleNames.add(roleName.toLowerCase()))
            {
                roleName = "Signatory " + (roles.size() + 1);
                roleNames.add(roleName.toLowerCase());
            }
            SigningTemplateSignatory role = SigningTemplateSignatory.Factory.newInstance();
            stamp(role, true);
            role.setTemplate(template);
            role.setRoleName(roleName);
            role.setStageNumber(signatory.getStage().getStageNumber());
            role.setSigningOrder(signatory.getSigningOrder());
            role.setSignatoryType(signatory.getSignatoryType());
            role.setMandatory(signatory.isMandatory());
            role.setCanDecline(signatory.isCanDecline());
            role.setVerificationMethod(signatory.getVerificationMethod());
            role.setColour(signatory.getColour());
            template.getSignatories().add(role);
            roles.put(signatory, role);
        }
        for (SigningField field : workflow.getFields())
        {
            SigningTemplateSignatory role = field.getAssignment() == null ? null : roles.get(field.getAssignment().getSignatory());
            if (role == null)
            {
                continue;
            }
            SigningTemplateField copy = SigningTemplateField.Factory.newInstance();
            stamp(copy, true);
            copy.setTemplate(template);
            copy.setSignatoryRole(role);
            copy.setFieldType(field.getFieldType());
            copy.setLabel(field.getLabel());
            copy.setPageNumber(field.getPageNumber());
            copy.setXRatio(field.getXRatio());
            copy.setYRatio(field.getYRatio());
            copy.setWidthRatio(field.getWidthRatio());
            copy.setHeightRatio(field.getHeightRatio());
            copy.setRequired(field.isRequired());
            copy.setReadOnly(field.isReadOnly());
            copy.setPlaceholder(field.getPlaceholder());
            copy.setFormatPattern(field.getFormatPattern());
            copy.setValidationRegex(field.getValidationRegex());
            copy.setOptionsJson(field.getOptionsJson());
            copy.setTabOrder(field.getTabOrder());
            template.getFields().add(copy);
        }
        SigningTemplate saved = signingTemplateRepository.saveAndFlush(template);
        audit(saved, "Template '" + saved.getName() + "' created from workflow " + workflow.getReferenceNumber());
        return dto(saved);
    }

    // ---------------------------------------------------------------- definition

    /**
     * Copies the definition. Attributes left out keep their value (or take the organisation's signing policy
     * defaults on create); lists that are given replace the roles and fields. A field names its role by the
     * role's id, or, for a role added in the same request, by the role's name.
     */
    private void apply(SigningTemplate template, SigningTemplateDTO dto, boolean creating)
    {
        OrganisationSigningPolicy policy = template.getOrganisation().getSigningPolicy();
        List<String> errors = new ArrayList<String>();

        String name = dto.getName() != null ? StringUtils.trimToNull(dto.getName()) : template.getName();
        if (name == null)
        {
            errors.add("The name of the template is required");
        }
        else
        {
            try
            {
                checkName(template, name);
            }
            catch (SigningTemplateServiceException e)
            {
                errors.add(e.getMessage());
            }
            template.setName(name);
        }
        if (dto.getDescription() != null || creating)
        {
            template.setDescription(StringUtils.trimToNull(dto.getDescription()));
        }
        if (dto.getMessage() != null || creating)
        {
            template.setMessage(StringUtils.trimToNull(dto.getMessage()));
        }
        template.setExecutionMode(dto.getExecutionMode() != null ? dto.getExecutionMode()
                : !creating ? template.getExecutionMode()
                : policy != null && policy.getDefaultExecutionMode() != null ? policy.getDefaultExecutionMode()
                : ExecutionMode.SEQUENTIAL);
        int expiryDays = dto.getDefaultExpiryDays() != null ? dto.getDefaultExpiryDays()
                : !creating ? template.getDefaultExpiryDays()
                : policy != null && policy.getDefaultExpiryDays() > 0 ? policy.getDefaultExpiryDays() : DEFAULT_EXPIRY_DAYS;
        if (expiryDays < 1)
        {
            errors.add("Workflows from the template must run for at least one day");
        }
        template.setDefaultExpiryDays(expiryDays);

        SignatureAssuranceLevel minimum = policy == null || policy.getMinimumAssuranceLevel() == null
                ? SignatureAssuranceLevel.ELECTRONIC_BASIC : policy.getMinimumAssuranceLevel();
        SignatureAssuranceLevel level = dto.getAssuranceLevel() != null ? dto.getAssuranceLevel()
                : !creating && template.getAssuranceLevel() != null ? template.getAssuranceLevel() : minimum;
        VerificationMethod method = dto.getVerificationMethod() != null ? dto.getVerificationMethod()
                : !creating && template.getVerificationMethod() != null ? template.getVerificationMethod()
                : policy != null && policy.getDefaultVerificationMethod() != null ? policy.getDefaultVerificationMethod()
                : VerificationMethod.EMAIL_LINK;
        boolean verified = level.ordinal() >= SignatureAssuranceLevel.ELECTRONIC_VERIFIED.ordinal();
        if (verified && method == VerificationMethod.EMAIL_LINK && dto.getVerificationMethod() == null)
        {
            method = VerificationMethod.EMAIL_OTP;
        }
        if (level.ordinal() < minimum.ordinal())
        {
            errors.add("The organisation requires at least " + text(minimum) + " signatures");
        }
        if ((level == SignatureAssuranceLevel.ADVANCED_DIGITAL || level == SignatureAssuranceLevel.QUALIFIED)
                && (policy == null || !policy.isDigitalSignatureEnabled()))
        {
            errors.add("Digital signatures are not enabled for the organisation");
        }
        if (verified && method == VerificationMethod.EMAIL_LINK)
        {
            errors.add("Verified signatures need a one-time code or an identity provider, not just the e-mail link");
        }
        template.setAssuranceLevel(level);
        template.setVerificationMethod(method);

        if (dto.getBaseDocumentId() != null)
        {
            if (StringUtils.isBlank(dto.getBaseDocumentId()))
            {
                template.setBaseDocument(null);
            }
            else
            {
                Document document = documentRepository.findById(uuid(dto.getBaseDocumentId(), "document"))
                        .filter(d -> d.getDeletedAt() == null
                                && d.getOrganisation().getId().equals(template.getOrganisation().getId()))
                        .orElseThrow(() -> SigningTemplateServiceException.notFound("The base document does not exist"));
                template.setBaseDocument(document);
            }
        }
        if (dto.getPageCount() != null)
        {
            if (dto.getPageCount() < 1)
            {
                errors.add("The page count must be at least 1");
            }
            template.setPageCount(dto.getPageCount());
        }
        else if (template.getPageCount() == null && template.getBaseDocument() != null)
        {
            template.setPageCount(template.getBaseDocument().getPageCount());
        }
        failIfAny(errors);

        if (dto.getSignatories() != null)
        {
            mergeRoles(template, dto.getSignatories(), policy);
        }
        if (dto.getFields() != null)
        {
            mergeFields(template, dto.getFields());
        }
        if (template.getPageCount() != null)
        {
            int highest = template.getFields().stream().mapToInt(SigningTemplateField::getPageNumber).max().orElse(0);
            if (highest > template.getPageCount())
            {
                throw new SigningTemplateServiceException("The template has " + template.getPageCount()
                        + " pages but places a field on page " + highest);
            }
        }
    }

    private void mergeRoles(SigningTemplate template, List<SigningTemplateSignatoryDTO> roles,
            OrganisationSigningPolicy policy)
    {
        Map<String, SigningTemplateSignatory> existing = new HashMap<String, SigningTemplateSignatory>();
        template.getSignatories().forEach(role -> existing.put(role.getId().toString(), role));
        List<String> errors = new ArrayList<String>();
        Set<String> names = new HashSet<String>();
        Set<String> kept = new HashSet<String>();
        for (int i = 0; i < roles.size(); i++)
        {
            SigningTemplateSignatoryDTO role = roles.get(i);
            String prefix = "Role " + (i + 1) + ": ";
            if (StringUtils.isBlank(role.getRoleName()))
            {
                errors.add(prefix + "the role name is required");
            }
            else if (!names.add(role.getRoleName().trim().toLowerCase()))
            {
                errors.add("More than one role is called " + role.getRoleName().trim());
            }
            if (role.getStageNumber() != null && role.getStageNumber() < 1)
            {
                errors.add(prefix + "stages are numbered from 1");
            }
            if (role.getSigningOrder() != null && role.getSigningOrder() < 1)
            {
                errors.add(prefix + "the signing order starts at 1");
            }
            if (role.getVerificationMethod() == VerificationMethod.INTERNAL_ACCOUNT
                    && role.getSignatoryType() != SignatoryType.INTERNAL_USER)
            {
                errors.add(prefix + "only users of the organisation can sign with their own account");
            }
            if (StringUtils.isNotBlank(role.getId()))
            {
                if (!existing.containsKey(role.getId().trim()))
                {
                    errors.add(prefix + "it is not a role of this template");
                }
                else
                {
                    kept.add(role.getId().trim());
                }
            }
        }
        failIfAny(errors);

        // Roles that are not in the list go, with the fields placed for them
        template.getFields().removeIf(field -> !kept.contains(field.getSignatoryRole().getId().toString()));
        persist(template);
        template.getSignatories().removeIf(role -> !kept.contains(role.getId().toString()));
        persist(template);

        Map<Integer, Integer> orders = new HashMap<Integer, Integer>();
        for (SigningTemplateSignatoryDTO dto : roles)
        {
            SigningTemplateSignatory role = StringUtils.isBlank(dto.getId()) ? null : existing.get(dto.getId().trim());
            if (role == null)
            {
                role = SigningTemplateSignatory.Factory.newInstance();
                stamp(role, true);
                role.setTemplate(template);
                template.getSignatories().add(role);
            }
            else
            {
                stamp(role, false);
            }
            role.setRoleName(dto.getRoleName().trim());
            role.setStageNumber(dto.getStageNumber() == null ? 1 : dto.getStageNumber());
            int order = orders.merge(role.getStageNumber(), 1, Integer::sum);
            role.setSigningOrder(dto.getSigningOrder() == null ? order : dto.getSigningOrder());
            role.setSignatoryType(dto.getSignatoryType() == null ? SignatoryType.EXTERNAL_PERSON : dto.getSignatoryType());
            role.setMandatory(dto.getMandatory() == null || dto.getMandatory());
            role.setCanDecline(dto.getCanDecline() != null ? dto.getCanDecline()
                    : policy == null || policy.isAllowSignatoryDecline());
            role.setVerificationMethod(dto.getVerificationMethod() != null ? dto.getVerificationMethod()
                    : role.getSignatoryType() == SignatoryType.INTERNAL_USER ? VerificationMethod.INTERNAL_ACCOUNT
                    : template.getVerificationMethod());
            role.setColour(StringUtils.isNotBlank(dto.getColour()) ? dto.getColour().trim()
                    : role.getColour() != null ? role.getColour()
                    : COLOURS[(template.getSignatories().size() - 1) % COLOURS.length]);
        }
    }

    private void mergeFields(SigningTemplate template, List<SigningTemplateFieldDTO> fields)
    {
        Map<String, SigningTemplateField> existing = new HashMap<String, SigningTemplateField>();
        template.getFields().forEach(field -> existing.put(field.getId().toString(), field));
        List<String> errors = new ArrayList<String>();
        Set<String> kept = new HashSet<String>();
        List<SigningTemplateSignatory> roles = new ArrayList<SigningTemplateSignatory>();
        for (int i = 0; i < fields.size(); i++)
        {
            SigningTemplateFieldDTO field = fields.get(i);
            String prefix = "Field " + (i + 1) + (StringUtils.isBlank(field.getLabel()) ? "" : " '" + field.getLabel() + "'")
                    + ": ";
            SigningTemplateSignatory role = role(template, field.getSignatoryRoleId());
            roles.add(role);
            if (role == null)
            {
                errors.add(prefix + "assign it to a role of the template");
            }
            if (field.getFieldType() == null)
            {
                errors.add(prefix + "say what kind of field it is");
            }
            if (field.getPageNumber() == null || field.getPageNumber() < 1)
            {
                errors.add(prefix + "the page is numbered from 1");
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
            if (field.getFieldType() == FieldType.RADIO && (field.getOptions() == null || field.getOptions().isEmpty()))
            {
                errors.add(prefix + "a radio button needs options");
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
            if (StringUtils.isNotBlank(field.getId()))
            {
                if (!existing.containsKey(field.getId().trim()))
                {
                    errors.add(prefix + "it is not a field of this template");
                }
                else if (!kept.add(field.getId().trim()))
                {
                    errors.add(prefix + "it is listed more than once");
                }
            }
        }
        failIfAny(errors);

        template.getFields().removeIf(field -> field.getId() != null && !kept.contains(field.getId().toString()));
        persist(template);
        for (int i = 0; i < fields.size(); i++)
        {
            SigningTemplateFieldDTO dto = fields.get(i);
            SigningTemplateField field = StringUtils.isBlank(dto.getId()) ? null : existing.get(dto.getId().trim());
            if (field == null)
            {
                field = SigningTemplateField.Factory.newInstance();
                stamp(field, true);
                field.setTemplate(template);
                template.getFields().add(field);
            }
            else
            {
                stamp(field, false);
            }
            field.setSignatoryRole(roles.get(i));
            field.setFieldType(dto.getFieldType());
            field.setLabel(StringUtils.trimToNull(dto.getLabel()));
            field.setPageNumber(dto.getPageNumber());
            field.setXRatio(dto.getXRatio());
            field.setYRatio(dto.getYRatio());
            field.setWidthRatio(dto.getWidthRatio());
            field.setHeightRatio(dto.getHeightRatio());
            field.setRequired(dto.getFieldType() == FieldType.SIGNATURE || Boolean.TRUE.equals(dto.getRequired()));
            field.setReadOnly(Boolean.TRUE.equals(dto.getReadOnly()));
            field.setPlaceholder(StringUtils.trimToNull(dto.getPlaceholder()));
            field.setFormatPattern(StringUtils.trimToNull(dto.getFormatPattern()));
            field.setValidationRegex(StringUtils.trimToNull(dto.getValidationRegex()));
            field.setOptionsJson(dto.getOptions() == null || dto.getOptions().isEmpty() ? null : json(dto.getOptions()));
            field.setTabOrder(dto.getTabOrder() != null ? dto.getTabOrder() : i + 1);
        }
    }

    /** The role a field names: by id, or by the name of a role (which may have been added in the same request). */
    private static SigningTemplateSignatory role(SigningTemplate template, String reference)
    {
        if (StringUtils.isBlank(reference))
        {
            return null;
        }
        String key = reference.trim();
        return template.getSignatories().stream()
                .filter(role -> role.getId() != null && role.getId().toString().equalsIgnoreCase(key))
                .findFirst()
                .or(() -> template.getSignatories().stream()
                        .filter(role -> key.equalsIgnoreCase(role.getRoleName()))
                        .findFirst())
                .orElse(null);
    }

    /** An active template must give every mandatory role a required signature field. */
    private static void checkUsable(SigningTemplate template)
    {
        List<String> errors = new ArrayList<String>();
        if (template.getSignatories().isEmpty())
        {
            errors.add("Add at least one signatory role");
        }
        for (SigningTemplateSignatory role : template.getSignatories())
        {
            if (role.isMandatory() && template.getFields().stream().noneMatch(field -> field.getSignatoryRole() == role
                    && field.getFieldType() == FieldType.SIGNATURE && field.isRequired()))
            {
                errors.add(role.getRoleName() + " has no signature field");
            }
        }
        failIfAny(errors);
    }

    private void checkName(SigningTemplate template, String name)
    {
        signingTemplateRepository.findByOrganisationIdAndNameIgnoreCase(template.getOrganisation().getId(), name)
                .filter(other -> other != template)
                .ifPresent(other -> {
                    throw new SigningTemplateServiceException("The organisation already has a template called " + name);
                });
    }

    // ---------------------------------------------------------------- views

    private SigningTemplateDTO dto(SigningTemplate entity)
    {
        SigningTemplateDTO dto = signingTemplateMapper.toSigningTemplateDTO(entity);
        dto.setOrganisationId(entity.getOrganisation().getId().toString());
        dto.setBaseDocumentId(entity.getBaseDocument() == null ? null : entity.getBaseDocument().getId().toString());
        dto.setSignatories(entity.getSignatories().stream().sorted(ROLE_ORDER)
                .map(signingTemplateSignatoryMapper::toSigningTemplateSignatoryDTO)
                .toList());
        dto.setFields(entity.getFields().stream().sorted(FIELD_ORDER).map(field -> {
            SigningTemplateFieldDTO fieldDto = signingTemplateFieldMapper.toSigningTemplateFieldDTO(field);
            fieldDto.setSignatoryRoleId(field.getSignatoryRole().getId().toString());
            fieldDto.setOptions(options(field.getOptionsJson()));
            return fieldDto;
        }).toList());
        return dto;
    }

    // ---------------------------------------------------------------- helpers

    private SigningTemplate load(String id)
    {
        SigningTemplate template = signingTemplateRepository.findById(uuid(id, "template"))
                .orElseThrow(() -> SigningTemplateServiceException.notFound("The template does not exist"));
        checkTenant(template.getOrganisation(), "The template does not exist");
        return template;
    }

    /** Templates of another tenant are reported as missing; platform administrators have no tenant and see all. */
    private void checkTenant(Organisation organisation, String message)
    {
        caller.tenantId()
                .filter(tenant -> !tenant.equals(organisation.getTenantId()))
                .ifPresent(tenant -> {
                    throw SigningTemplateServiceException.notFound(message);
                });
    }

    private void audit(SigningTemplate template, String details)
    {
        AuditEventDTO event = new AuditEventDTO();
        event.setEventType(AuditEventType.SETTINGS_CHANGED);
        event.setOccurredAt(LocalDateTime.now());
        event.setOrganisationId(template.getOrganisation().getId().toString());
        event.setActorType(caller.tenantId().isPresent() ? ActorType.ORGANISATION_USER : ActorType.PLATFORM_ADMIN);
        event.setActorId(caller.userId());
        event.setOutcome(AuditOutcome.SUCCESS);
        event.setEntityType("SigningTemplate");
        event.setEntityId(template.getId().toString());
        event.setDetails(details);
        auditTrailService.record(event);
    }

    /**
     * Writes the template and the roles and fields added to it. A new template is persisted; a managed one is only
     * flushed, so its new children are persisted in place. Merging it instead would cascade the merge to the new
     * children as copies, leaving fields that point at roles (and ids) that were never written.
     */
    private SigningTemplate persist(SigningTemplate template)
    {
        if (template.getId() == null)
        {
            return signingTemplateRepository.saveAndFlush(template);
        }
        signingTemplateRepository.flush();
        return template;
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

    private static List<String> options(String json)
    {
        if (StringUtils.isBlank(json))
        {
            return new ArrayList<String>();
        }
        try
        {
            return JSON.readValue(json, new TypeReference<List<String>>() { });
        }
        catch (JsonProcessingException e)
        {
            throw new IllegalStateException("The stored field options are not a JSON list: " + json, e);
        }
    }

    private static String json(Object value)
    {
        try
        {
            return JSON.writeValueAsString(value);
        }
        catch (JsonProcessingException e)
        {
            throw new IllegalStateException("Could not write JSON", e);
        }
    }

    private static void failIfAny(List<String> errors)
    {
        if (!errors.isEmpty())
        {
            throw new SigningTemplateServiceException(String.join("; ", errors));
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
            throw SigningTemplateServiceException.notFound("'" + id + "' is not the id of a " + what);
        }
    }

    private static String text(Enum<?> value)
    {
        return value.name().toLowerCase().replace('_', ' ');
    }

    // ---------------------------------------------------------------- transaction boundaries

    /*
     * The generated base class declares the public operations; @Transactional on this class does not reach
     * methods it inherits, so each operation is redeclared here to run in one transaction.
     */

    @Override
    @Transactional
    public SigningTemplateDTO create(final @Valid SigningTemplateDTO template)
    {
        return super.create(template);
    }

    @Override
    @Transactional(readOnly = true)
    public SigningTemplateDTO findById(final String id)
    {
        return super.findById(id);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SigningTemplateDTO> search(final @Valid SigningTemplateSearchCriteria criteria)
    {
        return super.search(criteria);
    }

    @Override
    @Transactional
    public SigningTemplateDTO update(final String id, final @Valid SigningTemplateDTO template)
    {
        return super.update(id, template);
    }

    @Override
    @Transactional
    public SigningTemplateDTO activate(final String id)
    {
        return super.activate(id);
    }

    @Override
    @Transactional
    public SigningTemplateDTO archive(final String id)
    {
        return super.archive(id);
    }

    @Override
    @Transactional
    public SigningWorkflowDTO instantiate(final String id, final @Valid TemplateInstantiationDTO instantiation)
    {
        return super.instantiate(id, instantiation);
    }

    @Override
    @Transactional
    public SigningTemplateDTO createFromWorkflow(final String workflowId, final String name)
    {
        return super.createFromWorkflow(workflowId, name);
    }
}
