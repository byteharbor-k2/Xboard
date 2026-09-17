package com.sinx.platform.subscription.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sinx.platform.configuration.application.PlatformConfigurationService;
import com.sinx.platform.configuration.application.SubscriptionTemplates;
import com.sinx.platform.subscription.application.SubscriptionNodeSelector;
import com.sinx.platform.subscription.domain.SubscriptionEntitlement;

/**
 * The request-level decisions: which nodes survive the query parameters, which
 * template gets read, and what the client is told about its own traffic.
 */
class ClientConfigServiceTest {

    private static final Instant EXPIRES = Instant.parse("2027-01-01T00:00:00Z");
    private static final long EXPIRES_EPOCH = 1_798_761_600L;

    private SubscriptionNodeSelector selector;
    private PlatformConfigurationService configuration;
    private SubscriptionTemplates templates;

    @BeforeEach
    void setUp() {
        templates = new SubscriptionTemplates(SubscriptionFixtures.mapper());
        selector = mock(SubscriptionNodeSelector.class);
        configuration = mock(PlatformConfigurationService.class);
        when(selector.select(any())).thenReturn(SubscriptionFixtures.nodes());
        when(configuration.appName()).thenReturn(SubscriptionFixtures.APP_NAME);
        when(configuration.appUrl()).thenReturn(Optional.of(SubscriptionFixtures.APP_URL));
        when(configuration.subscriptionTemplate(any())).thenAnswer(invocation ->
            templates.bundled(invocation.getArgument(0))
        );
    }

    private ClientConfigService service() {
        return new ClientConfigService(
            new ClientFormatResolver(SubscriptionFixtures.mapper()),
            selector,
            configuration
        );
    }

    private RenderedConfig render(SubscriptionEntitlement entitlement) {
        return service().render(
            entitlement, "meta", null, null, SubscriptionFixtures.REQUEST_HOST
        );
    }

