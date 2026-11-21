// license-header java merge-point
/**
 * This is only generated once! It will never be overwritten.
 * You can (and have to!) safely modify it by hand.
 * TEMPLATE:    SpringServiceImpl.vsl in andromda-spring cartridge
 * MODEL CLASS: AndroMDAModel::backend::bw.co.starsign::branding::BrandingService
 * STEREOTYPE:  Service
 */
package bw.co.starsign.branding;

import bw.co.starsign.audit.ActorType;
import bw.co.starsign.audit.AuditEventDTO;
import bw.co.starsign.audit.AuditEventType;
import bw.co.starsign.audit.AuditOutcome;
import bw.co.starsign.audit.AuditTrailService;
import bw.co.starsign.context.CallerContext;
import bw.co.starsign.organisation.Organisation;
import bw.co.starsign.organisation.OrganisationMapper;
import bw.co.starsign.organisation.OrganisationRepository;
import bw.co.starsign.storage.DocumentStorageReference;
import bw.co.starsign.storage.DocumentStorageReferenceDTO;
import bw.co.starsign.storage.DocumentStorageReferenceRepository;
import bw.co.starsign.storage.StorageNamespace;
import bw.co.starsign.storage.StorageService;
import bw.co.starsign.storage.StorageWriteRequestDTO;
import jakarta.validation.Valid;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Platform branding (the Branding row no organisation points to) and per-organisation branding. An
 * organisation's unset fields fall back to the platform branding, and the platform's to the built-in
 * StarSign defaults. Colours: an organisation that sets no seeds and no overrides shows the platform's
 * colours; otherwise its own seeds (falling back seed by seed) and only its own overrides.
 *
 * @see bw.co.starsign.branding.BrandingService
 */
