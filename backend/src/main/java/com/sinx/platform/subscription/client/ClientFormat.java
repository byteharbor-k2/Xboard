package com.sinx.platform.subscription.client;

import java.util.List;
import java.util.Set;

import com.sinx.platform.configuration.application.SubscriptionTemplates;

/**
 * One output format, and the clients that ask for it by name.
 *
 * @param name       what gets reported when the format needs naming
 * @param flags      the client names that select it, lower case. The original
 *                   asks each class whether any of its flags is a substring of
 *                   the request's flag or User-Agent, so {@code clash-verge}
 *                   selects the mihomo format through its {@code verge} flag.
 * @param protocols  the node protocols this format can express. A node outside
 *                   the list is not rendered, because a config that names a
 *                   protocol the client cannot build is a config that fails to
 *                   load rather than one with a node missing.
 * @param templateKind which template this format renders into, or null for the
 *                   generic URI list, which is built in code
 */
public record ClientFormat(
    String name,
    List<String> flags,
    Set<String> protocols,
    SubscriptionTemplates.Kind templateKind,
    ClientConfigRenderer renderer
) {

    public boolean accepts(String protocol) {
        return protocols.contains(protocol);
    }
}
