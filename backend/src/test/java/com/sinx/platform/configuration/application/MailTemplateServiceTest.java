package com.sinx.platform.configuration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;

import com.sinx.platform.configuration.domain.MailTemplate;
import com.sinx.platform.configuration.repository.MailTemplateRepository;
import com.sinx.platform.notification.email.ConfiguredNotificationMailSender;
import com.sinx.platform.notification.email.VerificationMailProperties;
import com.sinx.platform.shared.web.ApiProblemException;

class MailTemplateServiceTest {

    private static final Clock CLOCK = Clock.fixed(
        Instant.parse("2026-08-08T00:00:00Z"),
        ZoneOffset.UTC
    );

    private final MailTemplateRepository repository = mock(
        MailTemplateRepository.class
    );
    private final PlatformConfigurationService configuration = mock(
        PlatformConfigurationService.class
    );
    private final ConfiguredNotificationMailSender mail = mock(
        ConfiguredNotificationMailSender.class
    );
    private final Map<String, MailTemplate> stored = new LinkedHashMap<>();
    private MailTemplateService service;

    @BeforeEach
    void setUp() {
        when(configuration.appName()).thenReturn("SinX Cloud");
        when(configuration.appUrl())
            .thenReturn(Optional.of("https://panel.example.com"));
        when(repository.findById(anyString())).thenAnswer(invocation ->
            Optional.ofNullable(stored.get(invocation.getArgument(0)))
        );
        when(repository.save(any(MailTemplate.class))).thenAnswer(invocation -> {
            MailTemplate template = invocation.getArgument(0);
            stored.put(template.name(), template);
            return template;
        });
        Mockito.doAnswer(invocation -> {
            stored.remove(invocation.getArgument(0));
            return null;
        }).when(repository).deleteById(anyString());
        when(repository.findAll()).thenAnswer(invocation ->
            List.copyOf(stored.values())
        );
        service = new MailTemplateService(
            repository,
            configuration,
            mail,
            new VerificationMailProperties(
                "http://localhost:5173",
                "no-reply@dev.sinx.it.com"
            ),
            CLOCK
        );
    }

    @Test
    void listReportsEveryCatalogTemplateWithItsOverrideState() {
        List<MailTemplateService.Summary> summaries = service.list();

        assertThat(summaries).extracting(MailTemplateService.Summary::name)
            .containsExactly(
                "verify",
                "notify",
                "remindExpire",
                "remindTraffic",
                "mailLogin"
            );
        assertThat(summaries)
            .allSatisfy(summary -> {
                assertThat(summary.customized()).isFalse();
                assertThat(summary.subject()).isNull();
                assertThat(summary.updatedAt()).isNull();
            });
        assertThat(summaries.get(0).label()).isEqualTo("邮箱验证码");

        service.save("verify", "My subject", "Body {{code}}");
        MailTemplateService.Summary verify = service.list()
            .stream()
            .filter(summary -> summary.name().equals("verify"))
            .findFirst()
            .orElseThrow();

        assertThat(verify.customized()).isTrue();
        assertThat(verify.subject()).isEqualTo("My subject");
        assertThat(verify.updatedAt()).isEqualTo(
            Instant.parse("2026-08-08T00:00:00Z").getEpochSecond()
        );
        assertThat(service.list()
            .stream()
            .filter(summary -> summary.name().equals("notify"))
            .findFirst()
            .orElseThrow()
            .customized()).isFalse();
    }

    @Test
    void savedOverrideReadsBackThroughDetail() {
        service.save(
            "verify",
            "Custom {{name}} code",
            "Subject: {{code}} for {{url}}"
        );

        MailTemplateService.Detail detail = service.detail("verify");

        assertThat(detail.name()).isEqualTo("verify");
        assertThat(detail.label()).isEqualTo("邮箱验证码");
        assertThat(detail.customized()).isTrue();
        assertThat(detail.subject()).isEqualTo("Custom {{name}} code");
        assertThat(detail.content()).isEqualTo("Subject: {{code}} for {{url}}");
        assertThat(detail.requiredVars()).containsExactly("code");
        assertThat(detail.optionalVars()).containsExactly("name", "url");
    }

    @Test
    void detailWithoutOverrideServesTheBundledDefault() {
        MailTemplateService.Detail detail = service.detail("mailLogin");

        assertThat(detail.customized()).isFalse();
        assertThat(detail.requiredVars()).containsExactly("link");
        assertThat(detail.subject()).isEqualTo("SinX Cloud - 邮件登录");
        assertThat(detail.content())
            .contains("{{link}}")
            .contains("登录确认");
    }

    @Test
    void resetFallsBackToTheBundledDefault() {
        service.save("verify", "Custom subject", "Custom {{code}}");
        assertThat(service.detail("verify").customized()).isTrue();

        service.reset("verify");

        MailTemplateService.Detail detail = service.detail("verify");
        assertThat(detail.customized()).isFalse();
        assertThat(detail.subject()).isEqualTo("SinX Cloud - 邮箱验证码");
        assertThat(detail.content())
            .doesNotContain("Custom")
            .contains("{{code}}");
        assertThat(service.list()
            .stream()
            .filter(summary -> summary.name().equals("verify"))
            .findFirst()
            .orElseThrow()
            .customized()).isFalse();
    }

