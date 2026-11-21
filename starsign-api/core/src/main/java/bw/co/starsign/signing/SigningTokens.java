package bw.co.starsign.signing;

import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;

/**
 * Tokens, codes and hashes of the signing package. Raw tokens and codes are only ever handed to the signatory;
 * the database keeps their SHA-256 hashes.
 */
final class SigningTokens
{
    private static final SecureRandom RANDOM = new SecureRandom();

    private SigningTokens()
    {
    }

    /** 256 random bits, URL-safe. */
    static String newToken()
    {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** A numeric one-time code. */
    static String newCode(int digits)
    {
        StringBuilder code = new StringBuilder(digits);
        for (int i = 0; i < digits; i++)
        {
            code.append(RANDOM.nextInt(10));
        }
        return code.toString();
    }

    static String sha256(String value)
    {
        return DigestUtils.sha256Hex(value.getBytes(StandardCharsets.UTF_8));
    }

    static String sha256(byte[] value)
    {
        return DigestUtils.sha256Hex(value);
    }

    /** Constant-time comparison of two hex hashes. */
    static boolean sameHash(String expected, String actual)
    {
        if (expected == null || actual == null)
        {
            return false;
        }
        return MessageDigest.isEqual(expected.toLowerCase().getBytes(StandardCharsets.US_ASCII),
                actual.toLowerCase().getBytes(StandardCharsets.US_ASCII));
    }

    /** j***e@example.com */
    static String maskEmail(String email)
    {
        if (StringUtils.isBlank(email) || !email.contains("@"))
        {
            return null;
        }
        String local = StringUtils.substringBefore(email, "@");
        String domain = StringUtils.substringAfter(email, "@");
        String masked = local.length() <= 2
                ? local.charAt(0) + "*"
                : local.charAt(0) + "***" + local.charAt(local.length() - 1);
        return masked + "@" + domain;
    }

    /** ******4567 */
    static String maskPhone(String phone)
    {
        if (StringUtils.isBlank(phone))
        {
            return null;
        }
        String digits = phone.replaceAll("[^0-9+]", "");
        return digits.length() <= 4 ? "****" : "*".repeat(digits.length() - 4) + digits.substring(digits.length() - 4);
    }

    /**
     * Whether the address is inside one of the comma separated ranges (CIDR such as 10.0.0.0/8, or single
     * addresses). No ranges means any address.
     */
    static boolean ipAllowed(String ranges, String ipAddress)
    {
        if (StringUtils.isBlank(ranges))
        {
            return true;
        }
        if (StringUtils.isBlank(ipAddress))
        {
            return false;
        }
        byte[] address = parse(ipAddress.trim());
        if (address == null)
        {
            return false;
        }
        for (String range : StringUtils.split(ranges, ",; "))
        {
            String network = StringUtils.substringBefore(range, "/");
            byte[] base = parse(network);
            if (base == null || base.length != address.length)
            {
                continue;
            }
            int prefix = range.contains("/")
                    ? org.apache.commons.lang3.math.NumberUtils.toInt(StringUtils.substringAfter(range, "/").trim(), -1)
                    : base.length * 8;
            // A malformed range admits nobody rather than everybody
            if (prefix >= 0 && prefix <= base.length * 8 && samePrefix(base, address, prefix))
            {
                return true;
            }
        }
        return false;
    }

    private static boolean samePrefix(byte[] base, byte[] address, int prefix)
    {
        BigInteger mask = prefix == 0
                ? BigInteger.ZERO
                : BigInteger.ONE.shiftLeft(base.length * 8).subtract(BigInteger.ONE)
                        .xor(BigInteger.ONE.shiftLeft(base.length * 8 - prefix).subtract(BigInteger.ONE));
        return new BigInteger(1, base).and(mask).equals(new BigInteger(1, address).and(mask));
    }

    private static byte[] parse(String literal)
    {
        // Only IP literals: a host name here must never trigger a DNS lookup
        if (!literal.matches("[0-9a-fA-F:.]+"))
        {
            return null;
        }
        try
        {
            return InetAddress.getByName(literal).getAddress();
        }
        catch (UnknownHostException e)
        {
            return null;
        }
    }
}
