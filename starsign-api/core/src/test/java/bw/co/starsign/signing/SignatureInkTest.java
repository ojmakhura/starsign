package bw.co.starsign.signing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

class SignatureInkTest
{
    private static final int CYAN = 0x00E5FF;

    @Test
    void padStrokesBecomeBlackKeepingTheirOpacity() throws IOException
    {
        // The signing pad: transparent background, cyan strokes with anti-aliased (half-opaque) edges
        BufferedImage pad = new BufferedImage(3, 1, BufferedImage.TYPE_INT_ARGB);
        pad.setRGB(0, 0, 0x00000000);
        pad.setRGB(1, 0, (0xFF << 24) | CYAN);
        pad.setRGB(2, 0, (0x80 << 24) | CYAN);

        BufferedImage ink = SignatureInk.toBlack(png(pad));

        assertNotNull(ink);
        assertEquals(0x00000000, ink.getRGB(0, 0), "background stays transparent");
        assertEquals(0xFF000000, ink.getRGB(1, 0), "stroke is solid black");
        assertEquals(0x80000000, ink.getRGB(2, 0), "edge keeps its opacity, in black");
    }

    @Test
    void scannedInkOnPaperBecomesBlackOnTransparent()
    {
        // An opaque upload: white paper, dark blue ink
        BufferedImage scan = new BufferedImage(2, 1, BufferedImage.TYPE_INT_RGB);
        scan.setRGB(0, 0, 0xFFFFFF);
        scan.setRGB(1, 0, 0x101040);

        BufferedImage ink = SignatureInk.toBlack(scan);

        assertEquals(0, ink.getRGB(0, 0) >>> 24, "paper becomes transparent");
        assertEquals(0x000000, ink.getRGB(1, 0) & 0xFFFFFF, "ink is black");
        // Luma of #101040 is 21, so the ink keeps 255 - 21 of opacity
        assertEquals(234, ink.getRGB(1, 0) >>> 24, "dark ink stays nearly opaque");
    }

    @Test
    void unreadableBytesAreLeftToTheCaller()
    {
        assertNull(SignatureInk.toBlack(new byte[] { 1, 2, 3 }));
        assertNull(SignatureInk.toBlack((byte[]) null));
    }

    private static byte[] png(BufferedImage image) throws IOException
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
