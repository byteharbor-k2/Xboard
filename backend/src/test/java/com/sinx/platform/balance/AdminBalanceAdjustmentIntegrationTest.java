package com.sinx.platform.balance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.sinx.platform.balance.application.BalanceLedgerService;
import com.sinx.platform.balance.application.ViewerBalanceService;
import com.sinx.platform.balance.domain.BalanceLogType;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** The admin's absolute balance correction is a serialized, real cash posting. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class AdminBalanceAdjustmentIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES =
        new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("sinx_admin_balance_test")
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

    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mvc;
    @Autowired private BalanceLedgerService ledger;
    @Autowired private ViewerBalanceService viewerBalances;

    @Test
    void endpointPostsSignedDeltasAndRejectsInvalidTargetsWithoutLeakingSecrets()
        throws Exception {
        User user = user("balance-target");
        adjust(user, "12345", "Courtesy credit")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.balance").value(12345));
        assertAdminLog(user, 12_345, 12_345, "Courtesy credit");

        adjust(user, "5000", null)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.balance").value(5000));
        assertAdminLog(user, -7_345, 5_000, null);

        adjust(user, "5000", "No change")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.balance").value(5000));
        assertThat(countAdminLogs(user)).isEqualTo(2);

        for (String invalid : List.of("-1", "1.2", "1e3", "9223372036854775808")) {
            adjust(user, invalid, null).andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/v2/admin/user/balance")
                .with(adminJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":\"" + user.id() + "\",\"balanceMinor\":9000}"))
            .andExpect(status().isBadRequest());
        adjust(user, "0", "x".repeat(501)).andExpect(status().isBadRequest());
        assertThat(balance(user)).isEqualTo(5_000);
        assertThat(countAdminLogs(user)).isEqualTo(2);

        var response = adjust(user, "7000", null)
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertThat(response)
            .doesNotContain("password_hash", "subscription_token", "proxy_uuid", "refreshToken");
        assertThat(viewerBalances.summary(user.id()).totalCreditsMinor()).isEqualTo("14345");
        assertThat(viewerBalances.summary(user.id()).totalDebitsMinor()).isEqualTo("7345");
        assertThat(viewerBalances.logs(user.id(), 0, 10).items().get(0).note()).isNull();
        assertThat(viewerBalances.logs(user.id(), 0, 10).items().get(2).note())
            .isEqualTo("Courtesy credit");
        mvc.perform(post("/gateway")
                .with(userJwt(user.id()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"query":"{ viewerBalanceLogs(page: 0, limit: 10) { items { type amountMinor balanceAfterMinor note } } }"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.viewerBalanceLogs.items[2].note")
                .value("Courtesy credit"));

        mvc.perform(post("/api/v2/admin/user/balance")
                .with(userJwt(user.id()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(user.id(), "9000", null)))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/v2/admin/user/balance")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(user.id(), "9000", null)))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void failedLedgerInsertRollsBackTargetBalance() throws Exception {
        User user = user("balance-rollback");
        adjust(user, "2500", null).andExpect(status().isOk());
        installFailureTrigger();
        try {
            adjust(user, "0", "Correction").andExpect(status().isInternalServerError());
        } finally {
            dropFailureTrigger();
        }
        assertThat(balance(user)).isEqualTo(2_500);
        assertThat(countAdminLogs(user)).isEqualTo(1);
    }

    @Test
    void legacyAccountSpeedDoesNotOverridePlanEntitlementOrAdminDisplay() throws Exception {
        User user = user("legacy-speed");
        mvc.perform(get("/api/v2/admin/user/getUserInfoById")
                .param("id", user.id().toString())
                .with(adminJwt()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.speed_limit_mbps").value(org.hamcrest.Matchers.nullValue()));

        mvc.perform(post("/api/v2/admin/user/update")
                .with(adminJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":\"" + user.id() + "\",\"speed_limit_mbps\":128}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.speed_limit_mbps").value(org.hamcrest.Matchers.nullValue()));
        assertThat(jdbc.queryForObject(
            "select speed_limit_mbps from users where id = ?::uuid",
            Integer.class,
            user.id().toString()
        )).isEqualTo(64);
    }

    @Test
    void adjustmentAndCommissionCreditSerializeOnTheSameAccountRow() throws Exception {
        User user = user("balance-concurrent");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> adjustment = pool.submit(() ->
                ledger.setBalanceTarget(user.id(), 10_000, "Admin correction"));
            Future<?> commission = pool.submit(() -> ledger.credit(
                user.id(), 750, BalanceLogType.COMMISSION_CREDIT,
                null, 1, Instant.now()
            ));
            adjustment.get();
            commission.get();
        } finally {
            pool.shutdownNow();
        }

        long current = balance(user);
        assertThat(current).isIn(10_000L, 10_750L);
        List<Long> afterValues = jdbc.queryForList("""
            select balance_after_minor from balance_logs
            where user_id = ?::uuid and type in ('ADMIN_ADJUSTMENT', 'COMMISSION_CREDIT')
            order by ledger_sequence
            """, Long.class, user.id().toString());
        assertThat(afterValues).hasSize(2);
        assertThat(afterValues.get(1)).isEqualTo(current);
        assertThat(afterValues.get(0)).isLessThan(afterValues.get(1));
        assertThat(jdbc.queryForObject("""
            select count(*) from commission_logs where invite_user_id = ?::uuid
            """, Long.class, user.id().toString())).isZero();
    }

    @Test
    void v35SchemaMigratesForwardWithNoteAndSignedAdjustmentConstraints() throws Exception {
        String schema = "balance_v36_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("create schema " + schema);
        }
        try {
            Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("35"))
                .load()
                .migrate();
            assertThat(columnExists(schema, "note")).isFalse();

            Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                .load()
                .migrate();

            assertThat(columnExists(schema, "note")).isTrue();
            try (Connection connection = DriverManager.getConnection(
                    POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                 var statement = connection.createStatement()) {
                statement.execute("""
                    insert into %s.users (id, email, password_hash, display_name, status,
                        subscription_token, balance_minor, created_at, updated_at)
                    values ('%s', 'migration@example.test', 'hash', 'migration', 'ACTIVE',
                        'migration-token', 0, now(), now())
                    """.formatted(schema, UUID.randomUUID()));
                statement.execute("""
                    insert into %s.balance_logs (id, user_id, type, amount_minor,
                        balance_after_minor, currency, note, created_at)
                    select gen_random_uuid(), id, 'ADMIN_ADJUSTMENT', 100, 100,
                        'CNY', 'welcome', now() from %s.users
                    """.formatted(schema, schema));
                statement.execute("""
                    insert into %s.balance_logs (id, user_id, type, amount_minor,
                        balance_after_minor, currency, created_at)
                    select gen_random_uuid(), id, 'ADMIN_ADJUSTMENT', -100, 0,
                        'CNY', now() from %s.users
                    """.formatted(schema, schema));
            }
        } finally {
            try (Connection connection = DriverManager.getConnection(
                    POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                 var statement = connection.createStatement()) {
                statement.execute("drop schema if exists " + schema + " cascade");
            }
        }
    }

    private org.springframework.test.web.servlet.ResultActions adjust(
        User user,
        String target,
        String note
    ) throws Exception {
        return mvc.perform(post("/api/v2/admin/user/balance")
            .with(adminJwt())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(user.id(), target, note)));
    }

    private String body(UUID id, String target, String note) {
        return "{\"id\":\"" + id + "\",\"balanceMinor\":\"" + target + "\",\"note\":"
            + (note == null ? "null" : "\"" + note + "\"") + "}";
    }

    private void assertAdminLog(User user, long amount, long after, String note) {
        assertThat(jdbc.queryForMap("""
            select amount_minor, balance_after_minor, note, trade_no, commission_level
            from balance_logs where user_id = ?::uuid and type = 'ADMIN_ADJUSTMENT'
            order by ledger_sequence desc limit 1
            """, user.id().toString()))
            .containsEntry("amount_minor", amount)
            .containsEntry("balance_after_minor", after)
            .containsEntry("note", note)
            .containsEntry("trade_no", null)
            .containsEntry("commission_level", null);
    }

    private User user(String label) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        String email = label + "-" + id + "@example.test";
        jdbc.update("""
            insert into users (id, email, password_hash, display_name, status,
                subscription_token, speed_limit_mbps, created_at, updated_at)
            values (?::uuid, ?, 'private-hash', ?, 'ACTIVE', ?, 64, ?, ?)
            """, id.toString(), email, label, UUID.randomUUID().toString().replace("-", ""), now, now);
        jdbc.update("insert into user_roles (user_id, role_code) values (?::uuid, 'USER')", id.toString());
        return new User(id, email);
    }

    private long balance(User user) {
        return jdbc.queryForObject("select balance_minor from users where id = ?::uuid",
            Long.class, user.id().toString());
    }

    private long countAdminLogs(User user) {
        return jdbc.queryForObject("""
            select count(*) from balance_logs where user_id = ?::uuid
              and type = 'ADMIN_ADJUSTMENT'
            """, Long.class, user.id().toString());
    }

    private boolean columnExists(String schema, String column) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.prepareStatement("""
                select exists (select 1 from information_schema.columns
                  where table_schema = ? and table_name = 'balance_logs' and column_name = ?)
                """)) {
            statement.setString(1, schema);
            statement.setString(2, column);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getBoolean(1);
            }
        }
    }

    private void installFailureTrigger() {
        jdbc.execute("""
            create function admin_balance_test_failure() returns trigger language plpgsql as $$
            begin
                if new.type = 'ADMIN_ADJUSTMENT' then raise exception 'balance log failure'; end if;
                return new;
            end $$
            """);
        jdbc.execute("""
            create trigger admin_balance_test_failure_trigger before insert on balance_logs
            for each row execute function admin_balance_test_failure()
            """);
    }

    private void dropFailureTrigger() {
        jdbc.execute("drop trigger admin_balance_test_failure_trigger on balance_logs");
        jdbc.execute("drop function admin_balance_test_failure()");
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor adminJwt() {
        return jwt().authorities(
            new SimpleGrantedAuthority("ROLE_ADMIN"),
            new SimpleGrantedAuthority("SCOPE_ADMIN")
        );
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor userJwt(UUID id) {
        return jwt().jwt(token -> token.subject(id.toString())).authorities(
            new SimpleGrantedAuthority("ROLE_USER"),
            new SimpleGrantedAuthority("SCOPE_USER")
        );
    }

    private record User(UUID id, String email) { }
}
