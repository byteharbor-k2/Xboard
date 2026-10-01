package com.sinx.platform.subscription.web;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sinx.platform.subscription.application.AdminTrafficResetView;
import com.sinx.platform.subscription.application.TrafficResetService;

/**
 * The traffic reset ledger on the admin surface.
 *
 * Access is already decided by the security configuration, which requires
 * both the admin role and the admin token scope for everything under
 * {@code /api/v2/admin}, so these methods carry no checks of their own.
 */
@RestController
@RequestMapping("/api/v2/admin/traffic-reset")
public class AdminTrafficResetController {

    private final TrafficResetService trafficResets;

    public AdminTrafficResetController(TrafficResetService trafficResets) {
        this.trafficResets = trafficResets;
    }

    /**
     * The newest resets, a screens-worth at a time - long enough to answer
     * "when did this customer last get their cycle", short enough to render
     * before the operator loses interest. A user id narrows it to one
     * account.
     */
    @GetMapping("/records")
    XboardResponse<List<AdminTrafficResetView>> records(
        @RequestParam(name = "user_id", required = false) UUID userId,
        @RequestParam(name = "limit", required = false, defaultValue = "100")
        Integer limit
    ) {
        return XboardResponse.of(
            trafficResets.adminRecords(userId, limit)
        );
    }

    record XboardResponse<T>(T data) {
        static <T> XboardResponse<T> of(T data) {
            return new XboardResponse<>(data);
        }
    }
}
