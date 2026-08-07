package com.sinx.platform.node.application;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

public record NodeAccessGroupsChangedEvent(
    Set<Long> groupIds,
    String reason
) {
    public NodeAccessGroupsChangedEvent {
        LinkedHashSet<Long> normalized = new LinkedHashSet<>();
        if (groupIds != null) {
            groupIds.stream()
                .filter(java.util.Objects::nonNull)
                .filter(id -> id > 0)
                .forEach(normalized::add);
        }
        groupIds = Set.copyOf(normalized);
        reason = reason == null ? "node user access changed" : reason;
    }

    public static NodeAccessGroupsChangedEvent of(
        Collection<Long> groupIds,
        String reason
    ) {
        return new NodeAccessGroupsChangedEvent(
            groupIds == null ? Set.of() : new LinkedHashSet<>(groupIds),
            reason
        );
    }
}
