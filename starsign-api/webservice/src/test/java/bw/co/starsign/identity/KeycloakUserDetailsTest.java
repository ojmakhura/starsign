package bw.co.starsign.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class KeycloakUserDetailsTest
{
    private static final String ORGANISATION = UUID.randomUUID().toString();

    private KeycloakAdminService keycloak;
    private RoleService roles;
    private KeycloakUserDetails details;

    @BeforeEach
    void setUp()
    {
        keycloak = mock(KeycloakAdminService.class);
        roles = mock(RoleService.class);
        details = new KeycloakUserDetails(keycloak, roles);
        when(roles.findAll(ORGANISATION)).thenReturn(List.of(
            role("ORG_ADMIN", "ORGANISATION_ADMIN"),
            role("DOCUMENT_SIGNER", "SIGNING_OFFICER"),
            role("LEGAL", "LEGAL")));
    }

    @AfterEach
    void tearDown()
    {
        details.close();
    }

    @Test
    void aUserGetsTheRolesAttributesAndSecurityStateKeycloakHolds()
    {
        when(keycloak.findUserById("kc-ada")).thenReturn(account("kc-ada",
            List.of("STARSIGN_USER", "SIGNING_OFFICER", "LEGAL", "ORGANISATION_ADMIN", "PLATFORM_ADMIN"),
            Map.of(KeycloakAttributes.JOB_TITLE, List.of("Counsel"), KeycloakAttributes.PHONE_NUMBER,
                List.of("+267 71 234 567"), KeycloakAttributes.LOCALE, List.of("en"))));
        when(keycloak.isMfaConfigured("kc-ada")).thenReturn(true);
        when(keycloak.isLockedOut("kc-ada")).thenReturn(false);

        OrganisationUserDTO user = details.fill(user("kc-ada", UserStatus.ACTIVE));

        // StarSign codes, sorted; the base role and platform roles are no StarSign roles of the organisation
        assertEquals(List.of("DOCUMENT_SIGNER", "LEGAL", "ORG_ADMIN"), user.getRoles());
        assertEquals("Counsel", user.getJobTitle());
        assertEquals("+267 71 234 567", user.getPhoneNumber());
        assertEquals("en", user.getLocale());
        assertTrue(user.getEmailVerified());
        assertTrue(user.getMfaEnabled());
        assertEquals(UserStatus.ACTIVE, user.getStatus());
        // The copy in the database wins over Keycloak's name
        assertEquals("Ada", user.getFirstName());
    }

    @Test
    void aBruteForceLockoutShowsAsLocked()
    {
        when(keycloak.findUserById("kc-ada")).thenReturn(account("kc-ada", List.of(), Map.of()));
        when(keycloak.isLockedOut("kc-ada")).thenReturn(true);

        assertEquals(UserStatus.LOCKED, details.fill(user("kc-ada", UserStatus.ACTIVE)).getStatus());
        // A deactivated user stays deactivated
        assertEquals(UserStatus.DEACTIVATED, details.fill(user("kc-ada", UserStatus.DEACTIVATED)).getStatus());
    }

    @Test
    void anAccountWithoutProfileGetsItsNameAndRealmRoles()
    {
        when(keycloak.findUserById("kc-admin")).thenReturn(account("kc-admin", List.of("PLATFORM_ADMIN"), Map.of()));
        OrganisationUserDTO platformAdmin = new OrganisationUserDTO();
        platformAdmin.setKeycloakUserId("kc-admin");
        platformAdmin.setStatus(UserStatus.ACTIVE);

        details.fill(platformAdmin);

        assertEquals(List.of("PLATFORM_ADMIN"), platformAdmin.getRoles());
        assertEquals("Keycloak", platformAdmin.getFirstName());
        assertEquals("kc-admin@acme.test", platformAdmin.getEmailAddress());
    }

    @Test
    void keycloakFailuresLeaveTheDetailsEmpty()
    {
        when(keycloak.findUserById(anyString())).thenThrow(new KeycloakAdminServiceException("Keycloak is down"));
        when(keycloak.isMfaConfigured(anyString())).thenThrow(new KeycloakAdminServiceException("Keycloak is down"));
        when(keycloak.isLockedOut(anyString())).thenThrow(new KeycloakAdminServiceException("Keycloak is down"));

        OrganisationUserDTO user = details.fill(user("kc-ada", UserStatus.ACTIVE));

        assertEquals(List.of(), user.getRoles());
        assertNull(user.getJobTitle());
        assertNull(user.getMfaEnabled());
        assertEquals(UserStatus.ACTIVE, user.getStatus());
    }

    @Test
    void listItemsGetJobTitlesAndRolesOneAccountEach()
    {
        when(keycloak.findUserById("kc-ada")).thenReturn(account("kc-ada", List.of("ORGANISATION_ADMIN"),
            Map.of(KeycloakAttributes.JOB_TITLE, List.of("Counsel"))));
        when(keycloak.findUserById("kc-bob")).thenThrow(new KeycloakAdminServiceException("Keycloak is down"));
        List<OrganisationUserListDTO> items = new ArrayList<>(List.of(item("kc-ada"), item("kc-bob"), item(null)));

        details.fill(items, ORGANISATION);

        assertEquals(List.of("ORG_ADMIN"), items.get(0).getRoles());
        assertEquals("Counsel", items.get(0).getJobTitle());
        assertEquals(List.of(), items.get(1).getRoles());
        assertNull(items.get(1).getJobTitle());
        assertEquals(List.of(), items.get(2).getRoles());
    }

    @Test
    void listsAcrossOrganisationsNameTheBuiltInRolesOnly()
    {
        String none = new UUID(0L, 0L).toString();
        when(roles.findAll(none)).thenReturn(List.of(role("ORG_ADMIN", "ORGANISATION_ADMIN")));
        when(keycloak.findUserById("kc-ada")).thenReturn(account("kc-ada", List.of("ORGANISATION_ADMIN", "LEGAL"),
            Map.of()));
        List<OrganisationUserListDTO> items = List.of(item("kc-ada"));

        details.fill(items, null);

        assertEquals(List.of("ORG_ADMIN"), items.get(0).getRoles());
        assertFalse(items.get(0).getRoles().contains("LEGAL"));
    }

    private static RoleDTO role(String code, String realmRole)
    {
        RoleDTO role = new RoleDTO();
        role.setCode(code);
        role.setKeycloakRoleName(realmRole);
        return role;
    }

    private static KeycloakUserDTO account(String id, List<String> realmRoles, Map<String, Object> attributes)
    {
        KeycloakUserDTO account = new KeycloakUserDTO();
        account.setUserId(id);
        account.setUsername(id);
        account.setEmail(id + "@acme.test");
        account.setFirstName("Keycloak");
        account.setLastName("Name");
        account.setEmailVerified(true);
        account.setRealmRoles(new ArrayList<>(realmRoles));
        account.setAttributes(attributes);
        return account;
    }

    private static OrganisationUserDTO user(String keycloakUserId, UserStatus status)
    {
        OrganisationUserDTO user = new OrganisationUserDTO();
        user.setKeycloakUserId(keycloakUserId);
        user.setOrganisationId(ORGANISATION);
        user.setFirstName("Ada");
        user.setLastName("Lovelace");
        user.setStatus(status);
        return user;
    }

    private static OrganisationUserListDTO item(String keycloakUserId)
    {
        OrganisationUserListDTO item = new OrganisationUserListDTO();
        item.setId(UUID.randomUUID().toString());
        item.setKeycloakUserId(keycloakUserId);
        return item;
    }
}
