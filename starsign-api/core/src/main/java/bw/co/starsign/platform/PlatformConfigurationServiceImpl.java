// license-header java merge-point
/**
 * This is only generated once! It will never be overwritten.
 * You can (and have to!) safely modify it by hand.
 * TEMPLATE:    SpringServiceImpl.vsl in andromda-spring cartridge
 * MODEL CLASS: AndroMDAModel::backend::bw.co.starsign::platform::PlatformConfigurationService
 * STEREOTYPE:  Service
 */
package bw.co.starsign.platform;

import bw.co.starsign.audit.ActorType;
import bw.co.starsign.audit.AuditEventDTO;
import bw.co.starsign.audit.AuditEventType;
import bw.co.starsign.audit.AuditOutcome;
import bw.co.starsign.audit.AuditTrailService;
import bw.co.starsign.context.CallerContext;
import bw.co.starsign.signing.SignatureAssuranceLevel;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * @see bw.co.starsign.platform.PlatformConfigurationService
 */
@Service("platformConfigurationService")
@Transactional(propagation = Propagation.REQUIRED, readOnly = false)
public class PlatformConfigurationServiceImpl
    extends PlatformConfigurationServiceBase
{
    private static final Pattern KEY = Pattern.compile("^[a-z0-9]+([.-][a-z0-9]+)*$");
    private static final int MAX_KEY_LENGTH = 150;
    /** Shown instead of a sensitive value; saving it back keeps the stored value. */
    static final String REDACTED = "********";
    /** Lists every category. */
    private static final String ALL = "all";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final CallerContext caller;

    public PlatformConfigurationServiceImpl(
        @Lazy AuditTrailService auditTrailService,
        SystemConfigurationRepository systemConfigurationRepository,
        SystemConfigurationMapper systemConfigurationMapper,
        SecurityPolicyRepository securityPolicyRepository,
        SecurityPolicyMapper securityPolicyMapper,
        CallerContext caller) {

        super(
            auditTrailService,
            systemConfigurationRepository,
            systemConfigurationMapper,
            securityPolicyRepository,
            securityPolicyMapper);
        this.caller = caller;
    }

    /**
     * @see bw.co.starsign.platform.PlatformConfigurationService#findAll(String)
     */
    @Override
    protected List<SystemConfigurationDTO> handleFindAll(String category)
        throws Exception
    {
        require("admin-configuration:list", "see the platform configuration");
        String wanted = category.trim();
        List<SystemConfiguration> configurations = ALL.equalsIgnoreCase(wanted)
                ? systemConfigurationRepository.findAll(Sort.by(Sort.Direction.ASC, "category", "configKey"))
                : systemConfigurationRepository.findByCategoryIgnoreCaseOrderByConfigKeyAsc(wanted);
        return configurations.stream().map(this::toDTO).toList();
    }

    /**
     * @see bw.co.starsign.platform.PlatformConfigurationService#save(@Valid SystemConfigurationDTO)
     */
    @Override
    protected SystemConfigurationDTO handleSave(@Valid SystemConfigurationDTO configuration)
        throws Exception
    {
        require("admin-configuration:edit", "change the platform configuration");
        List<String> errors = new ArrayList<String>();
        String key = StringUtils.trimToEmpty(configuration.getConfigKey()).toLowerCase(Locale.ROOT);
        if (key.isEmpty() || key.length() > MAX_KEY_LENGTH || !KEY.matcher(key).matches())
        {
            errors.add("The key is required: lower case words separated by '.' or '-', e.g. organisations.auto-activate");
        }
        if (StringUtils.isBlank(configuration.getCategory()))
        {
            errors.add("The category is required");
        }
        if (configuration.getValueType() == null)
        {
            errors.add("Say which type the value has");
        }
        if (!errors.isEmpty())
        {
            throw new PlatformConfigurationServiceException(String.join("; ", errors));
        }

        SystemConfiguration entity = systemConfigurationRepository.findByConfigKey(key).orElse(null);
        if (StringUtils.isNotBlank(configuration.getId()) && (entity == null
                || !entity.getId().toString().equalsIgnoreCase(configuration.getId().trim())))
        {
            throw new PlatformConfigurationServiceException("The key of a setting cannot be changed");
        }
        boolean creating = entity == null;
        if (creating)
        {
            entity = SystemConfiguration.Factory.newInstance();
            entity.setConfigKey(key);
            entity.setCreatedBy(caller.userId());
            entity.setCreatedAt(LocalDateTime.now());
        }
        else
        {
            entity.setModifiedBy(caller.userId());
            entity.setModifiedAt(LocalDateTime.now());
        }

        // A secret reference is never shown again, so it cannot be made visible either
        boolean sensitive = Boolean.TRUE.equals(configuration.getSensitive()) || (!creating && entity.isSensitive());
        String value = configuration.getConfigValue();
        boolean keepValue = !creating && sensitive && REDACTED.equals(value);
        if (!keepValue)
        {
            if (value == null)
            {
                throw new PlatformConfigurationServiceException("The value is required");
            }
            value = value.trim();
            checkValue(value, configuration.getValueType());
            entity.setConfigValue(value);
        }
        else if (entity.getValueType() != configuration.getValueType())
        {
            checkValue(entity.getConfigValue(), configuration.getValueType());
        }
        entity.setValueType(configuration.getValueType());
        entity.setDescription(StringUtils.trimToNull(configuration.getDescription()));
        entity.setSensitive(sensitive);
        entity.setCategory(configuration.getCategory().trim().toLowerCase(Locale.ROOT));

        SystemConfiguration saved = systemConfigurationRepository.save(entity);
        // The value of a sensitive setting stays out of the audit trail as well
        audit("SystemConfiguration", saved.getId().toString(), "Setting " + key + (sensitive ? " changed"
                : " set to " + StringUtils.abbreviate(saved.getConfigValue(), 200)));
        return toDTO(saved);
    }

    /**
     * @see bw.co.starsign.platform.PlatformConfigurationService#getSecurityPolicy()
     */
    @Override
    protected SecurityPolicyDTO handleGetSecurityPolicy()
        throws Exception
    {
        // Not limited to administrators: the services read the policy to apply it (OTPs, uploads, invitations)
        return securityPolicyMapper.toSecurityPolicyDTO(policy());
    }

    /**
     * @see bw.co.starsign.platform.PlatformConfigurationService#updateSecurityPolicy(@Valid SecurityPolicyDTO)
     */
    @Override
    protected SecurityPolicyDTO handleUpdateSecurityPolicy(@Valid SecurityPolicyDTO policy)
        throws Exception
    {
        require("admin-configuration:configure", "change the security policy");
        List<String> errors = new ArrayList<String>();
        checkRange(policy.getInvitationTokenBytes(), 16, 128, "Invitation tokens must have 16 to 128 random bytes", errors);
        checkRange(policy.getMaxInvitationValidityHours(), 1, 8760, "Invitations can be valid for 1 to 8760 hours", errors);
        checkRange(policy.getAccessTokenTtlMinutes(), 1, 1440, "Signing access tokens live 1 to 1440 minutes", errors);
        checkRange(policy.getOtpLength(), 4, 10, "One-time passwords have 4 to 10 digits", errors);
        checkRange(policy.getOtpTtlMinutes(), 1, 60, "One-time passwords are valid for 1 to 60 minutes", errors);
        checkRange(policy.getMaxOtpAttempts(), 1, 20, "Allow 1 to 20 one-time password attempts", errors);
        checkRange(policy.getSigningRateLimitPerMinute(), 1, 10000, "The signing rate limit is 1 to 10000 a minute", errors);
        checkRange(policy.getApiRateLimitPerMinute(), 1, 100000, "The API rate limit is 1 to 100000 a minute", errors);
        checkRange(policy.getMaxUploadSizeMb(), 1, 1024, "Uploads can be limited to 1 to 1024 MB", errors);
        checkRange(policy.getAuditSealIntervalMinutes(), 1, 10080, "The audit trail is sealed every 1 to 10080 minutes", errors);
        if (!errors.isEmpty())
        {
            throw new PlatformConfigurationServiceException(String.join("; ", errors));
        }

        // Null fields keep their current value
        SecurityPolicy entity = policy();
        if (policy.getInvitationTokenBytes() != null) entity.setInvitationTokenBytes(policy.getInvitationTokenBytes());
        if (policy.getMaxInvitationValidityHours() != null) entity.setMaxInvitationValidityHours(policy.getMaxInvitationValidityHours());
        if (policy.getAccessTokenTtlMinutes() != null) entity.setAccessTokenTtlMinutes(policy.getAccessTokenTtlMinutes());
        if (policy.getOtpLength() != null) entity.setOtpLength(policy.getOtpLength());
        if (policy.getOtpTtlMinutes() != null) entity.setOtpTtlMinutes(policy.getOtpTtlMinutes());
        if (policy.getMaxOtpAttempts() != null) entity.setMaxOtpAttempts(policy.getMaxOtpAttempts());
        if (policy.getSigningRateLimitPerMinute() != null) entity.setSigningRateLimitPerMinute(policy.getSigningRateLimitPerMinute());
        if (policy.getApiRateLimitPerMinute() != null) entity.setApiRateLimitPerMinute(policy.getApiRateLimitPerMinute());
        if (policy.getMaxUploadSizeMb() != null) entity.setMaxUploadSizeMb(policy.getMaxUploadSizeMb());
        if (policy.getMalwareScanRequired() != null) entity.setMalwareScanRequired(policy.getMalwareScanRequired());
        if (policy.getSanitisePdfOnUpload() != null) entity.setSanitisePdfOnUpload(policy.getSanitisePdfOnUpload());
        if (policy.getRejectEncryptedPdf() != null) entity.setRejectEncryptedPdf(policy.getRejectEncryptedPdf());
        if (policy.getRejectJavascriptPdf() != null) entity.setRejectJavascriptPdf(policy.getRejectJavascriptPdf());
        if (policy.getAuditSealIntervalMinutes() != null) entity.setAuditSealIntervalMinutes(policy.getAuditSealIntervalMinutes());
        if (policy.getIpAddressCaptureEnabled() != null) entity.setIpAddressCaptureEnabled(policy.getIpAddressCaptureEnabled());
        if (policy.getMinimumAssuranceLevel() != null) entity.setMinimumAssuranceLevel(policy.getMinimumAssuranceLevel());
        if (policy.getRequireMfaForAdmins() != null) entity.setRequireMfaForAdmins(policy.getRequireMfaForAdmins());
        entity.setModifiedBy(caller.userId());
        entity.setModifiedAt(LocalDateTime.now());

        SecurityPolicy saved = securityPolicyRepository.save(entity);
        audit("SecurityPolicy", saved.getId().toString(), "Security policy changed");
        return securityPolicyMapper.toSecurityPolicyDTO(saved);
    }

    private void require(String permission, String what)
    {
        if (!caller.has(permission))
        {
            throw new PlatformConfigurationServiceException("Only a platform administrator can " + what);
        }
    }

    /** The policy in force; the first time it is needed it is created with the defaults. */
    private SecurityPolicy policy()
    {
        return securityPolicyRepository.findFirstByOrderByCreatedAtAsc().orElseGet(() -> {
            SecurityPolicy policy = SecurityPolicy.Factory.newInstance();
            policy.setInvitationTokenBytes(32);
            policy.setMaxInvitationValidityHours(720);
            policy.setAccessTokenTtlMinutes(30);
            policy.setOtpLength(6);
            policy.setOtpTtlMinutes(10);
            policy.setMaxOtpAttempts(5);
            policy.setSigningRateLimitPerMinute(30);
            policy.setApiRateLimitPerMinute(600);
            policy.setMaxUploadSizeMb(25);
            // No scanner ships with the platform: requiring one would block every upload
            policy.setMalwareScanRequired(false);
            policy.setSanitisePdfOnUpload(true);
            policy.setRejectEncryptedPdf(true);
            policy.setRejectJavascriptPdf(true);
            policy.setAuditSealIntervalMinutes(60);
            policy.setIpAddressCaptureEnabled(true);
            policy.setMinimumAssuranceLevel(SignatureAssuranceLevel.ELECTRONIC_BASIC);
            policy.setRequireMfaForAdmins(true);
            policy.setCreatedBy("system");
            policy.setCreatedAt(LocalDateTime.now());
            return securityPolicyRepository.save(policy);
        });
    }

    private SystemConfigurationDTO toDTO(SystemConfiguration entity)
    {
        SystemConfigurationDTO dto = systemConfigurationMapper.toSystemConfigurationDTO(entity);
        if (entity.isSensitive())
        {
            dto.setConfigValue(REDACTED);
        }
        return dto;
    }

    private static void checkValue(String value, ConfigurationValueType type)
    {
        String problem = null;
        switch (type)
        {
            case INTEGER:
                try
                {
                    Long.parseLong(value);
                }
                catch (NumberFormatException e)
                {
                    problem = "The value must be a whole number";
                }
                break;
            case BOOLEAN:
                if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value))
                {
                    problem = "The value must be true or false";
                }
                break;
            case DURATION:
                try
                {
                    Duration.parse(value);
                }
                catch (DateTimeParseException e)
                {
                    problem = "The value must be an ISO-8601 duration, e.g. PT15M or P1D";
                }
                break;
            case JSON:
                try
                {
                    JSON.readTree(value);
                }
                catch (JsonProcessingException e)
                {
                    problem = "The value is not valid JSON";
                }
                break;
            default:
                break;
        }
        if (problem != null)
        {
            throw new PlatformConfigurationServiceException(problem);
        }
    }

    private static void checkRange(Integer value, int min, int max, String message, List<String> errors)
    {
        if (value != null && (value < min || value > max))
        {
            errors.add(message);
        }
    }

    private void audit(String entityType, String entityId, String details)
    {
        AuditEventDTO event = new AuditEventDTO();
        event.setChainKey("platform");
        event.setEventType(AuditEventType.SETTINGS_CHANGED);
        event.setOccurredAt(LocalDateTime.now());
        event.setActorType(ActorType.PLATFORM_ADMIN);
        event.setActorId(caller.userId());
        event.setOutcome(AuditOutcome.SUCCESS);
        event.setEntityType(entityType);
        event.setEntityId(entityId);
        event.setDetails(details);
        auditTrailService.record(event);
    }
}
