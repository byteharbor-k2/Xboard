package com.sinx.platform.stats.web;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sinx.platform.stats.application.AdminStatsService;

/**
 * Dashboard statistics on the Xboard-compatible admin surface.
 *
 * Access is decided by the security configuration, which requires the admin
 * role and the admin token scope for everything under {@code /api/v2/admin}.
 * The panel client reads these paths with a bare JSON body (no {@code data}
 * envelope), matching the shape the existing dashboard code already parses.
 */
@RestController
@RequestMapping("/api/v2/admin/stat")
public class AdminStatController {

    private final AdminStatsService stats;

    public AdminStatController(AdminStatsService stats) {
        this.stats = stats;
    }

    @GetMapping("/getStats")
    Map<String, Object> summary() {
        return stats.summary();
    }

    /**
     * Node ranking. {@code period} is accepted for compatibility with the
     * dashboard call but the window is always the trailing month, which is the
     * only slice the daily ledger aggregates for.
     */
    @GetMapping("/getServerLastRank")
    List<Map<String, Object>> nodeRanking(
        @RequestParam(name = "period", required = false) String period
    ) {
        return stats.nodeRanking();
    }

    @GetMapping("/getTrafficRank")
    List<Map<String, Object>> userRanking(
        @RequestParam(name = "period", required = false) String period
    ) {
        return stats.userRanking();
    }
}
