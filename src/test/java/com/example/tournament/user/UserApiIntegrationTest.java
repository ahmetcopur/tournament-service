package com.example.tournament.user;

import static org.hamcrest.Matchers.oneOf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.tournament.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class UserApiIntegrationTest extends IntegrationTestBase {

    private ResultActions createUser(String username) throws Exception {
        return mvc.perform(post("/users").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\"}"));
    }

    @Test
    void newUserStartsAtLevelOneWithStartingCoins() throws Exception {
        createUser("alice")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.country").value(oneOf("TR", "US", "GB", "DE", "FR")))
                .andExpect(jsonPath("$.level").value(1))
                .andExpect(jsonPath("$.coins").value(5000));
    }

    @Test
    void duplicateUsernameIsRejected() throws Exception {
        createUser("bob").andExpect(status().isCreated());
        createUser("bob")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USERNAME_TAKEN"));
    }

    @Test
    void invalidUsernameIsRejected() throws Exception {
        createUser("x")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void getReturnsTheUser() throws Exception {
        long id = body(createUser("carol")).get("id").asLong();
        mvc.perform(get("/users/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("carol"));
    }

    @Test
    void unknownUserIs404() throws Exception {
        mvc.perform(get("/users/{id}", 999_999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"))
                .andExpect(jsonPath("$.message").isString());
        levelUp(999_999)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }

    @Test
    void levelUpAddsOneLevelAndTwentyFiveCoins() throws Exception {
        long id = body(createUser("dave")).get("id").asLong();
        levelUp(id).andExpect(status().isOk())
                .andExpect(jsonPath("$.level").value(2))
                .andExpect(jsonPath("$.coins").value(5025));
        levelUp(id).andExpect(jsonPath("$.level").value(3))
                .andExpect(jsonPath("$.coins").value(5050));
    }

    @Test
    void unknownRouteUsesTheErrorFormat() throws Exception {
        mvc.perform(get("/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