    private static List<Map<String, Object>> proxies(RenderedConfig rendered) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> proxies =
            (List<Map<String, Object>>) (List<?>) ClashYaml.parse(rendered.body()).get("proxies");
        return proxies;
    }

    private static SubscriptionEntitlement entitlement() {
        SubscriptionEntitlement entitlement = mock(SubscriptionEntitlement.class);
        when(entitlement.getUploadedBytes()).thenReturn(1024L);
        when(entitlement.getDownloadedBytes()).thenReturn(2048L);
        when(entitlement.getTransferLimitBytes()).thenReturn(1_000_000L);
        when(entitlement.getExpiresAt()).thenReturn(EXPIRES);
        return entitlement;
    }

    // ------------------------------------------------------------------
    // The traffic header
    // ------------------------------------------------------------------

    @Test
    void theClientIsToldItsOwnTrafficInRawBytesAndSeconds() {
        assertThat(render(entitlement()).headers())
            .containsEntry(
                "subscription-userinfo",
                "upload=1024; download=2048; total=1000000; expire=" + EXPIRES_EPOCH
            );
    }

    /**
     * Zero here would be 1970 to a client that renders the header, so an
     * account with no expiry is sent an empty value instead.
     */
    @Test
    void anAccountWithNoExpirySendsAnEmptyExpire() {
        SubscriptionEntitlement entitlement = mock(SubscriptionEntitlement.class);
        when(entitlement.getExpiresAt()).thenReturn(null);

        assertThat(render(entitlement).headers())
            .containsEntry("subscription-userinfo", "upload=0; download=0; total=0; expire=");
    }

    @Test
    void theTrafficHeaderIsAddedToWhateverTheFormatSent() {
        assertThat(render(entitlement()).headers())
            .containsKeys(
                "subscription-userinfo", "profile-update-interval", "content-disposition"
            );
    }

    // ------------------------------------------------------------------
    // types
    // ------------------------------------------------------------------

    @Test
    void aProtocolWhitelistKeepsOnlyWhatItNames() {
        RenderedConfig rendered = service().render(
            entitlement(), "meta", "vless|trojan", null, SubscriptionFixtures.REQUEST_HOST
        );

        assertThat(rendered.body()).contains("vless-reality").contains("trojan-grpc");
        assertThat(rendered.body()).doesNotContain("vmess-ws").doesNotContain("socks");
    }

    @Test
    void aWhitelistSplitOnCommasIsTrimmed() {
        RenderedConfig rendered = service().render(
            entitlement(), "meta", " vless , trojan ", null, SubscriptionFixtures.REQUEST_HOST
        );

        assertThat(rendered.body()).contains("vless-reality").contains("trojan-grpc");
    }

    /**
     * The fullwidth vertical bar is a delimiter because the original's split
     * pattern says so; the fullwidth comma is not, and a client that sends one
     * is asking for a protocol named "vless，trojan" - which no node has.
     */
    @Test
    void theFullwidthVerticalBarSplitsAndTheFullwidthCommaDoesNot() {
        RenderedConfig split = service().render(
            entitlement(), "meta", "vless｜trojan", null, SubscriptionFixtures.REQUEST_HOST
        );
        RenderedConfig unsplit = service().render(
            entitlement(), "meta", "vless，trojan", null, SubscriptionFixtures.REQUEST_HOST
        );

        assertThat(split.body()).contains("vless-reality");
        assertThat(proxies(unsplit)).isEmpty();
    }

    @Test
    void aBlankOrAllWhitelistMeansEveryProtocol() {
        assertThat(proxies(service().render(
            entitlement(), "meta", "all", null, SubscriptionFixtures.REQUEST_HOST
        ))).hasSize(10);
        assertThat(proxies(service().render(
            entitlement(), "meta", "  ", null, SubscriptionFixtures.REQUEST_HOST
        ))).hasSize(10);
        assertThat(proxies(service().render(
            entitlement(), "meta", null, null, SubscriptionFixtures.REQUEST_HOST
        ))).hasSize(10);
    }

    /** The literal is compared exactly, as the original's {@code === 'all'} is. */
    @Test
    void allInCapitalsIsNotTheKeyword() {
        assertThat(proxies(service().render(
            entitlement(), "meta", "ALL", null, SubscriptionFixtures.REQUEST_HOST
        ))).isEmpty();
    }

    // ------------------------------------------------------------------
    // filter
    // ------------------------------------------------------------------

    @Test
    void aKeywordKeepsTheNodesWhoseNameContainsIt() {
        RenderedConfig rendered = service().render(
            entitlement(), "meta", null, "香港", SubscriptionFixtures.REQUEST_HOST
        );

        assertThat(rendered.body()).contains("香港 01").doesNotContain("日本");
        assertThat(proxies(rendered)).hasSize(1);
    }

    @Test
    void aKeywordMatchesANameWithoutRegardToCase() {
        assertThat(proxies(service().render(
            entitlement(), "meta", null, "ss2022", SubscriptionFixtures.REQUEST_HOST
        ))).hasSize(1);
    }

    @Test
    void aKeywordMatchesATagExactly() {
        RenderedConfig tagged = service().render(
            entitlement(), "meta", null, "hk", SubscriptionFixtures.REQUEST_HOST
        );

        assertThat(proxies(tagged)).hasSize(10);
    }

    @Test
    void aKeywordThatMatchesNothingLeavesAnEmptyConfig() {
        assertThat(proxies(service().render(
            entitlement(), "meta", null, "火星", SubscriptionFixtures.REQUEST_HOST
        ))).isEmpty();
    }

    /**
     * The cap is what keeps the parameter from being used to walk a node list
     * the account is not being shown, so an over-long keyword filters nothing
     * rather than filtering on its first twenty characters.
     */
    @Test
    void anOverLongKeywordIsDiscardedRatherThanShortened() {
        String twenty = "香".repeat(20);
        String twentyOne = "香".repeat(21);

        assertThat(proxies(service().render(
            entitlement(), "meta", null, twenty, SubscriptionFixtures.REQUEST_HOST
        ))).isEmpty();
        assertThat(proxies(service().render(
            entitlement(), "meta", null, twentyOne, SubscriptionFixtures.REQUEST_HOST
        ))).hasSize(10);
    }

    // ------------------------------------------------------------------
    // Format, template and site name
    // ------------------------------------------------------------------

    @Test
    void theFormatDecidesWhichNodesAreEvenOffered() {
        RenderedConfig narrow = service().render(
            entitlement(), "clash", null, null, SubscriptionFixtures.REQUEST_HOST
        );

        assertThat(proxies(narrow)).hasSize(5);
        assertThat(narrow.headers()).containsEntry(
            "content-disposition", "attachment;filename*=UTF-8''SinX%20Cloud"
        );
    }

    @Test
    void onlyTheTemplateTheFormatRendersIntoIsRead() {
        service().render(
            entitlement(), "meta", null, null, SubscriptionFixtures.REQUEST_HOST
        );
        verify(configuration).subscriptionTemplate(SubscriptionTemplates.Kind.CLASH_META);

        service().render(
            entitlement(), null, null, null, SubscriptionFixtures.REQUEST_HOST
        );
        verify(configuration, never()).subscriptionTemplate(SubscriptionTemplates.Kind.CLASH);
    }

    @Test
    void aStoredTemplateReplacesTheBundledOne() {
        when(configuration.subscriptionTemplate(SubscriptionTemplates.Kind.CLASH_META))
            .thenReturn("""
                proxies: []
                proxy-groups:
                  - { name: "$app_name", type: select, proxies: [] }
                rules:
                  - MATCH,DIRECT
                """);

        RenderedConfig rendered = render(entitlement());

        assertThat(rendered.body()).contains("MATCH,DIRECT").doesNotContain("geolocation");
    }

    /** The format a client asks for by name is the same one the header names. */
    @Test
    void theSiteNameAndAddressComeFromTheSettings() {
        assertThat(render(entitlement()).headers())
            .containsEntry("profile-web-page-url", SubscriptionFixtures.APP_URL);

        RenderedConfig generic = service().render(
            entitlement(), null, null, null, SubscriptionFixtures.REQUEST_HOST
        );
        assertThat(generic.contentType()).isEqualTo("text/plain");
        assertThat(generic.headers()).doesNotContainKey("profile-web-page-url");
    }
}
