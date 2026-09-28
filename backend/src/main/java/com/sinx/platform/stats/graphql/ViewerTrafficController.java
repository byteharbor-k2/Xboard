package com.sinx.platform.stats.graphql;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Controller;

import com.sinx.platform.stats.application.ViewerTrafficView;
import com.sinx.platform.stats.repository.TrafficDailyRepository;

/**
 * The account's own traffic ledger, exposed through the user gateway.
 *
 * The {@code @PreAuthorize} guard keeps anonymous requests out of the whole
 * viewer field group - a complaint that turns on "what exactly was billed on
 * that day" is answered by these rows, and they name one account only.
 */
@Controller
public class ViewerTrafficController {

    /** 31 days: a full trailing month, the retention the page promises. */
    private static final int RETENTION_DAYS = 31;
    private static final int ROW_LIMIT = 1_000;

    private final TrafficDailyRepository trafficDaily;
    private final Clock clock;

    public ViewerTrafficController(
        TrafficDailyRepository trafficDaily,
        Clock clock
    ) {
        this.trafficDaily = trafficDaily;
        this.clock = clock;
    }

    @QueryMapping
    @PreAuthorize("hasRole('USER') and hasAuthority('SCOPE_USER')")
    List<ViewerTrafficView> viewerTrafficDaily(
        @AuthenticationPrincipal Jwt jwt
    ) {
        UUID userId = UUID.fromString(jwt.getSubject());
        LocalDate from = LocalDate.now(clock).minusDays(RETENTION_DAYS);
        return trafficDaily.trafficOfViewer(userId, from, ROW_LIMIT).stream()
            .map(row -> new ViewerTrafficView(
                row.getDay().toString(),
                row.getNodeName(),
                row.getUploadBytes(),
                row.getDownloadBytes(),
                row.getBilledBytes()
            ))
            .toList();
    }
}
