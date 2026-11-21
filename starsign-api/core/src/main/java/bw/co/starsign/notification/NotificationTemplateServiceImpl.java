// license-header java merge-point
/**
 * This is only generated once! It will never be overwritten.
 * You can (and have to!) safely modify it by hand.
 * TEMPLATE:    SpringServiceImpl.vsl in andromda-spring cartridge
 * MODEL CLASS: AndroMDAModel::backend::bw.co.starsign::notification::NotificationTemplateService
 * STEREOTYPE:  Service
 */
package bw.co.starsign.notification;

import bw.co.starsign.context.CallerContext;
import bw.co.starsign.organisation.Organisation;
import bw.co.starsign.organisation.OrganisationRepository;
import jakarta.validation.Valid;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Templates belong to an organisation (its overrides) or to the platform (the defaults, no
 * organisation). Rendering prefers the organisation's template, then the platform's, best locale first.
 *
 * @see bw.co.starsign.notification.NotificationTemplateService
 */
@Service("notificationTemplateService")
@Transactional(propagation = Propagation.REQUIRED, readOnly = false)
public class NotificationTemplateServiceImpl
    extends NotificationTemplateServiceBase
{
    private static final Pattern CODE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.-]{0,99}$");
    private static final Pattern LOCALE = Pattern.compile("^[A-Za-z]{2,3}([-_][A-Za-z0-9]{2,8})*$");

    private final OrganisationRepository organisationRepository;
    private final NotificationTemplates templates;
    private final CallerContext caller;

    public NotificationTemplateServiceImpl(
        NotificationTemplateRepository notificationTemplateRepository,
        NotificationTemplateMapper notificationTemplateMapper,
        OrganisationRepository organisationRepository,
        NotificationTemplates templates,
        @Lazy CallerContext caller) {

        super(
            notificationTemplateRepository,
            notificationTemplateMapper);
        this.organisationRepository = organisationRepository;
        this.templates = templates;
        this.caller = caller;
    }

    /**
     * The organisation's templates and the platform defaults it falls back on.
     *
     * @see bw.co.starsign.notification.NotificationTemplateService#findAll(String)
     */
    @Override
    protected List<NotificationTemplateDTO> handleFindAll(String organisationId)
        throws Exception
    {
        return toDtos(notificationTemplateRepository.findForOrganisation(uuid(organisationId, "organisation")));
    }

    /**
     * @see bw.co.starsign.notification.NotificationTemplateService#findById(String)
     */
    @Override
    protected NotificationTemplateDTO handleFindById(String id)
        throws Exception
    {
        return toDto(load(id));
    }

    /**
     * @see bw.co.starsign.notification.NotificationTemplateService#save(NotificationTemplateDTO)
     */
    @Override
    protected NotificationTemplateDTO handleSave(@Valid NotificationTemplateDTO template)
        throws Exception
    {
        check(template);

        LocalDateTime now = LocalDateTime.now();
        NotificationTemplate entity;
        if (StringUtils.isBlank(template.getId()))
        {
            entity = NotificationTemplate.Factory.newInstance();
            if (StringUtils.isNotBlank(template.getOrganisationId()))
            {
                Organisation organisation = organisationRepository.findById(uuid(template.getOrganisationId(), "organisation"))
                        .orElseThrow(() -> NotificationTemplateServiceException.notFound("The organisation does not exist"));
                entity.setOrganisation(organisation);
            }
            entity.setCreatedAt(now);
            entity.setCreatedBy(caller.userId());
        }
        else
        {
            entity = load(template.getId());
            String current = entity.getOrganisation() == null ? null : entity.getOrganisation().getId().toString();
            if (!StringUtils.equalsIgnoreCase(current, StringUtils.trimToNull(template.getOrganisationId())))
            {
                throw new NotificationTemplateServiceException("A template cannot be moved to another organisation");
            }
            entity.setModifiedAt(now);
            entity.setModifiedBy(caller.userId());
        }

        String code = template.getCode().trim();
        String locale = template.getLocale().trim().replace('_', '-');
        UUID organisationId = entity.getOrganisation() == null ? null : entity.getOrganisation().getId();
        final NotificationTemplate saving = entity;
        boolean taken = notificationTemplateRepository
                .findByCodeIgnoreCaseAndChannelAndLocaleIgnoreCase(code, template.getChannel(), locale).stream()
                .filter(other -> !other.equals(saving))
                .anyMatch(other -> Objects.equals(organisationId,
                        other.getOrganisation() == null ? null : other.getOrganisation().getId()));
        if (taken)
        {
            throw new NotificationTemplateServiceException("There is already a " + template.getChannel()
                    + " template with the code " + code + " for the locale " + locale);
        }

        entity.setCode(code);
        entity.setEventType(template.getEventType());
        entity.setChannel(template.getChannel());
        entity.setLocale(locale);
        entity.setSubjectTemplate(StringUtils.trimToNull(template.getSubjectTemplate()));
        entity.setBodyTemplate(template.getBodyTemplate());
        entity.setActive(template.getActive() == null || template.getActive());
        // Only platform templates can be the system default of an event
        entity.setSystemDefault(entity.getOrganisation() == null && Boolean.TRUE.equals(template.getSystemDefault()));
        return toDto(notificationTemplateRepository.save(entity));
    }

    /**
     * @see bw.co.starsign.notification.NotificationTemplateService#remove(String)
     */
    @Override
    protected boolean handleRemove(String id)
        throws Exception
    {
        NotificationTemplate entity = load(id);
        if (entity.isSystemDefault())
        {
            throw new NotificationTemplateServiceException(
                    "A system default template cannot be removed: deactivate it or change its wording instead");
        }
        notificationTemplateRepository.delete(entity);
        return true;
    }

    /**
     * @see bw.co.starsign.notification.NotificationTemplateService#render(String, NotificationChannel, String, String, Map)
     */
    @Override
    protected String handleRender(String code, NotificationChannel channel, String locale, String organisationId, Map model)
        throws Exception
    {
        NotificationTemplate template = templates.byCode(code, channel, locale, uuid(organisationId, "organisation"))
                .orElseThrow(() -> NotificationTemplateServiceException.notFound(
                        "There is no active " + channel + " template with the code " + code.trim()));
        return NotificationTemplates.render(template.getBodyTemplate(), model, channel == NotificationChannel.EMAIL);
    }

    /**
     * The body of an unsaved template rendered with sample values.
     *
     * @see bw.co.starsign.notification.NotificationTemplateService#preview(NotificationTemplateDTO, Map)
     */
    @Override
    protected String handlePreview(@Valid NotificationTemplateDTO template, Map model)
        throws Exception
    {
        if (StringUtils.isBlank(template.getBodyTemplate()))
        {
            throw new NotificationTemplateServiceException("The body is required");
        }
        return NotificationTemplates.render(template.getBodyTemplate(), model,
                template.getChannel() == null || template.getChannel() == NotificationChannel.EMAIL);
    }

    private static void check(NotificationTemplateDTO template)
    {
        List<String> errors = new ArrayList<String>();
        if (StringUtils.isBlank(template.getCode()) || !CODE.matcher(template.getCode().trim()).matches())
        {
            errors.add("The code is required: up to 100 letters, digits, '.', '-' or '_'");
        }
        if (template.getEventType() == null)
        {
            errors.add("Say which event the template is for");
        }
        if (template.getChannel() == null)
        {
            errors.add("Say which channel the template is for: EMAIL, SMS or IN_APP");
        }
        if (StringUtils.isBlank(template.getLocale()) || !LOCALE.matcher(template.getLocale().trim()).matches())
        {
            errors.add("The locale is required, e.g. en or en-BW");
        }
        if (StringUtils.isBlank(template.getBodyTemplate()))
        {
            errors.add("The body is required");
        }
        if (template.getChannel() == NotificationChannel.EMAIL && StringUtils.isBlank(template.getSubjectTemplate()))
        {
            errors.add("An email template needs a subject");
        }
        if (StringUtils.length(template.getSubjectTemplate()) > 255)
        {
            errors.add("The subject is longer than 255 characters");
        }
        if (!errors.isEmpty())
        {
            throw new NotificationTemplateServiceException(String.join("; ", errors));
        }
    }

    private NotificationTemplate load(String id)
    {
        return notificationTemplateRepository.findById(uuid(id, "template"))
                .orElseThrow(() -> NotificationTemplateServiceException.notFound("The template does not exist"));
    }

    private NotificationTemplateDTO toDto(NotificationTemplate entity)
    {
        NotificationTemplateDTO dto = notificationTemplateMapper.toNotificationTemplateDTO(entity);
        dto.setOrganisationId(entity.getOrganisation() == null ? null : entity.getOrganisation().getId().toString());
        return dto;
    }

    private List<NotificationTemplateDTO> toDtos(List<NotificationTemplate> entities)
    {
        List<NotificationTemplateDTO> dtos = new ArrayList<NotificationTemplateDTO>(entities.size());
        for (NotificationTemplate entity : entities)
        {
            dtos.add(toDto(entity));
        }
        return dtos;
    }

    private static UUID uuid(String id, String what)
    {
        try
        {
            return UUID.fromString(id.trim());
        }
        catch (IllegalArgumentException e)
        {
            throw NotificationTemplateServiceException.notFound("'" + id + "' is not the id of a " + what);
        }
    }
}
