package com.example.tournament.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.tournament.support.IntegrationTestBase;
import com.example.tournament.user.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
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
    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void freshIndex() {
        elasticsearch.indexOps(PlayerDocument.class).delete();
        search.reconcile(); // recreates the index; Postgres is empty, so nothing to copy
    }

    private JsonNode signUp(String username) throws Exception {
        return body(mvc.perform(post("/users").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\"}")).andExpect(status().isCreated()));
    }

    /** Indexing runs in the background now: wait for queued sign-ups, then make them searchable. */
    private void refresh() throws Exception {
        search.awaitIndexed(Duration.ofSeconds(10));
        elasticsearch.indexOps(PlayerDocument.class).refresh();
    }

    private ResultActions find(String query) throws Exception {
        return mvc.perform(get("/users/search?" + query).with(anyPlayer()));
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

    /*
     * Review finding: reconciling by document counts. Equal counts don't mean the same players,
     * for example after Postgres was reset while Elasticsearch kept its data.
     */

    @Test
    void reconcileFixesAMissingPlayerHiddenByAnOrphanedDocument() throws Exception {
        signUp("anna");
        long missing = newPlayer("TR"); // in Postgres, never indexed
        String missingName = jdbc.queryForObject("SELECT username FROM users WHERE id = ?", String.class, missing);
        elasticsearch.save(PlayerDocument.of(9_999_999L, "ghost", "TR")); // no such user
        refresh();
        // Two users, two documents: a count comparison sees nothing to do.

        search.reconcile();
        refresh();

        find("q=" + missingName).andExpect(jsonPath("$[0].id").value(missing));
        assertThat(elasticsearch.get("9999999", PlayerDocument.class)).as("orphan removed").isNull();
    }

    @Test
    void searchNeverReturnsAPlayerUnderAnotherName() throws Exception {
        long id = signUp("realname").get("id").asLong();
        // A leftover document for the same id with someone else's name.
        elasticsearch.save(PlayerDocument.of(id, "oldname", "TR"));
        refresh();

        assertThat(body(find("q=oldname"))).as("must not return 'realname' for 'oldname'").isEmpty();

        search.reconcile();
        refresh();
        find("q=realname").andExpect(jsonPath("$[0].id").value(id));
    }

    @Test
    void postgresAndJavaComputeTheSameFingerprint() {
        long id = newPlayer("TR");
        String name = jdbc.queryForObject("SELECT username FROM users WHERE id = ?", String.class, id);

        List<Object[]> checksums = userRepository.searchChecksums(10_000);

        assertThat(checksums).hasSize(1);
        assertThat(((Number) checksums.get(0)[1]).longValue()).isEqualTo(1);
        assertThat(((Number) checksums.get(0)[2]).longValue()).isEqualTo(PlayerDocument.fingerprint(id, name, "TR"));
    }

    @Test
    void reconcileOnlyExaminesIdRangesThatDiffer() throws Exception {
        // Three players in three different ranges of 10,000 ids.
        for (long id : List.of(5L, 15_005L, 30_005L)) {
            jdbc.update("INSERT INTO users (id, username, country) VALUES (?, ?, 'TR')", id, "ranged" + id);
        }

        assertThat(search.reconcileReport()).extracting("rangesTotal", "rangesExamined", "written")
                .containsExactly(3, 3, 3L);
        assertThat(search.reconcileReport()).as("in sync: one aggregation, one GROUP BY, nothing examined")
                .extracting("rangesExamined", "written", "deleted").containsExactly(0, 0L, 0L);

        elasticsearch.save(PlayerDocument.of(15_005L, "someone-else", "TR"));
        refresh();
        assertThat(search.reconcileReport()).extracting("rangesExamined", "written").containsExactly(1, 1L);
    }

    /**
     * Review finding: indexing ran in the sign-up request (an after-commit listener runs on the
     * request thread). With Elasticsearch stalled, the player was already committed but the
     * response hung until the search timeout; a client giving up then never got its token.
     */
    @Test
    void signUpDoesNotWaitForAStalledElasticsearch() throws Exception {
        JsonNode player;
        long millis;
        pauseElasticsearch();
        try {
            long started = System.nanoTime();
            player = signUp("stalled_search");
            millis = (System.nanoTime() - started) / 1_000_000;
        } finally {
            unpauseElasticsearch();
        }
        assertThat(player.get("token").asText()).isNotBlank();
        assertThat(millis).as("sign-up time with Elasticsearch frozen, in ms").isLessThan(500);

        // Once Elasticsearch is back the player becomes searchable (the indexer or the reconcile).
        search.reconcile();
        refresh();
        find("q=stalled_search").andExpect(jsonPath("$[0].id").value(player.get("id").asLong()));
    }

    @Test
    void rejectsBadParameters() throws Exception {
        find("q=").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        find("q=a&limit=0").andExpect(status().isBadRequest());
        find("q=a&country=tr").andExpect(status().isBadRequest());
        mvc.perform(get("/users/search").with(anyPlayer())).andExpect(status().isBadRequest());
    }
}
