package io.github.ahmetcopur.tournament.search;

import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregation;
import co.elastic.clients.elasticsearch._types.aggregations.HistogramBucket;
import io.github.ahmetcopur.tournament.common.ApiException;
import io.github.ahmetcopur.tournament.common.ErrorCode;
import io.github.ahmetcopur.tournament.user.User;
import io.github.ahmetcopur.tournament.user.UserCreated;
import io.github.ahmetcopur.tournament.user.UserRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.Set;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.client.elc.ElasticsearchAggregations;
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
    /** Ids per checksum range. */
    private static final long RANGE = 10_000;

    private final ElasticsearchOperations elasticsearch;
    private final UserRepository users;
    private final Counter indexFailures;
    private final Counter staleHits;
    private final Counter reconcileFixes;
    private final AtomicBoolean indexReady = new AtomicBoolean(false);
    /** One thread, bounded queue: sign-ups are never held up by indexing, and memory stays bounded. */
    private final ExecutorService indexer;

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
        this.indexer = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(10_000),
                task -> {
                    Thread thread = new Thread(task, "search-indexer");
                    thread.setDaemon(true);
                    return thread;
                },
                (task, executor) -> {
                    indexFailures.increment();
                    log.warn("Search indexing queue is full; the reconcile job will index the player");
                });
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
            if (user != null && hit.isFor(user)) {
                results.add(user);
            } else {
                staleHits.increment();
            }
        }
        return results;
    }

    /**
     * Indexes a new player off the request thread. An after-commit listener runs on the request
     * thread, so indexing there made sign up wait for Elasticsearch: with it stalled, the player
     * was committed but the response hung until the search timeout, and a client that gave up
     * never received its token. Now the response returns right after the commit; if indexing
     * fails, or the queue is full, the reconcile job indexes the player later.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUserCreated(UserCreated event) {
        indexer.execute(() -> {
            try {
                ensureIndex();
                elasticsearch.save(PlayerDocument.of(event.id(), event.username(), event.country()));
            } catch (RuntimeException ex) {
                indexFailures.increment();
                log.warn("Could not index player {}; the reconcile job will retry: {}", event.id(), ex.getMessage());
            }
        });
    }

    /** Waits until every sign-up queued so far has been indexed (or has failed). For tests. */
    public void awaitIndexed(Duration timeout) throws Exception {
        indexer.submit(() -> { }).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stopIndexer() throws InterruptedException {
        indexer.shutdown();
        indexer.awaitTermination(5, TimeUnit.SECONDS);
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
        } else {
            index.putMapping(); // adds fields introduced since the index was created (e.g. fingerprint)
        }
        indexReady.set(true);
    }

    /** What a reconcile run did, for logs and tests. */
    public record ReconcileReport(int rangesTotal, int rangesExamined, long written, long deleted) {
        public long fixed() {
            return written + deleted;
        }
    }

    /** See {@link #reconcileReport()}; returns the number of documents written or deleted. */
    public long reconcile() {
        return reconcileReport().fixed();
    }

    /**
     * Makes the index hold exactly the players in Postgres, compared by identity, not by count,
     * and without reading every player each time.
     *
     * <ol>
     *   <li>Checksums: for every range of 10,000 ids, Elasticsearch (one aggregation query) and
     *       Postgres (one GROUP BY) each give the number of players and the sum of their
     *       fingerprints. A range where both agree holds the same players with the same names.</li>
     *   <li>Only ranges that differ are compared player by player: Postgres to Elasticsearch
     *       (fetch the same ids, re-index what's missing or wrong) and Elasticsearch to Postgres
     *       (delete documents whose player doesn't exist).</li>
     * </ol>
     *
     * In sync, a run costs one aggregation and one GROUP BY, however many players there are. Both
     * passes are idempotent and safe alongside sign ups.
     */
    public ReconcileReport reconcileReport() {
        indexReady.set(false); // the index may have been deleted since we last looked
        ensureIndex();
        IndexOperations index = elasticsearch.indexOps(PlayerDocument.class);
        index.refresh();

        Map<Long, List<Long>> inIndex = indexChecksums();
        Map<Long, List<Long>> inPostgres = new HashMap<>();
        for (Object[] row : users.searchChecksums(RANGE)) {
            inPostgres.put(((Number) row[0]).longValue(),
                    List.of(((Number) row[1]).longValue(), ((Number) row[2]).longValue()));
        }
        Set<Long> ranges = new TreeSet<>(inIndex.keySet());
        ranges.addAll(inPostgres.keySet());
        List<Long> differing = ranges.stream()
                .filter(range -> !Objects.equals(inIndex.get(range), inPostgres.get(range)))
                .toList();

        long written = 0;
        long deleted = 0;
        for (long range : differing) {
            written += indexMissingOrWrong(range * RANGE, (range + 1) * RANGE);
            index.refresh();
            deleted += deleteOrphans(range * RANGE, (range + 1) * RANGE);
        }
        if (written + deleted > 0) {
            index.refresh();
            reconcileFixes.increment(written + deleted);
            log.info("Search reconcile: {} of {} id ranges differed; {} players (re)indexed, {} orphaned documents deleted",
                    differing.size(), ranges.size(), written, deleted);
        }
        return new ReconcileReport(ranges.size(), differing.size(), written, deleted);
    }

    /** Per id range: [documents, sum of fingerprints], in one aggregation query. */
    private Map<Long, List<Long>> indexChecksums() {
        NativeQuery query = NativeQuery.builder()
                .withQuery(q -> q.matchAll(m -> m))
                .withAggregation("ranges", Aggregation.of(a -> a
                        .histogram(h -> h.field("id").interval((double) RANGE).minDocCount(1))
                        .aggregations("fingerprints", Aggregation.of(sum -> sum.sum(f -> f.field("fingerprint"))))))
                .withMaxResults(0)
                .build();
        ElasticsearchAggregations aggregations =
                (ElasticsearchAggregations) elasticsearch.search(query, PlayerDocument.class).getAggregations();
        Map<Long, List<Long>> checksums = new HashMap<>();
        if (aggregations == null) {
            return checksums;
        }
        for (HistogramBucket bucket : aggregations.aggregationsAsMap().get("ranges").aggregation()
                .getAggregate().histogram().buckets().array()) {
            long sum = (long) bucket.aggregations().get("fingerprints").sum().value();
            checksums.put((long) (bucket.key() / RANGE), List.of(bucket.docCount(), sum));
        }
        return checksums;
    }

    /** Postgres to Elasticsearch for ids in [from, to): (re)index players missing or wrong. */
    private long indexMissingOrWrong(long from, long to) {
        long written = 0;
        long lastId = from - 1;
        List<User> batch;
        do {
            batch = users.findTop500ByIdGreaterThanAndIdLessThanOrderByIdAsc(lastId, to);
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
                    .map(PlayerDocument::of)
                    .filter(expected -> !expected.equals(indexed.get(expected.id())))
                    .toList();
            if (!fixes.isEmpty()) {
                elasticsearch.save(fixes);
                written += fixes.size();
            }
            lastId = batch.get(batch.size() - 1).getId();
        } while (batch.size() == REINDEX_BATCH);
        return written;
    }

    /** Elasticsearch to Postgres for ids in [from, to): delete documents whose player doesn't exist. */
    private long deleteOrphans(long from, long to) {
        long deleted = 0;
        NativeQuery inRange = new NativeQueryBuilder()
                .withQuery(q -> q.range(r -> r.number(n -> n.field("id").gte((double) from).lt((double) to))))
                .withPageable(PageRequest.of(0, REINDEX_BATCH))
                .build();
        try (SearchHitsIterator<PlayerDocument> documents = elasticsearch.searchForStream(inRange, PlayerDocument.class)) {
            List<Long> chunk = new ArrayList<>(REINDEX_BATCH);
            while (documents.hasNext()) {
                chunk.add(documents.next().getContent().id());
                if (chunk.size() == REINDEX_BATCH || !documents.hasNext()) {
                    Set<Long> existing = users.findAllById(chunk).stream().map(User::getId).collect(Collectors.toSet());
                    for (Long id : chunk) {
                        if (!existing.contains(id)) {
                            elasticsearch.delete(id.toString(), PlayerDocument.class);
                            deleted++;
                        }
                    }
                    chunk.clear();
                }
            }
        }
        return deleted;
    }
}
