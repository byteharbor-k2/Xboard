package com.sinx.platform.configuration.web;

import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sinx.platform.configuration.application.PlatformConfigurationService;
import com.sinx.platform.identity.repository.UserAccountRepository;
import com.sinx.platform.notification.email.ConfiguredNotificationMailSender;
import com.sinx.platform.shared.web.ApiProblemException;

@RestController
@RequestMapping("/api/v2/admin/config")
public class AdminConfigurationController {

    private final PlatformConfigurationService configuration;
    private final UserAccountRepository users;
    private final ConfiguredNotificationMailSender mailSender;

    public AdminConfigurationController(
        PlatformConfigurationService configuration,
        UserAccountRepository users,
        ConfiguredNotificationMailSender mailSender
    ) {
        this.configuration = configuration;
        this.users = users;
        this.mailSender = mailSender;
    }

    @GetMapping("/fetch")
    XboardResponse<Map<String, Map<String, Object>>> fetch(
        @RequestParam String key
    ) {
        return new XboardResponse<>(
            Map.of(key, configuration.sectionSettings(key))
        );
    }

    @PostMapping("/save")
    XboardResponse<Boolean> save(
        @RequestParam String key,
        @RequestBody Map<String, Object> values
    ) {
        configuration.saveSectionSettings(key, values);
        return new XboardResponse<>(true);
    }

    @PostMapping("/testSendMail")
    XboardResponse<Map<String, Object>> testSendMail(
        @AuthenticationPrincipal Jwt jwt
    ) {
        String recipient = users.findById(UUID.fromString(jwt.getSubject()))
            .orElseThrow(() -> new ApiProblemException(
                HttpStatus.NOT_FOUND,
                "ADMIN_NOT_FOUND",
                "The administrator account no longer exists"
            ))
            .getEmail();
        mailSender.sendTestEmail(recipient);
        return new XboardResponse<>(
            Map.of("success", true, "recipient", recipient)
        );
    }

    public record XboardResponse<T>(T data) {
    }
}
