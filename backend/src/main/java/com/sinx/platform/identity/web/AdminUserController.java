package com.sinx.platform.identity.web;

import java.time.Instant;
import java.util.UUID;

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
                request.banned(),
                request.planId(),
                request.transferLimitBytes(),
                request.expiresAt() == null
                    ? null
                    : Instant.ofEpochSecond(request.expiresAt())
            )
        ));
    }

    @PostMapping("/ban")
    XboardResponse<AdminUserView> ban(@RequestBody BanRequest request) {
        return XboardResponse.of(
            adminUsers.setBanned(request.id(), request.banned())
        );
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

    record IdRequest(UUID id) {
    }

    record BanRequest(UUID id, boolean banned) {
    }

    /**
     * Every field is optional; one that is absent is left as it was, so a form
     * editing a single thing cannot blank the rest.
     *
     * {@code expiresAt} is epoch seconds, matching the original's admin shape.
     */
    record UpdateRequest(
        UUID id,
        String email,
        String password,
        String remarks,
        @JsonProperty("speed_limit_mbps") Integer speedLimitMbps,
        Boolean banned,
        @JsonProperty("plan_id") UUID planId,
        @JsonProperty("transfer_limit_bytes") Long transferLimitBytes,
        @JsonProperty("expires_at") Long expiresAt
    ) {
    }

    record XboardResponse<T>(T data) {
        static <T> XboardResponse<T> of(T data) {
            return new XboardResponse<>(data);
        }
    }
}
