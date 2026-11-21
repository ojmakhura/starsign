package bw.co.starsign.signing;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/**
 * Writes plain-text evidence (the certificate page of a completed document, the evidence report) as A4 pages
 * appended to a PDF, wrapping long lines and starting new pages as needed.
 */
final class EvidencePages
{
    private static final PDType1Font REGULAR = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private static final PDType1Font BOLD = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
    private static final float MARGIN = 50;
    private static final float TITLE_SIZE = 16;
    private static final float HEADING_SIZE = 11;
    private static final float TEXT_SIZE = 9;

    /** A line of the report. */
    record Line(Kind kind, String text)
    {
        static Line title(String text)
        {
            return new Line(Kind.TITLE, text);
        }

        static Line heading(String text)
        {
            return new Line(Kind.HEADING, text);
        }

        static Line text(String text)
        {
            return new Line(Kind.TEXT, text);
        }

        static Line field(String label, Object value)
        {
            return new Line(Kind.TEXT, label + ": " + (value == null ? "-" : value));
        }

        static Line blank()
        {
            return new Line(Kind.TEXT, "");
        }
    }

    enum Kind
    {
        TITLE, HEADING, TEXT
    }

    private final PDDocument document;
    private PDPageContentStream content;
    private float y;

    private EvidencePages(PDDocument document)
    {
        this.document = document;
    }

    /** Appends the lines to the document on new pages. */
    static void append(PDDocument document, List<Line> lines) throws IOException
    {
        EvidencePages pages = new EvidencePages(document);
        try
        {
            pages.newPage();
            for (Line line : lines)
            {
                pages.write(line);
            }
        }
        finally
        {
            if (pages.content != null)
            {
                pages.content.close();
            }
        }
    }

    private void write(Line line) throws IOException
    {
        PDType1Font font = line.kind() == Kind.TEXT ? REGULAR : BOLD;
        float size = switch (line.kind())
        {
            case TITLE -> TITLE_SIZE;
            case HEADING -> HEADING_SIZE;
            case TEXT -> TEXT_SIZE;
        };
        float leading = size * 1.4f;
        if (line.kind() == Kind.HEADING)
        {
            y -= leading / 2;
        }
        String text = PdfFieldStamper.encodable(font, line.text() == null ? "" : line.text());
        for (String wrapped : wrap(font, size, text, PDRectangle.A4.getWidth() - 2 * MARGIN))
        {
            if (y - leading < MARGIN)
            {
                newPage();
            }
            y -= leading;
            content.beginText();
            content.setFont(font, size);
            content.newLineAtOffset(MARGIN, y);
            content.showText(wrapped);
            content.endText();
        }
    }

    private void newPage() throws IOException
    {
        if (content != null)
        {
            content.close();
        }
        PDPage page = new PDPage(PDRectangle.A4);
        document.addPage(page);
        content = new PDPageContentStream(document, page);
        y = PDRectangle.A4.getHeight() - MARGIN;
    }

    private static List<String> wrap(PDType1Font font, float size, String text, float width) throws IOException
    {
        List<String> lines = new ArrayList<>();
        if (text.isEmpty())
        {
            lines.add("");
            return lines;
        }
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" "))
        {
            String candidate = line.length() == 0 ? word : line + " " + word;
            if (font.getStringWidth(candidate) / 1000 * size <= width)
            {
                line.setLength(0);
                line.append(candidate);
                continue;
            }
            if (line.length() > 0)
            {
                lines.add(line.toString());
                line.setLength(0);
            }
            // Words longer than a line (hashes, user agents) are cut
            while (font.getStringWidth(word) / 1000 * size > width)
            {
                int cut = word.length() - 1;
                while (cut > 1 && font.getStringWidth(word.substring(0, cut)) / 1000 * size > width)
                {
                    cut--;
                }
                lines.add(word.substring(0, cut));
                word = word.substring(cut);
            }
            line.append(word);
        }
        if (line.length() > 0)
        {
            lines.add(line.toString());
        }
        return lines;
    }
}
