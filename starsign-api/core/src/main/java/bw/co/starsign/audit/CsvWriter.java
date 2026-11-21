package bw.co.starsign.audit;

import java.nio.charset.StandardCharsets;
import java.util.Collection;

/**
 * Minimal RFC 4180 CSV builder for audit and report exports.
 */
public final class CsvWriter
{
    private final StringBuilder out = new StringBuilder();

    public CsvWriter row(Object... cells)
    {
        boolean first = true;
        for (Object cell : cells)
        {
            if (!first)
            {
                out.append(',');
            }
            first = false;
            out.append(escape(cell));
        }
        out.append("\r\n");
        return this;
    }

    public CsvWriter row(Collection<?> cells)
    {
        return row(cells.toArray());
    }

    public byte[] toBytes()
    {
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String toString()
    {
        return out.toString();
    }

    private static String escape(Object cell)
    {
        if (cell == null)
        {
            return "";
        }
        String value = cell.toString();
        // Spreadsheet programs run cells starting with these as formulas (CSV injection)
        if (!value.isEmpty() && "=+-@\t\r".indexOf(value.charAt(0)) >= 0 && !(cell instanceof Number))
        {
            value = "'" + value;
        }
        if (value.indexOf(',') >= 0 || value.indexOf('"') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0)
        {
            value = '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }
}
