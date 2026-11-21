package bw.co.starsign.document;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;

/**
 * Finds (and removes) active content in a parsed PDF by walking every object reachable from the trailer, so
 * actions hidden in annotations, form fields, name trees or object streams are found as well as the obvious
 * document open action. PDFBox decodes obfuscated names (e.g. {@code /J#61vaScript}) while parsing.
 */
final class PdfActiveContent
{
    /** Actions that run code or open other programs/files. */
    private static final Set<COSName> ACTIVE_ACTIONS = Set.of(
            COSName.JAVA_SCRIPT,
            COSName.getPDFName("Launch"),
            COSName.getPDFName("ImportData"),
            COSName.getPDFName("RichMediaExecute"));

    private static final Set<String> EXECUTABLE_EXTENSIONS = Set.of(
            "exe", "dll", "com", "bat", "cmd", "scr", "msi", "msp", "ps1", "psm1", "vbs", "vbe", "js", "jse",
            "wsf", "wsh", "hta", "jar", "sh", "app", "apk", "lnk", "pif", "cpl", "reg", "dmg", "elf", "so", "dylib");

    /** Cap on the objects visited, so a hostile file cannot keep the walk busy forever. */
    private static final int MAX_OBJECTS = 2_000_000;

    private PdfActiveContent()
    {
    }

    record Findings(boolean javascript, boolean launchActions, boolean embeddedFiles,
            boolean executableAttachments, List<String> issues)
    {
        boolean clean()
        {
            return !javascript && !launchActions && !executableAttachments;
        }
    }

    static Findings scan(PDDocument document) throws IOException
    {
        boolean javascript = false;
        boolean launch = false;
        boolean embedded = false;
        boolean executable = false;
        List<String> issues = new ArrayList<String>();

        for (COSDictionary dictionary : dictionaries(document))
        {
            COSName action = dictionary.getCOSName(COSName.S);
            if (COSName.JAVA_SCRIPT.equals(action) || dictionary.containsKey(COSName.JS))
            {
                javascript = true;
            }
            else if (action != null && ACTIVE_ACTIONS.contains(action))
            {
                launch = true;
            }

            if (dictionary.containsKey(COSName.EF))
            {
                embedded = true;
                String name = fileName(dictionary);
                if (name != null && EXECUTABLE_EXTENSIONS.contains(extension(name)))
                {
                    executable = true;
                    issues.add("The PDF has an embedded program (" + name + ")");
                }
            }
            if (dictionary instanceof COSStream stream
                    && COSName.EMBEDDED_FILE.equals(stream.getCOSName(COSName.TYPE)))
            {
                embedded = true;
                if (executableHeader(stream))
                {
                    executable = true;
                    issues.add("The PDF has an embedded program");
                }
            }
        }
        if (javascript)
        {
            issues.add("The PDF contains JavaScript");
        }
        if (launch)
        {
            issues.add("The PDF contains actions that start other programs or import data");
        }
        return new Findings(javascript, launch, embedded, executable, issues);
    }

    /**
     * Removes JavaScript, launch and import actions, additional-actions (/AA) triggers and the embedded file
     * name tree. The document keeps its content; links that only navigate keep working.
     *
     * @return the number of entries removed
     */
    static int sanitise(PDDocument document) throws IOException
    {
        int removed = 0;
        for (COSDictionary dictionary : dictionaries(document))
        {
            if (dictionary.containsKey(COSName.AA))
            {
                dictionary.removeItem(COSName.AA);
                removed++;
            }
            for (COSName key : List.of(COSName.A, COSName.OPEN_ACTION, COSName.NEXT))
            {
                if (dictionary.getDictionaryObject(key) instanceof COSDictionary target && isActive(target))
                {
                    dictionary.removeItem(key);
                    removed++;
                }
                else if (dictionary.getDictionaryObject(key) instanceof COSArray actions)
                {
                    // /Next may be an array of actions
                    for (int i = actions.size() - 1; i >= 0; i--)
                    {
                        if (actions.getObject(i) instanceof COSDictionary target && isActive(target))
                        {
                            actions.remove(i);
                            removed++;
                        }
                    }
                }
            }
        }

        COSDictionary names = document.getDocumentCatalog().getCOSObject().getCOSDictionary(COSName.NAMES);
        if (names != null)
        {
            for (COSName key : List.of(COSName.JAVA_SCRIPT, COSName.EMBEDDED_FILES))
            {
                if (names.containsKey(key))
                {
                    names.removeItem(key);
                    removed++;
                }
            }
        }
        return removed;
    }

    private static boolean isActive(COSDictionary action)
    {
        COSName type = action.getCOSName(COSName.S);
        return action.containsKey(COSName.JS) || (type != null && ACTIVE_ACTIONS.contains(type));
    }

    /** Every dictionary (and stream) reachable from the trailer, each once. */
    private static List<COSDictionary> dictionaries(PDDocument document)
    {
        List<COSDictionary> found = new ArrayList<COSDictionary>();
        Set<COSBase> visited = Collections.newSetFromMap(new IdentityHashMap<COSBase, Boolean>());
        Deque<COSBase> pending = new ArrayDeque<COSBase>();
        pending.push(document.getDocument().getTrailer());

        while (!pending.isEmpty() && visited.size() < MAX_OBJECTS)
        {
            COSBase item = pending.pop();
            if (item instanceof COSObject reference)
            {
                item = reference.getObject();
            }
            if (item == null || !visited.add(item))
            {
                continue;
            }
            if (item instanceof COSDictionary dictionary)
            {
                found.add(dictionary);
                for (COSBase value : dictionary.getValues())
                {
                    pending.push(value);
                }
            }
            else if (item instanceof COSArray array)
            {
                for (COSBase value : array)
                {
                    pending.push(value);
                }
            }
        }
        return found;
    }

    private static String fileName(COSDictionary fileSpecification)
    {
        for (COSName key : List.of(COSName.UF, COSName.F))
        {
            if (fileSpecification.getDictionaryObject(key) instanceof COSString name)
            {
                return name.getString();
            }
        }
        return null;
    }

    private static String extension(String name)
    {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).trim().toLowerCase(Locale.ROOT);
    }

    /** Windows PE, ELF, Mach-O / Java class files and scripts with a shebang line. */
    private static boolean executableHeader(COSStream stream) throws IOException
    {
        byte[] head;
        try (InputStream in = stream.createInputStream())
        {
            head = in.readNBytes(4);
        }
        catch (IOException e)
        {
            // An embedded file we cannot decode cannot be checked: treat it as unsafe
            return true;
        }
        if (head.length >= 2 && ((head[0] == 'M' && head[1] == 'Z') || (head[0] == '#' && head[1] == '!')))
        {
            return true;
        }
        if (head.length < 4)
        {
            return false;
        }
        int magic = ((head[0] & 0xff) << 24) | ((head[1] & 0xff) << 16) | ((head[2] & 0xff) << 8) | (head[3] & 0xff);
        return magic == 0x7f454c46 // ELF
                || magic == 0xfeedface || magic == 0xfeedfacf || magic == 0xcefaedfe || magic == 0xcffaedfe // Mach-O
                || magic == 0xcafebabe; // Mach-O universal / Java class
    }
}
