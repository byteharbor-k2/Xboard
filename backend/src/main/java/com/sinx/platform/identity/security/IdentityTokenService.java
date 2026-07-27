package com.sinx.platform.identity.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

import com.sinx.platform.identity.domain.Role;
import com.sinx.platform.identity.domain.UserAccount;

@Component
public class IdentityTokenService {

    private final JwtEncoder jwtEncoder;
    private final IdentitySecurityProperties properties;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    public IdentityTokenService(
        JwtEncoder jwtEncoder,
        IdentitySecurityProperties properties,
        Clock clock
    ) {
        this.jwtEncoder = jwtEncoder;
        this.properties = properties;
        this.clock = clock;
    }

    public AccessTokenGrant issueAccessToken(UserAccount user) {
        Instant issuedAt = Instant.now(clock);
        Instant expiresAt = issuedAt.plus(properties.accessTokenTtl());
        List<String> roles = user.getRoles().stream()
            .map(Role::getCode)
            .sorted()
            .toList();

        JwtClaimsSet claims = JwtClaimsSet.builder()
            .issuer(properties.issuer())
            .subject(user.getId().toString())
            .audience(List.of("sinx-web"))
            .issuedAt(issuedAt)
            .expiresAt(expiresAt)
            .id(UUID.randomUUID().toString())
            .claim("roles", roles)
            .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256)
            .type("JWT")
            .build();
        String token = jwtEncoder.encode(
            JwtEncoderParameters.from(header, claims)
        ).getTokenValue();
        return new AccessTokenGrant(token, expiresAt);
    }

    public String newRefreshToken() {
        return newOpaqueToken();
    }

    public String newOpaqueToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String hashRefreshToken(String token) {
        return hashOpaqueToken(token);
    }

    public String hashOpaqueToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                digest.digest(token.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record AccessTokenGrant(String token, Instant expiresAt) {
    }
}
