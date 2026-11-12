package io.github.ahmetcopur.tournament.security;

import java.time.Instant;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/**
 * Issues the signed token a player gets at sign up. Its subject is the player's user id, and
 * every request that acts for a player must carry a token for that id.
 *
 * <p>Token times use the real clock, not the game clock: security must not depend on a clock that
 * tests (or a misconfigured game) can move.
 */
@Service
public class TokenService {

    public record IssuedToken(String token, Instant expiresAt) {
    }

    private final JwtEncoder encoder;
    private final AuthProperties auth;

    public TokenService(JwtEncoder encoder, AuthProperties auth) {
        this.encoder = encoder;
        this.auth = auth;
    }

    public IssuedToken issue(long userId) {
        return issue(userId, Instant.now().plus(auth.tokenTtl()));
    }

    public IssuedToken issue(long userId, Instant expiresAt) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(auth.issuer())
                .subject(Long.toString(userId))
                .issuedAt(expiresAt.isAfter(now) ? now : expiresAt.minusSeconds(60)) // must precede expiry
                .expiresAt(expiresAt)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return new IssuedToken(encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue(), expiresAt);
    }
}
