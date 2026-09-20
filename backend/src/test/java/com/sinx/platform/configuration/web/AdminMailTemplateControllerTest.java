package com.sinx.platform.configuration.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The admin panel's template tab speaks exactly this shape, so the round trip
 * is asserted end to end against a migrated database: list the catalog, read
 * the bundled default, save an override and read it back, send a test of it,
 * reset back to the default, and see the rejections the original made
 * (unknown template 404, missing required placeholder 422).
 *
 * Delivery falls back to the log sink in the test profile, so the test-mail
 * call needs no SMTP.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class AdminMailTemplateControllerTest {

    @Container
    static final PostgreSQLContainer POSTGRES =
        new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("sinx_mail_template_test")
            .withUsername("sinx")
            .withPassword("sinx_test");

    @Container
    static final GenericContainer<?> REDIS =
        new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void managesMailTemplatesThroughTheXboardAdminSurface() throws Exception {
        var administrator = jwt().authorities(
            new SimpleGrantedAuthority("ROLE_ADMIN"),
            new SimpleGrantedAuthority("SCOPE_ADMIN")
        );
        try {
            // No credentials: /api/v2/admin stays admin-only.
            mockMvc.perform(get("/api/v2/admin/mail/template/list"))
                .andExpect(status().isUnauthorized());

            // The catalog as shipped: all five, none customized yet.
            MvcResult listResult = mockMvc.perform(
                    get("/api/v2/admin/mail/template/list")
                        .with(administrator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(5))
                .andExpect(jsonPath("$.data[0].name").value("verify"))
                .andExpect(jsonPath("$.data[0].label").value("邮箱验证码"))
                .andExpect(jsonPath("$.data[4].name").value("mailLogin"))
                .andReturn();
            List<Map<String, Object>> catalog = JsonPath.read(
                listResult.getResponse().getContentAsString(),
                "$.data"
            );
            Map<String, Object> verifyEntry = catalog.stream()
                .filter(entry -> "verify".equals(entry.get("name")))
                .findFirst()
                .orElseThrow();
            assertThat((Boolean) verifyEntry.get("customized")).isFalse();
            assertThat(verifyEntry.get("subject")).isNull();
            assertThat(verifyEntry.get("updated_at")).isNull();

            // The bundled default: label and placeholders from the catalog,
            // subject from the configured site name.
            MvcResult beforeSave = mockMvc.perform(
                    get("/api/v2/admin/mail/template/get")
                        .param("name", "verify")
                        .with(administrator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("verify"))
                .andExpect(jsonPath("$.data.label").value("邮箱验证码"))
                .andExpect(jsonPath("$.data.customized").value(false))
                .andExpect(jsonPath("$.data.required_vars[0]").value("code"))
                .andExpect(jsonPath("$.data.optional_vars.length()").value(2))
                .andExpect(jsonPath("$.data.content").value(
                    org.hamcrest.Matchers.containsString("{{code}}")
                ))
                .andReturn();
            String defaultSubject = JsonPath.read(
                beforeSave.getResponse().getContentAsString(),
                "$.data.subject"
            );
            String defaultContent = JsonPath.read(
                beforeSave.getResponse().getContentAsString(),
                "$.data.content"
            );
            assertThat(defaultSubject).isNotBlank();
            assertThat(defaultContent).contains("{{code}}");

            // Save an override, then read it back through get and list.
            mockMvc.perform(post("/api/v2/admin/mail/template/save")
                    .with(administrator)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "name":"verify",
                          "subject":"Custom {{name}} verification",
                          "content":"Code <b>{{code}}</b> from {{url}}."
                        }
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(true));

            mockMvc.perform(get("/api/v2/admin/mail/template/get")
                    .param("name", "verify")
                    .with(administrator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customized").value(true))
                .andExpect(jsonPath("$.data.subject")
                    .value("Custom {{name}} verification"))
                .andExpect(jsonPath("$.data.content")
                    .value("Code <b>{{code}}</b> from {{url}}."));

            MvcResult savedList = mockMvc.perform(
                    get("/api/v2/admin/mail/template/list")
                        .with(administrator))
                .andExpect(status().isOk())
                .andReturn();
            List<Map<String, Object>> savedCatalog = JsonPath.read(
                savedList.getResponse().getContentAsString(),
                "$.data"
            );
            Map<String, Object> savedVerify = savedCatalog.stream()
                .filter(entry -> "verify".equals(entry.get("name")))
                .findFirst()
                .orElseThrow();
            assertThat((Boolean) savedVerify.get("customized")).isTrue();
            assertThat(savedVerify.get("subject"))
                .isEqualTo("Custom {{name}} verification");
            assertThat(savedVerify.get("updated_at")).isInstanceOf(Number.class);
            assertThat(((Number) savedVerify.get("updated_at")).longValue())
                .isGreaterThan(0);

            // A test of the customized template renders with test values.
            mockMvc.perform(post("/api/v2/admin/mail/template/test")
                    .with(administrator)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"name":"verify","email":"template-test@example.com"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(true));

            // Reset back to the bundled default.
            mockMvc.perform(post("/api/v2/admin/mail/template/reset")
                    .with(administrator)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"name":"verify"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(true));

            mockMvc.perform(get("/api/v2/admin/mail/template/get")
                    .param("name", "verify")
                    .with(administrator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customized").value(false))
                .andExpect(jsonPath("$.data.subject").value(defaultSubject))
                .andExpect(jsonPath("$.data.content").value(defaultContent));

            // The original's rejections.
            mockMvc.perform(post("/api/v2/admin/mail/template/save")
                    .with(administrator)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"name":"nope","subject":"s","content":"c"}
                        """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MAIL_TEMPLATE_NOT_FOUND"));

            mockMvc.perform(post("/api/v2/admin/mail/template/save")
                    .with(administrator)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"name":"verify","subject":"s","content":"no placeholder"}
                        """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code")
                    .value("MAIL_TEMPLATE_CONTENT_INVALID"));
        } finally {
            jdbcTemplate.update(
                "DELETE FROM mail_templates WHERE name = 'verify'"
            );
        }
    }
}
