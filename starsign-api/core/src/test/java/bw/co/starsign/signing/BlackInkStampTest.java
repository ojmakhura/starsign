package bw.co.starsign.signing;

import static org.junit.jupiter.api.Assertions.assertTrue;

import bw.co.starsign.workflow.FieldType;
import bw.co.starsign.workflow.SigningField;
import java.awt.image.BufferedImage;
import java.io.IOException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;

/** Signatures land on the page in black ink: drawn (from a cyan pad image) and typed. */
class BlackInkStampTest
{
    @Test
    void drawnAndTypedSignaturesAreStampedBlack() throws IOException
    {
        // A pad image: a thick cyan bar on transparency
        BufferedImage pad = new BufferedImage(200, 50, BufferedImage.TYPE_INT_ARGB);
        for (int y = 15; y < 35; y++)
        {
            for (int x = 10; x < 190; x++)
            {
                pad.setRGB(x, y, 0xFF00E5FF);
            }
        }

        try (PDDocument document = new PDDocument())
        {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page))
            {
                PdfFieldStamper.draw(content, page, new PdfFieldStamper.Stamp(field(0.1, 0.1), null, false,
                        LosslessFactory.createFromImage(document, SignatureInk.toBlack(pad))));
                PdfFieldStamper.draw(content, page, new PdfFieldStamper.Stamp(field(0.1, 0.5), "Signatory Two",
                        false, null));
            }

            BufferedImage rendered = new PDFRenderer(document).renderImageWithDPI(0, 72, ImageType.RGB);
            assertInkIsBlack(rendered, 0.1, 0.1, "drawn signature");
            assertInkIsBlack(rendered, 0.1, 0.5, "typed signature");
        }
    }

    private static SigningField field(double x, double y)
    {
        SigningField field = new SigningField();
        field.setFieldType(FieldType.SIGNATURE);
        field.setPageNumber(1);
        field.setXRatio(x);
        field.setYRatio(y);
        field.setWidthRatio(0.4);
        field.setHeightRatio(0.06);
        return field;
    }

    /** Every non-white pixel in the field is a neutral dark grey or black: no colour anywhere. */
    private static void assertInkIsBlack(BufferedImage page, double xRatio, double yRatio, String what)
    {
        int x0 = (int) (xRatio * page.getWidth());
        int y0 = (int) (yRatio * page.getHeight());
        int inked = 0;
        for (int y = y0; y < y0 + (int) (0.06 * page.getHeight()); y++)
        {
            for (int x = x0; x < x0 + (int) (0.4 * page.getWidth()); x++)
            {
                int rgb = page.getRGB(x, y);
                int r = (rgb >> 16) & 0xff;
                int g = (rgb >> 8) & 0xff;
                int b = rgb & 0xff;
                if (r == 255 && g == 255 && b == 255)
                {
                    continue;
                }
                inked++;
                assertTrue(Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) <= 2,
                        what + " has a coloured pixel #" + Integer.toHexString(rgb & 0xFFFFFF));
            }
        }
        assertTrue(inked > 50, what + " was not stamped");
    }
}
