package mygrant.rag;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import mygrant.rag.dto.RagQueryRequest;
import mygrant.rag.dto.RagQueryResponse;

@RestController
@RequestMapping("/rag")
public class RagController {

    private final RagService ragService;

    public RagController(RagService ragService) {
        this.ragService = ragService;
    }

    @PostMapping("/query")
    public RagQueryResponse query(@Valid @RequestBody RagQueryRequest request) {
        return ragService.query(request);
    }
}
