package com.sinx.platform.subscription.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sinx.platform.identity.domain.UserAccount;
import com.sinx.platform.identity.domain.UserStatus;
import com.sinx.platform.node.domain.ProxyNode;
import com.sinx.platform.node.repository.ProxyNodeRepository;
import com.sinx.platform.subscription.client.NodeClientView;
import com.sinx.platform.subscription.domain.SubscriptionEntitlement;

import tools.jackson.databind.ObjectMapper;

/**
 * Which nodes an account is shown.
 *
 * The group test is the one that matters: a node belongs to a set of groups and
 * a customer belongs to exactly one, so a node the customer's group is not in
 * must not appear in their config - that is the whole of the panel's
 * per-customer routing.
 */
class SubscriptionNodeSelectorTest {

    private static final Instant NOW = Instant.parse("2026-09-17T04:00:00Z");

    private ProxyNodeRepository nodes;
    private SubscriptionNodeSelector selector;

    @BeforeEach
    void setUp() {
        nodes = mock(ProxyNodeRepository.class);
        selector = new SubscriptionNodeSelector(nodes, new ObjectMapper());
    }

    @Test
    void aNodeInTheAccountsGroupIsIncludedAndOneOutsideItIsNot() {
        when(nodes.findByEnabledTrueAndShowTrueOrderBySortOrderAscIdAsc()).thenReturn(
            java.util.List.of(
                node("香港 01", "[1,2]"),
                node("日本 01", "[3]"),
                node("新加坡 01", "[]")
            )
        );

        assertThat(selector.select(entitlement(2L)))
            .extracting(NodeClientView::name)
            .containsExactly("香港 01");
    }

    /** The group id is compared as a JSON number, which is how the panel writes it. */
    @Test
    void aGroupIdWrittenAsAStringStillMatches() {
        when(nodes.findByEnabledTrueAndShowTrueOrderBySortOrderAscIdAsc()).thenReturn(
            java.util.List.of(node("香港 01", "[\"2\"]"))
        );

        assertThat(selector.select(entitlement(2L))).hasSize(1);
    }

    @Test
    void aNodeWhoseGroupListCannotBeReadIsSkippedRatherThanFatal() {
        when(nodes.findByEnabledTrueAndShowTrueOrderBySortOrderAscIdAsc()).thenReturn(
            java.util.List.of(
                node("broken", "not json"),
                node("香港 01", "[2]")
            )
        );

        assertThat(selector.select(entitlement(2L)))
            .extracting(NodeClientView::name)
            .containsExactly("香港 01");
    }

    /**
     * The control plane pushes nodes only to accounts that are active and have
     * an identity on the node side, so a config for anyone else would be a
     * config the node refuses.
     */
    @Test
    void anAccountTheNodeSideWouldNotServeGetsNothing() {
        when(nodes.findByEnabledTrueAndShowTrueOrderBySortOrderAscIdAsc()).thenReturn(
            java.util.List.of(node("香港 01", "[2]"))
        );

        assertThat(selector.select(entitlement(2L, UserStatus.SUSPENDED, true, 2L))).isEmpty();
        assertThat(selector.select(entitlement(2L, UserStatus.ACTIVE, false, 2L))).isEmpty();
        assertThat(selector.select(entitlement(2L, UserStatus.ACTIVE, true, null))).isEmpty();
        assertThat(selector.select(entitlement(null))).isEmpty();
    }

    @Test
    void theNodesComeBackInTheOrderThePanelSortsThem() {
        ProxyNode first = node("香港 01", "[2]");
        first.changeSort(-1, NOW);
        ProxyNode second = node("香港 02", "[2]");
        second.changeSort(5, NOW);
        when(nodes.findByEnabledTrueAndShowTrueOrderBySortOrderAscIdAsc()).thenReturn(
            java.util.List.of(first, second)
        );

        assertThat(selector.select(entitlement(2L)))
            .extracting(NodeClientView::name)
            .containsExactly("香港 01", "香港 02");
    }

    // ------------------------------------------------------------------

    private static ProxyNode node(String name, String groupIds) {
        ProxyNode node = ProxyNode.create(NOW);
        node.configure(
            "vmess", name, null, null, groupIds, "[]", name, BigDecimal.ONE,
            false, "[]", 0L, "[]", "v.example.com", 443, 443, "{}",
            "[]", "[]", "{}", true, true, 0, NOW
        );
        return node;
    }

    private static SubscriptionEntitlement entitlement(Long groupId) {
        return entitlement(groupId, UserStatus.ACTIVE, true, 1L);
    }

    private static SubscriptionEntitlement entitlement(
        Long groupId,
        UserStatus status,
        boolean hasNodeIdentity,
        Long nodeUserId
    ) {
        UserAccount user = mock(UserAccount.class);
        SubscriptionEntitlement entitlement = mock(SubscriptionEntitlement.class);
        when(user.getStatus()).thenReturn(status);
        when(user.getNodeUserId()).thenReturn(hasNodeIdentity ? nodeUserId : null);
        when(user.getId()).thenReturn(java.util.UUID.randomUUID());
        when(entitlement.getUser()).thenReturn(user);
        when(entitlement.getEffectiveServerGroupId()).thenReturn(groupId);
        return entitlement;
    }
}
