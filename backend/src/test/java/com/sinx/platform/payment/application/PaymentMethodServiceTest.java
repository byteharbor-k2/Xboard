package com.sinx.platform.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sinx.platform.order.repository.ServiceOrderRepository;
import com.sinx.platform.payment.config.PaymentProperties;
import com.sinx.platform.payment.domain.PaymentMethod;
import com.sinx.platform.payment.repository.PaymentMethodRepository;

import tools.jackson.databind.ObjectMapper;

/**
 * What the admin surface is told about a notify URL it is about to hand to a
 * gateway.
 *
 * The warning, not a refusal: a notify URL that only resolves inside this
 * network - the default development base URL, a loopback or a private
 * address - is still saved, but the list says so plainly, because an order
 * paid through it will wait on a callback that never arrives.
 */
class PaymentMethodServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-17T10:00:00Z");

    private PaymentMethodRepository methods;
    private PaymentMethodService service;

    @BeforeEach
    void setUp() {
        methods = mock(PaymentMethodRepository.class);
        service = serviceAt("http://localhost:5173");
    }

    @Test
    void flagsANotifyUrlBuiltFromTheDevelopmentBaseUrl() {
        when(methods.findAllByOrderBySortOrderAscCreatedAtAsc())
            .thenReturn(List.of(method(null)));

        PaymentMethodAdminView view = service.list().get(0);

        assertThat(view.notifyUrl())
            .startsWith("http://localhost:5173/api/v1/guest/payment/notify/epay/");
        assertThat(view.notifyUnreachable()).isTrue();
    }

    @Test
    void doesNotFlagANotifyUrlOnTheOpenInternet() {
        service = serviceAt("https://pay.example.com");
        when(methods.findAllByOrderBySortOrderAscCreatedAtAsc())
            .thenReturn(List.of(method(null)));

        PaymentMethodAdminView view = service.list().get(0);

        assertThat(view.notifyUrl())
            .startsWith("https://pay.example.com/api/v1/guest/payment/notify/epay/");
        assertThat(view.notifyUnreachable()).isFalse();
    }

    @Test
    void aMethodsOwnNotifyDomainOverridesTheSiteBaseUrl() {
        when(methods.findAllByOrderBySortOrderAscCreatedAtAsc())
            .thenReturn(List.of(method("pay.example.com")));

        PaymentMethodAdminView view = service.list().get(0);

        // The site still runs on localhost, but this method's callbacks are
        // addressed at a public domain.
        assertThat(view.notifyUrl())
            .startsWith("https://pay.example.com/api/v1/guest/payment/notify/epay/");
        assertThat(view.notifyUnreachable()).isFalse();
    }

    @Test
    void flagsANotifyDomainOnAPrivateNetworkToo() {
        when(methods.findAllByOrderBySortOrderAscCreatedAtAsc())
            .thenReturn(List.of(method("192.168.1.20")));

        PaymentMethodAdminView view = service.list().get(0);

        assertThat(view.notifyUrl())
            .startsWith("https://192.168.1.20/api/v1/guest/payment/notify/epay/");
        assertThat(view.notifyUnreachable()).isTrue();
    }

    private PaymentMethodService serviceAt(String baseUrl) {
        return new PaymentMethodService(
            methods,
            mock(ServiceOrderRepository.class),
            new PaymentGatewayRegistry(List.of()),
            new PaymentConfigCodec(new ObjectMapper()),
            new PaymentProperties(baseUrl),
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private PaymentMethod method(String notifyDomain) {
        PaymentMethod method = PaymentMethod.create("EPay", "WeChat", NOW);
        method.describe(null, notifyDomain, null, null, NOW);
        return method;
    }
}
