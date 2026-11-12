package io.github.ahmetcopur.tournament.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.ahmetcopur.tournament.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.ResultActions;
import com.nimbusds.jose.jwk.source.ImmutableSecret;

class AuthIntegrationTest extends IntegrationTestBase {

    private JsonNode signUp(String username) throws Exception {
        return body(mvc.perform(post("/users").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\"}")).andExpect(status().isCreated()));
    }

    private ResultActions levelUpWith(long userId, String token) throws Exception {
        return mvc.perform(post("/users/{id}/levels", userId).header("Authorization", "Bearer " + token));
    }

    @Test
    void signUpReturnsATokenThatActsAsThatPlayer() throws Exception {
        JsonNode alice = signUp("alice");
        String token = alice.get("token").asText();
        long id = alice.get("id").asLong();
        assertThat(token).isNotBlank();
        assertThat(Instant.parse(alice.get("tokenExpiresAt").asText())).isAfter(Instant.now().plusSeconds(29L * 24 * 3600));

        levelUpWith(id, token).andExpect(status().isOk()).andExpect(jsonPath("$.level").value(2));
        mvc.perform(get("/users/{id}", id).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.coins").value(5025));
    }

    @Test
    void missingTokenIs401() throws Exception {
        long player = newPlayer("TR");
        mvc.perform(post("/users/{id}/levels", player))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(get("/tournaments/current")).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT level FROM users WHERE id = ?", Integer.class, player)).isEqualTo(20);
    }

    @Test
    void anotherPlayersTokenIs403Everywhere() throws Exception {
        openTournament();
        long victim = newPlayer("TR");
        long attacker = newPlayer("US");
        String attackerToken = tokens.issue(attacker).token();

        levelUpWith(victim, attackerToken).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(post("/users/{id}/rewards/claim", victim).header("Authorization", "Bearer " + attackerToken))
                .andExpect(status().isForbidden());
        mvc.perform(get("/users/{id}", victim).header("Authorization", "Bearer " + attackerToken))
                .andExpect(status().isForbidden());
        mvc.perform(post("/tournaments/current/entries").header("Authorization", "Bearer " + attackerToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"userId\":" + victim + "}"))
                .andExpect(status().isForbidden());

        // Nothing happened to the victim, and a missing id gives 403 too (no probing for ids).
        assertThat(coins(victim)).isEqualTo(5000);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM group_members", Integer.class)).isZero();
        levelUpWith(999_999, attackerToken).andExpect(status().isForbidden());
    }

    @Test
    void anySignedInPlayerCanReadBoardsAndSearch() throws Exception {
        openTournament();
        FullGroup g = fullGroup();
        String outsider = tokens.issue(newPlayer("DE")).token();

        mvc.perform(get("/groups/{id}/leaderboard", g.groupId()).header("Authorization", "Bearer " + outsider))
                .andExpect(status().isOk());
        mvc.perform(get("/tournaments/current").header("Authorization", "Bearer " + outsider))
                .andExpect(status().isOk());
    }

    @Test
    void tamperedExpiredAndForeignTokensAre401() throws Exception {
        long player = newPlayer("TR");
        String good = tokens.issue(player).token();

        // Change the subject in the payload without re-signing: the signature no longer matches.
        String[] parts = good.split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                .replace("\"sub\":\"" + player + "\"", "\"sub\":\"" + (player + 1) + "\"");
        String tampered = parts[0] + "." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "." + parts[2];
        levelUpWith(player + 1, tampered).andExpect(status().isUnauthorized());

        String expired = tokens.issue(player, Instant.now().minusSeconds(3600)).token();
        levelUpWith(player, expired).andExpect(status().isUnauthorized());

        // Signed with a different key, as if minted by someone else.
        NimbusJwtEncoder otherKey = new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(
                "some-other-service-secret-0123456789abcdef".getBytes(StandardCharsets.UTF_8), "HmacSHA256")));
        String foreign = otherKey.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
                JwtClaimsSet.builder().issuer("tournament-service").subject(Long.toString(player))
                        .expiresAt(Instant.now().plusSeconds(3600)).build())).getTokenValue();
        levelUpWith(player, foreign).andExpect(status().isUnauthorized());

        levelUpWith(player, "not-a-token").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        levelUpWith(player, good).andExpect(status().isOk());
    }

    @Test
    void operationalEndpointsAreNotOnThePublicPort() throws Exception {
        String token = tokens.issue(newPlayer("TR")).token();
        // Even a valid player token can't reach them; without a token you're asked to sign in first.
        mvc.perform(get("/actuator/prometheus").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        mvc.perform(get("/actuator/health")).andExpect(status().isUnauthorized());
    }
}
