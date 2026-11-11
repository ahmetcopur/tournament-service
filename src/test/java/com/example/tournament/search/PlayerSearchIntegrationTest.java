package com.example.tournament.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.tournament.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class PlayerSearchIntegrationTest extends IntegrationTestBase {

    @Autowired
    private ElasticsearchOperations elasticsearch;
    @Autowired
    private PlayerSearchService search;

    @BeforeEach
    void freshIndex() {
        elasticsearch.indexOps(PlayerDocument.class).delete();
        search.reconcile(); // recreates the index; Postgres is empty, so nothing to copy
    }

    private JsonNode signUp(String username) throws Exception {
        return body(mvc.perform(post("/users").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\"}")).andExpect(status().isCreated()));
    }

    private void refresh() {
        elasticsearch.indexOps(PlayerDocument.class).refresh();
    }

    private ResultActions find(String query) throws Exception {
        return mvc.perform(get("/users/search?" + query));
    }

    private static List<String> usernames(JsonNode results) {
        List<String> names = new ArrayList<>();
        results.forEach(r -> names.add(r.get("username").asText()));
        return names;
    }

    @Test
    void findsByPrefixWithTheExactNameFirst() throws Exception {
        for (String name : List.of("ahmet", "ahmet_99", "ahmed", "mehmet", "alice")) {
            signUp(name);
        }
        refresh();

        assertThat(usernames(body(find("q=ahmet")))).startsWith("ahmet", "ahmet_99");
        assertThat(usernames(body(find("q=AHM")))).containsExactlyInAnyOrder("ahmet", "ahmet_99", "ahmed");
        assertThat(usernames(body(find("q=ali")))).containsExactly("alice");
    }

    @Test
    void toleratesTypos() throws Exception {
        signUp("alexander");
        signUp("bob");
        refresh();

        assertThat(usernames(body(find("q=alexnader")))).containsExactly("alexander");
    }

    @Test
    void filtersByCountryAndLimits() throws Exception {
        for (int i = 0; i < 12; i++) {
            signUp("player_" + i);
        }
        refresh();

        String country = signUp("player_x").get("country").asText();
        refresh();
        JsonNode inCountry = body(find("q=player&limit=50&country=" + country));
        assertThat(inCountry).isNotEmpty();
        inCountry.forEach(r -> assertThat(r.get("country").asText()).isEqualTo(country));

        find("q=player&limit=5").andExpect(jsonPath("$.length()").value(5));
    }

    @Test
    void returnsCurrentLevelAndCoinsFromPostgres() throws Exception {
        long id = signUp("climber").get("id").asLong();
        refresh();
        levelUp(id, 3);

        find("q=climber").andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id))
                .andExpect(jsonPath("$[0].level").value(4))
                .andExpect(jsonPath("$[0].coins").value(5075));
    }

    @Test
    void reconcileIndexesPlayersThatWereNeverIndexed() throws Exception {
        long missing = newPlayer("TR"); // inserted straight into Postgres, so never indexed
        String name = jdbc.queryForObject("SELECT username FROM users WHERE id = ?", String.class, missing);
        assertThat(body(find("q=" + name))).isEmpty();

        assertThat(search.reconcile()).isEqualTo(1);
        assertThat(search.reconcile()).isZero(); // nothing left to do

        find("q=" + name).andExpect(jsonPath("$[0].id").value(missing));
    }

    @Test
    void rejectsBadParameters() throws Exception {
        find("q=").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        find("q=a&limit=0").andExpect(status().isBadRequest());
        find("q=a&country=tr").andExpect(status().isBadRequest());
        mvc.perform(get("/users/search")).andExpect(status().isBadRequest());
    }
}
