package mygrant.ingestion;

import java.security.Principal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import mygrant.ingestion.dto.IngestionStatusResponse;
import mygrant.ingestion.dto.IngestionSyncResponse;
import mygrant.ingestion.federalregister.FederalRegisterClient;
import mygrant.ingestion.federalregister.FederalRegisterImportRequest;
import mygrant.ingestion.federalregister.FederalRegisterIngestionResponse;
import mygrant.ingestion.federalregister.FederalRegisterIngestionService;
import mygrant.ingestion.newsrss.NewsRssConnector;
import mygrant.ingestion.uscis.UscisProcessingTimeImporter;
import mygrant.ingestion.visabulletin.VisaBulletinImporter;
import mygrant.sources.PolicySource;
import mygrant.sources.PolicySourceRepository;
import mygrant.sources.PolicySourceSyncRun;
import mygrant.sources.PolicySourceSyncRunRepository;
import mygrant.sources.PolicySourceType;

@Service
public class SourceSyncService {

    private final PolicySourceRepository sourceRepository;
    private final PolicySourceSyncRunRepository runRepository;
    private final FederalRegisterClient federalRegisterClient;
    private final FederalRegisterIngestionService federalRegister;
    private final VisaBulletinImporter visaBulletin;
    private final NewsRssConnector newsRss;
    private final UscisProcessingTimeImporter uscis;

    public SourceSyncService(PolicySourceRepository sourceRepository,
            PolicySourceSyncRunRepository runRepository, FederalRegisterClient federalRegisterClient,
            FederalRegisterIngestionService federalRegister, VisaBulletinImporter visaBulletin,
            NewsRssConnector newsRss, UscisProcessingTimeImporter uscis) {
        this.sourceRepository = sourceRepository;
        this.runRepository = runRepository;
        this.federalRegisterClient = federalRegisterClient;
        this.federalRegister = federalRegister;
        this.visaBulletin = visaBulletin;
        this.newsRss = newsRss;
        this.uscis = uscis;
    }

    @Transactional(readOnly = true)
    public List<IngestionStatusResponse> status() {
        return sourceRepository.findAllByOrderByIdAsc().stream()
                .map(source -> status(source, runRepository.findTopBySource_IdOrderByStartedAtDesc(source.getId()),
                        runRepository.countFailures(source.getId())))
                .toList();
    }

    public List<IngestionSyncResponse> syncAll(Principal principal) {
        return sourceRepository.findAllByEnabledTrueOrderByIdAsc().stream()
                .map(source -> sync(source, principal.getName()))
                .toList();
    }

    public void syncDueSources() {
        Instant now = Instant.now();
        sourceRepository.findAllByEnabledTrueOrderByIdAsc().stream()
                .filter(source -> isDue(source, now))
                .forEach(source -> sync(source, "scheduler@system"));
    }

    public IngestionSyncResponse syncOne(Long sourceId, Principal principal) {
        PolicySource source = sourceRepository.findById(sourceId)
                .orElseThrow(() -> new IllegalArgumentException("Policy source not found: " + sourceId));
        if (!source.isEnabled()) {
            throw new IllegalArgumentException("Disabled policy sources cannot be synced");
        }
        return sync(source, principal.getName());
    }

    private synchronized IngestionSyncResponse sync(PolicySource source, String userEmail) {
        Instant startedAt = Instant.now();
        PolicySourceSyncRun run = runRepository.save(new PolicySourceSyncRun(source, startedAt));
        try {
            SyncCounts counts = switch (source.getType()) {
                case FEDERAL_REGISTER -> syncFederalRegister(source, userEmail);
                case VISA_BULLETIN -> syncVisaBulletin();
                case NEWS, RSS -> syncNewsRss();
                case USCIS -> syncUscis(source);
            };
            Instant finishedAt = Instant.now();
            run.complete(finishedAt, counts.documentsFetched(), counts.chunksVectorised());
            runRepository.save(run);
            source.recordSuccess(finishedAt);
            sourceRepository.save(source);
            return new IngestionSyncResponse(source.getId(), startedAt, finishedAt,
                    counts.documentsFetched(), counts.chunksVectorised(), true, null);
        } catch (RuntimeException exception) {
            Instant finishedAt = Instant.now();
            String error = errorMessage(exception);
            run.fail(finishedAt, error);
            runRepository.save(run);
            source.recordFailure(finishedAt, error);
            sourceRepository.save(source);
            return new IngestionSyncResponse(source.getId(), startedAt, finishedAt,
                    0, 0, false, error);
        }
    }

    private SyncCounts syncFederalRegister(PolicySource source, String userEmail) {
        LocalDate to = LocalDate.now();
        LocalDate from = source.getLastSuccessAt() == null
                ? to.minusDays(1) : source.getLastSuccessAt().atZone(java.time.ZoneOffset.UTC).toLocalDate();
        List<String> documents = federalRegisterClient.searchDocumentNumbers(from, to, 100);
        int chunks = 0;
        for (String documentNumber : documents) {
            FederalRegisterIngestionResponse response = federalRegister.ingest(
                    new FederalRegisterImportRequest(documentNumber, userEmail, false));
            chunks += response.ingestion().chunksIndexed();
        }
        return new SyncCounts(documents.size(), chunks);
    }

    private SyncCounts syncVisaBulletin() {
        VisaBulletinImporter.Result result = visaBulletin.importMonth(YearMonth.now());
        return new SyncCounts(1, 0);
    }

    private SyncCounts syncNewsRss() {
        NewsRssConnector.Result result = newsRss.pull();
        return new SyncCounts(result.articles(), result.chunksVectorised());
    }

    private SyncCounts syncUscis(PolicySource source) {
        uscis.pull(source.getUrl());
        return new SyncCounts(1, 0);
    }

    private boolean isDue(PolicySource source, Instant now) {
        return runRepository.findTopBySource_IdOrderByStartedAtDesc(source.getId())
                .map(run -> !run.getStartedAt().plusSeconds(source.getPollIntervalSeconds()).isAfter(now))
                .orElse(true);
    }

    private IngestionStatusResponse status(PolicySource source,
            java.util.Optional<PolicySourceSyncRun> latest, long failureCount) {
        PolicySourceSyncRun run = latest.orElse(null);
        return new IngestionStatusResponse(source.getId(), source.getName(), source.getType(),
                source.getUrl(), source.isEnabled(), run == null ? null : run.getStartedAt(),
                run == null ? 0 : run.getDocumentsFetched(),
                run == null ? 0 : run.getChunksVectorised(), failureCount,
                run != null && run.getError() != null ? run.getError() : source.getLastError());
    }

    private String errorMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message.substring(0, Math.min(message.length(), 2000));
    }

    private record SyncCounts(int documentsFetched, int chunksVectorised) {
    }
}
