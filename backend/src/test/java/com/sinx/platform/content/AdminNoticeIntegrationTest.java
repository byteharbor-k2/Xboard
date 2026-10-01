package com.sinx.platform.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.jayway.jsonpath.JsonPath;
import com.sinx.platform.notification.email.RegistrationCodeMailSender;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Notice administration and the dashboard carousel against a real database.
 *
 * A notice hides by default; the toggle is the operator's editorial decision.
 * Every move here runs over the same HTTP and GraphQL surface the pages use,
 * including the anonymous-refusal and the empty-carousel case.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(AdminNoticeIntegrationTest.TestMailConfiguration.class)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class AdminNoticeIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES =
        new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("sinx_notice_test")
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

    @Autowired
    private RecordingRegistrationCodeMailSender registrationCodeMailSender;

    @Test
    void adminCrudRoundTrip() throws Exception {
        // An anonymous call is refused on the admin surface.
        mockMvc.perform(get("/api/v2/admin/notice/fetch"))
            .andExpect(status().is4xxClientError());

        UUID id = saveAndGetId("""
            {"title":"Welcome to SinX","content":"First line\\nSecond line","show":true}
            """);

        Map<String, Object> row = jdbcTemplate.queryForMap(
            "SELECT title, is_shown, popup, sort FROM notices WHERE id = ?::uuid",
            id.toString()
        );
        assertThat(row.get("title")).isEqualTo("Welcome to SinX");
        assertThat(row.get("is_shown")).isEqualTo(true);
        assertThat(row.get("sort")).isEqualTo(0);

        // Edit: tags arrive and the toggled-off draft keeps what was there.
        mockMvc.perform(post("/api/v2/admin/notice/save")
                .with(administrator())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"id":"%s","title":"Welcome back","content":"Updated",
                     "tags":["maintenance"],"show":false,"sort":3}
                    """.formatted(id)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.title").value("Welcome back"))
            .andExpect(jsonPath("$.data.tags[0]").value("maintenance"))
            .andExpect(jsonPath("$.data.show").value(false))
            .andExpect(jsonPath("$.data.sort").value(3));

        // The toggle flips show back on.
        mockMvc.perform(post("/api/v2/admin/notice/show")
                .with(administrator())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":\"%s\"}".formatted(id)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.show").value(true));

        // Delete removes the row; a second drop is a 404.
        mockMvc.perform(post("/api/v2/admin/notice/drop")
                .with(administrator())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":\"%s\"}".formatted(id)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").value(true));

        assertThat(jdbcTemplate.queryForList(
            "SELECT id FROM notices WHERE id = ?::uuid", id.toString())
        ).isEmpty();

        mockMvc.perform(post("/api/v2/admin/notice/drop")
                .with(administrator())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":\"%s\"}".formatted(id)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOTICE_NOT_FOUND"));
    }

    @Test
    void refusesANoticeWithoutTitleOrContent() throws Exception {
        mockMvc.perform(post("/api/v2/admin/notice/save")
                .with(administrator())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"Missing title\\nText\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("NOTICE_DEFINITION_INVALID"));

        mockMvc.perform(post("/api/v2/admin/notice/save")
                .with(administrator())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Missing body\\nText\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("NOTICE_DEFINITION_INVALID"));
    }

    @Test
    void theCarouselServesOnlyShownNoticesInCarouselOrder() throws Exception {
        UUID firstShown = seed("shown-1", true, 5);
        UUID secondShown = seed("shown-2", true, 1);
        UUID hidden = seed("hidden-one", false, 0);

        String userToken = register("notice-reader@example.com");
        MvcResult answer = mockMvc.perform(post("/gateway")
                .header("Authorization", "Bearer " + userToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"query":"{ viewerNotices { id title content publishedAt } }"}
                    """))
            .andExpect(status().isOk())
            .andReturn();

        var document = com.jayway.jsonpath.JsonPath
            .parse(answer.getResponse().getContentAsString());
        var ids = document.<java.util.List<String>>read(
            "$.data.viewerNotices[*].id");
        // sort ASC then newest id first; the hidden one never appears.
        assertThat(ids)
            .containsExactly(secondShown.toString(), firstShown.toString())
            .doesNotContain(hidden.toString());
        assertThat((String) document.read("$.data.viewerNotices[0].title"))
            .isEqualTo("shown-2");
        assertThat(document.<String>read("$.data.viewerNotices[0].publishedAt"))
            .isNotEmpty();
    }

    @Test
    void anonymousViewerQueriesAreRefused() throws Exception {
        mockMvc.perform(post("/gateway")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"{ viewerNotices { id title } }\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.viewerNotices").doesNotExist())
            .andExpect(jsonPath("$.errors[0]").exists());
    }

    @Test
    void adminSortAppliesListPositions() throws Exception {
        UUID first = saveAndGetId("""
            {"title":"Sort landscape","content":"body text"}
            """);
        UUID second = saveAndGetId("""
            {"title":"Sort flower","content":"body text"}
            """);

        mockMvc.perform(post("/api/v2/admin/notice/sort")
                .with(administrator())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"ids":["%s","%s"]}
                    """.formatted(first, second)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").value(true));

        assertThat(jdbcTemplate.queryForObject(
            "SELECT sort FROM notices WHERE id = ?::uuid",
            Integer.class,
            first.toString()
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT sort FROM notices WHERE id = ?::uuid",
            Integer.class,
            second.toString()
        )).isEqualTo(2);
    }

    @Test
    void withNothingPublishedTheCarouselAnswerIsEmpty() throws Exception {
        // Other tests' notices share this database; the empty case needs a
        // table with nothing shown, so it starts from none.
        jdbcTemplate.update("DELETE FROM notices");
        String userToken = register("notice-empty@example.com");

        mockMvc.perform(post("/gateway")
                .header("Authorization", "Bearer " + userToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"query\":\"{ viewerNotices { id title content popup publishedAt } }\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.viewerNotices").isEmpty());
    }

    private UUID saveAndGetId(String body) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v2/admin/notice/save")
                .with(administrator())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").isNotEmpty())
            .andReturn();
        String id = JsonPath.read(
            result.getResponse().getContentAsString(),
            "$.data.id"
        );
        return UUID.fromString(id);
    }

    private UUID seed(String title, boolean shown, int sort) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v2/admin/notice/save")
                .with(administrator())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"title":"%s","content":"%s body","show":%s,"sort":%d}
                    """.formatted(title, title, shown, sort)))
            .andExpect(status().isOk())
            .andReturn();
        String id = JsonPath.read(
            result.getResponse().getContentAsString(),
            "$.data.id"
        );
        return UUID.fromString(id);
    }

    private String register(String email) throws Exception {
        mockMvc.perform(post("/session/registration/email-code")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\"}".formatted(email)))
            .andExpect(status().isAccepted());
        MvcResult registration = mockMvc.perform(post("/session/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "email":"%s",
                      "password":"notice-buyer-password",
                      "displayName":"Notice Reader",
                      "deviceLabel":"Notice Browser",
                      "emailCode":"%s"
                    }
                    """.formatted(email, registrationCodeMailSender.latestCode())))
            .andExpect(status().isCreated())
            .andReturn();
        return JsonPath.read(
            registration.getResponse().getContentAsString(),
            "$.accessToken"
        );
    }

    private RequestPostProcessor administrator() {
        return jwt().authorities(
            new SimpleGrantedAuthority("ROLE_ADMIN"),
            new SimpleGrantedAuthority("SCOPE_ADMIN")
        );
    }

    @TestConfiguration
    static class TestMailConfiguration {

        @Bean
        @Primary
        RecordingRegistrationCodeMailSender
            recordingRegistrationCodeMailSender() {
            return new RecordingRegistrationCodeMailSender();
        }
    }

    static class RecordingRegistrationCodeMailSender
        implements RegistrationCodeMailSender {

        private final AtomicReference<String> latestCode =
            new AtomicReference<>();

        @Override
        public void sendRegistrationCode(String recipient, String code) {
            latestCode.set(code);
        }

        String latestCode() {
            String code = latestCode.get();
            assertThat(code).matches("\\d{6}");
            return code;
        }
    }
}
