package bw.co.starsign.security;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.metamodel.EntityType;

/**
 * Finds the tenant of a document or workflow and whether the caller owns it: they are its owner, or an
 * internal signatory (Signatory.internalUser) on it. Plain JPQL over the entities of the UML model, so it
 * does not depend on which repository methods AndroMDA generates.
 */
@Component
@Transactional(readOnly = true)
public class OwnershipResolver {

    /** The record's tenant, and whether the given user owns or signs it. */
    public record Owner(String tenantId, boolean ownedByCaller) {
    }

    private static final Map<OwnerType, String> ENTITIES = Map.of(
            OwnerType.DOCUMENT, "Document",
            OwnerType.WORKFLOW, "SigningWorkflow");

    private static final Map<OwnerType, String> RECORD = Map.of(
            OwnerType.DOCUMENT, "select d.organisation.tenantId, d.owner.keycloakUserId from Document d "
                    + "where d.id = :id and d.deletedAt is null",
            OwnerType.WORKFLOW, "select w.organisation.tenantId, w.owner.keycloakUserId from SigningWorkflow w "
                    + "where w.id = :id");

    private static final Map<OwnerType, String> SIGNATORY = Map.of(
            OwnerType.DOCUMENT, "select count(s) from Signatory s "
                    + "where s.workflow.document.id = :id and s.internalUser.keycloakUserId = :user",
            OwnerType.WORKFLOW, "select count(s) from Signatory s "
                    + "where s.workflow.id = :id and s.internalUser.keycloakUserId = :user");

    @PersistenceContext
    private EntityManager entityManager;

    private final Map<String, Class<?>> idTypes = new ConcurrentHashMap<>();

    /**
     * @return the owner, or null when the record does not exist (or the id is malformed)
     */
    public Owner resolve(OwnerType type, Object id, String userId) {

        Object key = id(ENTITIES.get(type), id);
        if (key == null) {
            return null;
        }

        List<Object[]> rows = entityManager.createQuery(RECORD.get(type), Object[].class)
                .setParameter("id", key)
                .getResultList();
        if (rows.isEmpty()) {
            return null;
        }
        String tenantId = (String) rows.get(0)[0];
        if (userId != null && userId.equals(rows.get(0)[1])) {
            return new Owner(tenantId, true);
        }

        Long signs = entityManager.createQuery(SIGNATORY.get(type), Long.class)
                .setParameter("id", key)
                .setParameter("user", userId)
                .getSingleResult();
        return new Owner(tenantId, signs != null && signs > 0);
    }

    /** The id converted to the entity's identifier type (UUID or String). */
    private Object id(String entity, Object id) {
        if (id == null) {
            return null;
        }
        Class<?> idType = idTypes.computeIfAbsent(entity, this::idTypeOf);
        if (idType.isInstance(id)) {
            return id;
        }
        if (UUID.class.equals(idType)) {
            try {
                return UUID.fromString(id.toString());
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return id.toString();
    }

    private Class<?> idTypeOf(String entity) {
        for (EntityType<?> type : entityManager.getMetamodel().getEntities()) {
            if (type.getName().equals(entity)) {
                return type.getIdType().getJavaType();
            }
        }
        throw new IllegalStateException("Entity " + entity + " is not mapped");
    }
}
