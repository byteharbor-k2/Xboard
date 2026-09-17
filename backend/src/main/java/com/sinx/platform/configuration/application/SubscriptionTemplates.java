package com.sinx.platform.configuration.application;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import com.sinx.platform.shared.web.ApiProblemException;

import tools.jackson.databind.ObjectMapper;

/**
 * The client templates an administrator can edit, and the copies that ship
 * inside the jar.
 *
 * A blank setting means "use the copy from the jar". That is what makes
 * clearing the box in the admin panel a restore rather than a way to hand every
 * user an empty configuration, and it is why a fresh install serves a working
 * config without anything being seeded into the database.
 *
 * Stored templates are parsed before they are accepted. A template that does
 * not parse is not a broken setting but a broken product: it replaces the
 * config of every user at once, so refusing it while the administrator can
 * still see what they pasted is worth the extra check.
 */
@Component
public class SubscriptionTemplates {

    /**
     * One entry per template the renderers can actually produce.
     *
     * The names are the original panel's, so an administrator who knows this
     * panel already knows them; the setting key is derived from the name rather
     * than listed a second time.
     */
    public enum Kind {
        CLASH("clash", "/subscribe/default.clash.yaml", Format.YAML),
        CLASH_META("clashmeta", "/subscribe/default.clashmeta.yaml", Format.YAML),
        SING_BOX("singbox", "/subscribe/default.sing-box.json", Format.JSON);

        private static final Map<String, Kind> BY_SETTING_KEY;

        static {
            Map<String, Kind> bySettingKey = new LinkedHashMap<>();
            for (Kind kind : values()) {
                bySettingKey.put(kind.settingKey(), kind);
            }
            BY_SETTING_KEY = Map.copyOf(bySettingKey);
        }

        private final String name;
        private final String resource;
        private final Format format;

        Kind(String name, String resource, Format format) {
            this.name = name;
            this.resource = resource;
            this.format = format;
        }

        /** The template a settings key names, or null when it names none. */
        public static Kind bySettingKey(String key) {
            return BY_SETTING_KEY.get(key);
        }

        public String templateName() {
            return name;
        }

        public String settingKey() {
            return "subscribe_template_" + name;
        }

        /** The key a settings section is read and written under. */
        public String sectionKey() {
            return "subscribe_template." + settingKey();
        }

        /**
         * Whether this template is read by the Clash renderer, whose YAML it
         * has to parse. The two flags share one renderer and differ only in
         * which protocols they can express, so this is not {@code this == CLASH}.
         */
        public boolean isClashFamily() {
            return format == Format.YAML;
        }
    }

    private enum Format {
        YAML,
        JSON
    }

    private final ObjectMapper json;
    private final Map<Kind, String> bundled = new LinkedHashMap<>();

    public SubscriptionTemplates(ObjectMapper json) {
        this.json = json;
        for (Kind kind : Kind.values()) {
            bundled.put(kind, readBundled(kind));
        }
    }

    /**
     * The template an administrator has stored, or the bundled one when they
     * have not.
     */
    public String effective(Kind kind, String stored) {
        return stored == null || stored.isBlank() ? bundled.get(kind) : stored;
    }

    public String bundled(Kind kind) {
        return bundled.get(kind);
    }

    /**
     * Rejects a template the renderer would choke on.
     *
     * Beyond parseability this insists on the top-level keys the renderer
     * writes into. A template missing them parses perfectly well and then fails
     * at request time for every user, which is the failure this check exists to
     * move earlier.
     */
    public void validate(Kind kind, String content) {
        Object parsed = read(kind, content);
        if (!(parsed instanceof Map<?, ?> root)) {
            throw invalidTemplate(kind, "the template must be a mapping");
        }
        for (String key : requiredKeys(kind)) {
            if (!root.containsKey(key)) {
                throw invalidTemplate(kind, "missing the top-level key '" + key + "'");
            }
        }
    }

    private Object read(Kind kind, String content) {
        try {
            return kind.isClashFamily()
                ? new Yaml().load(content)
                : json.readValue(content, Map.class);
        } catch (RuntimeException exception) {
            throw invalidTemplate(
                kind,
                kind.isClashFamily() ? "it is not valid YAML" : "it is not valid JSON"
            );
        }
    }

    private String[] requiredKeys(Kind kind) {
        return kind.isClashFamily()
            ? new String[] { "proxies", "proxy-groups", "rules" }
            : new String[] { "outbounds" };
    }

    private String readBundled(Kind kind) {
        ClassPathResource resource = new ClassPathResource(
            kind.resource,
            getClass().getClassLoader()
        );
        try (InputStream stream = resource.getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            // The default is packaged with the application, so a missing one is
            // a build that should never have been produced rather than anything
            // an administrator can fix.
            throw new UncheckedIOException(
                "Missing bundled subscription template " + kind.resource,
                exception
            );
        }
    }

    private ApiProblemException invalidTemplate(Kind kind, String reason) {
        return new ApiProblemException(
            HttpStatus.BAD_REQUEST,
            "SUBSCRIBE_TEMPLATE_INVALID",
            "The " + kind.templateName() + " template was not saved because " + reason
        );
    }
}
