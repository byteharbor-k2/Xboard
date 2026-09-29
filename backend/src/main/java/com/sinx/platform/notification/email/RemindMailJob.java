package com.sinx.platform.notification.email;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fires the reminder sweep once a day, standing in for the original panel's
 * scheduled {@code send:remindMail} command.
 *
 * All the logic lives in {@link RemindMailService#sendDueReminders()}; this
 * class only decides when it runs, so the sweep itself is testable without
 * waiting for the cron. The 11:30 slot is the original deployment's habit,
 * and the zone is pinned because a server moved between time zones must not
 * silently move the mail along with it.
 */
@Component
public class RemindMailJob {

    private final RemindMailService remindMailService;

    public RemindMailJob(RemindMailService remindMailService) {
        this.remindMailService = remindMailService;
    }

    @Scheduled(
        cron = "${sinx.remind.cron:0 30 11 * * *}",
        zone = "Asia/Shanghai"
    )
    public void sendDailyReminders() {
        remindMailService.sendDueReminders();
    }
}
