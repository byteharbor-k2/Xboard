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
    Instant createdAt,
    /**
     * The account's prepaid balance, in minor units. It is money the customer
     * has already paid in, so it belongs on their own pages rather than only
     * being discoverable at checkout.
     */
    String balanceMinor
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
            user.getCreatedAt(),
            Long.toString(user.getBalanceMinor())
        );
    }
}
