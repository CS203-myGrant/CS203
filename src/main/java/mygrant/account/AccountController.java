package mygrant.account;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import mygrant.account.dto.DeletionRequestResponse;
import mygrant.account.dto.DeletionConfirmationRequest;
import mygrant.account.dto.DeletionConfirmationResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/account")
@Tag(name = "Account")
public class AccountController {

    private final AccountDeletionService accountDeletionService;

    public AccountController(AccountDeletionService accountDeletionService) {
        this.accountDeletionService = accountDeletionService;
    }

    /** Requests deletion and returns a challenge; no account data is removed here. */
    @PostMapping("/deletion-request")
    @Operation(summary = "Start deleting the signed-in user's account",
            description = "Returns a one-time challenge for deletion-confirm. Nothing is deleted yet. Works "
                    + "before onboarding is finished.")
    public DeletionRequestResponse requestDeletion(Authentication authentication) {
        return accountDeletionService.requestDeletion(authentication.getName());
    }

    /** Confirms deletion and returns only after the purge has completed. */
    @PostMapping("/deletion-confirm")
    @Operation(summary = "Delete the signed-in user's account",
            description = "Answers once the account and its data are gone. Works before onboarding is finished.")
    @ApiResponse(responseCode = "200", description = "Deleted")
    @ApiResponse(responseCode = "400", description = "The challenge is missing, invalid or expired")
    public DeletionConfirmationResponse confirmDeletion(Authentication authentication,
            @Valid @RequestBody DeletionConfirmationRequest request) {
        return accountDeletionService.confirmDeletion(authentication.getName(), request.challenge());
    }
}
