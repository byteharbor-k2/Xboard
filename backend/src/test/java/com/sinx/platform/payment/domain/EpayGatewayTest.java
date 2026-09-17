package com.sinx.platform.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

/**
 * The Epay protocol, pinned against the original plugin's own output.
 *
 * The signatures below were produced by running the original panel's
 * {@code Plugin\Epay\Plugin} - the very {@code pay()} and {@code notify()}
 * this class reimplements - over the same inputs. That is the point of the
 * test: it is not enough for this implementation to agree with itself, it has
 * to agree with the PHP on the other end. A refactor that quietly changes the
 * hashed string would still pass every round-trip test and would break the
 * first real payment.
 */
class EpayGatewayTest {

    private static final String GATEWAY_UUID =
        "9f2c1a7b4e5d4f8a9b3c6e0d1a2f4c8b";
    private static final String TRADE_NO = "SX202609170301394440";
    private static final String NOTIFY_URL =
        "https://app.example.com/api/v1/guest/payment/notify/epay/"
            + GATEWAY_UUID;
    private static final String RETURN_URL =
        "https://app.example.com/account/orders/" + TRADE_NO;
    private static final String KEY = "test-communication-key";

    private final EpayGateway gateway = new EpayGateway();

    private Map<String, String> config() {
        return Map.of(
            "url", "https://pay.example.com",
            "pid", "1000",
            "key", KEY,
            "type", "alipay"
        );
    }

    @Test
    void signsAPaymentExactlyAsTheOriginalPluginDoes() {
        PaymentRedirect redirect = gateway.pay(
            config(),
            new PaymentContext(
                TRADE_NO,
                1234,
                "CNY",
                NOTIFY_URL,
                RETURN_URL
            )
        );

        assertThat(redirect.type()).isEqualTo(PaymentRedirect.REDIRECT);
        assertThat(redirect.data()).startsWith(
            "https://pay.example.com/submit.php?"
        );
        assertThat(queryOf(redirect.data()))
            .containsExactlyInAnyOrderEntriesOf(expectedPaymentQuery());
    }

    /**
     * The query the original plugin builds for the same order, sign included.
     * {@code md5("money=12.34&name=" + TRADE_NO + "&notify_url=" + NOTIFY_URL
     * + "&out_trade_no=" + TRADE_NO + "&pid=1000&return_url=" + RETURN_URL
     * + "&type=alipay" + KEY)} is {@code 74aac64492abd4f268da6373c17abfd6}.
     */
    private Map<String, String> expectedPaymentQuery() {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("money", "12.34");
        expected.put("name", TRADE_NO);
        expected.put("notify_url", NOTIFY_URL);
        expected.put("out_trade_no", TRADE_NO);
        expected.put("pid", "1000");
        expected.put("return_url", RETURN_URL);
        expected.put("type", "alipay");
        expected.put("sign", "74aac64492abd4f268da6373c17abfd6");
        expected.put("sign_type", "MD5");
        return expected;
    }

    @Test
    void signsAWholeYuanAmountWithoutTrailingZeroesAsPhpWould() {
        PaymentRedirect redirect = gateway.pay(
            Map.of("url", "https://pay.example.com/", "pid", "1000", "key", KEY),
            new PaymentContext(TRADE_NO, 2000, "CNY", NOTIFY_URL, RETURN_URL)
        );

        Map<String, String> query = queryOf(redirect.data());
        // The original divides by 100 in PHP, where 2000/100 prints as "20".
        assertThat(query).containsEntry("money", "20");
        assertThat(query).containsEntry("sign", "a173eceb02354a2dcfc8c1084870d16b");
        // A gateway address written with a trailing slash must not produce
        // "//submit.php".
        assertThat(redirect.data()).startsWith(
            "https://pay.example.com/submit.php?"
        );
    }

    @Test
    void verifiesACallbackTheGatewaySigned() {
        // md5 of the same parameters, signed by the original plugin with KEY.
        Map<String, String> callback = new LinkedHashMap<>(signedCallback());
        assertThat(callback).containsEntry(
            "sign",
            "5eb67833ed7601c1f5fbf12db2738ef3"
        );

        PaymentNotification notification = gateway.verify(config(), callback);

        assertThat(notification.tradeNo()).isEqualTo(TRADE_NO);
        assertThat(notification.callbackNo())
            .isEqualTo("2026091722001234567890");
        assertThat(notification.amount()).isEqualByComparingTo("12.34");
    }

