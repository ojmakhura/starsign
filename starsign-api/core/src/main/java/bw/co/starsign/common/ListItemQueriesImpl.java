package bw.co.starsign.common;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.List;
import org.hibernate.Session;
import org.hibernate.query.criteria.HibernateCriteriaBuilder;
import org.hibernate.query.criteria.JpaCriteriaQuery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.query.QueryUtils;
import org.springframework.data.support.PageableExecutionUtils;

/**
 * Turns the JPQL into a Hibernate criteria query, so the specification's predicate and the page's sort can be
 * added to it.
 */
public class ListItemQueriesImpl implements ListItemQueries
{
    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public <T, D> List<D> findListItems(ListQuery<T, D> query, Specification<T> specification, Pageable page)
    {
        List<D> rows = select(query, specification, page);
        return rows.isEmpty() ? rows : query.complete().apply(rows);
    }

    @Override
    public <T, D> Page<D> findListItemPage(ListQuery<T, D> query, Specification<T> specification, Pageable page)
    {
        List<D> rows = findListItems(query, specification, page);
        return PageableExecutionUtils.getPage(rows, page, () -> count(query, specification));
    }

    private <T, D> List<D> select(ListQuery<T, D> list, Specification<T> specification, Pageable page)
    {
        HibernateCriteriaBuilder builder = builder();
        JpaCriteriaQuery<D> query = builder.createQuery(list.jpql(), list.dto());
        // Also checks that the JPQL selects from the entity first
        @SuppressWarnings("unchecked")
        Root<T> root = (Root<T>) query.getRoot(0, list.entity());
        restrict(query, root, specification, builder);
        if (page.getSort().isSorted())
        {
            query.orderBy(QueryUtils.toOrders(page.getSort(), root, builder));
        }

        TypedQuery<D> typed = entityManager.createQuery(query);
        list.parameters().forEach(typed::setParameter);
        if (page.isPaged())
        {
            typed.setFirstResult(Math.toIntExact(page.getOffset()));
            typed.setMaxResults(page.getPageSize());
        }
        return typed.getResultList();
    }

    /**
     * The number of rows the specification matches. Counted from the entity alone: the joins of a list query
     * follow required associations, so they never change the number of rows.
     */
    private <T> long count(ListQuery<T, ?> list, Specification<T> specification)
    {
        HibernateCriteriaBuilder builder = builder();
        JpaCriteriaQuery<Long> query = builder.createQuery(Long.class);
        Root<T> root = query.from(list.entity());
        restrict(query, root, specification, builder);
        query.select(query.isDistinct() ? builder.countDistinct(root) : builder.count(root));
        return entityManager.createQuery(query).getSingleResult();
    }

    private static <T> void restrict(CriteriaQuery<?> query, Root<T> root, Specification<T> specification,
            HibernateCriteriaBuilder builder)
    {
        Predicate criteria = specification.toPredicate(root, query, builder);
        if (criteria != null)
        {
            Predicate restriction = query.getRestriction();
            query.where(restriction == null ? criteria : builder.and(restriction, criteria));
        }
    }

    private HibernateCriteriaBuilder builder()
    {
        return entityManager.unwrap(Session.class).getCriteriaBuilder();
    }
}
