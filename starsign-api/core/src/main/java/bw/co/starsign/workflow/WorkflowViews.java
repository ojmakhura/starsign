package bw.co.starsign.workflow;

import bw.co.starsign.identity.OrganisationUser;
import bw.co.starsign.signing.SignatureCaptureMethod;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

/**
 * Builds the workflow DTOs. The generated mappers copy the plain attributes only; the ids and names of
 * associated records, and the stored text forms (capture methods, field options), are filled in here.
 */
@Component
public class WorkflowViews
{
    static final Comparator<SigningStage> STAGE_ORDER = Comparator.comparingInt(SigningStage::getStageNumber);
    static final Comparator<Signatory> SIGNATORY_ORDER = Comparator
            .comparingInt((Signatory s) -> s.getStage() == null ? 0 : s.getStage().getStageNumber())
            .thenComparingInt(Signatory::getSigningOrder);
    static final Comparator<SigningField> FIELD_ORDER = Comparator.comparingInt(SigningField::getPageNumber)
            .thenComparingInt(SigningField::getTabOrder);

    private static final ObjectMapper JSON = new ObjectMapper();

    private final SigningWorkflowMapper signingWorkflowMapper;
    private final SigningStageMapper signingStageMapper;
    private final SignatoryMapper signatoryMapper;
    private final SigningFieldMapper signingFieldMapper;
    private final WorkflowConfigurationChangeMapper workflowConfigurationChangeMapper;

    public WorkflowViews(
        SigningWorkflowMapper signingWorkflowMapper,
        SigningStageMapper signingStageMapper,
        SignatoryMapper signatoryMapper,
        SigningFieldMapper signingFieldMapper,
        WorkflowConfigurationChangeMapper workflowConfigurationChangeMapper)
    {
        this.signingWorkflowMapper = signingWorkflowMapper;
        this.signingStageMapper = signingStageMapper;
        this.signatoryMapper = signatoryMapper;
        this.signingFieldMapper = signingFieldMapper;
        this.workflowConfigurationChangeMapper = workflowConfigurationChangeMapper;
    }

    public SigningWorkflowDTO workflow(SigningWorkflow entity)
    {
        SigningWorkflowDTO dto = signingWorkflowMapper.toSigningWorkflowDTO(entity);
        dto.setOrganisationId(entity.getOrganisation().getId().toString());
        dto.setDocumentId(entity.getDocument().getId().toString());
        dto.setDocumentTitle(entity.getDocument().getTitle());
        dto.setSourceVersionNumber(entity.getSourceVersion() == null ? null : entity.getSourceVersion().getVersionNumber());
        dto.setOwnerId(entity.getOwner().getId().toString());
        dto.setOwnerName(name(entity.getOwner()));
        dto.setTemplateId(entity.getTemplate() == null ? null : entity.getTemplate().getId().toString());
        dto.setFinalArtifactId(entity.getFinalArtifact() == null ? null : entity.getFinalArtifact().getId().toString());
        dto.setAllowedCaptureMethods(captureMethods(entity.getAllowedCaptureMethods()));
        dto.setStages(entity.getStages().stream().sorted(STAGE_ORDER).map(this::stage).toList());
        dto.setFields(fields(entity));
        return dto;
    }

    public SigningWorkflowListDTO listItem(SigningWorkflow entity)
    {
        SigningWorkflowListDTO dto = signingWorkflowMapper.toSigningWorkflowListDTO(entity);
        dto.setDocumentTitle(entity.getDocument().getTitle());
        dto.setOwnerName(name(entity.getOwner()));
        dto.setSignatoryCount(entity.getSignatories().size());
        dto.setSignedCount((int) entity.getSignatories().stream()
                .filter(s -> s.getStatus() == SignatoryStatus.SIGNED)
                .count());
        dto.setStageCount(entity.getStages().size());
        return dto;
    }

