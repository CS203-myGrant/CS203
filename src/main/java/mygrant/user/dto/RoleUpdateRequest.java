package mygrant.user.dto;

import jakarta.validation.constraints.NotNull;
import mygrant.user.UserRole;

public record RoleUpdateRequest(@NotNull UserRole role) {
}
