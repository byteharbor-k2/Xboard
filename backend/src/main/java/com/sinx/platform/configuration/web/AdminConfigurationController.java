package com.sinx.platform.configuration.web;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sinx.platform.configuration.application.SiteConfigurationService;
import com.sinx.platform.shared.web.ApiProblemException;

@RestController
@RequestMapping("/api/v2/admin/config")
public class AdminConfigurationController {

    private final SiteConfigurationService siteConfiguration;

    public AdminConfigurationController(
        SiteConfigurationService siteConfiguration
    ) {
        this.siteConfiguration = siteConfiguration;
    }

    @GetMapping("/fetch")
    XboardResponse<Map<String, Map<String, Object>>> fetch(
        @RequestParam String key
    ) {
        assertSiteSection(key);
        return new XboardResponse<>(
            Map.of("site", siteConfiguration.siteSettings())
        );
    }

    @PostMapping("/save")
    XboardResponse<Boolean> save(
        @RequestParam String key,
        @RequestBody Map<String, Object> values
    ) {
        assertSiteSection(key);
        siteConfiguration.saveSiteSettings(values);
        return new XboardResponse<>(true);
    }

    private void assertSiteSection(String key) {
        if (!"site".equals(key)) {
            throw new ApiProblemException(
                HttpStatus.NOT_FOUND,
                "SETTINGS_SECTION_NOT_AVAILABLE",
                "This settings section is not available yet"
            );
        }
    }

    public record XboardResponse<T>(T data) {
    }
}
