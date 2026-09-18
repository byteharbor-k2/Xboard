package com.sinx.platform.configuration.graphql;

import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import com.sinx.platform.configuration.application.PlatformConfigurationService;

/**
 * The one piece of configuration an anonymous visitor may read.
 *
 * The account dashboard builds a deep link per client, and the name it carries
 * is what the client shows for the imported profile. That has to be the name
 * the operator set, not a constant compiled into the bundle, or renaming the
 * site in the admin panel would leave every imported profile under the old
 * name. Everything else this module owns stays on the admin surface.
 */
@Controller
public class SiteController {

    private final PlatformConfigurationService configuration;

    public SiteController(PlatformConfigurationService configuration) {
        this.configuration = configuration;
    }

    @QueryMapping
    String siteName() {
        return configuration.appName();
    }
}
