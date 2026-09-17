package com.sinx.platform.payment.web;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sinx.platform.payment.application.GatewayFieldView;
import com.sinx.platform.payment.application.PaymentMethodAdminView;
import com.sinx.platform.payment.application.PaymentMethodService;
import com.sinx.platform.payment.application.PaymentMethodService.SavePaymentMethodRequest;

/**
 * Payment configuration on the Xboard-compatible admin surface.
 *
 * The paths and payloads follow the original panel's payment endpoints so the
 * same administrator expectations hold: {@code show} flips the switch rather
 * than setting it, {@code save} is an upsert keyed on a supplied id, and the
 * gateway's own fields are described by the server rather than known to the
 * browser. What is ours is the shape of the credentials themselves - the
 * original stores them as plugin-specific YAML-fenced config, this stores them
 * as a flat map the gateway names.
 */
@RestController
@RequestMapping("/api/v2/admin/payment")
public class AdminPaymentController {

    private final PaymentMethodService methods;

    public AdminPaymentController(PaymentMethodService methods) {
        this.methods = methods;
    }

    @GetMapping("/fetch")
    XboardResponse<List<PaymentMethodAdminView>> fetch() {
        return XboardResponse.of(methods.list());
    }

    /** The gateways this build can configure, for the form's picker. */
    @GetMapping("/getPaymentMethods")
    XboardResponse<Map<String, String>> getPaymentMethods() {
        return XboardResponse.of(methods.gatewayOptions());
    }

    /** The fields of one gateway, with anything already stored filled in. */
    @PostMapping("/getPaymentForm")
    XboardResponse<Map<String, GatewayFieldView>> getPaymentForm(
        @RequestBody PaymentFormRequest request
    ) {
        return XboardResponse.of(
            methods.form(request.gateway(), request.id())
        );
    }

    @PostMapping("/save")
    XboardResponse<String> save(
        @RequestBody SavePaymentMethodRequest request
    ) {
        return XboardResponse.of(methods.save(request).toString());
    }

    /** Changes a method's enabled state; the response is the state it is in now. */
    @PostMapping("/show")
    XboardResponse<Boolean> show(@RequestBody IdRequest request) {
        return XboardResponse.of(methods.toggle(request.id()));
    }

    @PostMapping("/drop")
    XboardResponse<Boolean> drop(@RequestBody IdRequest request) {
        methods.drop(request.id());
        return XboardResponse.of(true);
    }

    @PostMapping("/sort")
    XboardResponse<Boolean> sort(@RequestBody SortRequest request) {
        methods.sort(request.ids());
        return XboardResponse.of(true);
    }

    record PaymentFormRequest(
        @JsonProperty("payment") String gateway,
        @JsonProperty("id") UUID id
    ) {
    }

    record IdRequest(@JsonProperty("id") UUID id) {
    }

    record SortRequest(@JsonProperty("ids") List<UUID> ids) {
    }

    record XboardResponse<T>(T data) {
        static <T> XboardResponse<T> of(T data) {
            return new XboardResponse<>(data);
        }
    }
}
