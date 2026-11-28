package bw.co.starsign.identity;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;

/**
 * The Keycloak user attributes that hold a StarSign user's profile details. Keycloak owns them: they are declared
 * in the realm's user profile (starsign-realm.json), except {@code locale}, which Keycloak manages itself when
 * internationalisation is on.
 */
public final class KeycloakAttributes
{
    public static final String PHONE_NUMBER = "phoneNumber";
    public static final String JOB_TITLE = "jobTitle";
    public static final String LOCALE = "locale";

    private KeycloakAttributes()
    {
    }

    /** The first value of the attribute, or null when the account has none. */
    public static String first(KeycloakUserDTO account, String name)
    {
        if (account == null || account.getAttributes() == null)
        {
            return null;
        }
        Object value = account.getAttributes().get(name);
        if (value instanceof Collection<?> values)
        {
            return values.stream().filter(v -> v != null && StringUtils.isNotBlank(v.toString()))
                .map(Object::toString).findFirst().orElse(null);
        }
        return value == null ? null : StringUtils.trimToNull(value.toString());
    }

    /**
     * Attribute changes for {@link KeycloakAdminService#updateUser}: a null value leaves the attribute as it is,
     * a blank one removes it.
     */
    public static Map<String, Object> changes(String phoneNumber, String jobTitle, String locale)
    {
        Map<String, Object> attributes = new HashMap<>();
        put(attributes, PHONE_NUMBER, phoneNumber);
        put(attributes, JOB_TITLE, jobTitle);
        put(attributes, LOCALE, locale);
        return attributes;
    }

    private static void put(Map<String, Object> attributes, String name, String value)
    {
        if (value != null)
        {
            String trimmed = StringUtils.trimToNull(value);
            attributes.put(name, trimmed == null ? List.of() : List.of(trimmed));
        }
    }
}
