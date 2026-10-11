package com.sinx.platform.bootstrap.security;

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Retains imported Laravel bcrypt passwords while writing new passwords as Argon2. */
final class LegacyCompatiblePasswordEncoder implements PasswordEncoder {
    private final PasswordEncoder current = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    private final PasswordEncoder legacy = new BCryptPasswordEncoder();

    @Override
    public String encode(CharSequence rawPassword) {
        return current.encode(rawPassword);
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        if (encodedPassword == null) {
            return false;
        }
        if (encodedPassword.startsWith("$2y$") || encodedPassword.startsWith("$2a$")
            || encodedPassword.startsWith("$2b$")) {
            return legacy.matches(rawPassword, encodedPassword);
        }
        return current.matches(rawPassword, encodedPassword);
    }
}
