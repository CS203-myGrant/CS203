package mygrant.ingestion;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls enabled sources; each source's own interval is enforced by the sync service. */
@Component
public class SourceSyncScheduler {

    private final SourceSyncService sourceSyncService;

    public SourceSyncScheduler(SourceSyncService sourceSyncService) {
        this.sourceSyncService = sourceSyncService;
    }

    @Scheduled(fixedDelayString = "${mygrant.ingestion.scheduler-interval:60000}")
    public void pollEnabledSources() {
        sourceSyncService.syncDueSources();
    }
}
