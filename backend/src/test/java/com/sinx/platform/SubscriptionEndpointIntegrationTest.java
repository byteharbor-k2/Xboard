package com.sinx.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Fetching a subscription the way a client does.
 *
 * This is the only route in the panel that a session cannot open: the credential
 * is the URL, and everything a customer's client is ever given comes back from
 * here. So the test drives it the way a client does - no Authorization header,
 * a User-Agent and a couple of query parameters - and checks the three things
 * that decide whether the answer is usable: the body is the config for the
 * format that was asked for, the traffic header matches the entitlement, and a
 * credential that should no longer work does not.
 *
 * The nodes and the entitlement are written straight into the database, because
 * what is under test is the read path. How a row got there - an order being paid
 * for - is {@link PaymentCheckoutIntegrationTest}'s subject.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(SubscriptionEndpointIntegrationTest.TestMailConfiguration.class)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class SubscriptionEndpointIntegrationTest {

    /** The expiry the seeded entitlements carry, and the epoch it prints as. */
    private static final Instant EXPIRES_AT = Instant.parse("2027-01-01T00:00:00Z");
    private static final long EXPIRES_AT_EPOCH = EXPIRES_AT.getEpochSecond();
    private static final long TRANSFER_LIMIT = 1024L * 1024 * 1024;
    private static final String SITE_URL = "http://localhost:5173";

    @Container
    static final PostgreSQLContainer POSTGRES =
        new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("sinx_subscription_test")
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

    // ------------------------------------------------------------------
    // The body, per format
    // ------------------------------------------------------------------

    /**
     * A client the panel has never heard of gets the link list, and the traffic
     * header all three formats carry.
     */
    @Test
    void anUnknownClientGetsTheLinkListAndItsTraffic() throws Exception {
        Subscriber subscriber = subscribe("sub-generic@example.com");

        MvcResult result = fetch(subscriber.token())
            .andExpect(status().isOk())
            .andReturn();

        assertThat(result.getResponse().getContentType()).startsWith("text/plain");
        assertThat(result.getResponse().getHeader("subscription-userinfo"))
            .isEqualTo(
                "upload=1024; download=2048; total=" + TRANSFER_LIMIT
                    + "; expire=" + EXPIRES_AT_EPOCH
            );

        // Base64 of one link per node, CRLF separated. Both of this account's
        // nodes are protocols the generic format can carry.
        List<String> links = decodeLinks(result);
        assertThat(links).hasSize(2);
        assertThat(links.get(0)).startsWith("vmess://");
        assertThat(links.get(1)).startsWith("vless://");

        // The node in a group this account is not in is not in the answer.
        assertThat(String.join("\n", links)).doesNotContain("新加坡 01");
    }

    @Test
    void theNarrowClashFlagLeavesOutWhatThoseClientsCannotParse()
        throws Exception {
        seedSetting("site.app_url", SITE_URL);
        Subscriber subscriber = subscribe("sub-clash@example.com");

        MvcResult result = fetch(
            subscriber.token(),
            request -> request.param("flag", "clash")
        )
            .andExpect(status().isOk())
            .andReturn();

        String body = bodyOf(result);
        assertThat(result.getResponse().getContentType()).startsWith("text/yaml");
        // The narrow set has no vless proxy at all, so that node is not offered
        // - a config that will not load is worse than one node short.
        assertThat(body).contains("香港 01")
            .doesNotContain("香港 02")
            .doesNotContain("新加坡 01");

        // The subscription's own host is routed direct, and the three headers
        // the Clash family adds are present.
        assertThat(body).contains("DOMAIN,sub.example.com,DIRECT");
        assertThat(result.getResponse().getHeader("profile-update-interval"))
            .isEqualTo("24");
        assertThat(result.getResponse().getHeader("profile-web-page-url"))
            .isEqualTo(SITE_URL);
        assertThat(result.getResponse().getHeader("content-disposition"))
            .isEqualTo("attachment;filename*=UTF-8''SinX%20Cloud");
    }

    /**
     * A mihomo client is told apart from a plain one by its name alone, and gets
     * the protocols its renderer can express.
     */
    @Test
    void aMihomoClientIsRecognisedByItsUserAgent() throws Exception {
        Subscriber subscriber = subscribe("sub-verge@example.com");

        MvcResult result = fetch(subscriber.token(), request ->
            request.header("User-Agent", "clash-verge/v1.7.7"))
            .andExpect(status().isOk())
            .andReturn();

        String body = bodyOf(result);
        assertThat(body).contains("香港 01")
            .contains("香港 02")
            .doesNotContain("新加坡 01");
    }

    @Test
    void aSingBoxClientGetsJsonWithItsTitleHeader() throws Exception {
        Subscriber subscriber = subscribe("sub-singbox@example.com");

        MvcResult result = fetch(subscriber.token(), request ->
            request.header("User-Agent", "sing-box/1.10.0"))
            .andExpect(status().isOk())
            .andReturn();

        assertThat(result.getResponse().getContentType())
            .startsWith("application/json");
        assertThat(result.getResponse().getHeader("profile-title"))
            .isEqualTo(
                "base64:" + Base64.getEncoder().encodeToString(
                    "SinX Cloud".getBytes(StandardCharsets.UTF_8)
                )
            );

        String body = bodyOf(result);
        assertThat(body).contains("香港 01")
            .contains("香港 02")
            .doesNotContain("新加坡 01");
    }

    /**
     * Stash reads the same document as the other Clash clients and the same
     * node set as mihomo, so its output is checked for the second and not the
     * first.
     */
    @Test
    void theStashFlagIsServedTheClashShapeWithTheWideNodeSet() throws Exception {
        seedSetting("site.app_url", SITE_URL);
        Subscriber subscriber = subscribe("sub-stash@example.com");

        MvcResult result = fetch(
            subscriber.token(),
            request -> request.param("flag", "stash")
        )
            .andExpect(status().isOk())
            .andReturn();

        assertThat(result.getResponse().getContentType()).startsWith("text/yaml");
        String body = bodyOf(result);
        assertThat(body).contains("香港 01")
            .contains("香港 02")
            .doesNotContain("新加坡 01");
        assertThat(result.getResponse().getHeader("profile-web-page-url"))
            .isEqualTo(SITE_URL);
    }

    /**
     * Surge is recognised by its name, and writes proxy lines rather than a
     * document. Neither Surge nor Surfboard has ever implemented vless, so that
     * node is absent rather than written as a line the client refuses to load.
     */
    @Test
    void aSurgeClientIsRecognisedByItsUserAgent() throws Exception {
        Subscriber subscriber = subscribe("sub-surge@example.com");

        MvcResult result = fetch(subscriber.token(), request ->
            request.header("User-Agent", "Surge/5.8.0"))
            .andExpect(status().isOk())
            .andReturn();

        assertThat(result.getResponse().getContentType())
            .startsWith("application/octet-stream");
        assertThat(result.getResponse().getHeader("content-disposition"))
            .isEqualTo("attachment;filename*=UTF-8''SinX%20Cloud.conf");

        String body = bodyOf(result);
        assertThat(body).contains("香港 01 = vmess,hk1.example.com,443,")
            .doesNotContain("香港 02")
            .doesNotContain("新加坡 01");
        // The panel above the list is filled in from the account's own traffic.
        assertThat(body).contains("title=SinX Cloud订阅信息, content=上传流量：")
            .doesNotContain("$subscribe_info");
    }

    /**
     * The two are told apart by more than their name: Surfboard writes its
     * lines without the spaces around the separator, and it speaks four
     * protocols where Surge speaks seven. The socks node is the one that shows
     * the difference - a protocol Surge has and Surfboard does not.
     */
    @Test
    void theSurfboardFlagWritesItsOwnGrammarAndProtocolSet() throws Exception {
        Subscriber subscriber = subscribe("sub-surfboard@example.com");
        Long groupId = jdbcTemplate.queryForObject(
            "SELECT id FROM node_access_groups WHERE name = ?",
            Long.class,
            "group-sub-surfboard@example.com"
        );
        seedNode("socks-node", "socks", "s.example.com", groupId, 4);

        MvcResult result = fetch(
            subscriber.token(),
            request -> request.param("flag", "surfboard")
        )
            .andExpect(status().isOk())
            .andReturn();

        String body = bodyOf(result);
        assertThat(body).contains("香港 01=vmess,hk1.example.com,443,")
            .doesNotContain("香港 01 = vmess")
            .doesNotContain("香港 02")
            .doesNotContain("socks-node")
            .doesNotContain("新加坡 01");
    }

    /**
     * The client may name the protocols it wants. A version of a client that
     * cannot speak vless has no other way to ask for a config it can load.
     */
    @Test
    void theProtocolListNarrowsWhatIsOffered() throws Exception {
        Subscriber subscriber = subscribe("sub-types@example.com");

        MvcResult result = fetch(subscriber.token(), request ->
            request.param("types", "vless"))
            .andExpect(status().isOk())
            .andReturn();

        List<String> links = decodeLinks(result);
        assertThat(links).hasSize(1);
        assertThat(links.getFirst()).startsWith("vless://");
    }

    /**
     * The keyword filter matches a node name anywhere in it, which is the rule
     * that keeps "香港" usable without anchoring.
     */
    @Test
    void theKeywordFilterSelectsByNodeName() throws Exception {
        Subscriber subscriber = subscribe("sub-filter@example.com");

        assertThat(decodeLinks(
            fetch(subscriber.token(), request -> request.param("filter", "香港"))
                .andReturn()
        )).hasSize(2);
        assertThat(decodeLinks(
            fetch(subscriber.token(), request -> request.param("filter", "tokyo"))
                .andReturn()
        )).isEmpty();
    }

    // ------------------------------------------------------------------
    // Refusals
    // ------------------------------------------------------------------

    /**
     * A wrong credential and a retired one are the same answer, so that guessing
     * cannot be told from having once known.
     */
    @Test
    void nothingIsSaidAboutWhyACredentialWasRefused() throws Exception {
        MvcResult unknown = fetch("a-token-that-was-never-issued")
            .andExpect(status().isNotFound())
            .andReturn();

        assertThat(unknown.getResponse().getContentAsByteArray()).isEmpty();
        assertThat(unknown.getResponse().getContentType()).startsWith("text/plain");
    }

    /**
     * An account that cannot use the service is refused, not served an empty
     * config: the node control plane pushes no nodes for it, so a config would
     * be a list of things that do not connect.
     */
    @Test
    void anAccountThatCannotUseTheServiceIsRefused() throws Exception {
        Subscriber suspended = subscribe("sub-suspended@example.com");
        jdbcTemplate.update(
            "UPDATE users SET status = 'SUSPENDED' WHERE id = ?::uuid",
            suspended.userId().toString()
        );
        MvcResult refused = fetch(suspended.token())
            .andExpect(status().isForbidden())
            .andReturn();
        assertThat(refused.getResponse().getContentAsByteArray()).isEmpty();

        // An account with no entitlement at all is refused the same way.
        String bare = register("sub-no-entitlement@example.com");
        fetch(tokenOf(viewerSubscriptionUrl(bare))).andExpect(status().isForbidden());

        // And one whose traffic is spent. Its entitlement is otherwise live:
        // the limit is what is exhausted, not the expiry.
        Subscriber spent = subscribe("sub-exhausted@example.com");
        jdbcTemplate.update(
            """
            UPDATE subscription_entitlements
            SET downloaded_bytes = transfer_limit_bytes
            WHERE user_id = ?::uuid
            """,
            spent.userId().toString()
        );
        fetch(spent.token()).andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------
    // Rotation
    // ------------------------------------------------------------------

    @Test
    void rotatingTheCredentialRetiresTheOldAddressImmediately() throws Exception {
        Subscriber subscriber = subscribe("sub-rotate@example.com");
        fetch(subscriber.token()).andExpect(status().isOk());

        MvcResult rotated = mockMvc.perform(graphQl(
                subscriber.accessToken(),
                """
                {"query":"mutation { rotateSubscriptionCredential }"}
                """
            ))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andReturn();
        String newUrl = JsonPath.read(
            rotated.getResponse().getContentAsString(),
            "$.data.rotateSubscriptionCredential"
        );
        String newToken = tokenOf(newUrl);

        assertThat(newToken).isNotEqualTo(subscriber.token());
        assertThat(newUrl).startsWith("http://localhost:5173/sub/");

        fetch(subscriber.token()).andExpect(status().isNotFound());
        fetch(newToken).andExpect(status().isOk());
    }

    // ------------------------------------------------------------------
    // The templates an administrator edits
    // ------------------------------------------------------------------

    /**
     * Every template the settings section offers is one an adapter actually
     * reads, and a stored one replaces the bundled copy. A key that is offered
     * but not read would be a box an administrator fills in and never sees take
     * effect.
     */
    @Test
    void aStoredTemplateReplacesTheBundledOneAndBadOnesAreRefused()
        throws Exception {
        Subscriber subscriber = subscribe("sub-template@example.com");
        String custom = """
            proxies: []
            proxy-groups: []
            rules:
              - MATCH,DIRECT
            """;

        try {
            mockMvc.perform(get("/api/v2/admin/config/fetch")
                    .with(administrator())
                    .param("key", "subscribe_template"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.subscribe_template.subscribe_template_clash")
                    .isNotEmpty())
                .andExpect(jsonPath("$.data.subscribe_template.subscribe_template_clashmeta")
                    .isNotEmpty())
                .andExpect(jsonPath("$.data.subscribe_template.subscribe_template_singbox")
                    .isNotEmpty())
                .andExpect(jsonPath("$.data.subscribe_template.subscribe_template_stash")
                    .isNotEmpty())
                .andExpect(jsonPath("$.data.subscribe_template.subscribe_template_surge")
                    .isNotEmpty())
                .andExpect(jsonPath("$.data.subscribe_template.subscribe_template_surfboard")
                    .isNotEmpty());

            saveTemplate("subscribe_template_clash", custom)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(true));

            // The stored template is the shape of the config; the nodes are
            // written into it either way. What changes is which rules are in
            // force, so that is what is asserted.
            assertThat(servedTo(subscriber, "clash"))
                .contains("MATCH,DIRECT")
                .doesNotContain("MATCH,SinX Cloud")
                .doesNotContain("GEOIP,CN,DIRECT")
                .contains("香港 01");

            // A template that does not parse replaces every user's config at
            // once, so it is refused while the administrator can still see it.
            saveTemplate("subscribe_template_clash", "proxies: [:: not yaml")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SUBSCRIBE_TEMPLATE_INVALID"));

            // As is one that parses but is missing a key the renderer writes
            // into - it would store fine and then fail on every request.
            saveTemplate(
                "subscribe_template_singbox",
                "{\"log\":{\"level\":\"info\"}}"
            )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SUBSCRIBE_TEMPLATE_INVALID"));

            // The text templates are checked by placeholder rather than by
            // parsing, since there is nothing in them to parse.
            saveTemplate("subscribe_template_surge", """
                # an administrator's own header
                [Proxy]
                $proxies
                [Proxy Group]
                Proxy = select, $proxy_group
                """).andExpect(status().isOk());
            assertThat(servedTo(subscriber, "surge"))
                .contains("# an administrator's own header")
                .contains("香港 01 = vmess");

            saveTemplate("subscribe_template_surge", "# nothing to substitute")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SUBSCRIBE_TEMPLATE_INVALID"));

            // Neither refusal disturbed what was already stored.
            assertThat(servedTo(subscriber, "clash")).contains("MATCH,DIRECT");
            assertThat(servedTo(subscriber, "sing-box")).contains("香港 01");
            assertThat(servedTo(subscriber, "surge"))
                .contains("# an administrator's own header");
        } finally {
            clearTemplates();
        }
    }

    /**
     * Clearing a template restores the bundled copy rather than emptying every
     * user's config, which is what makes it safe for the settings page to show
     * the effective template in the box in the first place.
     */
    @Test
    void clearingATemplateRestoresTheBundledOne() throws Exception {
        Subscriber subscriber = subscribe("sub-restore@example.com");

        try {
            saveTemplate("subscribe_template_clash", """
                proxies: []
                proxy-groups: []
                rules:
                  - MATCH,DIRECT
                """).andExpect(status().isOk());
            assertThat(servedTo(subscriber, "clash"))
                .contains("MATCH,DIRECT")
                .doesNotContain("MATCH,SinX Cloud");

            saveTemplate("subscribe_template_clash", "").andExpect(status().isOk());

            assertThat(servedTo(subscriber, "clash"))
                .contains("MATCH,SinX Cloud")
                .doesNotContain("MATCH,DIRECT")
                .contains("香港 01");
        } finally {
            clearTemplates();
        }
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /**
     * An account with a live entitlement and two nodes - one the narrow Clash
     * clients can carry and one they cannot - plus a third node in a group this
     * account is not in, which must never appear.
     */
    private Subscriber subscribe(String email) throws Exception {
        String accessToken = register(email);
        String url = viewerSubscriptionUrl(accessToken);
        UUID userId = jdbcTemplate.queryForObject(
            "SELECT id FROM users WHERE LOWER(email) = LOWER(?)",
            UUID.class,
            email
        );
        long groupId = seedGroup("group-" + email);
        UUID planId = seedPlan("Starter", groupId);
        seedEntitlement(userId, planId);

        seedNode("香港 01", "vmess", "hk1.example.com", groupId, 1);
        seedNode("香港 02", "vless", "hk2.example.com", groupId, 2);
        seedNode("新加坡 01", "vmess", "sg1.example.com", groupId + 5000, 3);

        return new Subscriber(accessToken, userId, tokenOf(url), url);
    }

    private long seedGroup(String name) {
        return jdbcTemplate.queryForObject(
            """
            INSERT INTO node_access_groups (name, created_at, updated_at)
            VALUES (?, now(), now())
            RETURNING id
            """,
            Long.class,
            name
        );
    }

    private UUID seedPlan(String name, long groupId) {
        UUID planId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbcTemplate.update(
            """
            INSERT INTO service_plans (
                id, name, description, transfer_limit_bytes,
                speed_limit_mbps, reset_policy,
                capacity_limit, published, sellable, renewable,
                sort_order, server_group_id, created_at, updated_at
            ) VALUES (
                ?::uuid, ?, ?, ?, ?, ?, NULL, TRUE, TRUE, TRUE,
                1, ?, ?, ?
            )
            """,
            planId.toString(),
            name,
            name + " plan",
            TRANSFER_LIMIT,
            200,
            "MONTHLY_FROM_ACTIVATION",
            groupId,
            Timestamp.from(now),
            Timestamp.from(now)
        );
        return planId;
    }

    private void seedEntitlement(UUID userId, UUID planId) {
        Instant now = Instant.now();
        jdbcTemplate.update(
            """
            INSERT INTO subscription_entitlements (
                id, user_id, plan_id, plan_name, transfer_limit_bytes,
                uploaded_bytes, downloaded_bytes, speed_limit_mbps,
                reset_policy, starts_at, expires_at, next_reset_at,
                created_at, updated_at
            ) VALUES (
                ?::uuid, ?::uuid, ?::uuid, 'Starter', ?,
                1024, 2048, 200,
                'MONTHLY_FROM_ACTIVATION', ?, ?, NULL,
                ?, ?
            )
            """,
            UUID.randomUUID().toString(),
            userId.toString(),
            planId.toString(),
            TRANSFER_LIMIT,
            Timestamp.from(now.minusSeconds(60)),
            Timestamp.from(EXPIRES_AT),
            Timestamp.from(now),
            Timestamp.from(now)
        );
    }

    /**
     * A node as the admin API would have written it. The group list is the
     * audience, so a node whose list does not name the account's group is a node
     * it is not entitled to.
     */
    private void seedNode(
        String name,
        String type,
        String host,
        long groupId,
        int sortOrder
    ) {
        Instant now = Instant.now();
        jdbcTemplate.update(
            """
            INSERT INTO proxy_nodes (
                type, group_ids, route_ids, name, rate, rate_time_enable,
                rate_time_ranges, transfer_enable, upload_bytes, download_bytes,
                tags, host, port, server_port, protocol_settings,
                custom_outbounds, custom_routes, is_show, is_enabled,
                sort_order, created_at, updated_at
            ) VALUES (
                ?, ?, '[]', ?, 1, FALSE,
                '[]', 0, 0, 0,
                '["hk"]', ?, 443, 443, '{"network":"tcp","tls":0}',
                '[]', '[]', TRUE, TRUE,
                ?, ?, ?
            )
            """,
            type,
            "[" + groupId + "]",
            name,
            host,
            sortOrder,
            Timestamp.from(now),
            Timestamp.from(now)
        );
    }

    /** Writes a site setting the way the admin API would, replacing any value. */
    private void seedSetting(String key, String value) {
        jdbcTemplate.update(
            """
            INSERT INTO platform_settings (setting_key, setting_value, updated_at)
            VALUES (?, ?, now())
            ON CONFLICT (setting_key)
            DO UPDATE SET setting_value = EXCLUDED.setting_value, updated_at = now()
            """,
            key,
            value
        );
    }

    // ------------------------------------------------------------------
    // Driving the endpoint
    // ------------------------------------------------------------------

    /** A request as a client makes it: no Authorization header at all. */
    private ResultActions fetch(String token) throws Exception {
        return fetch(token, request -> {
        });
    }

    private ResultActions fetch(String token, Consumer<MockHttpServletRequestBuilder> customise)
        throws Exception {
        MockHttpServletRequestBuilder request = get("/sub/{token}", token)
            .header("Host", "sub.example.com");
        customise.accept(request);
        return mockMvc.perform(request);
    }

    /** The body a client of this format would be served. */
    private String servedTo(Subscriber subscriber, String flag) throws Exception {
        return bodyOf(
            fetch(subscriber.token(), request -> request.param("flag", flag))
                .andExpect(status().isOk())
                .andReturn()
        );
    }

    /**
     * The response bytes, read as UTF-8.
     *
     * The content type is served without a charset, exactly as the original
     * does - a client reading a Clash config assumes UTF-8, and a charset
     * parameter is one more thing between the panel and that assumption. It
     * does mean {@code getContentAsString()} decodes node names as Latin-1, so
     * the bytes are read here instead.
     */
    private static String bodyOf(MvcResult result) {
        return new String(
            result.getResponse().getContentAsByteArray(),
            StandardCharsets.UTF_8
        );
    }

    private List<String> decodeLinks(MvcResult result) {
        String decoded = new String(
            Base64.getDecoder().decode(bodyOf(result)),
            StandardCharsets.UTF_8
        );
        return java.util.Arrays.stream(decoded.split("\r\n"))
            .filter(line -> !line.isBlank())
            .toList();
    }

    private String tokenOf(String subscriptionUrl) {
        return subscriptionUrl.substring(subscriptionUrl.lastIndexOf('/') + 1);
    }

    private String viewerSubscriptionUrl(String accessToken) throws Exception {
        MvcResult result = mockMvc.perform(graphQl(accessToken, """
                {"query":"{ viewerSubscriptionUrl }"}
                """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andReturn();
        return JsonPath.read(
            result.getResponse().getContentAsString(),
            "$.data.viewerSubscriptionUrl"
        );
    }

    private ResultActions saveTemplate(String key, String value) throws Exception {
        return mockMvc.perform(post("/api/v2/admin/config/save")
            .with(administrator())
            .param("key", "subscribe_template")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"%s\":%s}".formatted(key, jsonString(value))));
    }

    /** Blank is how an administrator restores the bundled default. */
    private void clearTemplates() throws Exception {
        for (String key : List.of(
            "subscribe_template_clash",
            "subscribe_template_clashmeta",
            "subscribe_template_singbox",
            "subscribe_template_stash",
            "subscribe_template_surge",
            "subscribe_template_surfboard"
        )) {
            saveTemplate(key, "").andExpect(status().isOk());
        }
    }

    private static String jsonString(String value) {
        StringBuilder escaped = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> escaped.append(character);
            }
        }
        return escaped.append('"').toString();
    }

    private MockHttpServletRequestBuilder graphQl(
        String accessToken,
        String body
    ) {
        return post("/gateway")
            .header("Authorization", "Bearer " + accessToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body);
    }

    private RequestPostProcessor administrator() {
        return jwt().authorities(
            new SimpleGrantedAuthority("ROLE_ADMIN"),
            new SimpleGrantedAuthority("SCOPE_ADMIN")
        );
    }

    private String register(String email) throws Exception {
        mockMvc.perform(post("/session/registration/email-code")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s"}
                    """.formatted(email)))
            .andExpect(status().isAccepted());
        MvcResult registration = mockMvc.perform(post("/session/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "email":"%s",
                      "password":"subscriber-password",
                      "displayName":"Subscriber",
                      "deviceLabel":"Subscriber Browser",
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

    private record Subscriber(
        String accessToken,
        UUID userId,
        String token,
        String url
    ) {
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
