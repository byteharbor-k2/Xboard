package com.sinx.platform.payment.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sinx.platform.order.repository.ServiceOrderRepository;
import com.sinx.platform.payment.config.PaymentProperties;
import com.sinx.platform.payment.domain.GatewayField;
import com.sinx.platform.payment.domain.PaymentGateway;
import com.sinx.platform.payment.domain.PaymentMethod;
import com.sinx.platform.payment.repository.PaymentMethodRepository;
import com.sinx.platform.shared.web.ApiProblemException;

/**
 * Payment methods as an administrator manages them, and the URLs a gateway is
 * told about.
 *
 * The merchant key is handled as an opaque string throughout: it is stored as
 * given, returned only to the admin surface, and never written to a log.
 */
@Service
public class PaymentMethodService {

    private static final BigDecimal MAX_PERCENT = BigDecimal.valueOf(100);
    private static final String NOTIFY_PATH = "/api/v1/guest/payment/notify/";

    private final PaymentMethodRepository methods;
    private final ServiceOrderRepository orders;
    private final PaymentGatewayRegistry gateways;
    private final PaymentConfigCodec codec;
    private final PaymentProperties properties;
    private final Clock clock;

    public PaymentMethodService(
        PaymentMethodRepository methods,
        ServiceOrderRepository orders,
        PaymentGatewayRegistry gateways,
        PaymentConfigCodec codec,
        PaymentProperties properties,
        Clock clock
    ) {
        this.methods = methods;
        this.orders = orders;
        this.gateways = gateways;
        this.codec = codec;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<PaymentMethodAdminView> list() {
        return methods.findAllByOrderBySortOrderAscCreatedAtAsc().stream()
            .map(this::adminView)
            .toList();
    }

    /**
     * The fields of one gateway, with the values already stored on a method.
     *
     * An unsaved method is asked for by gateway code alone, which is how the
     * admin form renders itself before anything has been filled in.
     */
    @Transactional(readOnly = true)
    public Map<String, GatewayFieldView> form(String gatewayCode, UUID id) {
        PaymentGateway gateway = gateways.require(gatewayCode);
        Map<String, String> stored = id == null
            ? Map.of()
            : codec.read(require(id).getConfig());
        Map<String, GatewayFieldView> fields = new LinkedHashMap<>();
        for (GatewayField field : gateway.form()) {
            fields.put(
                field.key(),
                GatewayFieldView.from(field, stored.get(field.key()))
            );
        }
        return fields;
    }

    /** Every gateway this build can configure, for the admin form's picker. */
    @Transactional(readOnly = true)
    public Map<String, String> gatewayOptions() {
        Map<String, String> options = new LinkedHashMap<>();
        for (PaymentGateway gateway : gateways.all()) {
            options.put(gateway.code(), gateway.code());
        }
        return options;
    }

    @Transactional
    public UUID save(SavePaymentMethodRequest request) {
        Instant now = Instant.now(clock);
        PaymentGateway gateway = gateways.require(request.gateway());
        requireFees(request.handlingFeeFixed(), request.handlingFeePercent());

        PaymentMethod method = request.id() == null
            ? PaymentMethod.create(
                gateway.code(),
                request.name(),
                now
            )
            : require(request.id());

        if (request.id() != null) {
            method.rename(request.name(), now);
        }
        method.describe(
            request.icon(),
            request.notifyDomain(),
            request.handlingFeeFixed(),
            request.handlingFeePercent(),
            now
        );
        method.replaceConfig(codec.write(request.config()), now);

        // A method saved without the credentials its gateway needs would fail
        // when a customer reached checkout. Refusing the save puts that in front
        // of the administrator instead.
        requireConfigured(gateway, method);

        return methods.save(method).getId();
    }

    /**
     * Flips a method's switch, as the original's {@code show} does - the request
     * asks for a change, not for a state.
     */
    @Transactional
    public boolean toggle(UUID id) {
        PaymentMethod method = require(id);
        Instant now = Instant.now(clock);
        if (!method.isEnabled()) {
            requireConfigured(gateways.require(method.getGateway()), method);
        }
        method.toggle(now);
        return method.isEnabled();
    }

    @Transactional
    public void drop(UUID id) {
        PaymentMethod method = require(id);
        if (orders.existsByPaymentMethodId(id)) {
            throw new ApiProblemException(
                HttpStatus.CONFLICT,
                "PAYMENT_METHOD_IN_USE",
                "This payment method has been used by orders and cannot be "
                    + "deleted; switch it off instead"
            );
        }
        methods.delete(method);
    }

    /**
     * Reorders the methods to match the ids given, in the order given. Ids that
     * are not mentioned keep their relative order after the ones that are.
     */
    @Transactional
    public void sort(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        Instant now = Instant.now(clock);
        Map<UUID, PaymentMethod> known = new LinkedHashMap<>();
        for (PaymentMethod method : methods.findAllByOrderBySortOrderAscCreatedAtAsc()) {
            known.put(method.getId(), method);
        }
        List<UUID> ordered = new ArrayList<>();
        for (UUID id : ids) {
            if (known.containsKey(id) && !ordered.contains(id)) {
                ordered.add(id);
            }
        }
        for (UUID id : known.keySet()) {
            if (!ordered.contains(id)) {
                ordered.add(id);
            }
        }
        for (int position = 0; position < ordered.size(); position++) {
            known.get(ordered.get(position)).moveTo(position, now);
        }
    }

    /** Where the gateway should report the result of a payment. */
    public String notifyUrl(PaymentMethod method) {
        return origin(
            method.getNotifyDomain(),
            properties.publicBaseUrl()
        ) + NOTIFY_PATH + method.getGateway().toLowerCase(Locale.ROOT) + "/"
            + method.getUuid();
    }

    /** Where the customer lands once the gateway is done with them. */
    public String returnUrl(String tradeNo) {
        return origin(null, properties.publicBaseUrl())
            + "/account/orders/" + tradeNo;
    }

    /**
     * A method the customer may pay with, by id.
     *
     * A switched-off method is reported as missing rather than refused, so the
     * customer UI cannot be used to probe which methods exist.
     */
    @Transactional(readOnly = true)
    public PaymentMethod requireEnabled(UUID id) {
        return methods.findById(id)
            .filter(PaymentMethod::isEnabled)
            .orElseThrow(() -> problem(
                HttpStatus.NOT_FOUND,
                "PAYMENT_METHOD_NOT_FOUND",
                "That payment method is not available"
            ));
    }

    /**
     * A method a callback claims to come from, by the token in its notify URL.
     *
     * Switched off means refused: an administrator turning a method off is
     * saying no more money should be taken through it.
     */
    @Transactional(readOnly = true)
    public PaymentMethod requireEnabledByUuid(String uuid, String gatewayCode) {
        return methods.findByUuid(uuid)
            .filter(PaymentMethod::isEnabled)
            .filter(method -> method.is(gatewayCode))
            .orElseThrow(() -> problem(
                HttpStatus.NOT_FOUND,
                "PAYMENT_METHOD_NOT_FOUND",
                "That payment method is not available"
            ));
    }

    PaymentMethod require(UUID id) {
        return methods.findById(id).orElseThrow(() -> problem(
            HttpStatus.NOT_FOUND,
            "PAYMENT_METHOD_NOT_FOUND",
            "That payment method does not exist"
        ));
    }

    private PaymentMethodAdminView adminView(PaymentMethod method) {
        return PaymentMethodAdminView.from(
            method,
            notifyUrl(method),
            codec.read(method.getConfig())
        );
    }

    /**
     * Refuses a configuration that cannot be used, naming the fields at fault.
     * Only the keys are reported - never the values, which are credentials.
     */
    private void requireConfigured(
        PaymentGateway gateway,
        PaymentMethod method
    ) {
        Map<String, String> stored = codec.read(method.getConfig());
        List<String> missing = gateway.form().stream()
            .filter(GatewayField::required)
            .map(GatewayField::key)
            .filter(key -> {
                String value = stored.get(key);
                return value == null || value.isBlank();
            })
            .toList();
        if (!missing.isEmpty()) {
            throw new ApiProblemException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "PAYMENT_METHOD_INCOMPLETE",
                "These fields are required by " + gateway.code() + ": "
                    + String.join(", ", missing)
            );
        }
    }

