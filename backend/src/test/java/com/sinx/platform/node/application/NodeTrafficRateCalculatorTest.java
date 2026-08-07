package com.sinx.platform.node.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.sinx.platform.node.domain.ProxyNode;

import tools.jackson.databind.ObjectMapper;

class NodeTrafficRateCalculatorTest {

    private final NodeTrafficRateCalculator calculator =
        new NodeTrafficRateCalculator(new ObjectMapper(), "Asia/Shanghai");

    @Test
    void usesTheFirstMatchingTimeRangeInTheBillingZone() {
        ProxyNode node = mock(ProxyNode.class);
        when(node.getRate()).thenReturn(new BigDecimal("1.0"));
        when(node.isRateTimeEnable()).thenReturn(true);
        when(node.getRateTimeRanges()).thenReturn("""
            [
              {"start":"10:00","end":"12:00","rate":2.5},
              {"start":"11:00","end":"13:00","rate":3.0}
            ]
            """);

        BigDecimal rate = calculator.currentRate(
            node, Instant.parse("2026-08-06T03:30:00Z")
        );

        assertThat(rate).isEqualByComparingTo("2.5");
    }

    @Test
    void fallsBackToTheBaseRateForInvalidOrUnmatchedRanges() {
        ProxyNode node = mock(ProxyNode.class);
        when(node.getRate()).thenReturn(new BigDecimal("1.25"));
        when(node.isRateTimeEnable()).thenReturn(true);
        when(node.getRateTimeRanges()).thenReturn(
            "[{\"start\":\"broken\",\"end\":\"12:00\",\"rate\":2}]"
        );

        assertThat(calculator.currentRate(
            node, Instant.parse("2026-08-06T03:30:00Z")
        )).isEqualByComparingTo("1.25");
    }

    @Test
    void truncatesPositiveFractionalChargesLikeXboard() {
        assertThat(calculator.charge(5, new BigDecimal("2.5"))).isEqualTo(12);
        assertThat(calculator.charge(Long.MAX_VALUE, new BigDecimal("2")))
            .isEqualTo(Long.MAX_VALUE);
    }
}
