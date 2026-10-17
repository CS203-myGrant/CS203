package mygrant.feedback.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record FeedbackMessageRequest(@NotBlank @Size(max = 2000) String message) {}
