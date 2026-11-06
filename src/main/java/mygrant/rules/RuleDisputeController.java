package mygrant.rules;

import java.security.Principal;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/** Lets an admin clear a rule's disputed flag once a court lifts the block (CS203-86). */
@RestController
@RequestMapping("/api/v1/policies")
@Tag(name = "Policies")
public class RuleDisputeController {

    private final RuleDisputeService disputes;

    public RuleDisputeController(RuleDisputeService disputes) {
        this.disputes = disputes;
    }

    @PostMapping("/{id}/dispute/clear")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Clear a rule's dispute after a court lifts the block",
            description = "`{id}` is any document in the rule, as for the timeline. Clears `disputed` and records an "
                    + "append-only step with who cleared it, when, the reason, and the court order or news item that "
                    + "lifted the block. The stage and effective date stay, so the timeline shows the rule EFFECTIVE "
                    + "again once its date has come. No alert is sent and no other rule changes. A report of "
                    + "litigation published after the source's date disputes the rule again; one published on or "
                    + "before it is flagged for review instead.")
    @ApiResponse(responseCode = "200", description = "Cleared: the step written and the rule after it")
    @ApiResponse(responseCode = "400", description = "A field is missing, too long or invalid, or the source is dated "
            + "after your test-clock date or before the report that disputed the rule")
    @ApiResponse(responseCode = "404", description = "No policy has this id, or the policy is in no rule")
    @ApiResponse(responseCode = "409", description = "The rule is not disputed")
    public ClearDisputeResponse clear(
            @Parameter(description = "Id of any policy document in the rule", example = "41") @PathVariable Long id,
            @Valid @RequestBody ClearDisputeRequest request, Principal principal) {
        return disputes.clear(id, request, principal.getName());
    }
}
