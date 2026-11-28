package bw.co.starsign.common;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/** The page requests of the paged searches. */
public final class Pages
{
    private Pages()
    {
    }

    /**
     * The page request of the criteria's page number and size, the size defaulted and capped.
     *
     * @param defaultSize the size when the criteria give none
     * @param maxSize the largest size allowed
     */
    public static PageRequest request(BaseSearchCriteria criteria, int defaultSize, int maxSize, Sort sort)
    {
        int number = criteria.getPageNumber() == null ? 0 : Math.max(0, criteria.getPageNumber());
        int size = criteria.getPageSize() == null || criteria.getPageSize() < 1 ? defaultSize
                : Math.min(maxSize, criteria.getPageSize());
        return PageRequest.of(number, size, sort);
    }
}
