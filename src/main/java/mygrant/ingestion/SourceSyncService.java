package mygrant.ingestion;

import java.security.Principal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import mygrant.ingestion.dto.IngestionStatusResponse;
import mygrant.ingestion.dto.IngestionSyncResponse;
import mygrant.ingestion.dto.SyncFailureResponse;
import mygrant.ingestion.federalregister.FederalRegisterClient;
import mygrant.ingestion.federalregister.FederalRegisterImportRequest;
import mygrant.ingestion.federalregister.FederalRegisterIngestionResponse;
import mygrant.ingestion.federalregister.FederalRegisterIngestionService;
import mygrant.ingestion.federalregister.UnsupportedVisaCategoryException;
import mygrant.ingestion.newsrss.NewsRssConnector;
import mygrant.ingestion.uscis.UscisProcessingTimeImporter;
import mygrant.ingestion.visabulletin.VisaBulletinImporter;
import mygrant.ingestion.connector.ConnectorHealth;
import mygrant.ingestion.connector.ConnectorStatusService;
import mygrant.sources.PolicySource;
import mygrant.sources.PolicySourceRepository;
import mygrant.sources.PolicySourceSyncRun;
import mygrant.sources.PolicySourceSyncRunRepository;
import mygrant.sources.PolicySourceType;
import mygrant.sources.SyncFailure;
import mygrant.sources.SyncFailureRepository;

@Service
public class SourceSyncService {

    private static final Logger LOGGER = LoggerFactory.getLogger(SourceSyncService.class);

    private final PolicySourceRepository sourceRepository;
    private final PolicySourceSyncRunRepository runRepository;
    private final SyncFailureRepository failureRepository;
    private final FederalRegisterClient federalRegisterClient;
    private final FederalRegisterIngestionService federalRegister;
    private final VisaBulletinImporter visaBulletin;
    private final NewsRssConnector newsRss;
    private final UscisProcessingTimeImporter uscis;
    private final ConnectorStatusService connectorStatusService;
    private final int maxDocumentAttempts;

    public SourceSyncService(PolicySourceRepository sourceRepository,
            PolicySourceSyncRunRepository runRepository, SyncFailureRepository failureRepository,
            FederalRegisterClient federalRegisterClient,
            FederalRegisterIngestionService federalRegister, VisaBulletinImporter visaBulletin,
            NewsRssConnector newsRss, UscisProcessingTimeImporter uscis,
            ConnectorStatusService connectorStatusService,
            @Value("${mygrant.ingestion.max-document-attempts:5}") int maxDocumentAttempts) {
        if (maxDocumentAttempts < 1) {
            throw new IllegalArgumentException("mygrant.ingestion.max-document-attempts must be at least 1");
        }
        this.sourceRepository = sourceRepository;
        this.runRepository = runRepository;
        this.failureRepository = failureRepository;
        this.federalRegisterClient = federalRegisterClient;
        this.federalRegister = federalRegister;
        this.visaBulletin = visaBulletin;
        this.newsRss = newsRss;
        this.uscis = uscis;
        this.connectorStatusService = connectorStatusService;
        this.maxDocumentAttempts = maxDocumentAttempts;
    }

