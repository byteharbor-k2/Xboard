package com.sinx.platform.payment.application;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sinx.platform.payment.domain.GatewayField;
import com.sinx.platform.payment.domain.LocalizedText;

/**
 * A gateway's input, described for the admin form to render.
 *
 * The original panel's {@code getPaymentForm} returns the same shape, one entry
 * per field keyed by the field's own name, with the current value filled in.
 */
public record GatewayFieldView(
    @JsonProperty("type") String type,
    @JsonProperty("label") LocalizedText label,
    @JsonProperty("placeholder") LocalizedText placeholder,
    @JsonProperty("description") LocalizedText description,
    @JsonProperty("required") boolean required,
    /** Whether the admin UI should mask what it shows. */
    @JsonProperty("secret") boolean secret,
    @JsonProperty("value") String value
) {
    static GatewayFieldView from(GatewayField field, String value) {
        return new GatewayFieldView(
            field.type(),
            field.label(),
            field.placeholder(),
            field.description(),
            field.required(),
            field.secret(),
            value == null ? "" : value
        );
    }
}
