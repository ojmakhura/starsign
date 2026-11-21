package bw.co.starsign.notification;

import java.util.Locale;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;

/**
 * The default e-mail layout: a logo header, the message in a white card, and a footer with the
 * organisation's e-mail footer and support details. Table-based with inline styles, as e-mail clients
 * (Outlook in particular) ignore most CSS. A template that is already a whole HTML document
 * ({@code <html>} or {@code <body>}) is sent as it is.
 *
 * The portal's template editor draws the same layout in its preview (notification-templates/template-meta.ts,
 * emailLayout): change both together.
 */
public final class EmailLayout
{
    private static final Pattern DOCUMENT = Pattern.compile("(?i)<\\s*(html|body)[\\s>]");

    /** The StarSign e-mail logo (public/brand/email-logo.png, 560x156): shown at 200x56. */
    static final String DEFAULT_LOGO_PATH = "/brand/email-logo.png";

    /**
     * What the layout shows around the message; every field may be null.
     *
     * @param logoUrl absolute URL of the header logo; no logo row without one
     * @param defaultLogo true when {@code logoUrl} is the StarSign logo, whose size is known
     */
    public record Brand(String appName, String organisationName, String logoUrl, boolean defaultLogo,
            String footer, String supportEmail, String supportUrl)
    {
    }

    private EmailLayout()
    {
    }

    /** True when the body is a whole HTML document of its own, which the layout leaves alone. */
    public static boolean isDocument(String html)
    {
        return html != null && DOCUMENT.matcher(html).find();
    }

    /** The body inside the layout; a whole document is returned unchanged. */
    public static String wrap(String bodyHtml, String subject, Brand brand)
    {
        String body = StringUtils.defaultString(bodyHtml);
        if (isDocument(body))
        {
            return body;
        }
        String appName = StringUtils.defaultIfBlank(brand.appName(), "StarSign");
        StringBuilder out = new StringBuilder(body.length() + 3000);
        out.append("<!doctype html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
                .append("<meta name=\"color-scheme\" content=\"light\"><meta name=\"supported-color-schemes\" content=\"light\">")
                .append("<title>").append(escape(StringUtils.defaultString(subject))).append("</title></head>")
                .append("<body style=\"margin:0;padding:0;background:#f4f7fb;\">")
                // Preheader-free; the outer table gives the grey canvas in every client
                .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" ")
                .append("style=\"background:#f4f7fb;\" bgcolor=\"#f4f7fb\"><tr><td align=\"center\" style=\"padding:32px 12px;\">")
                .append("<table role=\"presentation\" width=\"600\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" ")
                .append("style=\"width:100%;max-width:600px;\">");

        // Header: the logo on white, with the brand's blue rule under it
        out.append("<tr><td bgcolor=\"#ffffff\" style=\"background:#ffffff;padding:24px 32px 20px;")
                .append("border-radius:8px 8px 0 0;border:1px solid #e2e8f0;border-bottom:3px solid #0284c7;\">");
        if (StringUtils.isNotBlank(brand.logoUrl()) && isWebUrl(brand.logoUrl()))
        {
            String alt = escape(appName);
            String src = escape(brand.logoUrl().trim());
            if (brand.defaultLogo())
            {
                out.append("<img src=\"").append(src).append("\" width=\"200\" height=\"56\" alt=\"").append(alt)
                        .append("\" style=\"display:block;width:200px;height:56px;border:0;outline:none;\">");
            }
            else
            {
                out.append("<img src=\"").append(src).append("\" height=\"56\" alt=\"").append(alt)
                        .append("\" style=\"display:block;height:56px;width:auto;max-width:280px;border:0;outline:none;\">");
            }
        }
        else
        {
            out.append("<span style=\"font:700 22px/28px Arial,Helvetica,sans-serif;color:#0f172a;\">")
                    .append(escape(appName)).append("</span>");
        }
        out.append("</td></tr>");

        // Message
        out.append("<tr><td bgcolor=\"#ffffff\" style=\"background:#ffffff;padding:28px 32px 32px;")
                .append("border:1px solid #e2e8f0;border-top:0;border-radius:0 0 8px 8px;")
                .append("font:15px/1.6 Arial,Helvetica,sans-serif;color:#1e293b;\">")
                .append(body)
                .append("</td></tr>");

        // Footer
        out.append("<tr><td style=\"padding:20px 32px 0;font:12px/1.6 Arial,Helvetica,sans-serif;color:#64748b;\" align=\"center\">");
        if (StringUtils.isNotBlank(brand.footer()))
        {
            out.append("<p style=\"margin:0 0 8px;\">").append(escape(brand.footer().trim()).replace("\n", "<br>"))
                    .append("</p>");
        }
        String support = support(brand);
        if (!support.isEmpty())
        {
            out.append("<p style=\"margin:0 0 8px;\">").append(support).append("</p>");
        }
        out.append("<p style=\"margin:0;\">Sent by ").append(escape(appName));
        if (StringUtils.isNotBlank(brand.organisationName()))
        {
            out.append(" on behalf of ").append(escape(brand.organisationName().trim()));
        }
        out.append("</p></td></tr></table></td></tr></table></body></html>");
        return out.toString();
    }

    /** The plain-text footer lines matching {@link #wrap}, to append to the text version. */
    public static String textFooter(Brand brand)
    {
        StringBuilder out = new StringBuilder();
        if (StringUtils.isNotBlank(brand.footer()))
        {
            out.append(brand.footer().trim()).append("\n\n");
        }
        if (StringUtils.isNotBlank(brand.supportEmail()))
        {
            out.append("Support: ").append(brand.supportEmail().trim()).append('\n');
        }
        if (StringUtils.isNotBlank(brand.supportUrl()))
        {
            out.append("Help: ").append(brand.supportUrl().trim()).append('\n');
        }
        out.append("Sent by ").append(StringUtils.defaultIfBlank(brand.appName(), "StarSign"));
        if (StringUtils.isNotBlank(brand.organisationName()))
        {
            out.append(" on behalf of ").append(brand.organisationName().trim());
        }
        return out.toString();
    }

    private static String support(Brand brand)
    {
        StringBuilder out = new StringBuilder();
        String link = "color:#0273b1;text-decoration:underline;";
        if (StringUtils.isNotBlank(brand.supportEmail()))
        {
            String email = escape(brand.supportEmail().trim());
            out.append("Questions? <a href=\"mailto:").append(email).append("\" style=\"").append(link).append("\">")
                    .append(email).append("</a>");
        }
        if (StringUtils.isNotBlank(brand.supportUrl()) && isWebUrl(brand.supportUrl()))
        {
            String url = escape(brand.supportUrl().trim());
            out.append(out.length() == 0 ? "" : " &middot; ").append("<a href=\"").append(url).append("\" style=\"")
                    .append(link).append("\">Help centre</a>");
        }
        return out.toString();
    }

    /** Only http(s) links reach the footer (no javascript: or data: URLs). */
    private static boolean isWebUrl(String url)
    {
        String lower = url.trim().toLowerCase(Locale.ROOT);
        return lower.startsWith("https://") || lower.startsWith("http://");
    }

    private static String escape(String text)
    {
        return NotificationTemplates.escapeHtml(text);
    }
}
