package com.sinx.platform.configuration.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.annotation.JsonProperty;

import com.sinx.platform.configuration.domain.MailTemplate;
import com.sinx.platform.configuration.repository.MailTemplateRepository;
import com.sinx.platform.notification.email.ConfiguredNotificationMailSender;
import com.sinx.platform.notification.email.VerificationMailProperties;
import com.sinx.platform.shared.web.ApiProblemException;

/**
 * Mail template management behind the original panel's
 * {@code /api/v2/admin/mail/template} endpoints.
 *
 * A stored row is an override for one bundled template; the catalog and the
 * bundled content ship in the jar, so reading a template always works,
 * deleting the override restores the default, and a fresh install needs no
 * seeding. Subject and content use the original's {@code {{placeholder}}}
 * syntax, rendered the same way its {@code MailService::renderPlaceholders}
 * did, including the {@code {{name|fallback}}} form.
 */
@Service
@Transactional(readOnly = true)
public class MailTemplateService {

    /** The original's placeholder syntax: {@code {{name}}} or {@code {{name|fallback}}}. */
    private static final Pattern PLACEHOLDER = Pattern.compile(
        "\\{\\{\\s*([a-zA-Z0-9_.-]+)(?:\\|([^}]*))?\\s*\\}\\}"
    );
    private static final int SUBJECT_MAXIMUM_LENGTH = 255;

    private final MailTemplateRepository templates;
    private final PlatformConfigurationService configuration;
    private final ConfiguredNotificationMailSender mail;
    private final VerificationMailProperties mailProperties;
    private final Clock clock;

    public MailTemplateService(
        MailTemplateRepository templates,
        PlatformConfigurationService configuration,
        ConfiguredNotificationMailSender mail,
        VerificationMailProperties mailProperties,
        Clock clock
    ) {
        this.templates = templates;
        this.configuration = configuration;
        this.mail = mail;
        this.mailProperties = mailProperties;
        this.clock = clock;
    }

    /**
     * The catalog with each entry's override state, the shape the template
     * picker renders from: {@code subject} and {@code updated_at} come from
     * the stored row when one exists, otherwise null.
     */
    public List<Summary> list() {
        Map<String, MailTemplate> stored = new LinkedHashMap<>();
        for (MailTemplate template : templates.findAll()) {
            stored.put(template.name(), template);
        }
        List<Summary> summaries = new ArrayList<>();
        for (MailTemplateCatalog.Definition definition
            : MailTemplateCatalog.all()) {
            MailTemplate override = stored.get(definition.name());
            summaries.add(new Summary(
                definition.name(),
                definition.label(),
                override != null,
                override == null ? null : override.subject(),
                override == null
                    ? null
                    : override.updatedAt().getEpochSecond()
            ));
        }
        return summaries;
    }

    public Detail detail(String name) {
        MailTemplateCatalog.Definition definition = require(name);
        MailTemplate override = templates.findById(name).orElse(null);
        return new Detail(
            definition.name(),
            definition.label(),
            definition.requiredVars(),
            definition.optionalVars(),
            override != null,
            override == null ? defaultSubject(definition) : override.subject(),
            override == null ? definition.defaultContent() : override.content()
        );
    }

    @Transactional
    public void save(
        String name,
        String subject,
        String content
    ) {
        MailTemplateCatalog.Definition definition = require(name);
        if (subject == null || subject.isEmpty()) {
            throw invalidSubject("邮件主题不能为空");
        }
        if (subject.length() > SUBJECT_MAXIMUM_LENGTH) {
            throw invalidSubject(
                "邮件主题不能超过 " + SUBJECT_MAXIMUM_LENGTH + " 个字符"
            );
        }
        if (content == null || content.isEmpty()) {
            throw invalidContent("模板内容不能为空");
        }

        // The original's guard: a template missing the placeholders the sender
        // fills in would ship a broken mail, so it is refused at save time
        // while the administrator can still see what they pasted.
        List<String> missing = new ArrayList<>();
        for (String variable : definition.requiredVars()) {
            if (!content.contains("{{" + variable + "}}")) {
                missing.add("缺少必要占位符: {{" + variable + "}}");
            }
        }
        if (!missing.isEmpty()) {
            throw invalidContent(String.join("; ", missing));
        }

        Instant now = Instant.now(clock);
        MailTemplate template = templates.findById(name)
            .orElseGet(() -> MailTemplate.create(
                definition.name(),
                subject,
                content,
                now
            ));
        template.update(subject, content, now);
        templates.save(template);
    }

