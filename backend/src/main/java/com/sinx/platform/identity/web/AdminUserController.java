package com.sinx.platform.identity.web;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sinx.platform.identity.application.AdminUserPage;
import com.sinx.platform.identity.application.AdminUserService;
import com.sinx.platform.identity.application.AdminUserService.AdminUserUpdate;
import com.sinx.platform.identity.application.AdminUserView;
import com.sinx.platform.identity.domain.UserStatus;

/**
 * Customer account administration on the Xboard-compatible surface.
 *
 * Access is already decided by the security configuration, which requires both
 * the admin role and the admin token scope for everything under
 * {@code /api/v2/admin}, so these methods carry no checks of their own.
 */
@RestController
@RequestMapping("/api/v2/admin/user")
public class AdminUserController {

    private final AdminUserService adminUsers;

    public AdminUserController(AdminUserService adminUsers) {
        this.adminUsers = adminUsers;
    }

    @GetMapping("/fetch")
    XboardResponse<AdminUserPage> fetch(
        @RequestParam(name = "search", required = false) String search,
        @RequestParam(name = "status", required = false) UserStatus status,
        @RequestParam(name = "page", required = false) Integer page,
        @RequestParam(name = "limit", required = false) Integer limit
    ) {
        return XboardResponse.of(adminUsers.list(search, status, page, limit));
    }

    @GetMapping("/getUserInfoById")
    XboardResponse<AdminUserView> getUserInfoById(@RequestParam UUID id) {
        return XboardResponse.of(adminUsers.detail(id));
    }

    @PostMapping("/update")
    XboardResponse<AdminUserView> update(@RequestBody UpdateRequest request) {
        return XboardResponse.of(adminUsers.update(
            request.id(),
            new AdminUserUpdate(
                request.email(),
                request.password(),
                request.remarks(),
                request.speedLimitMbps(),
                request.planId(),
                request.transferLimitBytes(),
                request.expiresAt() == null
                    ? null
                    : Instant.ofEpochSecond(request.expiresAt()),
                request.clearExpiry(),
                request.commissionType(),
                request.commissionRate(),
                request.clearCommissionRate()
            )
        ));
    }

    @PostMapping("/ban")
    XboardResponse<AdminUserView> ban(@RequestBody BanRequest request) {
        return XboardResponse.of(
            adminUsers.setBanned(request.id(), request.banned())
        );
    }

    /** Sets a user's ordinary balance to an absolute integer-cent target. */
    @PostMapping("/balance")
    XboardResponse<AdminUserView> adjustBalance(@RequestBody BalanceRequest request) {
        return XboardResponse.of(adminUsers.adjustBalance(
            request.id(), request.balanceMinor(), request.note()
        ));
    }

    /**
     * Answers with the replacement link rather than the raw token, because the
     * link is what the operator pastes back to the customer.
     */
    @PostMapping("/resetSecret")
    XboardResponse<String> resetSecret(@RequestBody IdRequest request) {
        return XboardResponse.of(
            adminUsers.resetSubscriptionToken(request.id())
        );
    }

    @PostMapping("/resetTraffic")
    XboardResponse<AdminUserView> resetTraffic(@RequestBody IdRequest request) {
        return XboardResponse.of(adminUsers.resetTraffic(request.id()));
    }

    @PostMapping("/destroy")
    XboardResponse<Boolean> destroy(@RequestBody IdRequest request) {
        adminUsers.delete(request.id());
        return XboardResponse.of(true);
    }

    /**
     * The whole customer base as a downloadable CSV.
     *
     * Deliberately not wrapped in {@code XboardResponse}: the payload is a
     * file an operator's browser saves, not data the panel's own client
     * renders, so it travels as {@code text/csv} with an attachment header
     * and the column names the fetched rows already use.
     */
    @GetMapping("/exportCsv")
    ResponseEntity<byte[]> exportCsv() {
        byte[] body = adminUsers.exportCsv().getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
            .contentType(new MediaType(MediaType.parseMediaType("text/csv"),
                StandardCharsets.UTF_8))
            .header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"users.csv\"")
            .body(body);
    }

    /**
     * Sends one mail the administrator wrote, straight to the account's
     * address. Nothing is stored, so the answer is a plain success flag.
     */
    @PostMapping("/sendMail")
    XboardResponse<Boolean> sendMail(@RequestBody SendMailRequest request) {
        adminUsers.sendMail(request.userId(), request.subject(), request.body());
        return XboardResponse.of(true);
    }

    /** Assigns or clears (a null {@code inviter_user_id}) the inviter. */
    @PostMapping("/assignInviter")
    XboardResponse<AdminUserView> assignInviter(@RequestBody AssignInviterRequest request) {
        return XboardResponse.of(
            adminUsers.assignInviter(request.userId(), request.inviterUserId())
        );
    }

    record IdRequest(UUID id) {
    }

    record BanRequest(UUID id, boolean banned) {
    }

    record BalanceRequest(UUID id, Object balanceMinor, Object note) {
    }

    record SendMailRequest(
        @JsonProperty("user_id") UUID userId,
        String subject,
        String body
    ) {
    }

    record AssignInviterRequest(
        @JsonProperty("user_id") UUID userId,
        @JsonProperty("inviter_user_id") UUID inviterUserId
    ) {
    }

    /**
     * Every field is optional; one that is absent is left as it was, so a form
     * editing a single thing cannot blank the rest.
     *
     * {@code expiresAt} is epoch seconds, matching the original's admin shape.
     * An absent {@code expiresAt} leaves the expiry untouched; clearing it to
     * make the account permanent is the explicit {@code clearExpiry} flag.
     *
     * Banning is deliberately not an update field: it is its own act on
     * {@code /ban}, because it revokes device sessions and pushes the node
     * user list, which a "harmless" form save must never do by accident.
     */
    record UpdateRequest(
        UUID id,
        String email,
        String password,
        String remarks,
        @JsonProperty("speed_limit_mbps") Integer speedLimitMbps,
        @JsonProperty("plan_id") UUID planId,
        @JsonProperty("transfer_limit_bytes") Long transferLimitBytes,
        @JsonProperty("expires_at") Long expiresAt,
        @JsonProperty("clear_expiry") Boolean clearExpiry,
        @JsonProperty("commission_type") Integer commissionType,
        @JsonProperty("commission_rate") Integer commissionRate,
        @JsonProperty("clear_commission_rate") Boolean clearCommissionRate
    ) {
    }

    record XboardResponse<T>(T data) {
        static <T> XboardResponse<T> of(T data) {
            return new XboardResponse<>(data);
        }
    }
}
