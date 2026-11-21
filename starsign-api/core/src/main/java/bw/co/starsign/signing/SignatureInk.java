package bw.co.starsign.signing;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import javax.imageio.ImageIO;

/**
 * Signatures are stamped in black ink, whatever colour they were captured in.
 *
 * <ul>
 * <li>An image with transparency (the signing pad's PNG: strokes on a transparent background) keeps each pixel's
 * opacity, so anti-aliased edges stay smooth, and every visible pixel becomes black.</li>
 * <li>A fully opaque image (an uploaded scan: ink on paper) is turned into ink: dark pixels become black, the light
 * paper becomes transparent.</li>
 * </ul>
 */
final class SignatureInk
{
    private static final int BLACK = 0x000000;

    private SignatureInk()
    {
    }

    /**
     * @return the signature in black ink, or null when the bytes are not an image Java can read (the caller then
     *         stamps the original bytes)
     */
    static BufferedImage toBlack(byte[] imageBytes)
    {
        if (imageBytes == null || imageBytes.length == 0)
        {
            return null;
        }
        BufferedImage source;
        try
        {
            source = ImageIO.read(new ByteArrayInputStream(imageBytes));
        }
        catch (IOException | RuntimeException e)
        {
            return null;
        }
        return source == null ? null : toBlack(source);
    }

    static BufferedImage toBlack(BufferedImage source)
    {
        int width = source.getWidth();
        int height = source.getHeight();
        boolean transparent = hasTransparency(source);
        BufferedImage ink = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++)
        {
            for (int x = 0; x < width; x++)
            {
                int argb = source.getRGB(x, y);
                int alpha = argb >>> 24;
                if (!transparent)
                {
                    // Ink on paper: the darker the pixel, the more ink
                    alpha = 255 - luminance(argb);
                }
                ink.setRGB(x, y, (alpha << 24) | BLACK);
            }
        }
        return ink;
    }

    private static boolean hasTransparency(BufferedImage image)
    {
        if (!image.getColorModel().hasAlpha())
        {
            return false;
        }
        for (int y = 0; y < image.getHeight(); y++)
        {
            for (int x = 0; x < image.getWidth(); x++)
            {
                if ((image.getRGB(x, y) >>> 24) < 255)
                {
                    return true;
                }
            }
        }
        return false;
    }

    /** Rec. 601 luma, 0 (black) to 255 (white). */
    private static int luminance(int argb)
    {
        int r = (argb >> 16) & 0xff;
        int g = (argb >> 8) & 0xff;
        int b = argb & 0xff;
        return (int) Math.round(0.299 * r + 0.587 * g + 0.114 * b);
    }
}
