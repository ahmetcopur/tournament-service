package com.example.tournament.leaderboard;

import com.example.tournament.config.GameProperties;
import org.springframework.stereotype.Component;

/**
 * Where a player's points live on a tournament's country board.
 *
 * <ul>
 *   <li><b>Shards.</b> The board is split into N slices by user id. Each slice has its own Redis
 *       Cluster hash tag, so country traffic spreads over N slots instead of one hot key. A read
 *       adds the slices up.</li>
 *   <li><b>Buckets.</b> Within a slice, the bookkeeping "highest score counted per player" is kept
 *       in hashes of about 100 players. Hashes that small use Redis's compact listpack encoding,
 *       several times smaller than one big hash with a field per player.</li>
 * </ul>
 *
 * The shard count is part of every key, so changing it starts fresh boards that are rebuilt from
 * Postgres instead of mixing two layouts.
 */
@Component
public class CountryBoardLayout {

    /** Under Redis's default hash-max-listpack-entries (128), with room for the epoch field. */
    static final int BUCKET_SIZE = 100;

    private final int shards;

    public CountryBoardLayout(GameProperties props) {
        this.shards = props.leaderboard().countryShards();
    }

    public int shards() {
        return shards;
    }

    public int shard(long userId) {
        return (int) Math.floorMod(userId, (long) shards);
    }

    public long bucket(long userId) {
        return Math.floorDiv(userId, shards) / BUCKET_SIZE;
    }

    /** Sorted set: country -> this slice's total. */
    public String totals(long tournamentId, int shard) {
        return "lb:country:{t" + tournamentId + ":s" + shard + "/" + shards + "}";
    }

    /** Hash: epoch, ready, final. */
    public String meta(long tournamentId, int shard) {
        return totals(tournamentId, shard) + ":meta";
    }

    /** Hash: __e (epoch), then user id -> highest score already counted. */
    public String bucketKey(long tournamentId, int shard, long bucket) {
        return totals(tournamentId, shard) + ":b" + bucket;
    }
}
