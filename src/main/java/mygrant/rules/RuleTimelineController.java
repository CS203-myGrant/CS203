package mygrant.rules;

import java.security.Principal;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

/** The stage history of the policy rule a stored policy belongs to (CS203-86). */
@RestController
@RequestMapping("/api/v1/policies")
@Tag(name = "Policies")
public class RuleTimelineController {

    private final RuleTimelineService timelines;

    public RuleTimelineController(RuleTimelineService timelines) {
        this.timelines = timelines;
    }

    @GetMapping("/{id}/timeline")
    @Operation(summary = "Get the stage history of a policy's rule",
            description = "A rule groups the Federal Register documents of one rulemaking (proposed rule, final "
                    + "rule, later notices) and the news linked to it. `{id}` is any of those documents. Steps are "
                    + "oldest first, each with its source; previous values are the step before's. `primaryStage` "
                    + "comes from primary sources only. `currentStage` is EFFECTIVE once a final rule's effective "
                    + "date is on or before the caller's test-clock date, shown as a derived TOOK_EFFECT step. A "
                    + "second final rule in the same rulemaking is an ADDITIONAL_FINAL_RULE step with its own date "
                    + "in `ownEffectiveDate`; the rule's `effectiveDate` stays. `disputed` is set by a linked "
                    + "secondary source (news, or a court order an admin recorded) reporting litigation or a stay, "
                    + "named in `disputedBy`. A disputed rule is never EFFECTIVE and has no TOOK_EFFECT step; "
                    + "`effectiveDatePassedWhileDisputed` says its date has passed. Once a court lifts the block, an "
                    + "admin clears the dispute (`POST /api/v1/policies/{id}/dispute/clear`): that is a DISPUTED step "
                    + "back to false, and a rule whose date has passed is EFFECTIVE again, with TOOK_EFFECT after "
                    + "that step.")
    @ApiResponse(responseCode = "200", description = "The rule's timeline")
    @ApiResponse(responseCode = "400", description = "The id is not a number")
    @ApiResponse(responseCode = "404", description = "No policy has this id, or the policy is in no rule, such as "
            + "news that matched none")
    public RuleTimelineResponse timeline(
            @Parameter(description = "Id of any policy document in the rule", example = "41") @PathVariable Long id,
            Principal principal) {
        return timelines.timeline(id, principal.getName());
    }
}
