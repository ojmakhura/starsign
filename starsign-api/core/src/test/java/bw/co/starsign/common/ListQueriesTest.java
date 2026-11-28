package bw.co.starsign.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import bw.co.starsign.document.Document;
import bw.co.starsign.document.DocumentListDTO;
import bw.co.starsign.document.DocumentRepository;
import bw.co.starsign.document.DocumentStatus;
import bw.co.starsign.identity.OrganisationUser;
import bw.co.starsign.identity.OrganisationUserListDTO;
import bw.co.starsign.identity.OrganisationUserRepository;
import bw.co.starsign.identity.UserRole;
import bw.co.starsign.organisation.Organisation;
import bw.co.starsign.organisation.OrganisationStatus;
import bw.co.starsign.organisation.OrganisationListDTO;
import bw.co.starsign.organisation.OrganisationRepository;
import bw.co.starsign.template.SigningTemplate;
import bw.co.starsign.template.SigningTemplateListDTO;
import bw.co.starsign.template.SigningTemplateRepository;
import bw.co.starsign.template.TemplateStatus;
import bw.co.starsign.workflow.ExecutionMode;
import bw.co.starsign.workflow.SigningWorkflow;
import bw.co.starsign.workflow.SigningWorkflowListDTO;
import bw.co.starsign.workflow.SigningWorkflowRepository;
import bw.co.starsign.workflow.WorkflowStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.transaction.annotation.Transactional;

/**
 * The list queries filter, sort, page and build their DTOs in one statement (the users' roles in one more),
 * however many rows are listed, and without loading any entity.
 */
@SpringBootTest(classes = ListQueriesTest.Config.class, webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.liquibase.enabled=false",
            "spring.datasource.url=jdbc:h2:mem:lists;INIT=CREATE DOMAIN IF NOT EXISTS JSONB AS JSON",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.jpa.show-sql=false",
            "spring.jpa.properties.hibernate.generate_statistics=true",
            "spring.cloud.aws.s3.enabled=false",
            "spring.cloud.aws.region.static=af-south-1",
        })
