package com.sinx.platform.identity.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.sinx.platform.identity.domain.Role;
import com.sinx.platform.identity.domain.UserAccount;

public record ViewerView(
    UUID id,
    String email,
    String displayName,
    boolean emailVerified,
    List<String> roles,
    Instant createdAt
) {
    static ViewerView from(UserAccount user) {
        return new ViewerView(
            user.getId(),
            user.getEmail(),
            user.getDisplayName(),
            user.isEmailVerified(),
            user.getRoles().stream()
                .map(Role::getCode)
                .sorted()
                .toList(),
            user.getCreatedAt()
        );
    }
}
