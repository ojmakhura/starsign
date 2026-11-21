package bw.co.starsign.security;

/**
 * What a {@link RequiresPermission} id expression identifies, so {@link OwnershipResolver} can find the
 * record's tenant and owner.
 */
public enum OwnerType {

    /** A {@code Document}: owned by its owner and by the internal signatories of its workflows. */
    DOCUMENT,

    /** A {@code SigningWorkflow}: owned by its owner and by its internal signatories. */
    WORKFLOW
}
