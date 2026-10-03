package com.sinx.platform.catalog.graphql;

import java.util.List;
import java.util.UUID;

import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Controller;

import com.sinx.platform.catalog.application.CatalogService;
import com.sinx.platform.catalog.application.PlanOfferView;

@Controller
public class CatalogController {

    private final CatalogService catalogService;

    public CatalogController(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @QueryMapping
    List<PlanOfferView> offerCatalog(
        @AuthenticationPrincipal(errorOnInvalidType = false) Jwt jwt
    ) {
        UUID viewerId = jwt == null ? null : UUID.fromString(jwt.getSubject());
        return catalogService.availableOffers(viewerId);
    }
}
