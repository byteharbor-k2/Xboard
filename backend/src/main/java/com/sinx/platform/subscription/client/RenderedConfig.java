package com.sinx.platform.subscription.client;

import java.util.Map;

/**
 * A finished subscription body and the headers that describe it.
 *
 * The traffic headers every format shares are added by the endpoint, not here;
 * what a renderer returns is only what its own format adds.
 */
public record RenderedConfig(
    String body,
    String contentType,
    Map<String, String> headers
) {
}
