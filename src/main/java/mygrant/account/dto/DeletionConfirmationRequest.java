package mygrant.account.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DeletionConfirmationRequest(
        @NotBlank @Size(min = 43, max = 43) String challenge) {
}