@Service("brandingService")
@Transactional(propagation = Propagation.REQUIRED, readOnly = false)
public class BrandingServiceImpl
    extends BrandingServiceBase
{
    static final String SVG = "image/svg+xml";
    private static final String PNG = "image/png";
    private static final String JPEG = "image/jpeg";
    private static final String WEBP = "image/webp";
    private static final String ICO = "image/x-icon";

    private static final long MAX_ASSET_BYTES = 1024 * 1024;
    private static final String DEFAULT_VERSION = "default";
    private static final String DEFAULT_APP_NAME = "StarSign";

    /** The built-in StarSign colours (portal styles/tailwind.css). */
    private static final Map<String, String> DEFAULT_SEEDS = Map.of(
            "primary", "#00e5ff",
            "secondary", "#1e2a4a",
            "tertiary", "#e5b94c",
            "neutral", "#10131a",
            "error", "#ff5c6c",
            "success", "#2e7d32",
            "warning", "#e5a000",
            "info", "#0288d1");

    private static final Map<BrandingAsset, Set<String>> ALLOWED_TYPES = Map.of(
            BrandingAsset.LOGO_LIGHT, Set.of(PNG, JPEG, WEBP, SVG),
            BrandingAsset.LOGO_DARK, Set.of(PNG, JPEG, WEBP, SVG),
            BrandingAsset.FAVICON, Set.of(PNG, ICO),
            BrandingAsset.EMAIL_LOGO, Set.of(PNG, JPEG));

    private static final Map<String, String> EXTENSIONS = Map.of(PNG, "png", JPEG, "jpg", WEBP, "webp", SVG, "svg", ICO, "ico");

    private static final Pattern TOKEN_NAME = Pattern.compile("^[a-z][a-z0-9-]{0,63}$");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern SVG_EVENT_HANDLER = Pattern.compile("\\son[a-z]+\\s*=", Pattern.CASE_INSENSITIVE);
    private static final int MAX_OVERRIDES = 200;

    private final DocumentStorageReferenceRepository documentStorageReferenceRepository;
    private final CallerContext caller;

    public BrandingServiceImpl(
        @Lazy StorageService storageService,
        @Lazy AuditTrailService auditTrailService,
        BrandingRepository brandingRepository,
        BrandingMapper brandingMapper,
        BrandingAssetFileRepository brandingAssetFileRepository,
        BrandingAssetFileMapper brandingAssetFileMapper,
        OrganisationRepository organisationRepository,
        OrganisationMapper organisationMapper,
        DocumentStorageReferenceRepository documentStorageReferenceRepository,
        CallerContext caller) {

        super(
            storageService,
            auditTrailService,
            brandingRepository,
            brandingMapper,
            brandingAssetFileRepository,
            brandingAssetFileMapper,
            organisationRepository,
            organisationMapper);
        this.documentStorageReferenceRepository = documentStorageReferenceRepository;
        this.caller = caller;
    }

    /**
     * @see bw.co.starsign.branding.BrandingService#getPlatform()
     */
    @Override
    protected BrandingDTO handleGetPlatform()
        throws Exception
    {
        return toDto(platform().orElse(null), null, null);
    }

    /**
     * @see bw.co.starsign.branding.BrandingService#getForOrganisation(String)
     */
    @Override
    protected BrandingDTO handleGetForOrganisation(String organisationId)
        throws Exception
    {
        Organisation organisation = organisation(organisationId);
        Branding platform = platform().orElse(null);
        if (organisation.getBranding() == null)
        {
            BrandingDTO inherited = toDto(platform, null, null);
            inherited.setOrganisationId(organisation.getId().toString());
            inherited.setInherited(true);
            return inherited;
        }
        return toDto(organisation.getBranding(), organisation, platform);
    }

    /**
     * Unset seeds fall back to the platform branding for organisation users, and to the defaults for
     * platform administrators (who edit the platform branding).
     *
     * @see bw.co.starsign.branding.BrandingService#preview(BrandingRequest)
     */
    @Override
    protected BrandingPreviewDTO handlePreview(@Valid BrandingRequest branding)
        throws Exception
    {
        checkRequest(branding);
        Map<String, String> fallback = callerTenant().isPresent()
                ? seeds(platform().orElse(null), DEFAULT_SEEDS) : DEFAULT_SEEDS;
        Map<String, String> seeds = merge(seedMap(branding.getSeeds()), fallback);
        ThemeTokensDTO overrides = branding.getOverrides();

        BrandingPreviewDTO preview = new BrandingPreviewDTO();
        preview.setTokens(tokens(seeds, overrides == null ? null : overrides.getLight(), overrides == null ? null : overrides.getDark()));
        preview.setIssues(contrastIssues(preview.getTokens()));
        return preview;
    }

    /**
     * @see bw.co.starsign.branding.BrandingService#savePlatform(BrandingRequest)
     */
    @Override
    protected BrandingDTO handleSavePlatform(@Valid BrandingRequest branding)
        throws Exception
    {
        checkRequest(branding);
        Branding entity = platform().orElseGet(this::newBranding);
        apply(entity, branding);
        checkContrast(colours(entity, null, null));
        entity = brandingRepository.save(entity);
        audit(null, entity, "Platform branding saved");
        return toDto(entity, null, null);
    }

    /**
     * Removes the platform branding and its files: the portals show the StarSign defaults again.
     *
     * @see bw.co.starsign.branding.BrandingService#resetPlatform()
     */
    @Override
    protected boolean handleResetPlatform()
        throws Exception
    {
        List<Branding> platform = brandingRepository.findPlatform();
        if (platform.isEmpty())
        {
            return false;
        }
        for (Branding branding : platform)
        {
            removeAll(branding);
        }
        audit(null, platform.get(0), "Platform branding reset to the defaults");
        return true;
    }

    /**
     * @see bw.co.starsign.branding.BrandingService#saveForOrganisation(String, BrandingRequest)
     */
    @Override
    protected BrandingDTO handleSaveForOrganisation(String organisationId, @Valid BrandingRequest branding)
        throws Exception
    {
        Organisation organisation = manageable(organisationId);
        checkRequest(branding);

        Branding platform = platform().orElse(null);
        Branding entity = organisation.getBranding() == null ? newBranding() : organisation.getBranding();
        apply(entity, branding);
        checkContrast(colours(entity, organisation, platform));
        entity = attach(organisation, entity);
        audit(organisation, entity, "Organisation branding saved");
        return toDto(entity, organisation, platform);
    }

    /**
     * @see bw.co.starsign.branding.BrandingService#resetForOrganisation(String)
     */
    @Override
    protected boolean handleResetForOrganisation(String organisationId)
        throws Exception
    {
        Organisation organisation = manageable(organisationId);
        Branding branding = organisation.getBranding();
        if (branding == null)
        {
            return false;
        }
        organisation.setBranding(null);
        organisationRepository.saveAndFlush(organisation);
        removeAll(branding);
        audit(organisation, branding, "Organisation branding removed; the platform branding applies again");
        return true;
    }

    /**
     * @see bw.co.starsign.branding.BrandingService#getPlatformAsset(String, String)
     */
    @Override
    protected Resource handleGetPlatformAsset(String asset, String v)
        throws Exception
    {
        BrandingAsset kind = asset(asset);
        BrandingAssetFile file = platform().flatMap(branding -> file(branding, kind))
                .orElseThrow(() -> BrandingServiceException.notFound("The platform has no " + slug(kind)));
        return resource(file, v);
    }

    /**
     * @see bw.co.starsign.branding.BrandingService#uploadPlatformAsset(String, MultipartFile)
     */
    @Override
    protected BrandingDTO handleUploadPlatformAsset(String asset, MultipartFile file)
        throws Exception
    {
        BrandingAsset kind = asset(asset);
        Branding branding = platform().orElseGet(() -> brandingRepository.save(newBranding()));
        upload(branding, null, kind, file);
        branding = brandingRepository.save(branding);
        audit(null, branding, "Platform " + slug(kind) + " uploaded");
        return toDto(branding, null, null);
    }

    /**
     * @see bw.co.starsign.branding.BrandingService#removePlatformAsset(String)
     */
    @Override
    protected BrandingDTO handleRemovePlatformAsset(String asset)
        throws Exception
    {
        BrandingAsset kind = asset(asset);
        Branding branding = platform().orElseThrow(() -> BrandingServiceException.notFound("The platform has no " + slug(kind)));
        removeAsset(branding, kind, "The platform has no " + slug(kind));
        branding = brandingRepository.save(branding);
        audit(null, branding, "Platform " + slug(kind) + " removed");
        return toDto(branding, null, null);
    }

    /**
     * The organisation's file, or the platform's when the organisation has none.
     *
     * @see bw.co.starsign.branding.BrandingService#getOrganisationAsset(String, String, String)
     */
    @Override
    protected Resource handleGetOrganisationAsset(String organisationId, String asset, String v)
        throws Exception
    {
        BrandingAsset kind = asset(asset);
        Organisation organisation = organisation(organisationId);
        Optional<BrandingAssetFile> file = organisation.getBranding() == null ? Optional.empty()
                : file(organisation.getBranding(), kind);
        if (file.isEmpty())
        {
            file = platform().flatMap(branding -> file(branding, kind));
        }
        return resource(file.orElseThrow(() -> BrandingServiceException.notFound("The organisation has no " + slug(kind))), v);
    }

    /**
     * @see bw.co.starsign.branding.BrandingService#uploadOrganisationAsset(String, String, MultipartFile)
     */
    @Override
    protected BrandingDTO handleUploadOrganisationAsset(String organisationId, String asset, MultipartFile file)
        throws Exception
    {
        BrandingAsset kind = asset(asset);
        Organisation organisation = manageable(organisationId);
        Branding branding = organisation.getBranding() == null ? attach(organisation, newBranding()) : organisation.getBranding();
        upload(branding, organisation, kind, file);
        branding = brandingRepository.save(branding);
        audit(organisation, branding, "Organisation " + slug(kind) + " uploaded");
        return toDto(branding, organisation, platform().orElse(null));
    }

    /**
     * @see bw.co.starsign.branding.BrandingService#removeOrganisationAsset(String, String)
     */
    @Override
    protected BrandingDTO handleRemoveOrganisationAsset(String organisationId, String asset)
        throws Exception
    {
        BrandingAsset kind = asset(asset);
        Organisation organisation = manageable(organisationId);
        Branding branding = organisation.getBranding();
        if (branding == null)
        {
            throw BrandingServiceException.notFound("The organisation has no " + slug(kind));
        }
        removeAsset(branding, kind, "The organisation has no " + slug(kind));
        branding = brandingRepository.save(branding);
        audit(organisation, branding, "Organisation " + slug(kind) + " removed");
        return toDto(branding, organisation, platform().orElse(null));
    }

    // ------------------------------------------------------------------------------------------------
    // Branding rows
    // ------------------------------------------------------------------------------------------------

    private Optional<Branding> platform()
    {
        return brandingRepository.findPlatform().stream().findFirst();
    }

    private Branding newBranding()
    {
        Branding branding = Branding.Factory.newInstance();
        branding.setBrandingVersion(newVersion());
        branding.setCreatedAt(LocalDateTime.now());
        branding.setCreatedBy(actorId());
        return branding;
    }

    /** Saves the organisation's branding and links it from the organisation (which owns the link). */
    private Branding attach(Organisation organisation, Branding branding)
    {
        Branding saved = brandingRepository.save(branding);
        if (organisation.getBranding() == null)
        {
            organisation.setBranding(saved);
            organisationRepository.save(organisation);
        }
        return saved;
    }

    /** A PUT: every field takes the request's value; unset ones fall back when the branding is read. */
    private void apply(Branding entity, BrandingRequest request)
    {
        BrandingSeedsDTO seeds = request.getSeeds() == null ? new BrandingSeedsDTO() : request.getSeeds();
        entity.setAppName(StringUtils.trimToNull(request.getAppName()));
        entity.setPrimarySeed(colour(seeds.getPrimary()));
        entity.setSecondarySeed(colour(seeds.getSecondary()));
        entity.setTertiarySeed(colour(seeds.getTertiary()));
        entity.setNeutralSeed(colour(seeds.getNeutral()));
        entity.setErrorSeed(colour(seeds.getError()));
        entity.setSuccessSeed(colour(seeds.getSuccess()));
        entity.setWarningSeed(colour(seeds.getWarning()));
        entity.setInfoSeed(colour(seeds.getInfo()));
        ThemeTokensDTO overrides = request.getOverrides();
        entity.setLightOverrides(overrides(overrides == null ? null : overrides.getLight()));
        entity.setDarkOverrides(overrides(overrides == null ? null : overrides.getDark()));
        entity.setSupportEmail(StringUtils.trimToNull(request.getSupportEmail()));
        entity.setSupportUrl(StringUtils.trimToNull(request.getSupportUrl()));
        entity.setSigningPortalMessage(StringUtils.trimToNull(request.getSigningPortalMessage()));
        entity.setEmailFooter(StringUtils.trimToNull(request.getEmailFooter()));
        changed(entity);
    }

    /** Every change gives a new version: the ETag of the branding. */
    private void changed(Branding entity)
    {
        entity.setBrandingVersion(newVersion());
        entity.setModifiedAt(LocalDateTime.now());
        entity.setModifiedBy(actorId());
    }

    /** Deletes the branding row and schedules its files for deletion. */
    private void removeAll(Branding branding)
    {
        for (BrandingAssetFile file : new ArrayList<>(branding.getAssets()))
        {
            deleteStored(file);
        }
        branding.getAssets().clear();
        brandingRepository.delete(branding);
    }

    // ------------------------------------------------------------------------------------------------
    // Assets
    // ------------------------------------------------------------------------------------------------

    private void upload(Branding branding, Organisation organisation, BrandingAsset kind, MultipartFile file) throws Exception
    {
        if (file.isEmpty())
        {
            throw new BrandingServiceException("The file is empty");
        }
        if (file.getSize() > MAX_ASSET_BYTES)
        {
            throw new BrandingServiceException("The " + slug(kind) + " is larger than 1 MB");
        }
        byte[] content = file.getBytes();
        String type = detectType(content);
        Set<String> allowed = ALLOWED_TYPES.get(kind);
        if (type == null || !allowed.contains(type))
        {
            throw new BrandingServiceException("The " + slug(kind) + " must be " + allowedNames(kind));
        }
        String declared = normaliseType(file.getContentType());
        if (declared != null && !"application/octet-stream".equals(declared) && !declared.equals(type))
        {
            throw new BrandingServiceException("The file's content does not match its type " + file.getContentType());
        }
        if (SVG.equals(type))
        {
            checkSvg(content);
        }

        String sha256 = DigestUtils.sha256Hex(content);
        StorageWriteRequestDTO request = new StorageWriteRequestDTO();
        request.setOrganisationId(organisation == null ? null : organisation.getId().toString());
        request.setNamespace(StorageNamespace.BRANDING);
        request.setContentType(type);
        request.setContentLength((long) content.length);
        request.setOriginalFileName(slug(kind) + "." + EXTENSIONS.get(type));
        request.setExpectedSha256(sha256);
        request.setMetadata(Map.of("asset", slug(kind)));
        DocumentStorageReferenceDTO stored = storageService.store(request, new ByteArrayInputStream(content));
        DocumentStorageReference reference = documentStorageReferenceRepository.findByObjectId(stored.getObjectId())
                .orElseThrow(() -> new BrandingServiceException("The stored " + slug(kind) + " could not be found", new IllegalStateException(stored.getObjectId())));

        LocalDateTime now = LocalDateTime.now();
        BrandingAssetFile assetFile = file(branding, kind).orElse(null);
        if (assetFile == null)
        {
            assetFile = BrandingAssetFile.Factory.newInstance();
            assetFile.setAsset(kind);
            assetFile.setBranding(branding);
            assetFile.setCreatedAt(now);
            assetFile.setCreatedBy(actorId());
            branding.getAssets().add(assetFile);
        }
        else
        {
            // The replaced file is kept until the purge, so cached pages still find it for a while
            deleteStored(assetFile);
            assetFile.setModifiedAt(now);
            assetFile.setModifiedBy(actorId());
        }
        assetFile.setContentType(type);
        assetFile.setSizeBytes(content.length);
        assetFile.setSha256(sha256);
        assetFile.setAssetVersion(sha256.substring(0, 16));
        assetFile.setStorageReference(reference);
        changed(branding);
    }

    private void removeAsset(Branding branding, BrandingAsset kind, String missing)
    {
        BrandingAssetFile file = file(branding, kind).orElseThrow(() -> BrandingServiceException.notFound(missing));
        deleteStored(file);
        branding.getAssets().remove(file);
        changed(branding);
    }

    private void deleteStored(BrandingAssetFile file)
    {
        if (file.getStorageReference() != null)
        {
            storageService.scheduleDeletion(file.getStorageReference().getObjectId(), LocalDateTime.now());
        }
    }

    private Resource resource(BrandingAssetFile file, String v) throws Exception
    {
        byte[] content;
        try (InputStream in = storageService.openStream(file.getStorageReference().getObjectId()))
        {
            content = in.readAllBytes();
        }
        String fileName = slug(file.getAsset()) + "." + EXTENSIONS.getOrDefault(file.getContentType(), "bin");
        return new BrandingAssetResource(content, file.getContentType(), file.getAssetVersion(),
                file.getAssetVersion().equals(StringUtils.trimToNull(v)), fileName);
    }

    private static Optional<BrandingAssetFile> file(Branding branding, BrandingAsset kind)
    {
        return branding.getAssets().stream().filter(file -> file.getAsset() == kind).findFirst();
    }

    /** By magic bytes; null when it is none of the image types branding accepts. */
    private static String detectType(byte[] content)
    {
        if (startsWith(content, 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a))
        {
            return PNG;
        }
        if (startsWith(content, 0xff, 0xd8, 0xff))
        {
            return JPEG;
        }
        if (content.length >= 12 && startsWith(content, 'R', 'I', 'F', 'F')
                && content[8] == 'W' && content[9] == 'E' && content[10] == 'B' && content[11] == 'P')
        {
            return WEBP;
        }
        if (startsWith(content, 0x00, 0x00, 0x01, 0x00))
        {
            return ICO;
        }
        String text = new String(content, 0, Math.min(content.length, 1024), StandardCharsets.UTF_8)
                .replace("﻿", "").stripLeading().toLowerCase(Locale.ROOT);
        if ((text.startsWith("<?xml") || text.startsWith("<svg") || text.startsWith("<!--")) && text.contains("<svg"))
        {
            return SVG;
        }
        return null;
    }

    private static boolean startsWith(byte[] content, int... magic)
    {
        if (content.length < magic.length)
        {
            return false;
        }
        for (int i = 0; i < magic.length; i++)
        {
            if ((content[i] & 0xff) != magic[i])
            {
                return false;
            }
        }
        return true;
    }

    /** SVGs are served sandboxed as well; scripts and external content are refused outright. */
    private static void checkSvg(byte[] content)
    {
        String svg = new String(content, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        if (svg.contains("<script") || svg.contains("javascript:") || svg.contains("<foreignobject")
                || svg.contains("<!entity") || svg.contains("<!doctype") || SVG_EVENT_HANDLER.matcher(svg).find())
        {
            throw new BrandingServiceException("The SVG contains scripts or embedded content; upload a plain SVG or a PNG");
        }
    }

    private static String normaliseType(String contentType)
    {
        if (StringUtils.isBlank(contentType))
        {
            return null;
        }
        String type = contentType.split(";")[0].trim().toLowerCase(Locale.ROOT);
        return switch (type)
        {
            case "image/jpg", "image/pjpeg" -> JPEG;
            case "image/vnd.microsoft.icon", "image/ico" -> ICO;
            default -> type;
        };
    }

    private static String allowedNames(BrandingAsset kind)
    {
        return ALLOWED_TYPES.get(kind).stream()
                .map(type -> switch (type)
                {
                    case PNG -> "PNG";
                    case JPEG -> "JPEG";
                    case WEBP -> "WebP";
                    case SVG -> "SVG";
                    default -> "ICO";
                })
                .sorted()
                .collect(Collectors.joining(", "));
    }

    /** Accepts the slug (logo-light) or the enum name (LOGO_LIGHT). */
    private static BrandingAsset asset(String asset)
    {
        String name = asset.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        try
        {
            return BrandingAsset.valueOf(name);
        }
        catch (IllegalArgumentException e)
        {
            throw BrandingServiceException.notFound("There is no branding file called '" + asset
                    + "' (logo-light, logo-dark, favicon or email-logo)");
        }
    }

    static String slug(BrandingAsset asset)
    {
        return asset.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    // ------------------------------------------------------------------------------------------------
    // Colours
    // ------------------------------------------------------------------------------------------------

    private record Colours(Map<String, String> seeds, Map<?, ?> light, Map<?, ?> dark)
    {
    }

    /** The colours a branding row shows; see the class comment for the fallbacks. */
    private Colours colours(Branding branding, Organisation organisation, Branding platform)
    {
        Map<String, String> platformSeeds = seeds(platform, DEFAULT_SEEDS);
        if (branding == null)
        {
            return new Colours(DEFAULT_SEEDS, null, null);
        }
        if (organisation != null && ownSeeds(branding).isEmpty() && isEmpty(branding.getLightOverrides())
                && isEmpty(branding.getDarkOverrides()))
        {
            return new Colours(platformSeeds, platform == null ? null : platform.getLightOverrides(),
                    platform == null ? null : platform.getDarkOverrides());
        }
        Map<String, String> fallback = organisation == null ? DEFAULT_SEEDS : platformSeeds;
        return new Colours(merge(ownSeeds(branding), fallback), branding.getLightOverrides(), branding.getDarkOverrides());
    }

    private static Map<String, String> seeds(Branding branding, Map<String, String> fallback)
    {
        return branding == null ? fallback : merge(ownSeeds(branding), fallback);
    }

    private static Map<String, String> ownSeeds(Branding branding)
    {
        Map<String, String> seeds = new LinkedHashMap<>();
        putIfSet(seeds, "primary", branding.getPrimarySeed());
        putIfSet(seeds, "secondary", branding.getSecondarySeed());
        putIfSet(seeds, "tertiary", branding.getTertiarySeed());
        putIfSet(seeds, "neutral", branding.getNeutralSeed());
        putIfSet(seeds, "error", branding.getErrorSeed());
        putIfSet(seeds, "success", branding.getSuccessSeed());
        putIfSet(seeds, "warning", branding.getWarningSeed());
        putIfSet(seeds, "info", branding.getInfoSeed());
        return seeds;
    }

    private static Map<String, String> seedMap(BrandingSeedsDTO dto)
    {
        Map<String, String> seeds = new LinkedHashMap<>();
        if (dto != null)
        {
            putIfSet(seeds, "primary", colour(dto.getPrimary()));
            putIfSet(seeds, "secondary", colour(dto.getSecondary()));
            putIfSet(seeds, "tertiary", colour(dto.getTertiary()));
            putIfSet(seeds, "neutral", colour(dto.getNeutral()));
            putIfSet(seeds, "error", colour(dto.getError()));
            putIfSet(seeds, "success", colour(dto.getSuccess()));
            putIfSet(seeds, "warning", colour(dto.getWarning()));
            putIfSet(seeds, "info", colour(dto.getInfo()));
        }
        return seeds;
    }

    private static BrandingSeedsDTO seedsDto(Map<String, String> seeds)
    {
        BrandingSeedsDTO dto = new BrandingSeedsDTO();
        dto.setPrimary(seeds.get("primary"));
        dto.setSecondary(seeds.get("secondary"));
        dto.setTertiary(seeds.get("tertiary"));
        dto.setNeutral(seeds.get("neutral"));
        dto.setError(seeds.get("error"));
        dto.setSuccess(seeds.get("success"));
        dto.setWarning(seeds.get("warning"));
        dto.setInfo(seeds.get("info"));
        return dto;
    }

    private static void putIfSet(Map<String, String> seeds, String name, String colour)
    {
        if (colour != null)
        {
            seeds.put(name, colour);
        }
    }

    private static Map<String, String> merge(Map<String, String> own, Map<String, String> fallback)
    {
        Map<String, String> merged = new LinkedHashMap<>(fallback);
        merged.putAll(own);
        return merged;
    }

    private static ThemeTokensDTO tokens(Map<String, String> seeds, Map<?, ?> light, Map<?, ?> dark)
    {
        ThemeTokensDTO tokens = new ThemeTokensDTO();
        tokens.setLight(withOverrides(ThemePalette.tokens(seeds, ThemePalette.LIGHT), light));
        tokens.setDark(withOverrides(ThemePalette.tokens(seeds, ThemePalette.DARK), dark));
        return tokens;
    }

    private static Map<String, String> withOverrides(Map<String, String> generated, Map<?, ?> overrides)
    {
        if (overrides != null)
        {
            overrides.forEach((name, value) -> {
                if (name != null && ThemePalette.isHex(String.valueOf(value)))
                {
                    generated.put(name.toString(), ThemePalette.normalise(String.valueOf(value)));
                }
            });
        }
        return generated;
    }

    @SuppressWarnings("unchecked")
    private static List<ContrastIssueDTO> contrastIssues(ThemeTokensDTO tokens)
    {
        List<ContrastIssueDTO> issues = new ArrayList<>(ThemePalette.contrastIssues(tokens.getLight(), ThemePalette.LIGHT));
        issues.addAll(ThemePalette.contrastIssues(tokens.getDark(), ThemePalette.DARK));
        return issues;
    }

    /** Text without enough contrast is refused when branding is saved. */
    private static void checkContrast(Colours colours)
    {
        List<ContrastIssueDTO> issues = contrastIssues(tokens(colours.seeds(), colours.light(), colours.dark()));
        if (!issues.isEmpty())
        {
            throw new BrandingServiceException("Text would not have enough contrast: " + issues.stream()
                    .map(issue -> String.format(Locale.ROOT, "%s on %s (%s mode) is %.2f:1, needs %.1f:1",
                            issue.getForeground(), issue.getBackground(), issue.getMode(), issue.getRatio(), issue.getMinimum()))
                    .collect(Collectors.joining("; ")));
        }
    }

    // ------------------------------------------------------------------------------------------------
    // DTO
    // ------------------------------------------------------------------------------------------------

    /**
     * {@code branding} null gives the built-in defaults; {@code organisation} null means the platform
     * branding.
     */
    private BrandingDTO toDto(Branding branding, Organisation organisation, Branding platform)
    {
        Colours colours = colours(branding, organisation, platform);
        Branding fallback = organisation == null ? null : platform;

        BrandingDTO dto = new BrandingDTO();
        dto.setOrganisationId(organisation == null ? null : organisation.getId().toString());
        dto.setVersion(branding == null ? DEFAULT_VERSION : branding.getBrandingVersion());
        dto.setAppName(first(branding == null ? null : branding.getAppName(),
                fallback == null ? null : fallback.getAppName(), DEFAULT_APP_NAME));
        dto.setSeeds(seedsDto(colours.seeds()));
        ThemeTokensDTO overrides = new ThemeTokensDTO();
        overrides.setLight(branding == null || branding.getLightOverrides() == null ? Map.of() : branding.getLightOverrides());
        overrides.setDark(branding == null || branding.getDarkOverrides() == null ? Map.of() : branding.getDarkOverrides());
        dto.setOverrides(overrides);
        dto.setTokens(tokens(colours.seeds(), colours.light(), colours.dark()));
        dto.setLogoLightUrl(assetUrl(branding, organisation, fallback, BrandingAsset.LOGO_LIGHT));
        dto.setLogoDarkUrl(assetUrl(branding, organisation, fallback, BrandingAsset.LOGO_DARK));
        dto.setFaviconUrl(assetUrl(branding, organisation, fallback, BrandingAsset.FAVICON));
        dto.setEmailLogoUrl(assetUrl(branding, organisation, fallback, BrandingAsset.EMAIL_LOGO));
        dto.setSupportEmail(first(branding == null ? null : branding.getSupportEmail(), fallback == null ? null : fallback.getSupportEmail(), null));
        dto.setSupportUrl(first(branding == null ? null : branding.getSupportUrl(), fallback == null ? null : fallback.getSupportUrl(), null));
        dto.setSigningPortalMessage(first(branding == null ? null : branding.getSigningPortalMessage(),
                fallback == null ? null : fallback.getSigningPortalMessage(), null));
        dto.setEmailFooter(first(branding == null ? null : branding.getEmailFooter(), fallback == null ? null : fallback.getEmailFooter(), null));
        dto.setInherited(false);
        if (branding != null)
        {
            dto.setModifiedBy(StringUtils.defaultIfBlank(branding.getModifiedBy(), branding.getCreatedBy()));
            dto.setModifiedAt(branding.getModifiedAt() == null ? branding.getCreatedAt() : branding.getModifiedAt());
        }
        return dto;
    }

    /**
     * API-relative URL of the file with its version as cache-buster; the organisation's file, else the
     * platform's.
     */
    private static String assetUrl(Branding branding, Organisation organisation, Branding platform, BrandingAsset kind)
    {
        Optional<BrandingAssetFile> own = branding == null ? Optional.empty() : file(branding, kind);
        if (own.isPresent())
        {
            return (organisation == null ? "/branding/assets/" : "/branding/organisations/" + organisation.getId() + "/assets/")
                    + slug(kind) + "?v=" + own.get().getAssetVersion();
        }
        Optional<BrandingAssetFile> inherited = platform == null ? Optional.empty() : file(platform, kind);
        return inherited.map(file -> "/branding/assets/" + slug(kind) + "?v=" + file.getAssetVersion()).orElse(null);
    }

    private static String first(String own, String fallback, String otherwise)
    {
        return StringUtils.firstNonBlank(own, fallback, otherwise);
    }

    // ------------------------------------------------------------------------------------------------
    // Validation, tenancy, audit
    // ------------------------------------------------------------------------------------------------

    private static void checkRequest(BrandingRequest request)
    {
        List<String> errors = new ArrayList<>();
        if (request.getAppName() != null && request.getAppName().trim().length() > 100)
        {
            errors.add("The app name is longer than 100 characters");
        }
        BrandingSeedsDTO seeds = request.getSeeds();
        if (seeds != null)
        {
            checkColour(errors, "primary", seeds.getPrimary());
            checkColour(errors, "secondary", seeds.getSecondary());
            checkColour(errors, "tertiary", seeds.getTertiary());
            checkColour(errors, "neutral", seeds.getNeutral());
            checkColour(errors, "error", seeds.getError());
            checkColour(errors, "success", seeds.getSuccess());
            checkColour(errors, "warning", seeds.getWarning());
            checkColour(errors, "info", seeds.getInfo());
        }
        if (request.getOverrides() != null)
        {
            checkOverrides(errors, ThemePalette.LIGHT, request.getOverrides().getLight());
            checkOverrides(errors, ThemePalette.DARK, request.getOverrides().getDark());
        }
        if (StringUtils.isNotBlank(request.getSupportEmail())
                && (request.getSupportEmail().trim().length() > 254 || !EMAIL.matcher(request.getSupportEmail().trim()).matches()))
        {
            errors.add("The support email address is not valid");
        }
        if (StringUtils.isNotBlank(request.getSupportUrl()) && !isWebUrl(request.getSupportUrl().trim()))
        {
            errors.add("The support URL must be a web address starting with https:// or http://");
        }
        if (request.getSigningPortalMessage() != null && request.getSigningPortalMessage().trim().length() > 1000)
        {
            errors.add("The signing portal message is longer than 1000 characters");
        }
        if (request.getEmailFooter() != null && request.getEmailFooter().trim().length() > 2000)
        {
            errors.add("The email footer is longer than 2000 characters");
        }
        if (!errors.isEmpty())
        {
            throw new BrandingServiceException(String.join("; ", errors));
        }
    }

    private static void checkColour(List<String> errors, String name, String colour)
    {
        if (StringUtils.isNotBlank(colour) && !ThemePalette.isHex(colour))
        {
            errors.add("The " + name + " colour must be a hex colour such as #1a73e8");
        }
    }

    private static void checkOverrides(List<String> errors, String mode, Map<?, ?> overrides)
    {
        if (overrides == null)
        {
            return;
        }
        if (overrides.size() > MAX_OVERRIDES)
        {
            errors.add("There are more than " + MAX_OVERRIDES + " " + mode + " overrides");
            return;
        }
        overrides.forEach((name, value) -> {
            if (name == null || !TOKEN_NAME.matcher(name.toString()).matches())
            {
                errors.add("'" + name + "' is not a token name (lower-case letters, digits and hyphens, e.g. color-primary)");
            }
            else if (value == null || !ThemePalette.isHex(value.toString()))
            {
                errors.add("The " + mode + " override " + name + " must be a hex colour such as #1a73e8");
            }
        });
    }

    private static boolean isWebUrl(String url)
    {
        try
        {
            URI uri = new URI(url);
            return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && StringUtils.isNotBlank(uri.getHost()) && url.length() <= 500;
        }
        catch (Exception e)
        {
            return false;
        }
    }

    private static String colour(String colour)
    {
        return StringUtils.isBlank(colour) ? null : ThemePalette.normalise(colour);
    }

    private static Map<String, String> overrides(Map<?, ?> overrides)
    {
        if (isEmpty(overrides))
        {
            return null;
        }
        Map<String, String> normalised = new LinkedHashMap<>();
        overrides.forEach((name, value) -> normalised.put(name.toString(), ThemePalette.normalise(value.toString())));
        return normalised;
    }

    private static boolean isEmpty(Map<?, ?> map)
    {
        return map == null || map.isEmpty();
    }

    private static String newVersion()
    {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private Organisation organisation(String organisationId)
    {
        UUID id;
        try
        {
            id = UUID.fromString(organisationId.trim());
        }
        catch (IllegalArgumentException e)
        {
            throw BrandingServiceException.notFound("'" + organisationId + "' is not the id of an organisation");
        }
        return organisationRepository.findById(id)
                .orElseThrow(() -> BrandingServiceException.notFound("The organisation does not exist"));
    }

    /** The organisation, when the caller may change its branding: its own users, or platform administrators. */
    private Organisation manageable(String organisationId)
    {
        Organisation organisation = organisation(organisationId);
        Optional<String> tenant = callerTenant();
        if (tenant.isPresent() && !tenant.get().equals(organisation.getTenantId())
                && !organisation.getId().toString().equals(callerOrganisation().orElse(null)))
        {
            throw BrandingServiceException.notFound("The organisation does not exist");
        }
        return organisation;
    }

    private void audit(Organisation organisation, Branding branding, String details)
    {
        AuditEventDTO event = new AuditEventDTO();
        event.setEventType(AuditEventType.SETTINGS_CHANGED);
        event.setOutcome(AuditOutcome.SUCCESS);
        event.setOccurredAt(LocalDateTime.now());
        event.setOrganisationId(organisation == null ? null : organisation.getId().toString());
        Optional<String> user = callerUser();
        event.setActorType(user.isEmpty() ? ActorType.SYSTEM
                : callerTenant().isPresent() ? ActorType.ORGANISATION_USER : ActorType.PLATFORM_ADMIN);
        event.setActorId(user.orElse("system"));
        event.setEntityType("Branding");
        event.setEntityId(branding.getId() == null ? null : branding.getId().toString());
        event.setDetails(details);
        auditTrailService.record(event);
    }

    private String actorId()
    {
        return callerUser().orElse("system");
    }

    /* CallerContext refuses when nobody is signed in (the public branding endpoints). */
    private Optional<String> callerUser()
    {
        try
        {
            return Optional.ofNullable(caller.userId());
        }
        catch (RuntimeException notSignedIn)
        {
            return Optional.empty();
        }
    }

    private Optional<String> callerTenant()
    {
        try
        {
            return caller.tenantId();
        }
        catch (RuntimeException notSignedIn)
        {
            return Optional.empty();
        }
    }

    private Optional<String> callerOrganisation()
    {
        try
        {
            return caller.organisationId();
        }
        catch (RuntimeException notSignedIn)
        {
            return Optional.empty();
        }
    }
}
