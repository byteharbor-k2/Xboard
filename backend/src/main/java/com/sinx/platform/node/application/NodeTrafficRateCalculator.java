package com.sinx.platform.node.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.sinx.platform.node.domain.ProxyNode;

import tools.jackson.databind.ObjectMapper;

@Component
public class NodeTrafficRateCalculator {

    private static final DateTimeFormatter TIME_FORMAT =
        DateTimeFormatter.ofPattern("HH:mm");

    private final ObjectMapper objectMapper;
    private final ZoneId billingZone;

    public NodeTrafficRateCalculator(
        ObjectMapper objectMapper,
        @Value("${sinx.node.billing-time-zone:Asia/Shanghai}") String billingTimeZone
    ) {
        this.objectMapper = objectMapper;
        this.billingZone = ZoneId.of(billingTimeZone);
    }

    public BigDecimal currentRate(ProxyNode node, Instant now) {
        BigDecimal baseRate = nonNegative(node.getRate());
        if (!node.isRateTimeEnable()) return baseRate;
        LocalTime current = now.atZone(billingZone).toLocalTime();
        for (Map<String, Object> range : ranges(node.getRateTimeRanges())) {
            LocalTime start = time(range.get("start"));
            LocalTime end = time(range.get("end"));
            BigDecimal rate = decimal(range.get("rate"));
            if (start == null || end == null || rate == null) continue;
            if (!current.isBefore(start) && !current.isAfter(end)) {
                return rate;
            }
        }
        return baseRate;
    }

    /** Mirrors Xboard's positive integer truncation after applying a rate. */
    public long charge(long bytes, BigDecimal rate) {
        if (bytes <= 0) return 0;
        BigDecimal charged = BigDecimal.valueOf(bytes)
            .multiply(nonNegative(rate))
            .setScale(0, RoundingMode.DOWN);
        return charged.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) >= 0
            ? Long.MAX_VALUE : charged.longValue();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> ranges(String encoded) {
        if (encoded == null || encoded.isBlank()) return List.of();
        try {
            Object decoded = objectMapper.readValue(encoded, Object.class);
            if (!(decoded instanceof List<?> values)) return List.of();
            return values.stream()
                .filter(Map.class::isInstance)
                .map(value -> (Map<String, Object>) value)
                .toList();
        } catch (Exception exception) {
            return List.of();
        }
    }

    private LocalTime time(Object value) {
        if (!(value instanceof String string)) return null;
        try {
            return LocalTime.parse(string, TIME_FORMAT);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private BigDecimal decimal(Object value) {
        try {
            BigDecimal result = new BigDecimal(String.valueOf(value));
            return result.signum() >= 0 ? result : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private BigDecimal nonNegative(BigDecimal value) {
        return value == null || value.signum() < 0 ? BigDecimal.ZERO : value;
    }
}
