package com.example.tournament.search;

import com.example.tournament.user.UserResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PlayerSearchController {

    private final PlayerSearchService search;

    public PlayerSearchController(PlayerSearchService search) {
        this.search = search;
    }

    /** GET /users/search?q=ahm&amp;country=TR&amp;limit=20, best match first. */
    @GetMapping("/users/search")
    public List<UserResponse> search(
            @RequestParam @NotBlank @Size(max = 32) String q,
            @RequestParam(required = false) @Pattern(regexp = "[A-Z]{2}") String country,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit) {
        return search.search(q, country, limit).stream().map(UserResponse::from).toList();
    }
}
