package com.sinx.platform.subscription.client;

import java.util.LinkedHashMap;
import java.util.Map;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Reading and writing the Clash templates.
 *
 * Keys keep the order the template wrote them in, because the output is a
 * config an administrator will read and diff; a template that puts {@code dns}
 * first should not come back with it last.
 *
 * The exact whitespace does not match the original's dumper, which is PHP's.
 * Both write block YAML that every Clash client parses to the same document,
 * and no client cares whether a sequence is indented two spaces or four.
 */
final class ClashYaml {

    private ClashYaml() {
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> parse(String template) {
        Object parsed = new Yaml(new SafeConstructor(new LoaderOptions())).load(template);
        if (!(parsed instanceof Map<?, ?> map)) {
            throw new IllegalStateException(
                "The stored Clash template is not a YAML mapping; it should have been "
                    + "rejected when it was saved."
            );
        }
        Map<String, Object> config = new LinkedHashMap<>();
        map.forEach((key, value) -> config.put(String.valueOf(key), value));
        return config;
    }

    static String dump(Map<String, Object> config) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setIndent(4);
        options.setIndicatorIndent(2);
        options.setWidth(Integer.MAX_VALUE);
        options.setSplitLines(false);
        return new Yaml(options).dump(config);
    }

    /** A sequence value, or an empty one when the key holds something else. */
    static java.util.List<Object> list(Map<String, Object> config, String key) {
        return config.get(key) instanceof java.util.List<?> list
            ? new java.util.ArrayList<>(list)
            : new java.util.ArrayList<>();
    }
}
