package com.sinx.platform.bootstrap.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

class LegacyCompatiblePasswordEncoderTest {
    private final LegacyCompatiblePasswordEncoder encoder = new LegacyCompatiblePasswordEncoder();

    @Test
    void acceptsLaravelBcryptWithoutChangingNewPasswordFormat() {
        String bcrypt = new BCryptPasswordEncoder(4).encode("imported-password-123");
        String laravel = "$2y$" + bcrypt.substring(4);
        assertThat(encoder.matches("imported-password-123", laravel)).isTrue();
        assertThat(encoder.matches("wrong-password", laravel)).isFalse();
        assertThat(encoder.matches("imported-password-123", bcrypt)).isTrue();
        String current = encoder.encode("new-password-123");
        assertThat(current).startsWith("$argon2");
        assertThat(encoder.matches("new-password-123", current)).isTrue();
        assertThat(encoder.matches("wrong-password", current)).isFalse();
        assertThat(encoder.matches("new-password-123", null)).isFalse();
    }
}
