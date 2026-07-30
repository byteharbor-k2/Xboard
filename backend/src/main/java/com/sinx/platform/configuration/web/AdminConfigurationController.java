package com.sinx.platform.configuration.web;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sinx.platform.configuration.application.PlatformConfigurationService;

@RestController
@RequestMapping("/api/v2/admin/config")
public class AdminConfigurationController {

    private final PlatformConfigurationService configuration;

    public AdminConfigurationController(
        PlatformConfigurationService configuration
    ) {
        this.configuration = configuration;
    }

    @GetMapping("/fetch")
    XboardResponse<Map<String, Map<String, Object>>> fetch(
        @RequestParam String key
    ) {
        return new XboardResponse<>(
            Map.of(key, configuration.sectionSettings(key))
        );
    }

    @PostMapping("/save")
    XboardResponse<Boolean> save(
        @RequestParam String key,
        @RequestBody Map<String, Object> values
    ) {
        configuration.saveSectionSettings(key, values);
        return new XboardResponse<>(true);
    }

    public record XboardResponse<T>(T data) {
    }
}
