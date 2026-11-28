package bw.co.starsign.identity;

import java.util.UUID;

/** The code of a role a user holds. */
public record UserRoleCode(UUID userId, String code)
{
}
