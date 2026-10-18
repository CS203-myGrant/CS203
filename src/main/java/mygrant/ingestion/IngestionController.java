package mygrant.ingestion;

import java.security.Principal;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;

import jakarta.validation.Valid;
import mygrant.ingestion.dto.IngestPolicyRequest;
import mygrant.ingestion.dto.IngestionResponse;
import mygrant.ingestion.federalregister.FederalRegisterIngestionResponse;
import mygrant.ingestion.federalregister.FederalRegisterIngestionService;

/** HTTP entry points for manual policy and Federal Register imports. */
@RestController
@RequestMapping("/api/v1/ingestion")
public class IngestionController {

    private final PolicyIngestionService ingestionService;
    private final FederalRegisterIngestionService federalRegisterIngestionService;

    public IngestionController(PolicyIngestionService ingestionService,
            FederalRegisterIngestionService federalRegisterIngestionService) {
        this.ingestionService = ingestionService;
        this.federalRegisterIngestionService = federalRegisterIngestionService;
    }

    /** Indexes a policy supplied in the request body. */
    @PostMapping("/documents")
    @ResponseStatus(HttpStatus.CREATED)
    public IngestionResponse ingest(@Valid @RequestBody IngestPolicyRequest request) {
        return ingestionService.ingest(request);
    }

    /** Downloads, classifies, and indexes a published Federal Register document. */
    @PostMapping("/federal-register/{documentNumber}")
    @ResponseStatus(HttpStatus.CREATED)
    public FederalRegisterIngestionResponse ingestFederalRegister(
            @PathVariable String documentNumber, Principal principal) {
        return federalRegisterIngestionService.ingest(documentNumber, principal.getName());
    }
}
