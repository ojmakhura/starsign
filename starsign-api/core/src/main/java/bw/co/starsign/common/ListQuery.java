package bw.co.starsign.common;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * A list query that {@link ListItemQueries} runs: a JPQL constructor expression that builds the list DTOs, the
 * values of its named parameters, and a completion step for what the expression cannot hold.
 *
 * @param entity the entity the JPQL selects from first; the specification, the sort and the count refer to it
 * @param dto the class the JPQL builds
 * @param jpql a {@code select new <dto>(...) from <Entity> <alias> ...} query, without a where or order by clause
 *        the specification and the sort would clash with
 * @param parameters the values of the named parameters of the JPQL
 * @param complete fills in the rows of a page that need their own query (a collection, for example), once for
 *        the whole page; it is not called for an empty page
 * @param <T> the entity
 * @param <D> the list DTO
 */
public record ListQuery<T, D>(Class<T> entity, Class<D> dto, String jpql, Map<String, ?> parameters,
        UnaryOperator<List<D>> complete)
{
    public ListQuery
    {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(dto, "dto");
        Objects.requireNonNull(jpql, "jpql");
        parameters = Map.copyOf(parameters);
        Objects.requireNonNull(complete, "complete");
    }

    /** A query with no parameters and nothing to complete. */
    public static <T, D> ListQuery<T, D> of(Class<T> entity, Class<D> dto, String jpql)
    {
        return new ListQuery<>(entity, dto, jpql, Map.of(), UnaryOperator.identity());
    }

    /** This query with a value for one more named parameter. */
    public ListQuery<T, D> with(String name, Object value)
    {
        Map<String, Object> values = new LinkedHashMap<>(parameters);
        values.put(name, value);
        return new ListQuery<>(entity, dto, jpql, values, complete);
    }

    /** This query with the completion step that fills in the rows of each page. */
    public ListQuery<T, D> completedBy(UnaryOperator<List<D>> completion)
    {
        return new ListQuery<>(entity, dto, jpql, parameters, completion);
    }
}
