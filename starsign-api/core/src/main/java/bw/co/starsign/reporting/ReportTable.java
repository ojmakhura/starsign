package bw.co.starsign.reporting;

import bw.co.starsign.audit.CsvWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/**
 * A report as a title, summary lines and one table, rendered as CSV or as a simple PDF.
 */
final class ReportTable
{
    private static final DateTimeFormatter GENERATED = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final float MARGIN = 36f;
    private static final float FONT_SIZE = 9f;
    private static final float LINE = 14f;

    private final String title;
    private final Map<String, Object> summary = new LinkedHashMap<>();
    private final List<String> headers;
    private final List<List<Object>> rows = new ArrayList<>();

    ReportTable(String title, String... headers)
    {
        this.title = title;
        this.headers = List.of(headers);
    }

    ReportTable summary(String label, Object value)
    {
        summary.put(label, value);
        return this;
    }

    ReportTable row(Object... cells)
    {
        rows.add(java.util.Arrays.asList(cells));
        return this;
    }

    byte[] toCsv()
    {
        CsvWriter csv = new CsvWriter();
        if (!summary.isEmpty())
        {
            csv.row("Metric", "Value");
            summary.forEach((label, value) -> csv.row(label, value));
            csv.row();
        }
        if (!headers.isEmpty())
        {
            csv.row(headers);
            rows.forEach(csv::row);
        }
        return csv.toBytes();
    }

    byte[] toPdf() throws IOException
    {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream())
        {
            PDFont regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDFont bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            PDRectangle landscape = new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth());
            Writer writer = new Writer(document, landscape);

            writer.text(bold, 14f, MARGIN, title);
            writer.newLine(LINE * 1.5f);
            writer.text(regular, FONT_SIZE, MARGIN, "Generated " + GENERATED.format(LocalDateTime.now()));
            writer.newLine(LINE * 1.5f);

            for (Map.Entry<String, Object> line : summary.entrySet())
            {
                writer.ensureRoom();
                writer.text(bold, FONT_SIZE, MARGIN, line.getKey());
                writer.text(regular, FONT_SIZE, MARGIN + 220f, String.valueOf(line.getValue() == null ? "-" : line.getValue()));
                writer.newLine(LINE);
            }
            if (!summary.isEmpty())
            {
                writer.newLine(LINE);
            }

            if (!headers.isEmpty())
            {
                writer.ensureRoom();
                float width = (landscape.getWidth() - 2 * MARGIN) / headers.size();
                writer.row(bold, headers, width);
                for (List<Object> row : rows)
                {
                    if (writer.atBottom())
                    {
                        writer.newPage();
                        writer.row(bold, headers, width);
                    }
                    writer.row(regular, row, width);
                }
                if (rows.isEmpty())
                {
                    writer.ensureRoom();
                    writer.text(regular, FONT_SIZE, MARGIN, "No data for this period.");
                }
            }
            writer.close();
            document.save(out);
            return out.toByteArray();
        }
    }

    /** Writes lines top to bottom, starting a new page when the current one is full. */
    private static final class Writer
    {
        private final PDDocument document;
        private final PDRectangle size;
        private PDPageContentStream stream;
        private float y;

        Writer(PDDocument document, PDRectangle size) throws IOException
        {
            this.document = document;
            this.size = size;
            newPage();
        }

        void newPage() throws IOException
        {
            close();
            PDPage page = new PDPage(size);
            document.addPage(page);
            stream = new PDPageContentStream(document, page);
            y = size.getHeight() - MARGIN;
        }

        boolean atBottom()
        {
            return y < MARGIN + LINE;
        }

        void newLine(float height)
        {
            y -= height;
        }

        void ensureRoom() throws IOException
        {
            if (atBottom())
            {
                newPage();
            }
        }

        void row(PDFont font, List<?> cells, float width) throws IOException
        {
            float x = MARGIN;
            for (Object cell : cells)
            {
                text(font, FONT_SIZE, x, fit(font, cell == null ? "" : cell.toString(), width - 6f));
                x += width;
            }
            newLine(LINE);
        }

        void text(PDFont font, float fontSize, float x, String value) throws IOException
        {
            stream.beginText();
            stream.setFont(font, fontSize);
            stream.newLineAtOffset(x, y);
            stream.showText(printable(value));
            stream.endText();
        }

        void close() throws IOException
        {
            if (stream != null)
            {
                stream.close();
                stream = null;
            }
        }

        private static String fit(PDFont font, String value, float width) throws IOException
        {
            String text = printable(value);
            if (font.getStringWidth(text) / 1000f * FONT_SIZE <= width)
            {
                return text;
            }
            while (!text.isEmpty() && font.getStringWidth(text + "...") / 1000f * FONT_SIZE > width)
            {
                text = text.substring(0, text.length() - 1);
            }
            return text + "...";
        }

        /** The standard PDF fonts only cover Latin-1 (WinAnsi); anything else is shown as '?'. */
        private static String printable(String value)
        {
            StringBuilder out = new StringBuilder(value.length());
            for (char c : value.toCharArray())
            {
                out.append((c >= 0x20 && c <= 0x7E) || (c >= 0xA0 && c <= 0xFF) ? c : c == '\t' ? ' ' : '?');
            }
            return out.toString();
        }
    }
}
