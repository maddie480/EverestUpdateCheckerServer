package com.max480.everest.updatechecker;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.representer.Representer;

import java.io.*;
import java.nio.charset.StandardCharsets;

/**
 * A utility class to parse and dump YAML objects.
 */
public class YamlUtil {
    private static final Yaml yaml;

    static {
        // mod_search_database.yaml is larger than 3 MB, which is the default code point limit in SnakeYAML.
        // So we need to raise it a bit! We set it to 8 MB instead.
        LoaderOptions loaderOptions = new LoaderOptions();
        loaderOptions.setCodePointLimit(8 * 1024 * 1024);

        // use the block flow style rather than the default "block at the root level, flow on deeper levels" default.
        DumperOptions dumperOptions = new DumperOptions();
        dumperOptions.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);

        // use SafeConstructor to avoid callers being able to construct arbitrary Java objects
        yaml = new Yaml(new SafeConstructor(loaderOptions), new Representer(dumperOptions), dumperOptions, loaderOptions);
    }

    /**
     * Loads YAML data from an input stream.
     */
    public static <T> T load(InputStream is) {
        synchronized (yaml) {
            return yaml.load(is);
        }
    }

    /**
     * Dumps YAML data to an output stream.
     */
    public static void dump(Object data, OutputStream os) throws IOException {
        synchronized (yaml) {
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8))) {
                yaml.dump(data, writer);
            }
        }
    }
}
