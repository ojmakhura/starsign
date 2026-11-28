package bw.co.starsign.identity;

import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Moves the profile details StarSign used to keep in ORGANISATION_USER (job title, phone number, locale) to the
 * Keycloak user attributes, which own them now, then empties the old columns. Runs in the background after startup
 * and copies a value only where the Keycloak attribute is still empty, so it never overwrites what users changed in
 * Keycloak. Users whose Keycloak account cannot be updated keep their values and are tried again at the next start.
 * Does nothing once the columns are empty or gone (changeset 002-keycloak-owned-users keeps them until a later
 * release drops them).
 */
@Component
public class KeycloakProfileBackfill
{
    private static final Logger logger = LoggerFactory.getLogger(KeycloakProfileBackfill.class);

    private static final List<String> COLUMNS = List.of("job_title", "phone_number", "locale");

    private final JdbcTemplate jdbc;
    private final KeycloakAdminService keycloakAdminService;
    private final boolean enabled;

    public KeycloakProfileBackfill(JdbcTemplate jdbc, KeycloakAdminService keycloakAdminService,
        @Value("${app.keycloak-admin.profile-backfill:true}") boolean enabled)
    {
        this.jdbc = jdbc;
        this.keycloakAdminService = keycloakAdminService;
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start()
    {
        if (enabled)
        {
            Thread.ofVirtual().name("keycloak-profile-backfill").start(this::run);
        }
    }

    /** @return how many users' details were moved to Keycloak */
    int run()
    {
        try
        {
            if (!columnsExist())
            {
                return 0;
            }
            List<Map<String, Object>> rows = jdbc.queryForList("select id, keycloak_user_id, job_title, phone_number,"
                + " locale from organisation_user where job_title is not null or phone_number is not null"
                + " or locale is not null");
            int moved = 0;
            for (Map<String, Object> row : rows)
            {
                if (move(row))
                {
                    moved++;
                }
            }
            if (!rows.isEmpty())
            {
                logger.info("Moved the profile details of {} of {} users to Keycloak", moved, rows.size());
            }
            return moved;
        }
        catch (RuntimeException e)
        {
            logger.warn("Could not move the users' profile details to Keycloak: {}", e.getMessage());
            return 0;
        }
    }

    private boolean move(Map<String, Object> row)
    {
        String userId = (String) row.get("keycloak_user_id");
        try
        {
            KeycloakUserDTO account = keycloakAdminService.findUserById(userId);
            Map<String, Object> changes = KeycloakAttributes.changes(
                missing(account, KeycloakAttributes.PHONE_NUMBER, row.get("phone_number")),
                missing(account, KeycloakAttributes.JOB_TITLE, row.get("job_title")),
                missing(account, KeycloakAttributes.LOCALE, row.get("locale")));
            if (!changes.isEmpty())
            {
                KeycloakUserDTO update = new KeycloakUserDTO();
                update.setUserId(userId);
                update.setAttributes(changes);
                keycloakAdminService.updateUser(update);
            }
            jdbc.update("update organisation_user set job_title = null, phone_number = null, locale = null where id = ?",
                row.get("id"));
            return true;
        }
        catch (RuntimeException e)
        {
            logger.warn("Could not move the profile details of user {} to Keycloak: {}", userId, e.getMessage());
            return false;
        }
    }

    /** The old value, when Keycloak has none for the attribute yet; else null (left as it is). */
    private static String missing(KeycloakUserDTO account, String attribute, Object value)
    {
        String old = value == null ? null : StringUtils.trimToNull(value.toString());
        return old != null && KeycloakAttributes.first(account, attribute) == null ? old : null;
    }

    private boolean columnsExist()
    {
        Integer found = jdbc.queryForObject("select count(*) from information_schema.columns"
            + " where table_name = 'organisation_user' and column_name in ('job_title', 'phone_number', 'locale')"
            + " and table_schema = current_schema()", Integer.class);
        return found != null && found == COLUMNS.size();
    }
}
