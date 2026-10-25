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
    public IngestionResponse ingest(@Valid @RequestBody IngestPolicyRequest request) {
        return ingestionService.ingest(request);
    }

    /** Downloads, classifies, and indexes a published Federal Register document. */
    @PostMapping("/federal-register/{documentNumber}")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public FederalRegisterIngestionResponse ingestFederalRegister(
            @PathVariable String documentNumber, Principal principal) {
        return federalRegisterIngestionService.ingest(documentNumber, principal.getName());
    }

    /** Admin health view for the Federal Register connector. */
    @GetMapping("/federal-register/health")
    @PreAuthorize("hasRole('ADMIN')")
    public ConnectorHealth federalRegisterHealth() {
        return federalRegisterIngestionService.health();
    }

    /** Admin health view for all configured connectors. */
    @GetMapping("/status")
    @PreAuthorize("hasRole('ADMIN')")
    public Page<ConnectorHealth> status(@PageableDefault(size = 20) Pageable pageable) {
        return Pages.of(connectorStatusService.status(), pageable);
    }
}
