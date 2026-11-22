package bw.co.starsign.signing;

import bw.co.starsign.workflow.FieldType;
import bw.co.starsign.workflow.SigningField;
import java.awt.Color;
import java.io.IOException;
import java.time.format.DateTimeFormatter;
import org.apache.commons.lang3.StringUtils;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.util.Matrix;

/**
 * Draws field values onto a PDF page. Field positions are ratios of the page as the signatory saw it (top-left
 * origin, after the page's /Rotate); each field is drawn in its own box coordinate system that is mapped back to
 * the unrotated PDF user space, so text and images stay upright whatever the page rotation.
 */
final class PdfFieldStamper
{
    static final PDType1Font TEXT_FONT = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    static final PDType1Font TYPED_SIGNATURE_FONT = new PDType1Font(Standard14Fonts.FontName.TIMES_ITALIC);

    private PdfFieldStamper()
    {
    }

    /** A value ready to draw. */
    record Stamp(SigningField field, String text, boolean ticked, PDImageXObject image)
    {
    }

    static void draw(PDPageContentStream content, PDPage page, Stamp stamp) throws IOException
    {
        SigningField field = stamp.field();
        PDRectangle box = page.getCropBox();
        int rotation = ((page.getRotation() % 360) + 360) % 360;
        boolean sideways = rotation == 90 || rotation == 270;
        double viewWidth = sideways ? box.getHeight() : box.getWidth();
        double viewHeight = sideways ? box.getWidth() : box.getHeight();

        // The field in the page as viewed: top-left origin
        float dx = (float) (clamp(field.getXRatio()) * viewWidth);
        float dy = (float) (clamp(field.getYRatio()) * viewHeight);
        float width = (float) (clamp(field.getWidthRatio()) * viewWidth);
        float height = (float) (clamp(field.getHeightRatio()) * viewHeight);
        if (width <= 0 || height <= 0)
        {
            return;
        }
        float llx = box.getLowerLeftX();
        float lly = box.getLowerLeftY();
        float pageWidth = box.getWidth();
        float pageHeight = box.getHeight();

        // Box coordinates (a to the right, b upwards as viewed) to unrotated user space
        Matrix matrix = switch (rotation)
        {
            case 90 -> new Matrix(0, 1, -1, 0, llx + dy + height, lly + dx);
            case 180 -> new Matrix(-1, 0, 0, -1, llx + pageWidth - dx, lly + dy + height);
            case 270 -> new Matrix(0, -1, 1, 0, llx + pageWidth - dy - height, lly + pageHeight - dx);
            default -> new Matrix(1, 0, 0, 1, llx + dx, lly + pageHeight - dy - height);
        };

        content.saveGraphicsState();
        content.transform(matrix);
        // Values are written in black ink (signature images are made black in SignatureInk)
        content.setNonStrokingColor(Color.BLACK);
        content.setStrokingColor(Color.BLACK);
        if (stamp.image() != null)
        {
            drawImage(content, stamp.image(), width, height);
        }
        else if (field.getFieldType() == FieldType.CHECKBOX)
        {
            if (stamp.ticked())
            {
                drawTick(content, width, height);
            }
        }
        else if (StringUtils.isNotBlank(stamp.text()))
        {
            boolean signature = SigningDocuments.isSignature(field);
            PDType1Font font = signature ? TYPED_SIGNATURE_FONT : TEXT_FONT;
            float preferred = field.getFontSize() != null && field.getFontSize() > 0 && !signature
                    ? field.getFontSize().floatValue()
                    : height * (signature ? 0.7f : 0.6f);
            drawText(content, font, encodable(font, stamp.text()), preferred, width, height);
        }
        content.restoreGraphicsState();
    }

    /** Scaled to fit the box, keeping the aspect ratio, centred. */
    private static void drawImage(PDPageContentStream content, PDImageXObject image, float width, float height)
        throws IOException
    {
        float scale = Math.min(width / image.getWidth(), height / image.getHeight());
        float drawnWidth = image.getWidth() * scale;
        float drawnHeight = image.getHeight() * scale;
        content.drawImage(image, (width - drawnWidth) / 2, (height - drawnHeight) / 2, drawnWidth, drawnHeight);
    }

    private static void drawTick(PDPageContentStream content, float width, float height) throws IOException
    {
        float size = Math.min(width, height);
        float x = (width - size) / 2;
        float y = (height - size) / 2;
        content.setLineWidth(Math.max(1f, size / 8));
        content.moveTo(x + size * 0.15f, y + size * 0.5f);
        content.lineTo(x + size * 0.4f, y + size * 0.2f);
        content.lineTo(x + size * 0.85f, y + size * 0.85f);
        content.stroke();
    }

    /** One line, shrunk until it fits the box, vertically centred. */
    private static void drawText(PDPageContentStream content, PDType1Font font, String text, float preferredSize,
        float width, float height) throws IOException
    {
        float size = Math.max(4f, Math.min(preferredSize, height * 0.9f));
        float textWidth = font.getStringWidth(text) / 1000 * size;
        if (textWidth > width && textWidth > 0)
        {
            size = Math.max(4f, size * width / textWidth);
            textWidth = font.getStringWidth(text) / 1000 * size;
        }
        float capHeight = font.getFontDescriptor() != null && font.getFontDescriptor().getCapHeight() > 0
                ? font.getFontDescriptor().getCapHeight() / 1000 * size
                : size * 0.7f;
        content.beginText();
        content.setFont(font, size);
        content.newLineAtOffset(Math.min(2f, Math.max(0, width - textWidth)), (height - capHeight) / 2);
        content.showText(text);
        content.endText();
    }

    /** Characters the standard fonts cannot show (WinAnsi) are replaced so that stamping never fails. */
    static String encodable(PDType1Font font, String text)
    {
        StringBuilder result = new StringBuilder(text.length());
        text.codePoints().forEach(codePoint -> {
            String character = Character.isWhitespace(codePoint) ? " " : new String(Character.toChars(codePoint));
            try
            {
                font.encode(character);
                result.append(character);
            }
            catch (IOException | IllegalArgumentException e)
            {
                result.append('?');
            }
        });
        return result.toString();
    }

    static String dateText(SigningField field, java.time.LocalDate date)
    {
        if (date == null)
        {
            return null;
        }
        if (StringUtils.isNotBlank(field.getFormatPattern()))
        {
            try
            {
                return date.format(DateTimeFormatter.ofPattern(field.getFormatPattern()));
            }
            catch (IllegalArgumentException | java.time.DateTimeException e)
            {
                // An unusable pattern falls back to ISO dates, which are always unambiguous
                return date.toString();
            }
        }
        return date.toString();
    }

    private static double clamp(double ratio)
    {
        return Math.max(0, Math.min(1, ratio));
    }
}
