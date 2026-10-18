package mygrant.account.dto;

import java.time.Instant;

public record DeletionConfirmationResponse(String status, Instant deletedAt) {
}
