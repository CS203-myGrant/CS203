package mygrant.account.dto;

import java.time.Instant;

/** Confirmation challenge returned when a user requests account deletion. */
public record DeletionRequestResponse(String challenge, Instant expiresAt) {
}