    /**
     * Delete the override so the template serves the bundled default again.
     */
    @Transactional
    public void reset(String name) {
        require(name);
        templates.deleteById(name);
    }

    /**
     * Send a test of the template in its current shape (override or default)
     * to {@code recipient}, which the controller resolves to the requesting
     * administrator when the request carries no address.
     */
    public void sendTest(String name, String recipient) {
        MailTemplateCatalog.Definition definition = require(name);
        MailTemplate override = templates.findById(name).orElse(null);
        Map<String, String> vars = testVars(definition);

        // The original sent the template's own subject when a custom one was
        // stored (rendered with the test values, so an administrator sees a
        // broken subject in the test mail), falling back to the test subject
        // otherwise.
        String subject = override == null
            ? ""
            : renderPlaceholders(override.subject(), vars);
        if (subject.isBlank()) {
            subject = testSubject(definition);
        }
        String content = override == null
            ? definition.defaultContent()
            : override.content();

        try {
            mail.sendHtml(recipient, subject, renderPlaceholders(content, vars));
        } catch (ApiProblemException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ApiProblemException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "MAIL_TEMPLATE_TEST_FAILED",
                "发送失败: " + exception.getMessage()
            );
        }
    }

    private String defaultSubject(MailTemplateCatalog.Definition definition) {
        return configuration.appName() + " - " + definition.defaultSubjectSuffix();
    }

    private String testSubject(MailTemplateCatalog.Definition definition) {
        return configuration.appName() + " - " + definition.testSubjectSuffix();
    }

    private Map<String, String> testVars(
        MailTemplateCatalog.Definition definition
    ) {
        // The original's test values, per template.
        String url = configuration.appUrl()
            .orElse(mailProperties.publicBaseUrl());
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("name", escapeHtml(configuration.appName()));
        vars.put("url", escapeHtml(url));
        switch (definition.name()) {
            case "verify" -> vars.put("code", "123456");
            case "notify" ->
                // Left unescaped on purpose: the original treated content as
                // admin-authored HTML, not as text to escape.
                vars.put("content", "这是一封测试通知邮件。");
            case "mailLogin" -> vars.put(
                "link",
                escapeHtml(url + "/login?token=test-token")
            );
            default -> {
            }
        }
        return vars;
    }

    private MailTemplateCatalog.Definition require(String name) {
        return MailTemplateCatalog.byName(name)
            .orElseThrow(() -> new ApiProblemException(
                HttpStatus.NOT_FOUND,
                "MAIL_TEMPLATE_NOT_FOUND",
                "模板不存在"
            ));
    }

    private ApiProblemException invalidSubject(String message) {
        return new ApiProblemException(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "MAIL_TEMPLATE_SUBJECT_INVALID",
            message
        );
    }

    private ApiProblemException invalidContent(String message) {
        return new ApiProblemException(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "MAIL_TEMPLATE_CONTENT_INVALID",
            message
        );
    }

    /**
     * Substitute {@code {{name}}} and {@code {{name|fallback}}} placeholders,
     * port of the original's regex renderer: a value that is absent or empty
     * yields the fallback when there is one and keeps the placeholder text
     * when there is not.
     */
    static String renderPlaceholders(
        String template,
        Map<String, String> vars
    ) {
        if (template == null || template.isEmpty() || vars.isEmpty()) {
            return template;
        }
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder rendered = new StringBuilder();
        while (matcher.find()) {
            String value = vars.get(matcher.group(1));
            String fallback = matcher.group(2) == null
                ? null
                : matcher.group(2).trim();
            String replacement;
            if (value == null || value.isEmpty()) {
                replacement = fallback != null
                    ? fallback
                    : matcher.group();
            } else {
                replacement = value;
            }
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }

    /**
     * Placeholder values reach admin-authored HTML, so the original escaped
     * everything it substituted (its {@code buildSafeVars}); mail content is
     * the one exception, as noted in {@link #testVars}.
     */
    private String escapeHtml(String value) {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
    }

    public record Summary(
        String name,
        String label,
        boolean customized,
        String subject,
        @JsonProperty("updated_at") Long updatedAt
    ) {
    }

    public record Detail(
        String name,
        String label,
        @JsonProperty("required_vars") List<String> requiredVars,
        @JsonProperty("optional_vars") List<String> optionalVars,
        boolean customized,
        String subject,
        String content
    ) {
    }
}
