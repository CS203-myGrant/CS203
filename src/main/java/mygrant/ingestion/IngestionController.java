package mygrant.ingestion;

import java.security.Principal;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;

import org.springdoc.core.annotations.ParameterObject;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import mygrant.common.Pages;
import mygrant.ingestion.dto.IngestPolicyRequest;
import mygrant.ingestion.dto.IngestionResponse;
import mygrant.ingestion.connector.ConnectorHealth;
import mygrant.ingestion.federalregister.FederalRegisterIngestionResponse;
import mygrant.ingestion.federalregister.FederalRegisterIngestionService;

/** HTTP entry points for manual policy and Federal Register imports. */
@RestController
@RequestMapping("/api/v1/ingestion")
@Tag(name = "Ingestion")
public class IngestionController {

    private final PolicyIngestionService ingestionService;
    private final FederalRegisterIngestionService federalRegisterIngestionService;
    private final SourceSyncService sourceSyncService;
    private final EmbeddingRebuildService embeddingRebuildService;

    public IngestionController(PolicyIngestionService ingestionService,
            FederalRegisterIngestionService federalRegisterIngestionService,
            SourceSyncService sourceSyncService,
            EmbeddingRebuildService embeddingRebuildService) {
        this.ingestionService = ingestionService;
        this.federalRegisterIngestionService = federalRegisterIngestionService;
        this.sourceSyncService = sourceSyncService;
        this.embeddingRebuildService = embeddingRebuildService;
    }

    /** Indexes a policy supplied in the request body. */
    @PostMapping("/documents")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Index a policy from the request body",
            description = "Saves the policy, splits and embeds its text for RAG, and alerts affected "
                    + "applicants. An unchanged policy with the same external id is reused.")
    @ApiResponse(responseCode = "201", description = "Indexed, or reused when unchanged")
    @ApiResponse(responseCode = "400", description = "A field is missing, too long or invalid")
    @ApiResponse(responseCode = "409", description = "A different policy already has this external id")
    public IngestionResponse ingest(@Valid @RequestBody IngestPolicyRequest request) {
        return ingestionService.ingest(request);
    }

    /** Backwards-compatible health view for the Federal Register connector. */
    @GetMapping("/federal-register/health")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Get Federal Register connector health",
            description = "Reports the latest Federal Register connector attempt and error state.")
    public ConnectorHealth federalRegisterHealth() {
        return federalRegisterIngestionService.health();
    }

    /** Downloads, classifies, and indexes a published Federal Register document. */
    @PostMapping("/federal-register/{documentNumber}")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Import a Federal Register document",
            description = "Downloads the document, detects the F-1, J-1 and H-1B categories it affects, "
                    + "indexes it and assesses its impact on the caller's profile.")
    @ApiResponse(responseCode = "201", description = "Imported, with its classification and assessment")
    @ApiResponse(responseCode = "400", description = "The document was not found, or affects no supported "
            + "visa category")
    public FederalRegisterIngestionResponse ingestFederalRegister(
            @PathVariable String documentNumber, Principal principal) {
        return federalRegisterIngestionService.ingest(documentNumber, principal.getName());
    }

    /**
     * Re-embeds every stored chunk with the configured embedding model. Run once after
     * changing the model; until then, questions match against vectors from the old one.
     */
    @PostMapping("/embeddings/rebuild")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Re-embed every stored policy chunk",
            description = "Recomputes each chunk's vector with the configured embedding model. Run once "
                    + "after changing the model; policies, alerts and citations are unchanged.")
    @ApiResponse(responseCode = "200", description = "How many chunks were re-embedded")
    public Map<String, Integer> rebuildEmbeddings() {
        return Map.of("chunksReembedded", embeddingRebuildService.rebuild());
    }

    /** Admin health view for all configured policy sources. */
    @GetMapping("/status")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Health of every configured policy source",
            description = "Reports the latest run, fetched documents, vectorised chunks and failures.")
    public Page<mygrant.ingestion.dto.IngestionStatusResponse> status(
            @ParameterObject @PageableDefault(size = 20) Pageable pageable) {
        return Pages.of(sourceSyncService.status(), pageable);
    }

    @PostMapping("/sync")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Synchronise policy sources",
            description = "Synchronises one enabled source when sourceId is supplied, or all enabled sources otherwise.")
    public Object sync(@RequestBody(required = false) mygrant.ingestion.dto.IngestionSyncRequest request,
            Principal principal) {
        if (request == null || request.sourceId() == null) {
            return sourceSyncService.syncAll(principal);
        }
        return sourceSyncService.syncOne(request.sourceId(), principal);
    }
}
