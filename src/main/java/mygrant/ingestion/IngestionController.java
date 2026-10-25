package mygrant.ingestion;

import java.security.Principal;

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
import mygrant.ingestion.connector.ConnectorStatusService;
import mygrant.ingestion.federalregister.FederalRegisterIngestionResponse;
import mygrant.ingestion.federalregister.FederalRegisterIngestionService;

/** HTTP entry points for manual policy and Federal Register imports. */
@RestController
@RequestMapping("/api/v1/ingestion")
@Tag(name = "Ingestion")
public class IngestionController {

    private final PolicyIngestionService ingestionService;
    private final FederalRegisterIngestionService federalRegisterIngestionService;
    private final ConnectorStatusService connectorStatusService;

    public IngestionController(PolicyIngestionService ingestionService,
            FederalRegisterIngestionService federalRegisterIngestionService,
            ConnectorStatusService connectorStatusService) {
        this.ingestionService = ingestionService;
        this.federalRegisterIngestionService = federalRegisterIngestionService;
        this.connectorStatusService = connectorStatusService;
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

    /** Admin health view for the Federal Register connector. */
    @GetMapping("/federal-register/health")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Federal Register import health",
            description = "Kept in memory, so it resets when the app restarts.")
    public ConnectorHealth federalRegisterHealth() {
        return federalRegisterIngestionService.health();
    }

    /** Admin health view for all configured connectors. */
    @GetMapping("/status")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Health of every source connector",
            description = "Ordered by source id; `sort` is ignored.")
    public Page<ConnectorHealth> status(@ParameterObject @PageableDefault(size = 20) Pageable pageable) {
        return Pages.of(connectorStatusService.status(), pageable);
    }
}
