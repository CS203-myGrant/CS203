package mygrant.account;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import mygrant.account.dto.DeletionRequestResponse;
import mygrant.account.dto.DeletionConfirmationRequest;
import mygrant.account.dto.DeletionConfirmationResponse;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/account")
public class AccountController {

    private final AccountDeletionService accountDeletionService;

    public AccountController(AccountDeletionService accountDeletionService) {
        this.accountDeletionService = accountDeletionService;
    }

    /** Requests deletion and returns a challenge; no account data is removed here. */
    @PostMapping("/deletion-request")
    public DeletionRequestResponse requestDeletion(Authentication authentication) {
        return accountDeletionService.requestDeletion(authentication.getName());
    }

    /** Confirms deletion and returns only after the purge has completed. */
    @PostMapping("/deletion-confirm")
    public DeletionConfirmationResponse confirmDeletion(Authentication authentication,
            @Valid @RequestBody DeletionConfirmationRequest request) {
        return accountDeletionService.confirmDeletion(authentication.getName(), request.challenge());
    }
}
