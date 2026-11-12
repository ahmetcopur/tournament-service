package io.github.ahmetcopur.tournament.security;

import io.github.ahmetcopur.tournament.common.ApiError;
import io.github.ahmetcopur.tournament.common.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Stateless bearer-token security, built on Spring Security's OAuth2 resource server.
 *
 * <ul>
 *   <li>Sign up ({@code POST /users}) is open and returns the player's token.</li>
 *   <li>Everything else needs a valid token. Acting for a player (level up, join, claim, reading
 *       coins) needs a token for that player; see {@link CurrentPlayer}.</li>
 *   <li>Actuator endpoints are only served on the management port, which deployments keep
 *       internal. On the public port they are refused even if misconfigured.</li>
 * </ul>
 *
 * Tokens are HS256 and issued by this service. Moving to an external identity provider is a
 * configuration change: replace the {@link JwtDecoder} with one for the provider's keys.
 */
@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    SecretKey tokenSigningKey(AuthProperties auth) {
        byte[] key;
        if (auth.tokenSecret() == null || auth.tokenSecret().isBlank()) {
            key = new byte[32];
            new SecureRandom().nextBytes(key);
            log.warn("auth.token-secret is not set, so tokens are signed with a random key: they stop working "
                    + "on restart and other instances reject them. Set AUTH_TOKEN_SECRET (32+ bytes) in any real deployment.");
        } else {
            key = auth.tokenSecret().getBytes(StandardCharsets.UTF_8);
            if (key.length < 32) {
                throw new IllegalStateException("auth.token-secret must be at least 32 bytes for HS256");
            }
        }
        return new SecretKeySpec(key, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey tokenSigningKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(tokenSigningKey));
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey tokenSigningKey, AuthProperties auth) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(tokenSigningKey).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(auth.issuer())); // expiry + issuer
        return decoder;
    }

    @Bean
    SecurityFilterChain api(HttpSecurity http, ObjectMapper json, MeterRegistry meters,
                            @Value("${management.server.port:-1}") int managementPort) throws Exception {
        RequestMatcher onManagementPort = request -> managementPort > 0 && request.getLocalPort() == managementPort;
        http.csrf(csrf -> csrf.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(onManagementPort).permitAll()
                        .requestMatchers("/actuator/**").denyAll()
                        .requestMatchers(HttpMethod.POST, "/users").permitAll()
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint((request, response, ex) ->
                                writeError(response, json, meters, ErrorCode.UNAUTHORIZED))
                        .accessDeniedHandler((request, response, ex) ->
                                writeError(response, json, meters, ErrorCode.FORBIDDEN)))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, ex) ->
                                writeError(response, json, meters, ErrorCode.UNAUTHORIZED))
                        .accessDeniedHandler((request, response, ex) ->
                                writeError(response, json, meters, ErrorCode.FORBIDDEN)));
        return http.build();
    }

    /** Same {code, message} body as every other error, written before the request reaches MVC. */
    private static void writeError(HttpServletResponse response, ObjectMapper json, MeterRegistry meters,
                                   ErrorCode code) throws IOException {
        Counter.builder("api.errors").tag("code", code.name()).register(meters).increment();
        response.setStatus(code.status().value());
        if (code == ErrorCode.UNAUTHORIZED) {
            response.setHeader("WWW-Authenticate", "Bearer");
        }
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        json.writeValue(response.getOutputStream(), ApiError.of(code, code.defaultMessage()));
    }
}
