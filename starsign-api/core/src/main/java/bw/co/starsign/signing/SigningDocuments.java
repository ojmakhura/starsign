package bw.co.starsign.signing;

import bw.co.starsign.document.ArtifactType;
import bw.co.starsign.document.DocumentArtifact;
import bw.co.starsign.document.DocumentVersion;
import bw.co.starsign.organisation.OrganisationSigningPolicy;
import bw.co.starsign.workflow.FieldType;
import bw.co.starsign.workflow.Signatory;
import bw.co.starsign.workflow.SigningAssignment;
import bw.co.starsign.workflow.SigningField;
import bw.co.starsign.workflow.SigningWorkflow;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;

/**
 * Rules shared by the signing services and the PDF engine: which version of the document is being signed,
 * which fields belong to a signatory and what the workflow allows.
 */
final class SigningDocuments
{
    static final String DEFAULT_CONSENT_TEXT = "I agree to sign this document electronically. I understand that my "
            + "electronic signature is the legal equivalent of my handwritten signature and that a record of this "
            + "signing (time, network address and device) is kept as evidence.";

    /** Artifacts holding the full PDF of a version, best first. */
    private static final List<ArtifactType> PDF_TYPES = List.of(ArtifactType.FINAL_SIGNED,
            ArtifactType.INTERMEDIATE_SIGNED, ArtifactType.PREPARATION, ArtifactType.ORIGINAL);

    private static final Pattern JSON_STRING = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");

    private SigningDocuments()
    {
    }

    /** The latest version of the workflow's document: every signature adds a version (append-only). */
    static DocumentVersion currentVersion(SigningWorkflow workflow)
    {
        if (workflow.getDocument() == null)
        {
            return workflow.getSourceVersion();
        }
        return workflow.getDocument().getVersions().stream()
                .max(Comparator.comparingInt(DocumentVersion::getVersionNumber))
                .orElse(workflow.getSourceVersion());
    }

    /** The PDF of a version, or null when it has none. */
    static DocumentArtifact pdfArtifact(DocumentVersion version)
    {
        if (version == null)
        {
            return null;
        }
        for (ArtifactType type : PDF_TYPES)
        {
            DocumentArtifact artifact = version.getArtifacts().stream()
                    .filter(candidate -> candidate.getArtifactType() == type && candidate.getStorageReference() != null)
                    .max(Comparator.comparing(DocumentArtifact::getGeneratedAt,
                            Comparator.nullsFirst(Comparator.naturalOrder())))
                    .orElse(null);
            if (artifact != null)
            {
                return artifact;
            }
        }
        return null;
    }

    /** The hash the signatory must have viewed: the current PDF's, or the version's. */
    static String currentSha256(SigningWorkflow workflow)
    {
        DocumentVersion version = currentVersion(workflow);
        DocumentArtifact pdf = pdfArtifact(version);
        if (pdf != null && pdf.getSha256() != null)
        {
            return pdf.getSha256();
        }
        return version == null ? null : version.getSha256();
    }

    /** The fields assigned to the signatory, in tab order. */
    static List<SigningField> fieldsOf(Signatory signatory)
    {
        List<SigningField> fields = new ArrayList<>();
        for (SigningAssignment assignment : signatory.getAssignments())
        {
            if (assignment.getField() != null)
            {
                fields.add(assignment.getField());
            }
        }
        fields.sort(Comparator.comparingInt(SigningField::getPageNumber)
                .thenComparingInt(SigningField::getTabOrder));
        return fields;
    }

    static boolean isSignature(SigningField field)
    {
        return field.getFieldType() == FieldType.SIGNATURE || field.getFieldType() == FieldType.INITIALS;
    }

    /** The workflow's capture methods; falls back to the organisation policy, then to all of them. */
    static List<SignatureCaptureMethod> allowedCaptureMethods(SigningWorkflow workflow)
    {
        Set<SignatureCaptureMethod> methods = EnumSet.noneOf(SignatureCaptureMethod.class);
        for (String name : StringUtils.split(StringUtils.defaultString(workflow.getAllowedCaptureMethods()), ",; []\""))
        {
            for (SignatureCaptureMethod method : SignatureCaptureMethod.values())
            {
                if (method.name().equalsIgnoreCase(name.trim()))
                {
                    methods.add(method);
                }
            }
        }
        OrganisationSigningPolicy policy = policy(workflow);
        if (methods.isEmpty() && policy != null)
        {
            if (policy.isAllowDrawnSignature())
            {
                methods.add(SignatureCaptureMethod.DRAWN);
            }
            if (policy.isAllowTypedSignature())
            {
                methods.add(SignatureCaptureMethod.TYPED);
            }
            if (policy.isAllowUploadedSignatureImage())
            {
                methods.add(SignatureCaptureMethod.UPLOADED_IMAGE);
            }
        }
        if (methods.isEmpty())
        {
            methods.addAll(EnumSet.allOf(SignatureCaptureMethod.class));
        }
        return new ArrayList<>(methods);
    }

    static String consentText(SigningWorkflow workflow)
    {
        OrganisationSigningPolicy policy = policy(workflow);
        return policy == null ? DEFAULT_CONSENT_TEXT : StringUtils.defaultIfBlank(policy.getConsentText(), DEFAULT_CONSENT_TEXT);
    }

    static OrganisationSigningPolicy policy(SigningWorkflow workflow)
    {
        return workflow.getOrganisation() == null ? null : workflow.getOrganisation().getSigningPolicy();
    }

    /** Whether the signatory may decline: the workflow and the signatory must both allow it. */
    static boolean canDecline(Signatory signatory)
    {
        return signatory.getWorkflow().isAllowDecline() && signatory.isCanDecline();
    }

    /** The verification the signatory must pass; the workflow's when the signatory has none. */
    static VerificationMethod verificationMethod(Signatory signatory)
    {
        return signatory.getVerificationMethod() != null
                ? signatory.getVerificationMethod()
                : Objects.requireNonNullElse(signatory.getWorkflow().getVerificationMethod(), VerificationMethod.EMAIL_LINK);
    }

    /** The options of a radio group field, from its JSON array of strings. */
    static List<String> options(String optionsJson)
    {
        List<String> options = new ArrayList<>();
        if (StringUtils.isBlank(optionsJson))
        {
            return options;
        }
        Matcher matcher = JSON_STRING.matcher(optionsJson);
        while (matcher.find())
        {
            options.add(matcher.group(1).replace("\\\"", "\"").replace("\\\\", "\\"));
        }
        return options;
    }
}