@Transactional
class ListQueriesTest
{
    private static final Set<WorkflowStatus> ACTIVE = EnumSet.of(WorkflowStatus.DRAFT, WorkflowStatus.READY,
            WorkflowStatus.IN_PROGRESS, WorkflowStatus.COMPLETING);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 28, 12, 0);

    @Configuration
    @EnableAutoConfiguration
    @AutoConfigurationPackage(basePackages = "bw.co.starsign")
    static class Config
    {
    }

    @PersistenceContext
    private EntityManager entityManager;
    @Autowired
    private DocumentRepository documentRepository;
    @Autowired
    private SigningWorkflowRepository workflowRepository;
    @Autowired
    private OrganisationRepository organisationRepository;
    @Autowired
    private OrganisationUserRepository userRepository;
    @Autowired
    private SigningTemplateRepository templateRepository;

    private Statistics statistics;
    private int placeholders;
    private UUID organisation;
    private UUID ada;
    private UUID nameless;
    private UUID removed;
    private UUID admin;
    private UUID signer;

    @BeforeEach
    void setUp()
    {
        statistics = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        entityManager.createNativeQuery("SET REFERENTIAL_INTEGRITY FALSE").executeUpdate();

        organisation = insert("ORGANISATION", Map.of("NAME", "Acme", "TENANT_ID", "acme", "STATUS", "ACTIVE",
                "CREATED_AT", NOW));
        ada = insert("ORGANISATION_USER", Map.of("ORGANISATION_FK", organisation, "FIRST_NAME", " Ada ",
                "LAST_NAME", "Lovelace", "EMAIL_ADDRESS", "ada@acme.test", "STATUS", "ACTIVE"));
        nameless = insert("ORGANISATION_USER", Map.of("ORGANISATION_FK", organisation, "FIRST_NAME", " ", "LAST_NAME", "",
                "EMAIL_ADDRESS", "nameless@acme.test", "STATUS", "ACTIVE"));
        removed = insert("ORGANISATION_USER", Map.of("ORGANISATION_FK", organisation, "FIRST_NAME", "Gone",
                "EMAIL_ADDRESS", "gone@acme.test", "STATUS", "ACTIVE", "DELETED_AT", NOW));
        admin = insert("ROLE", Map.of("CODE", "ORG_ADMIN"));
        signer = insert("ROLE", Map.of("CODE", "DOCUMENT_SIGNER"));
        insert("USER_ROLE", Map.of("USER_FK", ada, "ROLE_FK", admin));
        insert("USER_ROLE", Map.of("USER_FK", ada, "ROLE_FK", signer));
    }

    @Test
    void documentListItemsCountTheSignatoriesOfTheRelevantWorkflow()
    {
        // The active workflow counts even though a finished one is newer
        UUID withActive = document("DOC-1", ada, NOW.minusDays(3));
        workflow(withActive, WorkflowStatus.COMPLETED, NOW.minusDays(1), 3, 2, 1);
        workflow(withActive, WorkflowStatus.IN_PROGRESS, NOW.minusDays(2), 2, 1, 1);
        // Without an active workflow, the latest one counts
        UUID finished = document("DOC-2", nameless, NOW.minusDays(2));
        workflow(finished, WorkflowStatus.CANCELLED, NOW.minusDays(2), 4, 0, 1);
        workflow(finished, WorkflowStatus.COMPLETED, NOW.minusDays(1), 1, 1, 1);
        UUID unsent = document("DOC-3", ada, NOW.minusDays(1));

        statistics.clear();
        List<DocumentListDTO> items = documentRepository.findListItems(Specification.unrestricted(),
                PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id"))), ACTIVE);

        assertEquals(1, statistics.getPrepareStatementCount());
        assertEquals(0, statistics.getEntityLoadCount());
        assertEquals(List.of("DOC-3", "DOC-2", "DOC-1"), items.stream().map(DocumentListDTO::getReferenceNumber).toList());
        assertEquals(List.of(unsent.toString(), finished.toString(), withActive.toString()),
                items.stream().map(DocumentListDTO::getId).toList());
        assertCounts(items.get(0), 0, 0);
        assertCounts(items.get(1), 1, 1);
        assertCounts(items.get(2), 2, 1);
        assertEquals("Ada Lovelace", items.get(2).getOwnerName());
        assertEquals("nameless@acme.test", items.get(1).getOwnerName());
        assertEquals(DocumentStatus.DRAFT, items.get(2).getStatus());
        assertEquals(7, items.get(2).getPageCount());
    }

    @Test
    void documentListItemsAreFilteredSortedAndPagedInTheSameQuery()
    {
        document("DOC-A", ada, NOW.minusDays(1));
        document("DOC-B", ada, NOW.minusDays(2));
        document("DOC-C", ada, NOW.minusDays(3));
        document("DOC-D", nameless, NOW.minusDays(4));
        // Filters through the owner, a join the JPQL also makes
        Specification<Document> adasNotC = (root, query, builder) -> builder.and(
                builder.notEqual(root.get("referenceNumber"), "DOC-C"),
                builder.equal(root.get("owner").get("id"), ada));
        Sort oldestFirst = Sort.by(Sort.Direction.ASC, "createdAt").and(Sort.by("id"));

        statistics.clear();
        List<DocumentListDTO> first = documentRepository.findListItems(adasNotC, PageRequest.of(0, 1, oldestFirst), ACTIVE);
        List<DocumentListDTO> second = documentRepository.findListItems(adasNotC, PageRequest.of(1, 1, oldestFirst), ACTIVE);
        List<DocumentListDTO> past = documentRepository.findListItems(adasNotC, PageRequest.of(2, 1, oldestFirst), ACTIVE);

        assertEquals(3, statistics.getPrepareStatementCount());
        assertEquals(0, statistics.getEntityLoadCount());
        assertEquals(0, statistics.getCollectionLoadCount());
        assertEquals(List.of("DOC-B"), first.stream().map(DocumentListDTO::getReferenceNumber).toList());
        assertEquals(List.of("DOC-A"), second.stream().map(DocumentListDTO::getReferenceNumber).toList());
        assertEquals(List.of(), past);
    }

    @Test
    void workflowListItemsCountSignatoriesAndStages()
    {
        UUID document = document("DOC-1", ada, NOW.minusDays(3));
        UUID busy = workflow(document, WorkflowStatus.IN_PROGRESS, NOW.minusDays(2), 3, 1, 2);
        UUID empty = workflow(document, WorkflowStatus.DRAFT, NOW.minusDays(1), 0, 0, 0);
        workflow(document, WorkflowStatus.CANCELLED, NOW.minusDays(1), 1, 0, 1);
        Specification<SigningWorkflow> open = (root, query, builder) -> root.get("status")
                .in(WorkflowStatus.IN_PROGRESS, WorkflowStatus.DRAFT);

        statistics.clear();
        List<SigningWorkflowListDTO> items = workflowRepository.findListItems(open,
                PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id"))));

        assertEquals(1, statistics.getPrepareStatementCount());
        assertEquals(0, statistics.getEntityLoadCount());
        assertEquals(List.of(empty.toString(), busy.toString()), items.stream().map(SigningWorkflowListDTO::getId).toList());
        SigningWorkflowListDTO item = items.get(1);
        assertEquals("Document DOC-1", item.getDocumentTitle());
        assertEquals("Ada Lovelace", item.getOwnerName());
        assertEquals(WorkflowStatus.IN_PROGRESS, item.getStatus());
        assertEquals(3, item.getSignatoryCount());
        assertEquals(1, item.getSignedCount());
        assertEquals(2, item.getStageCount());
        assertEquals(1, item.getCurrentStageNumber());
        assertEquals(0, items.get(0).getSignatoryCount());
        assertEquals(0, items.get(0).getStageCount());
    }

    @Test
    void organisationListItemsLeaveOutRemovedUsers()
    {
        UUID other = insert("ORGANISATION", Map.of("NAME", "Beta", "TENANT_ID", "beta", "STATUS", "ACTIVE"));
        insert("ORGANISATION", Map.of("NAME", "Closed", "TENANT_ID", "closed", "STATUS", "CLOSED"));
        Specification<Organisation> active = (root, query, builder) -> builder.equal(root.get("status"),
                OrganisationStatus.ACTIVE);

        statistics.clear();
        List<OrganisationListDTO> items = organisationRepository.findListItems(active,
                Sort.by(Sort.Direction.DESC, "name"));

        assertEquals(1, statistics.getPrepareStatementCount());
        assertEquals(0, statistics.getEntityLoadCount());
        assertEquals(List.of("Beta", "Acme"), items.stream().map(OrganisationListDTO::getName).toList());
        assertEquals(other.toString(), items.get(0).getId());
        assertEquals(0L, items.get(0).getUserCount());
        assertEquals(2L, items.get(1).getUserCount());
    }

    @Test
    void userListItemsCarryTheirRoleCodes()
    {
        Specification<OrganisationUser> live = (root, query, builder) -> builder.isNull(root.get("deletedAt"));

        statistics.clear();
        List<OrganisationUserListDTO> items = userRepository.findListItems(live,
                Pageable.unpaged(Sort.by(Sort.Direction.DESC, "emailAddress")));

        assertEquals(2, statistics.getPrepareStatementCount());
        assertEquals(0, statistics.getEntityLoadCount());
        assertEquals(0, statistics.getCollectionLoadCount());
        assertEquals(List.of(nameless.toString(), ada.toString()), items.stream().map(OrganisationUserListDTO::getId).toList());
        assertEquals(List.of("DOCUMENT_SIGNER", "ORG_ADMIN"), items.get(1).getRoles());
        assertEquals(List.of(), items.get(0).getRoles());
        assertEquals("ada@acme.test", items.get(1).getEmailAddress());
    }

    @Test
    void userListItemsFilterByRoleThroughASubquery()
    {
        // The specification the user search builds for a role code
        Specification<OrganisationUser> admins = (root, query, builder) -> {
            Subquery<UUID> holders = query.subquery(UUID.class);
            Root<UserRole> role = holders.from(UserRole.class);
            holders.select(role.get("user").get("id")).where(builder.equal(role.get("role").get("code"), "ORG_ADMIN"));
            return root.get("id").in(holders);
        };

        statistics.clear();
        List<OrganisationUserListDTO> items = userRepository.findListItems(admins,
                PageRequest.of(0, 5, Sort.by("lastName", "firstName", "id")));
        List<OrganisationUserListDTO> none = userRepository.findListItems(
                (root, query, builder) -> builder.equal(root.get("emailAddress"), "nobody@acme.test"),
                Pageable.unpaged(Sort.by("id")));

        // The roles query is left out when no user matches
        assertEquals(3, statistics.getPrepareStatementCount());
        assertEquals(List.of(ada.toString()), items.stream().map(OrganisationUserListDTO::getId).toList());
        assertEquals(List.of("DOCUMENT_SIGNER", "ORG_ADMIN"), items.get(0).getRoles());
        assertEquals(List.of(), none);
    }

    @Test
    void documentPagesCountTheMatchesOnlyWhenThePageCannotShowThem()
    {
        for (int day = 1; day <= 5; day++)
        {
            document("DOC-" + day, ada, NOW.minusDays(day));
        }
        Specification<Document> adas = (root, query, builder) -> builder.equal(root.get("owner").get("id"), ada);
        Sort newestFirst = Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id"));

        // A full first page cannot tell how many more there are: one select and one count
        statistics.clear();
        Page<DocumentListDTO> first = documentRepository.findListItemPage(adas, PageRequest.of(0, 2, newestFirst), ACTIVE);
        assertEquals(2, statistics.getPrepareStatementCount());
        assertEquals(5, first.getTotalElements());
        assertEquals(3, first.getTotalPages());
        assertEquals(List.of("DOC-1", "DOC-2"), first.map(DocumentListDTO::getReferenceNumber).getContent());

        // The last page shows the total itself: no count
        statistics.clear();
        Page<DocumentListDTO> last = documentRepository.findListItemPage(adas, PageRequest.of(2, 2, newestFirst), ACTIVE);
        assertEquals(1, statistics.getPrepareStatementCount());
        assertEquals(5, last.getTotalElements());
        assertEquals(List.of("DOC-5"), last.map(DocumentListDTO::getReferenceNumber).getContent());

        // As does a first page that is not full
        statistics.clear();
        Page<DocumentListDTO> all = documentRepository.findListItemPage(adas, PageRequest.of(0, 10, newestFirst), ACTIVE);
        assertEquals(1, statistics.getPrepareStatementCount());
        assertEquals(5, all.getTotalElements());
        assertEquals(0, statistics.getEntityLoadCount());
    }

    @Test
    void workflowAndOrganisationPagesCarryTheirTotals()
    {
        UUID document = document("DOC-1", ada, NOW.minusDays(3));
        for (int day = 1; day <= 3; day++)
        {
            workflow(document, WorkflowStatus.DRAFT, NOW.minusDays(day), 1, 0, 1);
        }
        insert("ORGANISATION", Map.of("NAME", "Beta", "TENANT_ID", "beta", "STATUS", "ACTIVE"));

        Page<SigningWorkflowListDTO> workflows = workflowRepository.findListItemPage(Specification.unrestricted(),
                PageRequest.of(0, 2, Sort.by("createdAt", "id")));
        Page<OrganisationListDTO> organisations = organisationRepository.findListItemPage(Specification.unrestricted(),
                PageRequest.of(0, 1, Sort.by("name", "id")));

        assertEquals(3, workflows.getTotalElements());
        assertEquals(2, workflows.getContent().size());
        assertEquals(2, organisations.getTotalElements());
        assertEquals(List.of("Acme"), organisations.map(OrganisationListDTO::getName).getContent());
    }

    @Test
    void userPagesAreCompletedWithTheirRolesOnce()
    {
        Specification<OrganisationUser> live = (root, query, builder) -> builder.isNull(root.get("deletedAt"));

        // Select, roles and count: the completion runs once for the page, not once per user
        statistics.clear();
        Page<OrganisationUserListDTO> page = userRepository.findListItemPage(live,
                PageRequest.of(0, 1, Sort.by("emailAddress", "id")));

        assertEquals(3, statistics.getPrepareStatementCount());
        assertEquals(2, page.getTotalElements());
        assertEquals(List.of(ada.toString()), page.map(OrganisationUserListDTO::getId).getContent());
        assertEquals(List.of("DOCUMENT_SIGNER", "ORG_ADMIN"), page.getContent().get(0).getRoles());

        // An empty page skips both the completion and the count
        statistics.clear();
        Page<OrganisationUserListDTO> empty = userRepository.findListItemPage(
                (root, query, builder) -> builder.equal(root.get("emailAddress"), "nobody@acme.test"),
                PageRequest.of(0, 5, Sort.by("id")));
        assertEquals(1, statistics.getPrepareStatementCount());
        assertEquals(0, empty.getTotalElements());
    }

    @Test
    void templateListItemsCountRolesStagesAndFields()
    {
        UUID routed = template("Board resolution", TemplateStatus.ACTIVE, NOW.minusDays(2));
        UUID role = templateRole(routed, 1);
        templateRole(routed, 1);
        templateRole(routed, 2);
        insert("SIGNING_TEMPLATE_FIELD", Map.of("TEMPLATE_FK", routed, "SIGNATORY_ROLE_FK", role));
        insert("SIGNING_TEMPLATE_FIELD", Map.of("TEMPLATE_FK", routed, "SIGNATORY_ROLE_FK", role));
        UUID empty = template("Blank", TemplateStatus.DRAFT, NOW.minusDays(1));
        template("Old", TemplateStatus.ARCHIVED, NOW.minusDays(3));
        Specification<SigningTemplate> current = (root, query, builder) -> builder.notEqual(root.get("status"),
                TemplateStatus.ARCHIVED);

        statistics.clear();
        Page<SigningTemplateListDTO> page = templateRepository.findListItemPage(current,
                PageRequest.of(0, 10, Sort.by("name", "id")));

        assertEquals(1, statistics.getPrepareStatementCount());
        assertEquals(0, statistics.getEntityLoadCount());
        assertEquals(2, page.getTotalElements());
        assertEquals(List.of(empty.toString(), routed.toString()),
                page.map(SigningTemplateListDTO::getId).getContent());
        SigningTemplateListDTO item = page.getContent().get(1);
        assertEquals("Board resolution", item.getName());
        assertEquals(TemplateStatus.ACTIVE, item.getStatus());
        assertEquals(ExecutionMode.SEQUENTIAL, item.getExecutionMode());
        assertEquals(3, item.getRoleCount());
        assertEquals(2, item.getStageCount());
        assertEquals(2, item.getFieldCount());
        SigningTemplateListDTO blank = page.getContent().get(0);
        assertEquals(0, blank.getRoleCount());
        assertEquals(0, blank.getStageCount());
        assertEquals(0, blank.getFieldCount());
    }

    @Test
    void aListQueryMustSelectFromItsEntityFirst()
    {
        ListQuery<Document, OrganisationListDTO> wrong = ListQuery.of(Document.class, OrganisationListDTO.class,
                OrganisationRepository.LIST_ITEMS);

        assertThrows(RuntimeException.class,
                () -> documentRepository.findListItems(wrong, Specification.unrestricted(), Pageable.unpaged()));
    }

    /* ---------------------------------------------------------------- data */

    private static void assertCounts(DocumentListDTO item, int signatories, int signed)
    {
        assertEquals(signatories, item.getSignatoryCount(), item.getReferenceNumber() + " signatories");
        assertEquals(signed, item.getSignedCount(), item.getReferenceNumber() + " signed");
    }

    private UUID document(String reference, UUID owner, LocalDateTime createdAt)
    {
        return insert("DOCUMENT", Map.of("ORGANISATION_FK", organisation, "OWNER_FK", owner, "REFERENCE_NUMBER", reference,
                "TITLE", "Document " + reference, "STATUS", "DRAFT", "PAGE_COUNT", 7, "CREATED_AT", createdAt));
    }

    private UUID workflow(UUID document, WorkflowStatus status, LocalDateTime createdAt, int signatories, int signed,
            int stages)
    {
        UUID workflow = insert("SIGNING_WORKFLOW", Map.of("ORGANISATION_FK", organisation, "DOCUMENT_FK", document,
                "OWNER_FK", ada, "STATUS", status.name(), "CREATED_AT", createdAt, "CURRENT_STAGE_NUMBER", 1));
        UUID stage = null;
        for (int number = 1; number <= stages; number++)
        {
            stage = insert("SIGNING_STAGE", Map.of("WORKFLOW_FK", workflow, "STAGE_NUMBER", number));
        }
        for (int index = 0; index < signatories; index++)
        {
            Map<String, Object> values = new LinkedHashMap<>(Map.of("WORKFLOW_FK", workflow,
                    "STATUS", index < signed ? "SIGNED" : "INVITED"));
            if (stage != null)
            {
                values.put("STAGE_FK", stage);
            }
            insert("SIGNATORY", values);
        }
        return workflow;
    }

    private UUID template(String name, TemplateStatus status, LocalDateTime createdAt)
    {
        return insert("SIGNING_TEMPLATE", Map.of("ORGANISATION_FK", organisation, "NAME", name,
                "STATUS", status.name(), "EXECUTION_MODE", "SEQUENTIAL", "CREATED_AT", createdAt));
    }

    private UUID templateRole(UUID template, int stage)
    {
        return insert("SIGNING_TEMPLATE_SIGNATORY", Map.of("TEMPLATE_FK", template, "STAGE_NUMBER", stage));
    }

    /** Inserts a row with the given values, filling the other required columns with placeholders. */
    private UUID insert(String table, Map<String, Object> given)
    {
        Map<String, Object> values = new LinkedHashMap<>(given);
        UUID id = UUID.randomUUID();
        values.put("ID", id);
        @SuppressWarnings("unchecked")
        List<Object[]> columns = entityManager.createNativeQuery("select COLUMN_NAME, DATA_TYPE, DTD_IDENTIFIER"
                + " from INFORMATION_SCHEMA.COLUMNS where TABLE_NAME = ?1 and IS_NULLABLE = 'NO'")
                .setParameter(1, table)
                .getResultList();
        for (Object[] column : columns)
        {
            String name = (String) column[0];
            if (!values.containsKey(name))
            {
                values.put(name, placeholder(table, (String) column[1], (String) column[2]));
            }
        }
        List<String> names = new ArrayList<>(values.keySet());
        StringBuilder sql = new StringBuilder("insert into ").append(table).append(" (")
                .append(String.join(", ", names)).append(") values (");
        for (int index = 1; index <= names.size(); index++)
        {
            sql.append(index == 1 ? "?" : ", ?").append(index);
        }
        var query = entityManager.createNativeQuery(sql.append(")").toString());
        for (int index = 0; index < names.size(); index++)
        {
            Object value = values.get(names.get(index));
            query.setParameter(index + 1, value instanceof LocalDateTime time ? Timestamp.valueOf(time) : value);
        }
        query.executeUpdate();
        return id;
    }

    private Object placeholder(String table, String type, String dtd)
    {
        return switch (type)
        {
            case "ENUM" -> entityManager.createNativeQuery("select VALUE_NAME from INFORMATION_SCHEMA.ENUM_VALUES"
                    + " where OBJECT_NAME = ?1 and ENUM_IDENTIFIER = ?2 order by VALUE_ORDINAL fetch first 1 row only")
                    .setParameter(1, table)
                    .setParameter(2, dtd)
                    .getSingleResult();
            case "BOOLEAN" -> false;
            case "INTEGER", "BIGINT", "SMALLINT", "TINYINT", "NUMERIC", "DECIMAL", "DOUBLE PRECISION", "REAL" -> 0;
            case "UUID" -> UUID.randomUUID();
            case "DATE" -> java.sql.Date.valueOf(LocalDate.of(2026, 1, 1));
            case "TIMESTAMP", "TIMESTAMP WITH TIME ZONE" -> Timestamp.valueOf(NOW);
            case "BINARY VARYING", "BINARY LARGE OBJECT", "BINARY" -> new byte[0];
            default -> "p" + placeholders++;
        };
    }
}
