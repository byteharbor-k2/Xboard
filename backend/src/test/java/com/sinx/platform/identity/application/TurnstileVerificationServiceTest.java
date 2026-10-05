package com.sinx.platform.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.sinx.platform.configuration.application.PlatformConfigurationService;
import com.sinx.platform.shared.web.ApiProblemException;

class TurnstileVerificationServiceTest {

    private static final String SITE_KEY = "public-site-key";
    private static final String SECRET_KEY = "server-only-secret";
    private static final String VERIFY_URL =
        "https://challenges.cloudflare.com/turnstile/v0/siteverify";

    @Test
    void disabledPolicyBypassesVerificationWithoutMakingARequest() {
        TestClient client = testClient(configuration(
            new PlatformConfigurationService.TurnstilePolicy(false, null, null)
        ));

        client.service().verify(null, "192.0.2.1");

        client.server().verify();
    }

    @Test
    void enabledPolicyRejectsMissingTokenWithoutCallingCloudflare() {
        TestClient client = testClient(configuration(policy()));

        assertThatThrownBy(() -> client.service().verify(" ", null))
            .isInstanceOfSatisfying(ApiProblemException.class, exception -> {
                assertThat(exception.getCode()).isEqualTo("TURNSTILE_INVALID");
                assertThat(exception.getStatus().value()).isEqualTo(400);
            });

        client.server().verify();
    }

    @Test
    void enabledPolicyPostsSiteverifyFormAndAcceptsCloudflareSuccess() {
        TestClient client = testClient(configuration(policy()));
        client.server().expect(requestTo(VERIFY_URL))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().contentType(MediaType.APPLICATION_FORM_URLENCODED))
            .andExpect(content().string(
                org.hamcrest.Matchers.allOf(
                    org.hamcrest.Matchers.containsString("secret=" + SECRET_KEY),
                    org.hamcrest.Matchers.containsString("response=proof-token"),
                    org.hamcrest.Matchers.containsString("remoteip=192.0.2.44")
                )
            ))
            .andRespond(withSuccess(
                "{\"success\":true}",
                MediaType.APPLICATION_JSON
            ));

        client.service().verify("proof-token", "192.0.2.44");

        client.server().verify();
    }

    @Test
    void rejectsCloudflareFailureAndMapsNetworkErrorWithoutLeakingProof() {
        TestClient rejectedClient = testClient(configuration(policy()));
        rejectedClient.server().expect(requestTo(VERIFY_URL))
            .andRespond(withSuccess(
                "{\"success\":false}",
                MediaType.APPLICATION_JSON
            ));

        ApiProblemException rejected = catchProblem(() ->
            rejectedClient.service().verify("sensitive-proof", null)
        );
        assertThat(rejected.getCode()).isEqualTo("TURNSTILE_INVALID");
        assertThat(rejected.getMessage()).doesNotContain("sensitive-proof");
        rejectedClient.server().verify();

        TestClient unavailableClient = testClient(configuration(policy()));
        unavailableClient.server().expect(requestTo(VERIFY_URL))
            .andRespond(org.springframework.test.web.client.response
                .MockRestResponseCreators.withException(
                    new IOException("private transport detail")
                ));

        ApiProblemException unavailable = catchProblem(() ->
            unavailableClient.service().verify("another-sensitive-proof", null)
        );
        assertThat(unavailable.getCode()).isEqualTo("TURNSTILE_UNAVAILABLE");
        assertThat(unavailable.getMessage())
            .doesNotContain("another-sensitive-proof", "private transport detail");
        unavailableClient.server().verify();
    }

    @Test
    void enabledPolicyFailsClosedWhenEitherCloudflareKeyIsMissing() {
        for (PlatformConfigurationService.TurnstilePolicy policy : new PlatformConfigurationService.TurnstilePolicy[] {
            new PlatformConfigurationService.TurnstilePolicy(true, null, SECRET_KEY),
            new PlatformConfigurationService.TurnstilePolicy(true, SITE_KEY, null)
        }) {
            TestClient client = testClient(configuration(policy));

            assertThatThrownBy(() -> client.service().verify("proof-token", null))
                .isInstanceOfSatisfying(ApiProblemException.class, exception -> {
                    assertThat(exception.getCode())
                        .isEqualTo("TURNSTILE_NOT_CONFIGURED");
                    assertThat(exception.getStatus().value()).isEqualTo(503);
                });

            client.server().verify();
        }
    }

    private TestClient testClient(
        PlatformConfigurationService configuration
    ) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        return new TestClient(
            new TurnstileVerificationService(configuration, builder.build()),
            server
        );
    }

    private PlatformConfigurationService configuration(
        PlatformConfigurationService.TurnstilePolicy policy
    ) {
        PlatformConfigurationService configuration =
            mock(PlatformConfigurationService.class);
        when(configuration.turnstilePolicy()).thenReturn(policy);
        return configuration;
    }

    private PlatformConfigurationService.TurnstilePolicy policy() {
        return new PlatformConfigurationService.TurnstilePolicy(
            true,
            SITE_KEY,
            SECRET_KEY
        );
    }

    private ApiProblemException catchProblem(Runnable action) {
        try {
            action.run();
        } catch (ApiProblemException exception) {
            return exception;
        }
        throw new AssertionError("Expected a Turnstile problem response");
    }

    private record TestClient(
        TurnstileVerificationService service,
        MockRestServiceServer server
    ) {
    }
}
