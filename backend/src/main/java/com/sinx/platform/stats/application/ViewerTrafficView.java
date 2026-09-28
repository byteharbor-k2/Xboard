package com.sinx.platform.stats.application;

/**
 * One day-and-node slice of an account's billed traffic, as the account page
 * shows it. Byte counts ride as strings so a 64-bit value survives JSON.
 *
 * @param day the billing day, in the panel's billing timezone
 * @param nodeName the human node label; null when the node was deleted since
 * @param uploadBytes raw upload the node carried
 * @param downloadBytes raw download the node carried
 * @param billedBytes what the entitlement counters charged for both
 */
public record ViewerTrafficView(
    String day,
    String nodeName,
    long uploadBytes,
    long downloadBytes,
    long billedBytes
) {
}