    @Test
    void refusesACallbackWithNoSignature() {
        assertThatThrownBy(() -> gateway.verify(
            config(),
            Map.of("out_trade_no", TRADE_NO, "money", "12.34")
        ))
            .isInstanceOf(PaymentVerificationException.class)
            .hasMessageContaining("no signature");
    }

    @Test
    void refusesACallbackSignedWithAnotherKey() {
        Map<String, String> params = signedCallback();
        params.put("sign", "00000000000000000000000000000000");

        assertThatThrownBy(() -> gateway.verify(config(), params))
            .isInstanceOf(PaymentVerificationException.class)
            .hasMessageContaining("signature is not valid");
    }

    @Test
    void refusesACallbackSignedForAnotherMerchant() {
        Map<String, String> params = signedCallback();
        params.put("pid", "2000");
        params.put("sign", signatureOf(params));

        assertThatThrownBy(() -> gateway.verify(config(), params))
            .isInstanceOf(PaymentVerificationException.class)
            .hasMessageContaining("another merchant");
    }

    @Test
    void refusesACallbackThatReportsATradeWhichDidNotSucceed() {
        Map<String, String> params = signedCallback();
        params.put("trade_status", "TRADE_CLOSED");
        params.put("sign", signatureOf(params));

        assertThatThrownBy(() -> gateway.verify(config(), params))
            .isInstanceOf(PaymentVerificationException.class)
            .hasMessageContaining("did not succeed");
    }

    @Test
    void refusesACallbackThatDoesNotSayWhatWasPaid() {
        Map<String, String> params = signedCallback();
        params.remove("money");
        params.put("sign", signatureOf(params));

        assertThatThrownBy(() -> gateway.verify(config(), params))
            .isInstanceOf(PaymentVerificationException.class)
            .hasMessageContaining("what was paid");
    }

    @Test
    void refusesACallbackThatNamesNoOrder() {
        Map<String, String> params = signedCallback();
        params.remove("out_trade_no");
        params.put("sign", signatureOf(params));

        assertThatThrownBy(() -> gateway.verify(config(), params))
            .isInstanceOf(PaymentVerificationException.class)
            .hasMessageContaining("names no order");
    }

    /**
     * The amount is read, not just the signature. A gateway that reports a
     * different figure than the one the order was checked out at must not be
     * taken at its word.
     */
    @Test
    void reportsWhatWasPaidSoTheCallerCanCheckIt() {
        Map<String, String> params = signedCallback();
        params.put("money", "1.00");
        params.put("sign", signatureOf(params));

        assertThat(gateway.verify(config(), params).amount())
            .isEqualByComparingTo(BigDecimal.ONE);
    }

    /**
     * Every field the original signs, signed the way the original signs it.
     * Used to build callbacks whose signature is genuine but whose contents are
     * not - the shape a hostile gateway would present.
     */
    private Map<String, String> signedCallback() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("pid", "1000");
        params.put("trade_no", "2026091722001234567890");
        params.put("out_trade_no", TRADE_NO);
        params.put("type", "alipay");
        params.put("name", TRADE_NO);
        params.put("money", "12.34");
        params.put("trade_status", "TRADE_SUCCESS");
        params.put("notify_url", NOTIFY_URL);
        params.put("return_url", RETURN_URL);
        params.put("sign", signatureOf(params));
        params.put("sign_type", "MD5");
        return params;
    }

    private String signatureOf(Map<String, String> params) {
        Map<String, String> signed = new TreeMap<>(params);
        signed.remove("sign");
        signed.remove("sign_type");
        return PhpCompat.md5(PhpCompat.signedString(signed) + KEY);
    }

    private Map<String, String> queryOf(String url) {
        Map<String, String> query = new LinkedHashMap<>();
        for (String pair : url.substring(url.indexOf('?') + 1).split("&")) {
            int separator = pair.indexOf('=');
            query.put(
                decode(pair.substring(0, separator)),
                decode(pair.substring(separator + 1))
            );
        }
        return query;
    }

    private String decode(String value) {
        StringBuilder decoded = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '+') {
                decoded.append(' ');
            } else if (character == '%') {
                decoded.append((char) Integer.parseInt(
                    value.substring(index + 1, index + 3),
                    16
                ));
                index += 2;
            } else {
                decoded.append(character);
            }
        }
        return decoded.toString();
    }
}
