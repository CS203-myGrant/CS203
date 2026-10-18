package mygrant.rag.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RagQueryRequest(
        @NotBlank @Size(max = 2000) String question,
        @NotBlank @Size(max = 50) String visaType
) {
}
