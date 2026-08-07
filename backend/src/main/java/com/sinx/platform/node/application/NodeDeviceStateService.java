package com.sinx.platform.node.application;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class NodeDeviceStateService {

    private static final Duration SNAPSHOT_TTL = Duration.ofMinutes(5);
    private static final String USER_KEY_PREFIX = "node:devices:user:";
    private static final String NODE_KEY_PREFIX = "node:devices:index:";
    private static final Pattern BRACKETED_IPV6_WITH_PORT =
        Pattern.compile("^\\[([^]]+)](?::\\d+)?$");
    private static final Pattern IPV4_WITH_PORT =
        Pattern.compile("^(\\d{1,3}(?:\\.\\d{1,3}){3}):\\d+$");
    private static final Pattern IPV4 =
        Pattern.compile("^\\d{1,3}(?:\\.\\d{1,3}){3}$");
    private static final Pattern IPV6 =
        Pattern.compile("^[0-9a-fA-F:.%]+$");

    private final StringRedisTemplate redis;

    public NodeDeviceStateService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Replaces one node's complete device snapshot. State is deliberately
     * ephemeral in Redis, matching Xboard's device-limit control plane and
     * avoiding a database write for every online-IP change.
     */
    public void replaceSnapshot(
        long nodeId,
        Map<Long, List<String>> snapshot,
        Instant now
    ) {
        String nodeKey = nodeKey(nodeId);
        Set<Long> previousUsers = parseUserIds(redis.opsForSet().members(nodeKey));
        Set<Long> currentUsers = snapshot.keySet().stream()
            .filter(userId -> userId != null && userId > 0)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<Long> affectedUsers = new LinkedHashSet<>(previousUsers);
        affectedUsers.addAll(currentUsers);
        affectedUsers.forEach(userId -> removeNodeFields(userId, nodeId));

        String seenAt = Long.toString(now.getEpochSecond());
        snapshot.forEach((nodeUserId, rawAddresses) -> {
            if (nodeUserId == null || nodeUserId <= 0 || rawAddresses == null) return;
            Map<String, String> fields = new LinkedHashMap<>();
            normalizeAddresses(rawAddresses).forEach(address ->
                fields.put(nodeId + ":" + address, seenAt)
            );
            if (fields.isEmpty()) return;
            String userKey = userKey(nodeUserId);
            redis.opsForHash().putAll(userKey, fields);
            redis.expire(userKey, SNAPSHOT_TTL);
        });

        redis.delete(nodeKey);
        if (!currentUsers.isEmpty()) {
            redis.opsForSet().add(
                nodeKey,
                currentUsers.stream().map(String::valueOf).toArray(String[]::new)
            );
            redis.expire(nodeKey, SNAPSHOT_TTL);
        }
    }

    public Map<Long, List<String>> snapshotForUsers(
        Set<Long> nodeUserIds,
        Instant now
    ) {
        if (nodeUserIds == null || nodeUserIds.isEmpty()) return Map.of();
        long cutoff = now.minus(SNAPSHOT_TTL).getEpochSecond();
        Map<Long, List<String>> result = new LinkedHashMap<>();
        for (Long nodeUserId : nodeUserIds) {
            if (nodeUserId == null || nodeUserId <= 0) continue;
            Map<Object, Object> fields = redis.opsForHash().entries(userKey(nodeUserId));
            Set<String> addresses = new LinkedHashSet<>();
            fields.forEach((field, timestamp) -> {
                if (epochSecond(timestamp) < cutoff) return;
                String encoded = String.valueOf(field);
                int separator = encoded.indexOf(':');
                if (separator >= 0 && separator + 1 < encoded.length()) {
                    addresses.add(encoded.substring(separator + 1));
                }
            });
            if (!addresses.isEmpty()) result.put(nodeUserId, List.copyOf(addresses));
        }
        return result;
    }

    public void clearNode(long nodeId) {
        String nodeKey = nodeKey(nodeId);
        parseUserIds(redis.opsForSet().members(nodeKey))
            .forEach(userId -> removeNodeFields(userId, nodeId));
        redis.delete(nodeKey);
    }

    /** Redis expires snapshots automatically; retained for scheduled callers. */
    public int pruneExpired(Instant now) {
        return 0;
    }

    private void removeNodeFields(long nodeUserId, long nodeId) {
        String userKey = userKey(nodeUserId);
        String prefix = nodeId + ":";
        Object[] fields = redis.opsForHash().keys(userKey).stream()
            .map(String::valueOf)
            .filter(field -> field.startsWith(prefix))
            .toArray();
        if (fields.length > 0) redis.opsForHash().delete(userKey, fields);
    }

    private Set<Long> parseUserIds(Set<String> values) {
        if (values == null || values.isEmpty()) return Set.of();
        Set<Long> result = new LinkedHashSet<>();
        for (String value : values) {
            try {
                long parsed = Long.parseLong(value);
                if (parsed > 0) result.add(parsed);
            } catch (RuntimeException ignored) {
            }
        }
        return result;
    }

    private Set<String> normalizeAddresses(Collection<String> rawAddresses) {
        Set<String> normalized = new LinkedHashSet<>();
        for (String raw : rawAddresses) {
            String address = normalizeAddress(raw);
            if (address != null) normalized.add(address);
        }
        return normalized;
    }

    private String normalizeAddress(String raw) {
        if (raw == null) return null;
        String value = raw.trim();
        Matcher bracketed = BRACKETED_IPV6_WITH_PORT.matcher(value);
        if (bracketed.matches()) value = bracketed.group(1);
        Matcher ipv4WithPort = IPV4_WITH_PORT.matcher(value);
        if (ipv4WithPort.matches()) value = ipv4WithPort.group(1);
        if (value.isBlank() || value.length() > 64) return null;
        if (IPV4.matcher(value).matches()) {
            for (String octet : value.split("\\.")) {
                try {
                    if (Integer.parseInt(octet) > 255) return null;
                } catch (NumberFormatException exception) {
                    return null;
                }
            }
            return value;
        }
        if (value.contains(":") && IPV6.matcher(value).matches()) {
            return value.toLowerCase(Locale.ROOT);
        }
        return null;
    }

    private long epochSecond(Object value) {
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (RuntimeException exception) {
            return Long.MIN_VALUE;
        }
    }

    private String userKey(long nodeUserId) {
        return USER_KEY_PREFIX + nodeUserId;
    }

    private String nodeKey(long nodeId) {
        return NODE_KEY_PREFIX + nodeId;
    }
}
