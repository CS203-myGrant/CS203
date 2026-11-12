package mygrant.sources;

import java.util.List;

import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import io.swagger.v3.oas.annotations.media.Schema;

import mygrant.sources.SourceFreshnessService.Freshness;

/**
 * CS203-175: a page of assessments with the freshness of every enabled policy source. It keeps
 * the usual page fields and adds {@code sourceFreshness}, which is present even when the page is
 * empty, since an empty feed may be empty because the sources stopped updating.
 */
public class FreshnessPage<T> extends PageImpl<T> {

    private final transient Freshness sourceFreshness;

    public FreshnessPage(List<T> content, Pageable pageable, long total, Freshness sourceFreshness) {
        super(content, pageable, total);
        this.sourceFreshness = sourceFreshness;
    }

    @Schema(description = "CS203-175: freshness of every enabled policy source, for an out-of-date warning")
    public Freshness getSourceFreshness() {
        return sourceFreshness;
    }
}
