package com.sinx.platform.identity.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class TotpServiceTest {

    private static final String TEST_KEY =
        "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    @Test
    void matchesRfc6238Sha1VectorUsingSixDigits() {
        IdentitySecurityProperties properties = properties();
        TotpService service = new TotpService(
            properties,
            Clock.fixed(Instant.ofEpochSecond(59), ZoneOffset.UTC)
        );

        assertThat(service.currentCode(
            "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
        )).isEqualTo("287082");
    }

    @Test
    void encryptsSecretsAndNormalizesRecoveryCodes() {
        MfaCryptography cryptography = new MfaCryptography(properties());
        String encrypted = cryptography.encryptSecret(
            "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
        );

        assertThat(encrypted).doesNotContain(
            "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
        );
        assertThat(cryptography.decryptSecret(encrypted))
            .isEqualTo("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
        assertThat(cryptography.hashRecoveryCode("ABCDE-23456"))
            .isEqualTo(cryptography.hashRecoveryCode("abcde 23456"));
    }

    private IdentitySecurityProperties properties() {
        return new IdentitySecurityProperties(
            "sinx-test",
            Duration.ofMinutes(10),
            Duration.ofDays(30),
            Duration.ofMinutes(5),
            Duration.ofHours(12),
            Duration.ofMinutes(30),
            Duration.ofMinutes(5),
            Duration.ofSeconds(10),
            "SinX Cloud",
            TEST_KEY,
            "test-only-jwt-secret-with-at-least-32-characters",
            "rt_session",
            "rt_admin",
            false
        );
    }
}
