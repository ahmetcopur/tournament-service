package com.example.tournament.search;

import co.elastic.clients.elasticsearch._types.SortOrder;
import com.example.tournament.common.ApiException;
import com.example.tournament.common.ErrorCode;
import com.example.tournament.user.User;
import com.example.tournament.user.UserCreated;
import com.example.tournament.user.UserRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.query.Query;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Player search on Elasticsearch. Postgres stays the source of truth: the index only holds
 * id, username and country, a failed index write never fails a sign up, and a reconcile job
 * re-indexes from Postgres whenever the index is missing players.
 */
@Service
public class PlayerSearchService {

    private static final Logger log = LoggerFactory.getLogger(PlayerSearchService.class);
    private static final int REINDEX_BATCH = 500;

    private final ElasticsearchOperations elasticsearch;
    private final UserRepository users;
    private final Counter indexFailures;
    private final AtomicBoolean indexReady = new AtomicBoolean(false);

    public PlayerSearchService(ElasticsearchOperations elasticsearch, UserRepository users, MeterRegistry meters) {
        this.elasticsearch = elasticsearch;
        this.users = users;
        this.indexFailures = Counter.builder("search.index.failures")
                .description("Player documents that could not be written to Elasticsearch")
                .register(meters);
    }

    /**
     * Best match first: the exact username, then usernames starting with the query, then
     * usernames within a typo or two (fuzzy). Optionally only one country.
     */
    public List<User> search(String q, String country, int limit) {
        String term = q.trim().toLowerCase(Locale.ROOT);
        NativeQuery query = NativeQuery.builder()
                .withQuery(root -> root.bool(b -> {
                    b.should(s -> s.term(t -> t.field("username").value(term).boost(10f)));
                    b.should(s -> s.match(m -> m.field("username.prefix").query(term).boost(3f)));
                    b.should(s -> s.fuzzy(f -> f.field("username").value(term).fuzziness("AUTO")));
                    b.minimumShouldMatch("1");
                    if (country != null) {
                        b.filter(f -> f.term(t -> t.field("country").value(country)));
                    }
                    return b;
                }))
                .withSort(s -> s.score(sc -> sc.order(SortOrder.Desc)))
                .withSort(s -> s.field(f -> f.field("username").order(SortOrder.Asc)))
                .withMaxResults(limit)
                .build();

        List<Long> ids;
        try {
            ids = elasticsearch.search(query, PlayerDocument.class).stream()
                    .map(SearchHit::getContent)
                    .map(PlayerDocument::id)
                    .toList();
        } catch (RuntimeException ex) {
            log.warn("Player search failed: {}", ex.getMessage());
            throw new ApiException(ErrorCode.SEARCH_UNAVAILABLE);
        }

        // Fresh level and coins from Postgres, kept in search order.
        Map<Long, User> byId = users.findAllById(ids).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        return ids.stream().map(byId::get).filter(Objects::nonNull).toList();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUserCreated(UserCreated event) {
        try {
            ensureIndex();
            elasticsearch.save(new PlayerDocument(event.id(), event.username(), event.country()));
        } catch (RuntimeException ex) {
            indexFailures.increment();
            log.warn("Could not index player {}; the reconcile job will retry: {}", event.id(), ex.getMessage());
        }
    }

    /** Creates the index with its analyzers if it doesn't exist (never with dynamic mapping). */
    public void ensureIndex() {
        if (indexReady.get()) {
            return;
        }
        IndexOperations index = elasticsearch.indexOps(PlayerDocument.class);
        if (!index.exists()) {
            index.createWithMapping();
            log.info("Created Elasticsearch index 'players'");
        }
        indexReady.set(true);
    }

    /**
     * Re-indexes every player from Postgres when the index holds fewer documents than there are
     * users (a write failed, or Elasticsearch lost its data). Writes are idempotent: the document
     * id is the user id. Returns the number of players written.
     */
    public long reconcile() {
        indexReady.set(false); // the index may have been deleted since we last looked
        ensureIndex();
        IndexOperations index = elasticsearch.indexOps(PlayerDocument.class);
        index.refresh();
        long indexed = elasticsearch.count(Query.findAll(), PlayerDocument.class);
        long total = users.count();
        if (indexed >= total) {
            return 0;
        }
        long written = 0;
        long lastId = 0;
        List<User> batch;
        do {
            batch = users.findTop500ByIdGreaterThanOrderByIdAsc(lastId);
            if (!batch.isEmpty()) {
                elasticsearch.save(batch.stream()
                        .map(u -> new PlayerDocument(u.getId(), u.getUsername(), u.getCountry()))
                        .toList());
                written += batch.size();
                lastId = batch.get(batch.size() - 1).getId();
            }
        } while (batch.size() == REINDEX_BATCH);
        index.refresh();
        log.info("Re-indexed {} players ({} were in the index, {} in Postgres)", written, indexed, total);
        return written;
    }
}
