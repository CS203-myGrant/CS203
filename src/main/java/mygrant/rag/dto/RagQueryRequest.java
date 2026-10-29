package mygrant.rag.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RagQueryRequest(
        @NotBlank @Size(max = 2000) String question,
        @io.swagger.v3.oas.annotations.media.Schema(description="Legacy field, ignored. Retrieval uses the signed-in caller's saved profile.",deprecated=true)
        @Size(max = 50) String visaType
) {
}
