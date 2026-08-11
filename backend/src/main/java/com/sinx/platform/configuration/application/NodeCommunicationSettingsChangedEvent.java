package com.sinx.platform.configuration.application;

/**
 * Internal runtime consequences of a committed node communication setting
 * change. Polling intervals and device-limit behavior intentionally do not
 * imply a hot-reload protocol with xboard-node.
 */
public record NodeCommunicationSettingsChangedEvent(
    boolean legacyTokenChanged,
    boolean webSocketDisabled
) {
}
