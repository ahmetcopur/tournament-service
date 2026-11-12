package io.github.ahmetcopur.tournament.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateUserRequest(
        @NotBlank
        @Size(min = 3, max = 32)
        @Pattern(regexp = "[A-Za-z0-9_.-]+", message = "may only contain letters, digits, '_', '.' and '-'")
        String username) {
}
