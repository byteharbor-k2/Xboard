package com.sinx.platform.catalog.graphql;

import java.util.List;

import org.springframework.graphql.data.method.annotation.QueryMapping;
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
    List<PlanOfferView> offerCatalog() {
        return catalogService.availableOffers();
    }
}
