package io.github.ahmetcopur.tournament.search;

import io.github.ahmetcopur.tournament.user.User;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Mapping;
import org.springframework.data.elasticsearch.annotations.Setting;
import org.springframework.data.elasticsearch.annotations.WriteTypeHint;

/**
 * Only the fields people search by. Username and country never change, so a player is indexed
 * once at sign up. Level and coins change on every level up; they come from Postgres when a
 * search is answered instead of being re-indexed on the hot path.
 *
 * <p>{@code fingerprint} is a 31-bit hash of id, username and country, computed the same way in
 * Postgres. Summing it over an id range on both sides tells the reconcile job whether that range
 * matches without comparing every document.
 */
@Document(indexName = "players", createIndex = false, writeTypeHint = WriteTypeHint.FALSE)
@Setting(settingPath = "elasticsearch/players-settings.json")
@Mapping(mappingPath = "elasticsearch/players-mapping.json")
public record PlayerDocument(@Id Long id, String username, String country, Long fingerprint) {

    public static PlayerDocument of(User user) {
        return of(user.getId(), user.getUsername(), user.getCountry());
    }

    public static PlayerDocument of(long id, String username, String country) {
        return new PlayerDocument(id, username, country, fingerprint(id, username, country));
    }

    /** True if this document names the same player as {@code user} (the fingerprint aside). */
    public boolean isFor(User user) {
        return id != null && id.equals(user.getId()) && user.getUsername().equals(username)
                && user.getCountry().equals(country);
    }

    /**
     * First 31 bits of MD5("id:username:country"). Postgres computes the identical value with
     * {@code ('x' || substr(md5(id || ':' || username || ':' || country), 1, 8))::bit(32)::int & 2147483647}.
     */
    static long fingerprint(long id, String username, String country) {
        try {
            byte[] md5 = MessageDigest.getInstance("MD5")
                    .digest((id + ":" + username + ":" + country).getBytes(StandardCharsets.UTF_8));
            int first32 = ((md5[0] & 0xff) << 24) | ((md5[1] & 0xff) << 16) | ((md5[2] & 0xff) << 8) | (md5[3] & 0xff);
            return first32 & 0x7fffffffL;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