    private void requireFees(Long fixed, BigDecimal percent) {
        if (fixed != null && fixed < 0) {
            throw problem(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "PAYMENT_METHOD_INVALID_FEE",
                "A fixed handling fee cannot be negative"
            );
        }
        if (percent != null
            && (percent.signum() < 0 || percent.compareTo(MAX_PERCENT) > 0)) {
            throw problem(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "PAYMENT_METHOD_INVALID_FEE",
                "A percentage handling fee has to be between 0 and 100"
            );
        }
    }

    /**
     * The origin to build URLs from: the method's own override when it has one,
     * otherwise the site's public address. An override written without a scheme
     * is assumed to be https, which is what a gateway will need anyway.
     */
    private String origin(String override, String fallback) {
        String value = override == null || override.isBlank()
            ? fallback
            : override.trim();
        if (value.startsWith("http://") || value.startsWith("https://")) {
            return trimTrailingSlash(value);
        }
        return "https://" + trimTrailingSlash(value);
    }

    private String trimTrailingSlash(String value) {
        return value.endsWith("/")
            ? value.substring(0, value.length() - 1)
            : value;
    }

    private ApiProblemException problem(
        HttpStatus status,
        String code,
        String detail
    ) {
        return new ApiProblemException(status, code, detail);
    }

    /**
     * What the admin form submits. The gateway credentials arrive as whatever
     * the gateway's own fields are, so the map is the gateway's shape, not ours.
     */
    public record SavePaymentMethodRequest(
        @JsonProperty("id") UUID id,
        @JsonProperty("payment") String gateway,
        @JsonProperty("name") String name,
        @JsonProperty("icon") String icon,
        @JsonProperty("notify_domain") String notifyDomain,
        @JsonProperty("handling_fee_fixed") Long handlingFeeFixed,
        @JsonProperty("handling_fee_percent") BigDecimal handlingFeePercent,
        @JsonProperty("config") Map<String, String> config
    ) {

        public SavePaymentMethodRequest {
            config = Optional.ofNullable(config).orElseGet(Map::of);
        }
    }
}
