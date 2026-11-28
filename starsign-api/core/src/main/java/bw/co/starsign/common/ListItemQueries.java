package bw.co.starsign.common;

import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

/**
 * Repository fragment that runs {@link ListQuery list queries}: a JPQL constructor expression whose rows a
 * {@link Specification} filters, all in one statement. The JPQL selects the DTO columns and joins what they need;
 * the specification adds the search criteria to its where clause; the page sorts and limits the rows. No entity
 * is loaded. The query's completion step then fills in, once for the whole page, what the expression cannot hold.
 */
public interface ListItemQueries
{
    /**
     * The DTOs of the rows the specification matches, in one statement plus whatever the completion step runs.
     *
     * @param page the sort and the page; {@link Pageable#unpaged(org.springframework.data.domain.Sort)} for all rows
     */
    <T, D> List<D> findListItems(ListQuery<T, D> query, Specification<T> specification, Pageable page);

    /**
     * One page of the DTOs of the rows the specification matches, with the number of matching rows. That number
     * takes a count statement, left out when the page itself shows it: a first page that is not full, or the
     * last page.
     */
    <T, D> Page<D> findListItemPage(ListQuery<T, D> query, Specification<T> specification, Pageable page);
}
