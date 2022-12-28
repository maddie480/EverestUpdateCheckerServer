package com.max480.everest.updatechecker;

import org.apache.commons.io.IOUtils;
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

        yaml = new Yaml(new SafeConstructor(loaderOptions), new Representer(new DumperOptions()));
    }

    /**
     * Loads YAML data from an input stream.
     */
    public static <T> T load(InputStream is) {
        return yaml.load(is);
    }

    /**
     * Dumps YAML data to an output stream.
     */
    public static void dump(Object data, OutputStream os) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8))) {
            IOUtils.write(yaml.dumpAs(data, null, DumperOptions.FlowStyle.BLOCK), writer);
        }
    }
}
