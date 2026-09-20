package com.sinx.platform.configuration.web;

import java.util.List;
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

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sinx.platform.configuration.application.MailTemplateService;
import com.sinx.platform.configuration.application.MailTemplateService.Detail;
import com.sinx.platform.configuration.application.MailTemplateService.Summary;
import com.sinx.platform.identity.repository.UserAccountRepository;
import com.sinx.platform.shared.web.ApiProblemException;

/**
 * Mail template administration on the Xboard-compatible surface.
 *
 * The field names and the {@code {data: ...}} envelope are exactly what the
 * admin panel's template tab already speaks. Access is already decided by the
 * security configuration, which requires both the admin role and the admin
 * token scope for everything under {@code /api/v2/admin}, so these methods
 * carry no checks of their own.
 */
@RestController
@RequestMapping("/api/v2/admin/mail/template")
public class AdminMailTemplateController {

    private final MailTemplateService mailTemplates;
    private final UserAccountRepository users;

    public AdminMailTemplateController(
        MailTemplateService mailTemplates,
        UserAccountRepository users
    ) {
        this.mailTemplates = mailTemplates;
        this.users = users;
    }

    @GetMapping("/list")
    XboardResponse<List<Summary>> list() {
        return XboardResponse.of(mailTemplates.list());
    }

    @GetMapping("/get")
    XboardResponse<Detail> get(@RequestParam String name) {
        return XboardResponse.of(mailTemplates.detail(name));
    }

    @PostMapping("/save")
    XboardResponse<Boolean> save(@RequestBody SaveRequest request) {
        mailTemplates.save(request.name(), request.subject(), request.content());
        return XboardResponse.of(true);
    }

    @PostMapping("/reset")
    XboardResponse<Boolean> reset(@RequestBody NameRequest request) {
        mailTemplates.reset(request.name());
        return XboardResponse.of(true);
    }

    @PostMapping("/test")
    XboardResponse<Boolean> test(
        @AuthenticationPrincipal Jwt jwt,
        @RequestBody TestRequest request
    ) {
        // The original sent the test to the requesting administrator when the
        // request carried no address; the panel pre-fills that box, but a
        // stripped-down client might omit it.
        String recipient = request.email() == null || request.email().isBlank()
            ? users.findById(UUID.fromString(jwt.getSubject()))
                .orElseThrow(() -> new ApiProblemException(
                    HttpStatus.NOT_FOUND,
                    "ADMIN_NOT_FOUND",
                    "The administrator account no longer exists"
                ))
                .getEmail()
            : request.email().trim();
        mailTemplates.sendTest(request.name(), recipient);
        return XboardResponse.of(true);
    }

    record SaveRequest(String name, String subject, String content) {
    }

    record NameRequest(String name) {
    }

    record TestRequest(String name, String email) {
    }

    public record XboardResponse<T>(T data) {
        static <T> XboardResponse<T> of(T data) {
            return new XboardResponse<>(data);
        }
    }
}
