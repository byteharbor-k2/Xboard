package com.sinx.platform.subscription.application;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fires the traffic reset sweep every hour, standing in for the manual
 * 重置流量 presses the cycle used to hang on.
 *
 * All the logic lives in {@link TrafficResetService#runMonthlyResets()}; this
 * class only decides when it runs, so the sweep itself is testable without
 * waiting for the cron. Hourly, on the half hour, so any calendar or activation
 * boundary lands inside whichever hour it falls due. The zone is pinned because
 * a server moved between time zones must not silently move reset days.
 */
@Component
public class MonthlyTrafficResetJob {

    private final TrafficResetService trafficResetService;

    public MonthlyTrafficResetJob(TrafficResetService trafficResetService) {
        this.trafficResetService = trafficResetService;
    }

    @Scheduled(
        cron = "${sinx.traffic-reset.cron:0 30 * * * *}",
        zone = "Asia/Shanghai"
    )
    public void resetDueTraffic() {
        trafficResetService.runMonthlyResets();
    }
}
