package mygrant.actions;

import java.time.Instant;
import java.time.LocalDate;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "One step on the signed-in user's checklist, generated from a published impact assessment")
public record ActionItemView(
        long id,
        @Schema(description = "The impact assessment that produced this item")
        long assessmentId,
        long policyId,
        String policyTitle,
        @Schema(description = "The action, shortened to 117 characters and an ellipsis when it is longer than 120")
        String title,
        @Schema(description = "The full action when the title was shortened; null otherwise")
        String description,
        ActionPriority priority,
        ActionStatus status,
        @Schema(description = "The policy's effective date when the item was generated; null when it has none")
        LocalDate deadline,
        @Schema(description = "Still to do and due within seven days of today, or already overdue")
        boolean urgent,
        @Schema(description = "Still to do and the deadline has passed")
        boolean overdue,
        Instant completedAt,
        Instant dismissedAt,
        Instant createdAt,
        Instant updatedAt,
        @Schema(description = "The full action text: the description when there is one, otherwise the title. "
                + "Kept for impact-feed clients written before action items had titles.")
        String text) {
}