    @Test
    void saveRejectsContentMissingARequiredPlaceholder() {
        assertThatThrownBy(() ->
            service.save("verify", "Subject", "No placeholder here")
        ).isInstanceOfSatisfying(ApiProblemException.class, exception -> {
            assertThat(exception.getStatus())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(exception.getCode())
                .isEqualTo("MAIL_TEMPLATE_CONTENT_INVALID");
            assertThat(exception.getMessage())
                .isEqualTo("缺少必要占位符: {{code}}");
        });
        assertThat(stored).isEmpty();

        // mailLogin's required placeholder is the login link, and a template
        // with it passes.
        service.save("mailLogin", "Login", "Use {{link}}");
        assertThat(stored).containsKey("mailLogin");
    }

    @Test
    void saveRejectsAnEmptyOrOversizedSubject() {
        assertThatThrownBy(() ->
            service.save("verify", "", "Body {{code}}")
        ).isInstanceOfSatisfying(ApiProblemException.class, exception -> {
            assertThat(exception.getStatus())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(exception.getMessage()).isEqualTo("邮件主题不能为空");
        });
        assertThatThrownBy(() ->
            service.save("verify", "x".repeat(256), "Body {{code}}")
        ).isInstanceOfSatisfying(ApiProblemException.class, exception ->
            assertThat(exception.getMessage())
                .isEqualTo("邮件主题不能超过 255 个字符")
        );
        assertThat(stored).isEmpty();
    }

    @Test
    void unknownTemplatesAreRejectedWithNotFound() {
        for (String name : List.of("nope", "", "VERIFY")) {
            assertThatThrownBy(() -> service.detail(name))
                .isInstanceOfSatisfying(ApiProblemException.class,
                    exception -> {
                        assertThat(exception.getStatus())
                            .isEqualTo(HttpStatus.NOT_FOUND);
                        assertThat(exception.getCode())
                            .isEqualTo("MAIL_TEMPLATE_NOT_FOUND");
                        assertThat(exception.getMessage())
                            .isEqualTo("模板不存在");
                    });
            assertThatThrownBy(() ->
                service.save(name, "s", "c")
            ).isInstanceOf(ApiProblemException.class);
            assertThatThrownBy(() -> service.reset(name))
                .isInstanceOf(ApiProblemException.class);
            assertThatThrownBy(() -> service.sendTest(name, "a@b.c"))
                .isInstanceOf(ApiProblemException.class);
        }
    }

    @Test
    void testSendsTheRenderedOverride() {
        service.save(
            "verify",
            "{{name}} code for you",
            "Your code is <b>{{code}}</b>. Go to {{url}}."
        );

        service.sendTest("verify", "admin@example.com");

        verify(mail).sendHtml(
            "admin@example.com",
            "SinX Cloud code for you",
            "Your code is <b>123456</b>. Go to https://panel.example.com."
        );
    }

    @Test
    void testUsesTheBundledDefaultWhenNoOverrideIsStored() {
        service.sendTest("notify", "admin@example.com");

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(mail).sendHtml(
            Mockito.eq("admin@example.com"),
            subject.capture(),
            body.capture()
        );
        assertThat(subject.getValue()).isEqualTo("SinX Cloud - 通知测试");
        assertThat(body.getValue())
            .doesNotContain("{{name}}")
            .doesNotContain("{{content}}")
            .doesNotContain("{{url}}")
            .contains("SinX Cloud")
            .contains("这是一封测试通知邮件。")
            .contains("https://panel.example.com");
    }

    @Test
    void testFallsBackToTheTestSubjectWhenTheStoredOneRendersBlank() {
        service.save("verify", "{{missing|}}", "Body {{code}}");

        service.sendTest("verify", "admin@example.com");

        verify(mail).sendHtml(
            Mockito.eq("admin@example.com"),
            Mockito.eq("SinX Cloud - 验证码测试"),
            Mockito.anyString()
        );
    }

    @Test
    void testEscapesSubstitutedValuesButNotContent() {
        service.save(
            "notify",
            "Subject",
            "Body: {{content}} and {{name}}."
        );
        // name comes from the site setting; a hostile one must not inject.
        when(configuration.appName()).thenReturn("<script>SinX</script>");

        service.sendTest("notify", "admin@example.com");

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(mail).sendHtml(
            Mockito.eq("admin@example.com"),
            Mockito.anyString(),
            body.capture()
        );
        assertThat(body.getValue())
            .contains("这是一封测试通知邮件。")
            .contains("&lt;script&gt;SinX&lt;/script&gt;");
    }

    @Test
    void testReportsSenderFailureAsAProblem() {
        org.mockito.Mockito.doThrow(
            new IllegalStateException("smtp refused")
        ).when(mail).sendHtml(anyString(), anyString(), anyString());

        assertThatThrownBy(() ->
            service.sendTest("verify", "admin@example.com")
        ).isInstanceOfSatisfying(ApiProblemException.class, exception -> {
            assertThat(exception.getStatus())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(exception.getMessage()).isEqualTo("发送失败: smtp refused");
        });
    }

    @Test
    void renderPlaceholdersKeepsMissingAndHonoursFallbacks() {
        // a resolves, b is empty (no fallback, kept), c is missing (kept),
        // d falls back, e has an empty fallback (substituted with nothing).
        Map<String, String> vars = Map.of("a", "1", "b", "");
        String rendered = MailTemplateService.renderPlaceholders(
            "[{{a}} {{b}} {{c}} {{d|dflt}} {{e|}}]",
            vars
        );

        assertThat(rendered).isEqualTo("[1 {{b}} {{c}} dflt ]");
    }
}