    @Transactional(readOnly = true)
    public List<IngestionStatusResponse> status() {
        List<PolicySource> sources = sourceRepository.findAllByOrderByIdAsc();
        if (sources.isEmpty()) {
            return connectorStatusService.status().stream()
                    .map(this::status)
                    .toList();
        }
        return sources.stream()
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
            run.complete(finishedAt, counts.documentsFetched(), counts.chunksVectorised(), counts.documentErrors());
            runRepository.save(run);
            source.recordSuccess(finishedAt);
            sourceRepository.save(source);
            return new IngestionSyncResponse(source.getId(), startedAt, finishedAt,
                    counts.documentsFetched(), counts.chunksVectorised(), true, counts.documentErrors());
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

    /**
     * Imports what the Federal Register search finds since the last completed run, and retries the
     * documents earlier runs failed on. A document that fails is logged and recorded in
     * sync_failures, and the run carries on with the rest. It is tried again on every later run,
     * whether or not the search still finds it, until it imports or has failed maxDocumentAttempts
     * times in a row; then it is PERMANENTLY_FAILED and skipped. The run still completes, so the
     * search window (lastSuccessAt) moves past failed documents: sync_failures, not the window,
     * brings them back. A candidate the classifier finds is about no supported visa type is not a
     * failure. A failure of the search itself still fails the run, and the window stays put.
     */
    private SyncCounts syncFederalRegister(PolicySource source, String userEmail) {
        LocalDate to = LocalDate.now();
        LocalDate from = source.getLastSuccessAt() == null
                ? to.minusDays(1) : source.getLastSuccessAt().atZone(java.time.ZoneOffset.UTC).toLocalDate();
        Set<String> documents = new LinkedHashSet<>(federalRegisterClient.searchDocumentNumbers(from, to, 100));
        documents.addAll(failureRepository.findDocumentIds(source.getId(), SyncFailure.Status.RETRYING));
        Map<String, SyncFailure> failures = documents.isEmpty() ? Map.of()
                : failureRepository.findBySource_IdAndDocumentIdIn(source.getId(), documents).stream()
                        .collect(Collectors.toMap(SyncFailure::getDocumentId, Function.identity()));

        int attempted = 0;
        int imported = 0;
        int unsupported = 0;
        int chunks = 0;
        List<String> failed = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (String documentNumber : documents) {
            SyncFailure failure = failures.get(documentNumber);
            if (failure != null && failure.isPermanentlyFailed()) {
                skipped.add(documentNumber);
                continue;
            }
            attempted++;
            try {
                FederalRegisterIngestionResponse response = federalRegister.ingest(
                        new FederalRegisterImportRequest(documentNumber, userEmail, false));
                chunks += response.ingestion().chunksIndexed();
                imported++;
            } catch (UnsupportedVisaCategoryException exception) {
                // The search only finds candidates; the classifier decided this one is out of scope.
                unsupported++;
            } catch (RuntimeException exception) {
                failed.add(recordFailure(source, documentNumber, failure, exception));
                continue;
            }
            if (failure != null && failure.getStatus() == SyncFailure.Status.RETRYING) {
                failure.resolve(Instant.now());
                failureRepository.save(failure);
                LOGGER.info("Federal Register sync for source {} ({}): document {} imported after {} failed "
                        + "attempt(s)", source.getId(), source.getName(), documentNumber, failure.getAttempts());
            }
        }

        String documentErrors = failed.isEmpty() ? null
                : failed.size() + " of " + attempted + " documents failed: " + String.join("; ", failed);
        String summary = "Federal Register sync for source " + source.getId() + " (" + source.getName()
                + ") completed: " + attempted + " documents tried, " + imported + " imported, " + unsupported
                + " about no supported visa type, " + failed.size() + " failed, " + skipped.size()
                + " skipped as permanently failed" + (skipped.isEmpty() ? "" : " " + skipped);
        if (documentErrors == null) {
            LOGGER.info(summary);
        } else {
            LOGGER.warn("{}. {}", summary, documentErrors);
        }
        return new SyncCounts(attempted, chunks, documentErrors);
    }

    /**
     * Records one more failed attempt at a document and logs it. Returns how the run summary
     * describes it.
     */
    private String recordFailure(PolicySource source, String documentNumber, SyncFailure previous,
            RuntimeException exception) {
        Instant now = Instant.now();
        String error = errorMessage(exception);
        SyncFailure failure = previous;
        if (failure == null) {
            failure = new SyncFailure(source, documentNumber, error, now, maxDocumentAttempts);
        } else {
            failure.failedAgain(error, now, maxDocumentAttempts);
        }
        failureRepository.save(failure);
        String outcome = failure.isPermanentlyFailed() ? "will not be retried" : "will be retried on the next run";
        LOGGER.warn("Federal Register sync for source {} ({}): document {} failed on attempt {} of {} and {}: {}",
                source.getId(), source.getName(), documentNumber, failure.getAttempts(), maxDocumentAttempts,
                outcome, error, exception);
        return documentNumber + " (attempt " + failure.getAttempts() + " of " + maxDocumentAttempts + ", "
                + outcome + "): " + error;
    }

    /** Documents a sync failed on, for admins, newest failure first. */
    @Transactional(readOnly = true)
    public Page<SyncFailureResponse> failures(SyncFailure.Status status, Pageable pageable) {
        Page<SyncFailure> page = status == null ? failureRepository.findAllByOrderByLastFailedAtDescIdDesc(pageable)
                : failureRepository.findByStatusOrderByLastFailedAtDescIdDesc(status, pageable);
        return page.map(failure -> new SyncFailureResponse(failure.getId(), failure.getSource().getId(),
                failure.getSource().getName(), failure.getDocumentId(), failure.getStatus(), failure.getAttempts(),
                maxDocumentAttempts, failure.getLastError(), failure.getFirstFailedAt(), failure.getLastFailedAt(),
                failure.getResolvedAt()));
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

    private IngestionStatusResponse status(ConnectorHealth health) {
        String id = health.source().id();
        mygrant.sources.PolicySourceType type = switch (id) {
            case "FEDERAL_REGISTER" -> mygrant.sources.PolicySourceType.FEDERAL_REGISTER;
            case "USCIS_PROCESSING_TIMES" -> mygrant.sources.PolicySourceType.USCIS;
            case "VISA_BULLETIN" -> mygrant.sources.PolicySourceType.VISA_BULLETIN;
            case "NEWS_RSS" -> mygrant.sources.PolicySourceType.RSS;
            default -> mygrant.sources.PolicySourceType.NEWS;
        };
        return new IngestionStatusResponse(null, id, type, "", true,
                health.lastAttemptAt(), 0, 0,
                health.status() == ConnectorHealth.Status.FAILED ? 1 : 0,
                health.errorMessage());
    }

    private String errorMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message.substring(0, Math.min(message.length(), 2000));
    }

    /** documentErrors names the documents that failed, or is null when none did. */
    private record SyncCounts(int documentsFetched, int chunksVectorised, String documentErrors) {
        SyncCounts(int documentsFetched, int chunksVectorised) {
            this(documentsFetched, chunksVectorised, null);
        }
    }
}
