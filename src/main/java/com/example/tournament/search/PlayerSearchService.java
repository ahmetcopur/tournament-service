package com.example.tournament.search;

import co.elastic.clients.elasticsearch._types.SortOrder;
import com.example.tournament.common.ApiException;
import com.example.tournament.common.ErrorCode;
import com.example.tournament.user.User;
import com.example.tournament.user.UserCreated;
import com.example.tournament.user.UserRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.client.elc.NativeQueryBuilder;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.MultiGetItem;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHitsIterator;
import org.springframework.data.elasticsearch.core.query.Query;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Player search on Elasticsearch. Postgres stays the source of truth: the index only holds
 * id, username and country, a failed index write never fails a sign up, a search hit is only
 * returned if Postgres agrees on who it is, and a reconcile job makes the index match Postgres
 * player by player.
 */
@Service
public class PlayerSearchService {

    private static final Logger log = LoggerFactory.getLogger(PlayerSearchService.class);
    private static final int REINDEX_BATCH = 500;

    private final ElasticsearchOperations elasticsearch;
    private final UserRepository users;
    private final Counter indexFailures;
    private final Counter staleHits;
    private final Counter reconcileFixes;
    private final AtomicBoolean indexReady = new AtomicBoolean(false);

    public PlayerSearchService(ElasticsearchOperations elasticsearch, UserRepository users, MeterRegistry meters) {
        this.elasticsearch = elasticsearch;
        this.users = users;
        this.indexFailures = Counter.builder("search.index.failures")
                .description("Player documents that could not be written to Elasticsearch")
                .register(meters);
        this.staleHits = Counter.builder("search.stale.hits")
                .description("Search hits dropped because Postgres disagrees on who the player is")
                .register(meters);
        this.reconcileFixes = Counter.builder("search.reconcile.fixes")
                .description("Documents the reconcile job (re)indexed or deleted")
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

        List<PlayerDocument> hits;
        try {
            hits = elasticsearch.search(query, PlayerDocument.class).stream()
                    .map(SearchHit::getContent)
                    .toList();
        } catch (RuntimeException ex) {
            log.warn("Player search failed: {}", ex.getMessage());
            throw new ApiException(ErrorCode.SEARCH_UNAVAILABLE);
        }

        // Fresh level and coins from Postgres, kept in search order. A hit only counts if Postgres
        // agrees on who it is: a leftover document (say Postgres was reset and the id reused) must
        // never return one player under another player's name. The reconcile job fixes the index.
        Map<Long, User> byId = users.findAllById(hits.stream().map(PlayerDocument::id).toList()).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        List<User> results = new ArrayList<>(hits.size());
        for (PlayerDocument hit : hits) {
            User user = byId.get(hit.id());
            if (user != null && hit.equals(document(user))) {
                results.add(user);
            } else {
                staleHits.increment();
            }
        }
        return results;
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
     * Makes the index hold exactly the players in Postgres, compared by identity, not by count.
     * Equal counts can hide a missing player behind an orphaned document, for example after
     * Postgres was reset while Elasticsearch kept its data.
     *
     * <ol>
     *   <li>Postgres to Elasticsearch, 500 players at a time: fetch the same ids from the index
     *       and (re)index every player whose document is missing or differs.</li>
     *   <li>Elasticsearch to Postgres: walk every document and delete those whose player no
     *       longer exists.</li>
     * </ol>
     *
     * Both passes are idempotent and safe to run alongside sign ups. Cost is linear in the number
     * of players; fine every few minutes for this scale, a per-batch checksum would avoid reading
     * unchanged batches. Returns the number of documents written or deleted.
     */
    public long reconcile() {
        indexReady.set(false); // the index may have been deleted since we last looked
        ensureIndex();
        IndexOperations index = elasticsearch.indexOps(PlayerDocument.class);

        long written = 0;
        long lastId = 0;
        List<User> batch;
        do {
            batch = users.findTop500ByIdGreaterThanOrderByIdAsc(lastId);
            if (batch.isEmpty()) {
                break;
            }
            Map<Long, PlayerDocument> indexed = new HashMap<>();
            Query byIds = new NativeQueryBuilder()
                    .withIds(batch.stream().map(u -> u.getId().toString()).toList())
                    .build();
            for (MultiGetItem<PlayerDocument> item : elasticsearch.multiGet(byIds, PlayerDocument.class)) {
                if (item.hasItem()) {
                    indexed.put(item.getItem().id(), item.getItem());
                }
            }
            List<PlayerDocument> fixes = batch.stream()
                    .map(PlayerSearchService::document)
                    .filter(expected -> !expected.equals(indexed.get(expected.id())))
                    .toList();
            if (!fixes.isEmpty()) {
                elasticsearch.save(fixes);
                written += fixes.size();
            }
            lastId = batch.get(batch.size() - 1).getId();
        } while (batch.size() == REINDEX_BATCH);

        index.refresh();
        long deleted = 0;
        try (SearchHitsIterator<PlayerDocument> all = elasticsearch.searchForStream(
                new NativeQueryBuilder().withQuery(q -> q.matchAll(m -> m)).withPageable(PageRequest.of(0, REINDEX_BATCH)).build(),
                PlayerDocument.class)) {
            List<Long> chunk = new ArrayList<>(REINDEX_BATCH);
            while (all.hasNext()) {
                chunk.add(all.next().getContent().id());
                if (chunk.size() == REINDEX_BATCH || !all.hasNext()) {
                    deleted += deleteOrphans(chunk);
                    chunk.clear();
                }
            }
        }

        if (written + deleted > 0) {
            index.refresh();
            reconcileFixes.increment(written + deleted);
            log.info("Search reconcile: {} players (re)indexed, {} orphaned documents deleted", written, deleted);
        }
        return written + deleted;
    }

    private long deleteOrphans(List<Long> ids) {
        Set<Long> existing = users.findAllById(ids).stream().map(User::getId).collect(Collectors.toSet());
        long deleted = 0;
        for (Long id : ids) {
            if (!existing.contains(id)) {
                elasticsearch.delete(id.toString(), PlayerDocument.class);
                deleted++;
            }
        }
        return deleted;
    }

    private static PlayerDocument document(User user) {
        return new PlayerDocument(user.getId(), user.getUsername(), user.getCountry());
    }
}
