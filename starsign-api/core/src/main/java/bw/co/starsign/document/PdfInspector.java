package bw.co.starsign.document;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * Checks that bytes are a PDF StarSign can accept for signing, before anything is stored.
 * Shared by the upload and by {@link DocumentProcessingService#validatePdf(String)}.
 */
@Component
public class PdfInspector
{
    public static final String PDF = "application/pdf";

    /** The PDF header may follow up to 1024 bytes of junk (ISO 32000-1, annex H). */
    private static final int HEADER_WINDOW = 1024;

    private final MalwareScanner malwareScanner;

    public PdfInspector(@Lazy MalwareScanner malwareScanner)
    {
        this.malwareScanner = malwareScanner;
    }

    public PdfValidationResultDTO inspect(byte[] content, String fileName)
    {
        PdfValidationResultDTO result = new PdfValidationResultDTO();
        List<String> issues = new ArrayList<String>();
        result.setIssues(issues);
        result.setEncrypted(false);
        result.setContainsJavascript(false);
        result.setContainsEmbeddedFiles(false);
        result.setContainsDigitalSignatures(false);
        result.setPageCount(0);

        boolean header = hasPdfHeader(content);
        result.setMagicBytesValid(header);
        result.setMimeType(header ? PDF : "application/octet-stream");
        if (!header)
        {
            issues.add("The file is not a PDF");
            result.setMalwareScanPassed(false);
            result.setValid(false);
            return result;
        }

        boolean readable = false;
        try (PDDocument document = Loader.loadPDF(content))
        {
            readable = true;
            result.setPdfVersion(String.valueOf(document.getVersion()));
            result.setPageCount(document.getNumberOfPages());
            result.setEncrypted(document.isEncrypted());
            result.setContainsDigitalSignatures(!document.getSignatureDictionaries().isEmpty());

            PdfActiveContent.Findings findings = PdfActiveContent.scan(document);
            result.setContainsJavascript(findings.javascript());
            result.setContainsEmbeddedFiles(findings.embeddedFiles());

            if (document.getNumberOfPages() < 1)
            {
                issues.add("The PDF has no pages");
            }
            if (document.isEncrypted())
            {
                AccessPermission permission = document.getCurrentAccessPermission();
                if (!permission.canModifyAnnotations() || !permission.canFillInForm())
                {
                    issues.add("The PDF's security settings do not allow it to be signed: remove the restrictions and upload it again");
                }
            }
        }
        catch (InvalidPasswordException e)
        {
            result.setEncrypted(true);
            issues.add("The PDF is password protected: remove the password and upload it again");
        }
        catch (IOException e)
        {
            issues.add("The PDF is damaged and cannot be read");
        }

        boolean clean = readable && malwareScanner.scan(new ByteArrayInputStream(content), fileName);
        result.setMalwareScanPassed(clean);
        if (readable && !clean)
        {
            issues.add("The PDF contains active content (scripts, launch actions or embedded programs) and was rejected for safety");
        }
        result.setValid(issues.isEmpty());
        return result;
    }

    static boolean hasPdfHeader(byte[] content)
    {
        if (content == null)
        {
            return false;
        }
        String head = new String(content, 0, Math.min(content.length, HEADER_WINDOW), StandardCharsets.ISO_8859_1);
        return head.contains("%PDF-");
    }

    /** Linearised ("fast web view") files declare it in their first object. */
    static boolean isLinearized(byte[] content)
    {
        String head = new String(content, 0, Math.min(content.length, HEADER_WINDOW), StandardCharsets.ISO_8859_1);
        return head.contains("/Linearized");
    }

    static String sha256(byte[] content)
    {
        try
        {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