    public SigningStageDTO stage(SigningStage entity)
    {
        SigningStageDTO dto = signingStageMapper.toSigningStageDTO(entity);
        dto.setWorkflowId(entity.getWorkflow().getId() == null ? null : entity.getWorkflow().getId().toString());
        // The workflow's list is the one kept up to date while a request changes the configuration
        dto.setSignatories(entity.getWorkflow().getSignatories().stream()
                .filter(s -> s.getStage() == entity)
                .sorted(SIGNATORY_ORDER)
                .map(this::signatory)
                .toList());
        return dto;
    }

    public List<SigningStageDTO> stages(SigningWorkflow workflow)
    {
        return workflow.getStages().stream().sorted(STAGE_ORDER).map(this::stage).toList();
    }

    public SignatoryDTO signatory(Signatory entity)
    {
        SignatoryDTO dto = signatoryMapper.toSignatoryDTO(entity);
        dto.setWorkflowId(entity.getWorkflow().getId() == null ? null : entity.getWorkflow().getId().toString());
        if (entity.getStage() != null)
        {
            dto.setStageId(entity.getStage().getId() == null ? null : entity.getStage().getId().toString());
            dto.setStageNumber(entity.getStage().getStageNumber());
        }
        dto.setInternalUserId(entity.getInternalUser() == null ? null : entity.getInternalUser().getId().toString());
        dto.setFieldCount((int) entity.getWorkflow().getFields().stream()
                .filter(f -> f.getAssignment() != null && f.getAssignment().getSignatory() == entity)
                .count());
        return dto;
    }

    public List<SignatoryDTO> signatories(SigningWorkflow workflow)
    {
        return workflow.getSignatories().stream().sorted(SIGNATORY_ORDER).map(this::signatory).toList();
    }

    public SigningFieldDTO field(SigningField entity)
    {
        SigningFieldDTO dto = signingFieldMapper.toSigningFieldDTO(entity);
        dto.setWorkflowId(entity.getWorkflow().getId() == null ? null : entity.getWorkflow().getId().toString());
        dto.setOptions(options(entity.getOptionsJson()));
        SigningAssignment assignment = entity.getAssignment();
        if (assignment != null && assignment.getSignatory() != null)
        {
            Signatory signatory = assignment.getSignatory();
            dto.setSignatoryId(signatory.getId() == null ? null : signatory.getId().toString());
            dto.setSignatoryName(signatory.getFullName());
            dto.setColour(signatory.getColour());
            dto.setCompleted(assignment.isCompleted());
        }
        return dto;
    }

    public List<SigningFieldDTO> fields(SigningWorkflow workflow)
    {
        return workflow.getFields().stream().sorted(FIELD_ORDER).map(this::field).toList();
    }

    public List<WorkflowConfigurationChangeDTO> changes(SigningWorkflow workflow)
    {
        return workflowConfigurationChangeMapper.toWorkflowConfigurationChangeDTOCollection(workflow.getChanges().stream()
                .sorted(Comparator.comparingInt(WorkflowConfigurationChange::getChangeNumber))
                .toList());
    }

    static String name(OrganisationUser user)
    {
        if (user == null)
        {
            return null;
        }
        String name = StringUtils.trimToEmpty(StringUtils.defaultString(user.getFirstName()) + " "
                + StringUtils.defaultString(user.getLastName()));
        return name.isEmpty() ? user.getEmailAddress() : name;
    }

    /** Capture methods are stored as a comma separated list of names, e.g. {@code DRAWN,TYPED}. */
    static List<SignatureCaptureMethod> captureMethods(String stored)
    {
        List<SignatureCaptureMethod> methods = new ArrayList<SignatureCaptureMethod>();
        for (String name : StringUtils.split(StringUtils.defaultString(stored), ','))
        {
            if (StringUtils.isNotBlank(name))
            {
                methods.add(SignatureCaptureMethod.valueOf(name.trim()));
            }
        }
        return methods;
    }

    static String captureMethods(Collection<SignatureCaptureMethod> methods)
    {
        return String.join(",", methods.stream().distinct().map(Enum::name).toList());
    }

    static List<String> options(String json)
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

    static String options(List<String> options)
    {
        if (options == null || options.isEmpty())
        {
            return null;
        }
        return json(options);
    }

    static String json(Object value)
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
}
