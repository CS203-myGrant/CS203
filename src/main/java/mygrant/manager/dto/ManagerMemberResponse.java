package mygrant.manager.dto;

import mygrant.user.User;
import mygrant.user.UserProfile;

public record ManagerMemberResponse(
        Long id,
        String fullName,
        String email,
        String visaType
) {
    public static ManagerMemberResponse from(User user, UserProfile profile) {
        return new ManagerMemberResponse(
                user.getId(),
                user.getFullName(),
                user.getEmail(),
                profile == null ? null : profile.getVisaType()
        );
    }
}