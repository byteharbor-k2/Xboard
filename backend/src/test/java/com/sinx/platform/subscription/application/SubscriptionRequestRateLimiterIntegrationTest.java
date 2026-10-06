package com.sinx.platform.subscription.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** Exercises the limiter against Redis, including its atomic concurrent path. */
@Testcontainers
class SubscriptionRequestRateLimiterIntegrationTest {

    @Container
    static final GenericContainer<?> REDIS =
        new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;
    private static SubscriptionRequestRateLimiter limiter;

    @BeforeAll
    static void connectToRedis() {
        connectionFactory = new LettuceConnectionFactory(
            REDIS.getHost(),
            REDIS.getMappedPort(6379)
        );
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
        limiter = new SubscriptionRequestRateLimiter(redis);
    }

    @AfterAll
    static void closeRedisConnection() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void allowsTwoRejectsTheThirdAndReopensAfterTheRollingSecond() throws Exception {
        UUID userId = UUID.randomUUID();

        assertThat(limiter.tryAcquire(userId)).isTrue();
        assertThat(limiter.tryAcquire(userId)).isTrue();
        assertThat(limiter.tryAcquire(userId)).isFalse();

        // Poll across the boundary rather than assuming a particular scheduler
        // pause; Redis TIME, not this test host's wall clock, decides expiry.
        long deadline = System.nanoTime() + 2_000_000_000L;
        boolean accepted = false;
        while (!accepted && System.nanoTime() < deadline) {
            Thread.sleep(10);
            accepted = limiter.tryAcquire(userId);
        }
        assertThat(accepted).isTrue();
    }

    @Test
    void removesEventsExactlyAtTheOneSecondBoundary() {
        UUID userId = UUID.randomUUID();
        String key = "subscription:request-window:" + userId;
        DefaultRedisScript<Long> seedBoundaryEvents = new DefaultRedisScript<>("""
            local time = redis.call('TIME')
            local now = time[1] * 1000000 + time[2]
            local boundary = now - 1000000
            redis.call('ZADD', KEYS[1], boundary, 'boundary-a', boundary, 'boundary-b')
            return boundary
            """, Long.class);

        redis.execute(seedBoundaryEvents, List.of(key));

        assertThat(limiter.tryAcquire(userId)).isTrue();
        assertThat(limiter.tryAcquire(userId)).isTrue();
    }

    @Test
    void concurrentRequestsNeverAdmitMoreThanTwoForOneAccount() throws Exception {
        UUID userId = UUID.randomUUID();
        int callers = 20;
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(callers)) {
            List<Future<Boolean>> attempts = new ArrayList<>();
            for (int index = 0; index < callers; index++) {
                attempts.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return limiter.tryAcquire(userId);
                }));
            }
            ready.await();
            start.countDown();

            int accepted = 0;
            for (Future<Boolean> attempt : attempts) {
                if (attempt.get()) {
                    accepted++;
                }
            }
            assertThat(accepted).isEqualTo(2);
        }
    }

    @Test
    void differentAccountsHaveIndependentWindows() {
        UUID firstUser = UUID.randomUUID();
        UUID secondUser = UUID.randomUUID();

        assertThat(limiter.tryAcquire(firstUser)).isTrue();
        assertThat(limiter.tryAcquire(firstUser)).isTrue();
        assertThat(limiter.tryAcquire(firstUser)).isFalse();

        assertThat(limiter.tryAcquire(secondUser)).isTrue();
        assertThat(limiter.tryAcquire(secondUser)).isTrue();
        assertThat(limiter.tryAcquire(secondUser)).isFalse();
    }
}
