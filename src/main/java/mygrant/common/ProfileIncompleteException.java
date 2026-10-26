package mygrant.common;

import org.springframework.security.access.AccessDeniedException;

/** A signed-in user whose onboarding is unfinished called something other than the onboarding endpoints. */
public class ProfileIncompleteException extends AccessDeniedException {

    public ProfileIncompleteException() {
        super("Complete your profile before using this feature");
    }
}
