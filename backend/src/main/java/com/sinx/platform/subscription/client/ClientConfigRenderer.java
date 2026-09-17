package com.sinx.platform.subscription.client;

import java.util.List;

/**
 * Turns the nodes an account may use into one format's config.
 *
 * Implementations are pure: given the same request and the same nodes they
 * return the same bytes. The template's randomness - picking one of a node's
 * several paths, drawing a fingerprint - is resolved from the node itself
 * rather than from a random source, so a client that refreshes gets the same
 * config back.
 */
public interface ClientConfigRenderer {

    RenderedConfig render(ClientConfigRequest request, List<NodeClientView> nodes);
}
