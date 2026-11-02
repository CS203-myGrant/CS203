package mygrant.manager.dto;

import mygrant.user.User;

public record ManagerMemberResponse(Long id, String fullName, String email, String visaType) {
    public static ManagerMemberResponse from(User user) {
        return new ManagerMemberResponse(user.getId(), user.getFullName(), user.getEmail(), user.getVisaType());
    }
}
