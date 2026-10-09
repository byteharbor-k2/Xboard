package com.sinx.platform.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

class EpayReceiptSemanticsTest {

    private static final String KEY = "receipt-test-key";
    private static final String TRADE_NO = "SXRECEIPT123456";
    private static final Map<String, String> CONFIG = Map.of(
        "url", "https://pay.example.test",
        "pid", "merchant-42",
        "key", KEY,
        "type", "alipay"
    );

    private final EpayGateway gateway = new EpayGateway();

    @Test
    void aSignedCheckoutRequestCannotBeReplayedAsAReceipt() {
        PaymentRedirect redirect = gateway.pay(CONFIG, new PaymentContext(
            TRADE_NO,
            1234,
            "CNY",
            "https://site.example.test/notify",
            "https://site.example.test/orders/" + TRADE_NO
        ));
        Map<String, String> request = queryOf(redirect.data());

        assertThatThrownBy(() -> gateway.verify(CONFIG, request))
            .isInstanceOf(PaymentVerificationException.class)
            .hasMessageContaining("successful trade");

        request.put("trade_status", "TRADE_SUCCESS");
        assertThatThrownBy(() -> gateway.verify(CONFIG, request))
            .isInstanceOf(PaymentVerificationException.class)
            .hasMessageContaining("transaction number");
    }

    @Test
    void acceptsOnlySignedSuccessfulReceiptsWithGatewayTransactionIds() {
        Map<String, String> receipt = new LinkedHashMap<>();
        receipt.put("pid", "merchant-42");
        receipt.put("out_trade_no", TRADE_NO);
        receipt.put("money", "12.34");
        receipt.put("trade_status", "TRADE_SUCCESS");
        receipt.put("trade_no", "GW-20261009-1");
        receipt.put("sign_type", "MD5");
        receipt.put("sign", signatureOf(receipt));

        PaymentNotification notification = gateway.verify(CONFIG, receipt);

        assertThat(notification.tradeNo()).isEqualTo(TRADE_NO);
        assertThat(notification.callbackNo()).isEqualTo("GW-20261009-1");
        assertThat(notification.amount()).isEqualByComparingTo("12.34");
    }

    @Test
    void gatewayEndpointAndMerchantIdentityNormalizationPreservesInstanceScope() {
        assertThat(PaymentAttempt.normalizeGatewayUrl(
            "HTTPS://Gateway.Example.Test:443/EPay/Instance/", "method-a"))
            .isEqualTo("https://gateway.example.test/EPay/Instance");
        assertThat(PaymentAttempt.normalizeGatewayUrl(
            "HTTP://Gateway.Example.Test:80/EPay/Instance/", "method-a"))
            .isEqualTo("http://gateway.example.test/EPay/Instance");
        assertThat(PaymentAttempt.normalizeGatewayUrl(null, "method-a"))
            .isEqualTo("legacy://unknown/method-a");
        assertThat(PaymentAttempt.normalizeGatewayUrl(null, "method-b"))
            .isNotEqualTo(PaymentAttempt.normalizeGatewayUrl(null, "method-a"));
        assertThat(PaymentAttempt.normalizeMerchantIdentity(" Merchant-A "))
            .isEqualTo("Merchant-A");
    }

    private Map<String, String> queryOf(String url) {
        Map<String, String> query = new LinkedHashMap<>();
        for (String pair : url.substring(url.indexOf('?') + 1).split("&")) {
            int separator = pair.indexOf('=');
            query.put(
                URLDecoder.decode(pair.substring(0, separator), StandardCharsets.UTF_8),
                URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8)
            );
        }
        return query;
    }

    private String signatureOf(Map<String, String> params) {
        Map<String, String> signed = new TreeMap<>(params);
        signed.remove("sign");
        signed.remove("sign_type");
        return PhpCompat.md5(PhpCompat.signedString(signed) + KEY);
    }
}
