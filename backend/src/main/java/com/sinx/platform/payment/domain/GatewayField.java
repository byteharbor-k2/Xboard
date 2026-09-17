package com.sinx.platform.payment.domain;

/**
 * One configuration input a gateway needs, described rather than hard-coded in
 * the admin UI.
 *
 * The gateway owns this list, so adding another gateway later is a backend
 * change and nothing else - the same arrangement the original panel has, where
 * a plugin returns its own form definition.
 */
public record GatewayField(
    String key,
    /** One of: string, text, number, percentage, boolean, select. */
    String type,
    boolean required,
    /**
     * Whether the value must be masked in the UI. It is still returned to the
     * administrator so an existing configuration can be edited without
     * retyping the credential.
     */
    boolean secret,
    LocalizedText label,
    LocalizedText placeholder,
    LocalizedText description
) {

    public static GatewayField text(
        String key,
        boolean required,
        boolean secret,
        LocalizedText label,
        LocalizedText placeholder,
        LocalizedText description
    ) {
        return new GatewayField(
            key,
            "string",
            required,
            secret,
            label,
            placeholder,
            description
        );
    }
}
