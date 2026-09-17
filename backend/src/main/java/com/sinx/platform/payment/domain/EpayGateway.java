package com.sinx.platform.payment.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.stereotype.Component;

/**
 * Epay, the 易支付 protocol.
 *
 * The same protocol serves the fiat channels and the crypto ones, so one
 * implementation covers both: an administrator configures WeChat and Alipay
 * through one endpoint, and a BEpusdt upstream through another, differing only
 * in the gateway address, the merchant id, and which channel {@code type}
 * names.
 *
 * The signature is MD5 over the parameters in byte order with the merchant key
 * appended, and it is what stands between an anonymous HTTP request and a free
 * subscription. {@link PhpCompat} exists so that the string being hashed is the
 * one the gateway itself hashes.
 */
@Component
public class EpayGateway implements PaymentGateway {

    public static final String CODE = "EPay";

    private static final String SUBMIT_PATH = "/submit.php";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public List<GatewayField> form() {
        return List.of(
            GatewayField.text(
                "url",
                true,
                false,
                LocalizedText.of("支付网关地址", "Gateway address"),
                LocalizedText.of(
                    "https://pay.example.com",
                    "https://pay.example.com"
                ),
                LocalizedText.of(
                    "请填写完整的支付网关地址，包括协议（http或https）",
                    "The full gateway address, including the http or https protocol."
                )
            ),
            GatewayField.text(
                "pid",
                true,
                false,
                LocalizedText.of("商户ID", "Merchant ID"),
                LocalizedText.of("1000", "1000"),
                LocalizedText.of(
                    "请填写商户ID",
                    "The merchant ID issued by the gateway."
                )
            ),
            GatewayField.text(
                "key",
                true,
                true,
                LocalizedText.of("通信密钥", "Communication key"),
                LocalizedText.of("", ""),
                LocalizedText.of(
                    "请填写通信密钥，用于校验支付回调的签名",
                    "The shared secret used to sign and verify payment callbacks."
                )
            ),
            GatewayField.text(
                "type",
                false,
                false,
                LocalizedText.of("支付类型", "Payment type"),
                LocalizedText.of("alipay", "alipay"),
                LocalizedText.of(
                    "支付类型，如: alipay, wxpay, qqpay 等，可自定义",
                    "The channel to send the customer to, e.g. alipay, wxpay, qqpay."
                )
            )
        );
    }

    @Override
    public PaymentRedirect pay(
        Map<String, String> config,
        PaymentContext context
    ) {
        String key = require(config, "key");
        Map<String, String> params = new TreeMap<>();
        params.put("pid", require(config, "pid"));
        params.put("money", money(context.amountMinor()));
        // The original sends the trade number as the product name, which is
        // what the customer sees on the cashier page.
        params.put("name", context.tradeNo());
        params.put("out_trade_no", context.tradeNo());
        params.put("notify_url", context.notifyUrl());
        params.put("return_url", context.returnUrl());
        String type = config.get("type");
        if (type != null && !type.isBlank()) {
            params.put("type", type.trim());
        }

        params.put("sign", PhpCompat.md5(PhpCompat.signedString(params) + key));
        params.put("sign_type", "MD5");

        return PaymentRedirect.to(
            baseUrl(require(config, "url")) + SUBMIT_PATH + "?" + query(params)
        );
    }

    @Override
    public PaymentNotification verify(
        Map<String, String> config,
        Map<String, String> params
    ) {
        String signature = params.get("sign");
        if (signature == null || signature.isBlank()) {
            throw new PaymentVerificationException(
                "The callback carries no signature"
            );
        }
        String pid = params.get("pid");
        if (pid != null && !pid.equals(config.get("pid"))) {
            throw new PaymentVerificationException(
                "The callback was signed for another merchant"
            );
        }
        // A closed or refunded trade is signed just as validly as a successful
        // one, so the status has to be read rather than inferred. Only rejected
        // when the gateway actually states a failure - older Epay builds do not
        // send the field at all.
        String status = params.get("trade_status");
        if (status != null && !status.isBlank() && !"TRADE_SUCCESS".equals(status)) {
            throw new PaymentVerificationException(
                "The callback reports a trade that did not succeed: " + status
            );
        }

        Map<String, String> signed = new TreeMap<>(params);
        signed.remove("sign");
        signed.remove("sign_type");
        String expected = PhpCompat.md5(
            PhpCompat.signedString(signed) + require(config, "key")
        );
        if (!PhpCompat.sameSignature(expected, signature)) {
            throw new PaymentVerificationException(
                "The callback signature is not valid"
            );
        }

        String outTradeNo = params.get("out_trade_no");
        if (outTradeNo == null || outTradeNo.isBlank()) {
            throw new PaymentVerificationException(
                "The callback names no order"
            );
        }
        return new PaymentNotification(
            outTradeNo,
            params.get("trade_no"),
            amount(params.get("money"))
        );
    }

    /** The amount paid, as the gateway states it. Absent means unverifiable. */
    private static BigDecimal amount(String money) {
        if (money == null || money.isBlank()) {
            throw new PaymentVerificationException(
                "The callback does not say what was paid"
            );
        }
        try {
            return new BigDecimal(money.trim());
        } catch (NumberFormatException unreadable) {
            throw new PaymentVerificationException(
                "The callback states an unreadable amount: " + money
            );
        }
    }

    /**
     * Minor units as the gateway's unit of account, trimmed the way PHP would
     * print the division the original performs.
     */
    private static String money(long amountMinor) {
        return BigDecimal.valueOf(amountMinor)
            .movePointLeft(2)
            .stripTrailingZeros()
            .toPlainString();
    }

    private static String query(Map<String, String> params) {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (builder.length() > 0) {
                builder.append('&');
            }
            builder.append(PhpCompat.urlEncode(entry.getKey()))
                .append('=')
                .append(PhpCompat.urlEncode(entry.getValue()));
        }
        return builder.toString();
    }

    private static String baseUrl(String url) {
        String trimmed = url.trim();
        return trimmed.endsWith("/")
            ? trimmed.substring(0, trimmed.length() - 1)
            : trimmed;
    }

    private static String require(Map<String, String> config, String key) {
        String value = config.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                "The payment method is missing its " + key
            );
        }
        return value.trim();
    }
}
