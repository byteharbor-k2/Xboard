package com.sinx.platform.identity.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.sinx.platform.identity.domain.SessionScope;
import com.sinx.platform.identity.domain.UserAccount;

public record ViewerView(
    UUID id,
    String email,
    String displayName,
    boolean emailVerified,
    List<String> roles,
    Instant createdAt
) {
    public static ViewerView forScope(
        UserAccount user,
        SessionScope scope
    ) {
        return new ViewerView(
            user.getId(),
            user.getEmail(),
            user.getDisplayName(),
            user.isEmailVerified(),
            List.of(scope.name()),
            user.getCreatedAt()
        );
    }
}
