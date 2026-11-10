package mygrant.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.Principal;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import mygrant.sources.PolicySource;
import mygrant.sources.PolicySourceRepository;
import mygrant.sources.PolicySourceSyncRunRepository;
import mygrant.sources.PolicySourceType;

/** A configured Visa Bulletin source cannot quietly keep retrying a blocked HTTP download. */
@SpringBootTest
@Transactional
class VisaBulletinManualSyncTest {
    @Autowired SourceSyncService sync;
    @Autowired PolicySourceRepository sources;
    @Autowired PolicySourceSyncRunRepository runs;
    private static final Principal ADMIN = () -> "admin@example.test";

    @Test
    void scheduledAndBulkSyncSkipTheManualBulletinSource() {
        PolicySource source = sources.saveAndFlush(new PolicySource("Visa Bulletin manual source",
                PolicySourceType.VISA_BULLETIN,
                "https://travel.state.gov/content/travel/en/legal/visa-law0/visa-bulletin.html", 3600));
        sync.syncDueSources();
        assertThat(sync.syncAll(ADMIN)).isEmpty();
        assertThat(runs.findTopBySource_IdOrderByStartedAtDesc(source.getId())).isEmpty();
        assertThatThrownBy(() -> sync.syncOne(source.getId(), ADMIN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("import it in the admin page");
    }
}
