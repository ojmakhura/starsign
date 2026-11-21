package bw.co.starsign.branding;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Generates the portals' colour tokens (CSS custom properties without the leading "--", as in the portal's
 * styles/tailwind.css: color-primary, color-on-primary, color-surface-container, ...) from the branding seeds,
 * in the manner of Material 3: each seed gives a tonal palette whose tone is CIELAB lightness (L*), and each
 * role takes a fixed tone per mode. Tone pairs 50+ apart meet WCAG AA, so problems only come from overrides
 * and are reported by {@link #contrastIssues(Map, String)}.
 */
final class ThemePalette
{
    static final String LIGHT = "light";
    static final String DARK = "dark";

    static final double TEXT_MINIMUM = 4.5;
    static final double UI_MINIMUM = 3.0;

    private static final Pattern HEX = Pattern.compile("^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$");

    /** The accent roles: each has on-, -container and on-...-container tokens. */
    static final List<String> ACCENTS = List.of("primary", "secondary", "tertiary", "error", "success", "warning", "info");

    private ThemePalette()
    {
    }

    static boolean isHex(String colour)
    {
        return colour != null && HEX.matcher(colour.trim()).matches();
    }

    /** #rgb or #rrggbb as lower-case #rrggbb. */
    static String normalise(String colour)
    {
        String hex = colour.trim().substring(1).toLowerCase(Locale.ROOT);
        if (hex.length() == 3)
        {
            hex = "" + hex.charAt(0) + hex.charAt(0) + hex.charAt(1) + hex.charAt(1) + hex.charAt(2) + hex.charAt(2);
        }
        return "#" + hex;
    }

    /**
     * The tokens of one mode. {@code seeds} maps the accent names and "neutral" to #rrggbb colours (all
     * present).
     */
    static Map<String, String> tokens(Map<String, String> seeds, String mode)
    {
        boolean light = LIGHT.equals(mode);
        Map<String, String> tokens = new LinkedHashMap<>();

        for (String accent : ACCENTS)
        {
            double[] lch = lch(seeds.get(accent));
            tokens.put("color-" + accent, tone(lch, light ? 40 : 80));
            tokens.put("color-on-" + accent, tone(lch, light ? 100 : 20));
            tokens.put("color-" + accent + "-container", tone(lch, light ? 90 : 30));
            tokens.put("color-on-" + accent + "-container", tone(lch, light ? 10 : 90));
        }

        double[] seedNeutral = lch(seeds.get("neutral"));
        double[] neutral = { seedNeutral[0], Math.min(seedNeutral[1], 6), seedNeutral[2] };
        double[] neutralVariant = { seedNeutral[0], Math.min(seedNeutral[1] * 2, 12), seedNeutral[2] };
        double[] primary = lch(seeds.get("primary"));

        tokens.put("color-background", tone(neutral, light ? 98 : 6));
        tokens.put("color-on-background", tone(neutral, light ? 10 : 90));
        tokens.put("color-surface", tone(neutral, light ? 98 : 6));
        tokens.put("color-on-surface", tone(neutral, light ? 10 : 90));
        tokens.put("color-surface-dim", tone(neutral, light ? 87 : 6));
        tokens.put("color-surface-bright", tone(neutral, light ? 98 : 24));
        tokens.put("color-surface-container-lowest", tone(neutral, light ? 100 : 4));
        tokens.put("color-surface-container-low", tone(neutral, light ? 96 : 10));
        tokens.put("color-surface-container", tone(neutral, light ? 94 : 12));
        tokens.put("color-surface-container-high", tone(neutral, light ? 92 : 17));
        tokens.put("color-surface-container-highest", tone(neutral, light ? 90 : 22));
        tokens.put("color-surface-variant", tone(neutralVariant, light ? 90 : 30));
        tokens.put("color-on-surface-variant", tone(neutralVariant, light ? 30 : 80));
        tokens.put("color-outline", tone(neutralVariant, light ? 50 : 60));
        tokens.put("color-outline-variant", tone(neutralVariant, light ? 80 : 30));
        tokens.put("color-inverse-surface", tone(neutral, light ? 20 : 90));
        tokens.put("color-inverse-on-surface", tone(neutral, light ? 95 : 20));
        tokens.put("color-inverse-primary", tone(primary, light ? 80 : 40));
        tokens.put("color-surface-tint", tone(primary, light ? 40 : 80));
        tokens.put("color-shadow", tone(neutral, 0));
        tokens.put("color-scrim", tone(neutral, 0));
        return tokens;
    }

    /**
     * Foreground/background pairs below the WCAG AA minimum: 4.5:1 for text, 3:1 for accents used as
     * controls on the surface. Pairs whose tokens are not colours are skipped.
     */
    static List<ContrastIssueDTO> contrastIssues(Map<String, String> tokens, String mode)
    {
        List<String[]> pairs = new ArrayList<>();
        for (String accent : ACCENTS)
        {
            pairs.add(new String[] { "color-on-" + accent, "color-" + accent, "text" });
            pairs.add(new String[] { "color-on-" + accent + "-container", "color-" + accent + "-container", "text" });
        }
        for (String surface : List.of("color-surface", "color-surface-dim", "color-surface-bright",
                "color-surface-container-lowest", "color-surface-container-low", "color-surface-container",
                "color-surface-container-high", "color-surface-container-highest"))
        {
            pairs.add(new String[] { "color-on-surface", surface, "text" });
        }
        pairs.add(new String[] { "color-on-surface-variant", "color-surface", "text" });
        pairs.add(new String[] { "color-on-surface-variant", "color-surface-variant", "text" });
        pairs.add(new String[] { "color-on-background", "color-background", "text" });
        pairs.add(new String[] { "color-inverse-on-surface", "color-inverse-surface", "text" });
        pairs.add(new String[] { "color-primary", "color-surface", "ui" });
        pairs.add(new String[] { "color-error", "color-surface", "ui" });

        List<ContrastIssueDTO> issues = new ArrayList<>();
        for (String[] pair : pairs)
        {
            String foreground = tokens.get(pair[0]);
            String background = tokens.get(pair[1]);
            if (!isHex(foreground) || !isHex(background))
            {
                continue;
            }
            double minimum = "text".equals(pair[2]) ? TEXT_MINIMUM : UI_MINIMUM;
            double ratio = contrast(normalise(foreground), normalise(background));
            if (ratio < minimum)
            {
                ContrastIssueDTO issue = new ContrastIssueDTO();
                issue.setMode(mode);
                issue.setForeground(pair[0]);
                issue.setBackground(pair[1]);
                issue.setRatio(Math.floor(ratio * 100) / 100);
                issue.setMinimum(minimum);
                issues.add(issue);
            }
        }
        return issues;
    }

    /** WCAG contrast ratio of two #rrggbb colours. */
    static double contrast(String first, String second)
    {
        double a = luminance(first);
        double b = luminance(second);
        return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    }

    // ------------------------------------------------------------------------------------------------
    // Colour space: sRGB <-> CIE XYZ (D65) <-> CIELAB / LCh
    // ------------------------------------------------------------------------------------------------

    private static final double XN = 0.95047;
    private static final double YN = 1.0;
    private static final double ZN = 1.08883;

    private static double luminance(String hex)
    {
        double[] rgb = linear(hex);
        return 0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2];
    }

    private static double[] linear(String hex)
    {
        int value = Integer.parseInt(hex.substring(1), 16);
        return new double[] {
            toLinear(((value >> 16) & 0xff) / 255.0),
            toLinear(((value >> 8) & 0xff) / 255.0),
            toLinear((value & 0xff) / 255.0) };
    }

    private static double toLinear(double channel)
    {
        return channel <= 0.04045 ? channel / 12.92 : Math.pow((channel + 0.055) / 1.055, 2.4);
    }

    private static double fromLinear(double channel)
    {
        return channel <= 0.0031308 ? channel * 12.92 : 1.055 * Math.pow(channel, 1 / 2.4) - 0.055;
    }

    /** [L*, chroma, hue in radians] of a #rrggbb colour. */
    static double[] lch(String hex)
    {
        double[] rgb = linear(normalise(hex));
        double x = (0.4124 * rgb[0] + 0.3576 * rgb[1] + 0.1805 * rgb[2]) / XN;
        double y = (0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2]) / YN;
        double z = (0.0193 * rgb[0] + 0.1192 * rgb[1] + 0.9505 * rgb[2]) / ZN;
        double fx = labF(x);
        double fy = labF(y);
        double fz = labF(z);
        double l = 116 * fy - 16;
        double a = 500 * (fx - fy);
        double b = 200 * (fy - fz);
        return new double[] { l, Math.hypot(a, b), Math.atan2(b, a) };
    }

    /**
     * The colour of the seed's hue at lightness {@code tone}, with as much of the seed's chroma as fits in
     * sRGB.
     */
    static String tone(double[] seed, double tone)
    {
        if (tone <= 0)
        {
            return "#000000";
        }
        if (tone >= 100)
        {
            return "#ffffff";
        }
        double[] rgb = rgb(tone, seed[1], seed[2]);
        if (rgb == null)
        {
            // Out of gamut: the largest chroma that fits
            double low = 0;
            double high = seed[1];
            for (int i = 0; i < 24; i++)
            {
                double chroma = (low + high) / 2;
                if (rgb(tone, chroma, seed[2]) == null)
                {
                    high = chroma;
                }
                else
                {
                    low = chroma;
                }
            }
            rgb = rgb(tone, low, seed[2]);
        }
        return String.format(Locale.ROOT, "#%02x%02x%02x", channel(rgb[0]), channel(rgb[1]), channel(rgb[2]));
    }

    private static int channel(double linear)
    {
        return (int) Math.round(Math.max(0, Math.min(1, fromLinear(linear))) * 255);
    }

    /** Linear sRGB of an LCh colour, or null when it is outside the sRGB gamut. */
    private static double[] rgb(double l, double chroma, double hue)
    {
        double a = chroma * Math.cos(hue);
        double b = chroma * Math.sin(hue);
        double fy = (l + 16) / 116;
        double fx = fy + a / 500;
        double fz = fy - b / 200;
        double x = labFInverse(fx) * XN;
        double y = labFInverse(fy) * YN;
        double z = labFInverse(fz) * ZN;
        double r = 3.2406 * x - 1.5372 * y - 0.4986 * z;
        double g = -0.9689 * x + 1.8758 * y + 0.0415 * z;
        double bl = 0.0557 * x - 0.2040 * y + 1.0570 * z;
        double tolerance = 1e-4;
        if (r < -tolerance || r > 1 + tolerance || g < -tolerance || g > 1 + tolerance || bl < -tolerance || bl > 1 + tolerance)
        {
            return null;
        }
        return new double[] { r, g, bl };
    }

    private static double labF(double t)
    {
        return t > 216.0 / 24389 ? Math.cbrt(t) : (24389.0 / 27 * t + 16) / 116;
    }

    private static double labFInverse(double f)
    {
        double cube = f * f * f;
        return cube > 216.0 / 24389 ? cube : (116 * f - 16) / (24389.0 / 27);
    }
}
