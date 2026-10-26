package mygrant.common;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/** Pages a list that was loaded whole, for sources the database does not page. */
public final class Pages {

    private Pages() {
    }

    /** Ignores the requested sort: the list keeps the order its source gave it. */
    public static <T> Page<T> of(List<T> all, Pageable pageable) {
        Pageable unsorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
        int from = (int) Math.min(unsorted.getOffset(), all.size());
        int to = Math.min(from + unsorted.getPageSize(), all.size());
        return new PageImpl<>(all.subList(from, to), unsorted, all.size());
    }
}
