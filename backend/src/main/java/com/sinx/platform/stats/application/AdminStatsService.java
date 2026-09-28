package com.sinx.platform.stats.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sinx.platform.order.repository.ServiceOrderRepository;
import com.sinx.platform.stats.repository.TrafficDailyRepository;

/**
 * Read-only numbers behind the admin dashboard.
 *
 * Everything is an aggregate over the operational tables; no live counters are
 * smuggled into the panel. Money is the sum of orders that were actually paid
 * (a settled order always carries its payment time), reported in major
 * currency units because that is what the dashboard charts draw.
 *
 * The account counts are read by plain SQL rather than through the identity
 * module: statistics is a passive observer and idleness here must not turn
 * into account mutations from a statistics call.
 */
@Service
@Transactional(readOnly = true)
public class AdminStatsService {

    /** Money columns carry minor units; the dashboard charts whole currency. */
    private static final int MINOR_UNITS = 100;
    private static final int RANKING_LIMIT = 10;

    private final ServiceOrderRepository orders;
    private final TrafficDailyRepository trafficDaily;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ZoneId billingZone;

    public AdminStatsService(
        ServiceOrderRepository orders,
        TrafficDailyRepository trafficDaily,
        JdbcTemplate jdbc,
        Clock clock,
        @Value("${sinx.node.billing-time-zone:Asia/Shanghai}")
        String billingTimeZone
    ) {
        this.orders = orders;
        this.trafficDaily = trafficDaily;
        this.jdbc = jdbc;
        this.clock = clock;
        this.billingZone = ZoneId.of(billingTimeZone);
    }

    /**
     * The headline numbers of the dashboard: revenue today and over the
     * trailing month (paid orders), account totals, and traffic that was
     * actually carried over the trailing month.
     */
    public Map<String, Object> summary() {
        var now = clock.instant();
        Instant monthStart = now.minusSeconds(30L * 24 * 60 * 60);

        var totals = trafficDaily.totalsSince(today().minusDays(30));

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("todayIncome", toMajorUnit(
            orders.sumPaidOrderAmountSince(startOfDay())
        ));
        view.put("monthlyIncome", toMajorUnit(
            orders.sumPaidOrderAmountSince(monthStart)
        ));
        // Referral commissions are not a feature of this panel; the slot stays
        // on the wire because the dashboard card is part of the shared layout.
        view.put("pendingCommission", 0);
        view.put("monthlyUsers", countNewUsers(Date.from(monthStart)));
        view.put("totalUsers", countUsers());
        view.put("monthlyUploadBytes", totals.getUploadBytes());
        view.put("monthlyDownloadBytes", totals.getDownloadBytes());
        return view;
    }

    /** The nodes traffic actually flowed through, heaviest first. */
    public List<Map<String, Object>> nodeRanking() {
        var ranking = trafficDaily.nodeRanking(
            today().minusDays(30), RANKING_LIMIT
        );
        return ranking.stream().<Map<String, Object>>map(row -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", String.valueOf(row.getNodeId()));
            entry.put("label", row.getLabel());
            entry.put("bytes", row.getTotalBytes());
            entry.put("changePercent", null);
            return entry;
        }).toList();
    }

    /** The accounts traffic actually belongs to, heaviest first. */
    public List<Map<String, Object>> userRanking() {
        var ranking = trafficDaily.userRanking(
            today().minusDays(30), RANKING_LIMIT
        );
        return ranking.stream().<Map<String, Object>>map(row -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", row.getUserId().toString());
            entry.put("label", row.getLabel());
            entry.put("bytes", row.getTotalBytes());
            entry.put("changePercent", null);
            return entry;
        }).toList();
    }

    private LocalDate today() {
        return clock.instant().atZone(billingZone).toLocalDate();
    }

    private Instant startOfDay() {
        return today().atStartOfDay(billingZone).toInstant();
    }

    private BigDecimal toMajorUnit(long minorUnits) {
        return BigDecimal.valueOf(minorUnits)
            .setScale(2, RoundingMode.UNNECESSARY)
            .divide(BigDecimal.valueOf(MINOR_UNITS), RoundingMode.UNNECESSARY);
    }

    private long countUsers() {
        Long count = jdbc.queryForObject(
            "select count(*) from users", Long.class
        );
        return count == null ? 0 : count;
    }

    /**
     * The instant passed as {@code java.sql.Timestamp}: the plain JDBC
     * counter is the one place the date comes straight through the driver,
     * which cannot infer an Instant.
     */
    private long countNewUsers(Date since) {
        Long count = jdbc.queryForObject(
            "select count(*) from users where created_at >= ?", Long.class, since
        );
        return count == null ? 0 : count;
    }
}
