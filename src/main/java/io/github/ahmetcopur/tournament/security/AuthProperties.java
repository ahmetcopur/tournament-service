package io.github.ahmetcopur.tournament.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Player tokens. {@code tokenSecret} is the HS256 key (32+ bytes). If it is empty the app makes
 * up a random key at startup, which is fine on a laptop, but tokens then die with the process and
 * aren't accepted by other instances, so every real deployment must set it.
 */
@ConfigurationProperties(prefix = "auth")
public record AuthProperties(
        String tokenSecret,
        @DefaultValue("30d") Duration tokenTtl,
        @DefaultValue("tournament-service") String issuer) {
}
