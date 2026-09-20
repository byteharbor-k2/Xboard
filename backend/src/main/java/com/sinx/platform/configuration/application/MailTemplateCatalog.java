package com.sinx.platform.configuration.application;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.core.io.ClassPathResource;

/**
 * The fixed set of mail templates an administrator may edit.
 *
 * Port of the original panel's {@code MailTemplate::TEMPLATES}: the names,
 * labels, and required/optional placeholder lists are the original's, so an
 * administrator who knows that panel already knows these. The bundled default
 * content is the original's blade views converted to the panel's
 * {@code {{placeholder}}} syntax, packaged under {@code /mail/default}.
 */
public final class MailTemplateCatalog {

    /**
     * One template the admin panel can edit and the mail sender can render.
     *
     * {@code defaultSubjectSuffix} and {@code testSubjectSuffix} are appended
     * to the configured site name, the way the original built its default
     * and test subjects.
     */
    public record Definition(
        String name,
        String label,
        List<String> requiredVars,
        List<String> optionalVars,
        String defaultSubjectSuffix,
        String testSubjectSuffix,
        String defaultContent
    ) {
    }

    private static final Map<String, Definition> BY_NAME;
    private static final List<Definition> ALL;

    static {
        Map<String, Definition> byName = new LinkedHashMap<>();
        byName.put(
            "verify",
            definition(
                "verify",
                "邮箱验证码",
                List.of("code"),
                List.of("name", "url"),
                "邮箱验证码",
                "验证码测试"
            )
        );
        byName.put(
            "notify",
            definition(
                "notify",
                "站点通知",
                List.of("content"),
                List.of("name", "url"),
                "站点通知",
                "通知测试"
            )
        );
        byName.put(
            "remindExpire",
            definition(
                "remindExpire",
                "到期提醒",
                List.of(),
                List.of("name", "url"),
                "服务即将到期",
                "到期提醒测试"
            )
        );
        byName.put(
            "remindTraffic",
            definition(
                "remindTraffic",
                "流量提醒",
                List.of(),
                List.of("name", "url"),
                "流量使用提醒",
                "流量提醒测试"
            )
        );
        byName.put(
            "mailLogin",
            definition(
                "mailLogin",
                "邮件登录",
                List.of("link"),
                List.of("name", "url"),
                "邮件登录",
                "登录链接测试"
            )
        );
        BY_NAME = Map.copyOf(byName);
        ALL = List.copyOf(byName.values());
    }

    private MailTemplateCatalog() {
    }

    private static Definition definition(
        String name,
        String label,
        List<String> requiredVars,
        List<String> optionalVars,
        String defaultSubjectSuffix,
        String testSubjectSuffix
    ) {
        return new Definition(
            name,
            label,
            requiredVars,
            optionalVars,
            defaultSubjectSuffix,
            testSubjectSuffix,
            bundled(name)
        );
    }

    /** The templates in the order the original panel lists them. */
    public static List<Definition> all() {
        return ALL;
    }

    public static Optional<Definition> byName(String name) {
        return Optional.ofNullable(BY_NAME.get(name));
    }

    private static String bundled(String name) {
        ClassPathResource resource = new ClassPathResource(
            "/mail/default/" + name + ".html",
            MailTemplateCatalog.class.getClassLoader()
        );
        try (InputStream stream = resource.getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            // The default is packaged with the application, so a missing one
            // is a build that should never have been produced rather than
            // anything an administrator can fix.
            throw new UncheckedIOException(
                "Missing bundled mail template /mail/default/" + name + ".html",
                exception
            );
        }
    }
}
