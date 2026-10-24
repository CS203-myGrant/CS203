package mygrant.ingestion.uscis;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import mygrant.ingestion.connector.ConnectorFetch;
import mygrant.ingestion.connector.ConnectorHealth;
import mygrant.ingestion.connector.ContentHash;
import mygrant.ingestion.connector.DataConnector;
import mygrant.ingestion.connector.SourceIdentity;
import mygrant.policies.SourceType;

/** Imports an admin-saved USCIS result while live access is unavailable. */
@Service
public class UscisProcessingTimeImporter implements DataConnector<String, JsonNode,
        UscisProcessingTimeParser.Observation, UscisProcessingTimeImporter.Result> {
    private static final SourceIdentity SOURCE =
            new SourceIdentity("USCIS_PROCESSING_TIMES", SourceType.PRIMARY);
    private final ObjectMapper json;
    private final UscisProcessingTimeParser parser;
    private final UscisProcessingTimeStore store;

    public UscisProcessingTimeImporter(ObjectMapper json, UscisProcessingTimeParser parser,
            UscisProcessingTimeStore store) {
        this.json = json;
        this.parser = parser;
        this.store = store;
    }

    @Override
    public SourceIdentity source() {
        return SOURCE;
    }

    @Override
    public ConnectorFetch<JsonNode> fetch(String savedResponse) {
        if (savedResponse == null || savedResponse.isBlank()) {
            throw new IllegalArgumentException("Saved USCIS result is empty");
        }
        JsonNode data;
        try {
            data = json.readTree(savedResponse);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Saved USCIS result is not valid JSON", exception);
        }
        if (data == null || !data.isObject()) {
            throw new IllegalArgumentException("Saved USCIS result must be a JSON object");
        }
        JsonNode urlValue = data.get("sourceUrl");
        JsonNode excerptValue = data.get("sourceExcerpt");
        if (urlValue == null || !urlValue.isTextual()
                || excerptValue == null || !excerptValue.isTextual()
                || excerptValue.asText().isBlank()) {
            throw new IllegalArgumentException("Saved USCIS result requires sourceUrl and sourceExcerpt");
        }
        String sourceUrl = urlValue.asText().trim();
        validateOfficialUrl(sourceUrl);
        return new ConnectorFetch<>(data, sourceUrl, Instant.now(),
                ContentHash.sha256(excerptValue.asText()));
    }

    @Override
    public UscisProcessingTimeParser.Observation transform(ConnectorFetch<JsonNode> fetched,
            String request) {
        return parser.parse(fetched.payload(), fetched.sourceUrl(), fetched.contentHash());
    }

    @Override
    public Result persistDeduplicated(UscisProcessingTimeParser.Observation observation,
            String request) {
        UscisProcessingTimeStore.Saved saved = store.save(observation);
        return new Result(saved.id(), saved.duplicate(), observation.formType(),
                observation.formCategory(), observation.office(), observation.observedOn(),
                80, observation.percentileValue(), observation.durationUnit(),
                observation.sourceUrl());
    }

    @Override
    public ConnectorHealth health() {
        return store.health(source());
    }

    @Override
    public Result ingest(String savedResponse) {
        ConnectorHealth.Stage stage = ConnectorHealth.Stage.FETCH;
        try {
            ConnectorFetch<JsonNode> fetched = fetch(savedResponse);
            stage = ConnectorHealth.Stage.TRANSFORM;
            UscisProcessingTimeParser.Observation observation = transform(fetched, savedResponse);
            stage = ConnectorHealth.Stage.PERSIST;
            return persistDeduplicated(observation, savedResponse);
        } catch (RuntimeException exception) {
            store.fail(stage, exception.getMessage());
            throw exception;
        }
    }

    private void validateOfficialUrl(String sourceUrl) {
        URI uri;
        try {
            uri = URI.create(sourceUrl);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("sourceUrl must be an official USCIS processing-times URL", exception);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || !"egov.uscis.gov".equalsIgnoreCase(uri.getHost())
                || uri.getPort() != -1 || uri.getUserInfo() != null
                || uri.getRawPath() == null
                || !(uri.getRawPath().equals("/processing-times")
                        || uri.getRawPath().startsWith("/processing-times/"))) {
            throw new IllegalArgumentException("sourceUrl must be an official USCIS processing-times URL");
        }
    }

    public record Result(long snapshotId, boolean duplicate, String formType,
            String formCategory, String office, LocalDate observedOn, int percentile,
            java.math.BigDecimal percentileValue, String durationUnit, String sourceUrl) {}
}
