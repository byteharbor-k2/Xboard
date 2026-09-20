package com.sinx.platform.payment.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The addresses a remote gateway cannot reach.
 *
 * The bug this guards against is real: the notify URL is built from
 * {@code payment.public-base-url}, which defaults to localhost, and a gateway
 * on the open internet cannot call localhost back. An order then waits on a
 * callback that will never arrive.
 */
class NotifyUrlReachabilityTest {

    private static final String PATH = "/api/v1/guest/payment/notify/epay/abc123";

    @ParameterizedTest
    @CsvSource({
        // Local names: the machine the gateway would be told about is this one.
        "http://localhost:5173/notify,                 localhost",
        "http://LOCALHOST/notify,                      localhost",
        // Loopback addresses, the whole 127/8 not just .1.
        "http://127.0.0.1:8080/notify,                 loopback",
        "http://127.0.5.6/notify,                      loopback",
        "http://[::1]/notify,                          loopback",
        // The unspecified address binds, it does not serve.
        "http://0.0.0.0:8080/notify,                   unspecified",
        // Private networks: routable inside a site, not from the internet.
        "http://10.1.2.3/notify,                       private-network",
        "http://172.16.0.1/notify,                     private-network",
        "http://172.31.255.255/notify,                 private-network",
        "http://192.168.1.1/notify,                    private-network",
        // Link-local: exists on no route at all.
        "http://169.254.10.20/notify,                  link-local",
        // mDNS names resolve inside the local network only.
        "http://xboard.local/notify,                   local-domain",
        "http://machine.sub.local/notify,              local-domain"
    })
    void namesTheReasonForAnAddressAGatewayCannotReach(String url, String reason) {
        assertThat(NotifyUrlReachability.unreachableReason(url)).isEqualTo(reason);
        assertThat(NotifyUrlReachability.reachableByRemoteGateway(url)).isFalse();
    }

    @ParameterizedTest
    @CsvSource({
        // Public names and addresses: the string gives no reason to doubt them.
        "https://pay.example.com/notify",
        "https://pay.example.com:8443/notify?x=1",
        "http://1.2.3.4/notify",
        "http://8.8.8.8/notify",
        // Just outside the private ranges: no reason to flag them.
        "http://172.15.0.1/notify",
        "http://172.32.0.1/notify",
        "http://192.169.1.1/notify",
        // Not an IPv4 literal, so not an address this check can judge.
        "http://10.256.0.1/notify",
        "http://10.1.2/notify",
        // No scheme, no host, or malformed: not judged, not warned about.
        "localhost/notify",
        "pay.example.com/notify",
        "not a url at all"
    })
    void doesNotWarnAboutAnAddressItCannotJudge(String url) {
        assertThat(NotifyUrlReachability.unreachableReason(url)).isNull();
        assertThat(NotifyUrlReachability.reachableByRemoteGateway(url)).isTrue();
    }

    @Test
    void aBlankOrNullUrlIsNotJudged() {
        assertThat(NotifyUrlReachability.unreachableReason("")).isNull();
        assertThat(NotifyUrlReachability.reachableByRemoteGateway("")).isTrue();
        assertThat(NotifyUrlReachability.unreachableReason(null)).isNull();
        assertThat(NotifyUrlReachability.reachableByRemoteGateway(null)).isTrue();
    }

    @Test
    void ignoresASchemelessHostAndJudgesWhatIsGiven() {
        // A bare word is not a URL with a host, so it is not judged at all.
        assertThat(NotifyUrlReachability.unreachableReason("localhost")).isNull();
    }

    @Test
    void theDefaultDevelopmentBaseUrlIsTheExactCaseThisExistsFor() {
        String url = "http://localhost:5173" + PATH;

        assertThat(NotifyUrlReachability.reachableByRemoteGateway(url)).isFalse();
        assertThat(NotifyUrlReachability.unreachableReason(url)).isEqualTo("localhost");
    }
}
