package com.sinx.platform.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
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
 * Knowledge-base administration and the help centre against a real database.
 *
 * An article hides by default; the category grouping and language selection
 * are the original panel's own shape, every move here running over the same
 * HTTP and GraphQL surface the pages use.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(AdminKnowledgeIntegrationTest.TestMailConfiguration.class)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class AdminKnowledgeIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES =
        new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("sinx_knowledge_test")
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
        mockMvc.perform(get("/api/v2/admin/knowledge/fetch"))
            .andExpect(status().is4xxClientError());

        UUID id = save("""
            {
              "category":"Client setup",
              "language":"zh-CN",
              "title":"Import a subscription",
              "body":"Step one.\\nStep two.",
              "show":true
            }
            """);

        Map<String, Object> row = jdbcTemplate.queryForMap(
            """
            SELECT category, language, title, is_shown
            FROM knowledge_articles WHERE id = ?::uuid
            """,
            id.toString()
        );
        assertThat(row.get("category")).isEqualTo("Client setup");
        assertThat(row.get("language")).isEqualTo("zh-CN");
        assertThat(row.get("is_shown")).isEqualTo(true);

        // Edit keeps shown state and applies the new title.
        mockMvc.perform(post("/api/v2/admin/knowledge/save")
                .with(administrator())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"id":"%s","category":"Client setup","language":"en-US",
                     "title":"Import your subscription","body":"Revised.","show":false}
                    """.formatted(id)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.title").value("Import your subscription"))
            .andExpect(jsonPath("$.data.language").value("en-US"))
            .andExpect(jsonPath("$.data.show").value(false));

        // fetch?id answers the full row with the body, like the original.
        mockMvc.perform(get("/api/v2/admin/knowledge/fetch?id=" + id)
                .with(administrator()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.body").value("Revised."));

        // Toggle flips show back on.
        mockMvc.perform(post("/api/v2/admin/knowledge/show")
                .with(administrator())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":\"%s\"}".formatted(id)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.show").value(true));

        // Delete removes the row; a second drop is a 404.
        mockMvc.perform(post("/api/v2/admin/knowledge/drop")
                .with(administrator())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":\"%s\"}".formatted(id)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").value(true));

        mockMvc.perform(post("/api/v2/admin/knowledge/drop")
                .with(administrator())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":\"%s\"}".formatted(id)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("KNOWLEDGE_NOT_FOUND"));
    }

    @Test
    void refusesAnArticleMissingAnyRequiredField() throws Exception {
        String bodyWithoutCategory = """
            {"language":"zh-CN","title":"No category","body":"Text"}
            """;
        mockMvc.perform(post("/api/v2/admin/knowledge/save")
                .with(administrator())
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyWithoutCategory))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("KNOWLEDGE_DEFINITION_INVALID"));
    }

    @Test
    void theHelpCentreListsShownArticlesGroupedByCategoryWithDetail()
        throws Exception {
        UUID importId = seed(
            "Import",
            "Import guide\\nwith details",
            "Client setup",
            "zh-CN",
            true
        );
        UUID troubleshootId = seed(
            "Troubleshoot",
            "Troubleshooting steps",
            "FAQ",
            "zh-CN",
            true
        );
        seed("Hidden draft", "Draft body", "Client setup", "zh-CN", false);
        seed("English only", "English body", "Client setup", "en-US", true);

        String userToken = register("knowledge-reader@example.com");
        MvcResult answer = mockMvc.perform(post("/gateway")
                .header("Authorization", "Bearer " + userToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"query":"query Knowledge($language: String) { viewerKnowledge(language: $language) { category articles { id title } } }","variables":{"language":"zh-CN"}}
                    """))
            .andExpect(status().isOk())
            .andReturn();

        var document = JsonPath
            .parse(answer.getResponse().getContentAsString());
        List<String> categories =
            document.<List<String>>read("$.data.viewerKnowledge[*].category");
        // Both rows carry sort 0, so the group order is not defined by them;
        // what must hold is the grouping and that non-zh / hidden rows are
        // absent entirely.
        assertThat(categories).contains("Client setup", "FAQ");
        assertThat(categories.size()).isEqualTo(2);

        List<String> titles = document.<List<String>>read(
            "$.data.viewerKnowledge[*].articles[*].title"
        );
        assertThat(titles)
            .contains("Import", "Troubleshoot")
            .doesNotContain("Hidden draft", "English only");
    }

    @Test
    void theKeywordFilterReturnsOnlyMatchingArticles() throws Exception {
        seedWithJdbcTemplate("Proton tutorial", "How to use Proton", true);
        seedWithJdbcTemplate("Clash Verge tutorial", "How to use Clash", true);
        seedWithJdbcTemplate("Hidden tip", "Proton secret", false);

        String debug = register("knowledge-search@example.com");

        mockMvc.perform(post("/gateway")
                .header("Authorization", "Bearer " + debug)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"query":"query Knowledge($language: String, $keyword: String) { viewerKnowledge(language: $language, keyword: $keyword) { category articles { id } } }","variables":{"language":"en-US","keyword":"Proton"}}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.viewerKnowledge[0].articles.length()")
                .value(1));
    }

    @Test
    void theArticleDetailServesOnlyShownArticles() throws Exception {
        UUID shownId = seedWithJdbcTemplate(
            "Detail article", "Body line one\\nBody line two", true);
        UUID draftId = seedWithJdbcTemplate("Draft article", "Draft", false);

        String userToken = register("knowledge-detail@example.com");

        mockMvc.perform(post("/gateway")
                .header("Authorization", "Bearer " + userToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"query":"query Article($id: ID!) { viewerKnowledgeArticle(id: $id) { id title body updatedAt } }","variables":{"id":"%s"}}
                    """.formatted(shownId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.viewerKnowledgeArticle.title")
                .value("Detail article"))
            .andExpect(jsonPath("$.data.viewerKnowledgeArticle.body")
                .value("Body line one\\nBody line two"));

        // A hidden article answers null, and an unknown id too, so the
        // reader's open-article step never 500s on a stale link.
        mockMvc.perform(post("/gateway")
                .header("Authorization", "Bearer " + userToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"query\":\"query Article($id: ID!) { viewerKnowledgeArticle(id: $id) { id } }\",\"variables\":{\"id\":\"%s\"}}"
                        .formatted(draftId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.viewerKnowledgeArticle")
                .value(nullValue()));

        mockMvc.perform(post("/gateway")
                .header("Authorization", "Bearer " + userToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"query\":\"query Article($id: ID!) { viewerKnowledgeArticle(id: $id) { id } }\",\"variables\":{\"id\":\"%s\"}}"
                        .formatted(UUID.randomUUID())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.viewerKnowledgeArticle")
                .value(nullValue()));

        mockMvc.perform(post("/gateway")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"query\":\"query Article($id: ID!) { viewerKnowledgeArticle(id: $id) { id } }\",\"variables\":{\"id\":\"%s\"}}"
                        .formatted(shownId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.viewerKnowledgeArticle").doesNotExist())
            .andExpect(jsonPath("$.errors[0]").exists());
    }

    private UUID save(String body) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v2/admin/knowledge/save")
                .with(administrator())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andReturn();
        String id = JsonPath.read(
            result.getResponse().getContentAsString(),
            "$.data.id"
        );
        return UUID.fromString(id);
    }

    private UUID seed(
        String title,
        String body,
        String category,
        String language,
        boolean shown
    ) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v2/admin/knowledge/save")
                .with(administrator())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"category":"%s","language":"%s","title":"%s",
                     "body":"%s","show":%s}
                    """.formatted(category, language, title, body, shown)))
            .andExpect(status().isOk())
            .andReturn();
        String id = JsonPath.read(
            result.getResponse().getContentAsString(),
            "$.data.id"
        );
        return UUID.fromString(id);
    }

    /** Direct seeding keeps only what the GraphQL surface reads honest. */
    private UUID seedWithJdbcTemplate(
        String title,
        String body,
        boolean shown
    ) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            """
            INSERT INTO knowledge_articles (
                id, category, language, title, body, is_shown, sort,
                created_at, updated_at
            ) VALUES (?::uuid, 'FAQ', 'en-US', ?, ?, ?, 0, now(), now())
            """,
            id.toString(),
            title,
            body,
            shown
        );
        return id;
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
                      "password":"knowledge-buyer-password",
                      "displayName":"Knowledge Reader",
                      "deviceLabel":"Knowledge Browser",
                      "emailCode":"%s"
                    }
                    """.formatted(
                        email,
                        registrationCodeMailSender.latestCode())))
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

    private static org.hamcrest.Matcher<Object> nullValue() {
        return org.hamcrest.Matchers.nullValue();
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
