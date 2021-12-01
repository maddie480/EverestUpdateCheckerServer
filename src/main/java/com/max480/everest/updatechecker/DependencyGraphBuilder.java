package com.max480.everest.updatechecker;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static com.max480.everest.updatechecker.DatabaseUpdater.openStreamWithTimeout;

public class DependencyGraphBuilder {
    private static final Logger log = LoggerFactory.getLogger(DependencyGraphBuilder.class);

    public static void main(String[] args) throws IOException {
        updateDependencyGraph();
    }

    static void updateDependencyGraph() throws IOException {
        Map<String, Map<String, Object>> oldDependencyGraph;
        try (InputStream is = new FileInputStream("uploads/moddependencygraph.yaml")) {
            oldDependencyGraph = new Yaml().load(is);
        }

        Map<String, Map<String, Object>> everestUpdate;
        try (InputStream is = new FileInputStream("uploads/everestupdate.yaml")) {
            everestUpdate = new Yaml().load(is);
        }

        Map<String, Map<String, Object>> newDependencyGraph = new HashMap<>();

        // go across every entry in everest_update.yaml.
        for (Map.Entry<String, Map<String, Object>> mod : everestUpdate.entrySet()) {
            String name = mod.getKey();
            String url = (String) mod.getValue().get(Main.serverConfig.mainServerIsMirror ? "MirrorURL" : "URL");
            String mirrorUrl = (String) mod.getValue().get(Main.serverConfig.mainServerIsMirror ? "URL" : "MirrorURL");

            // try to find a matching entry (same URL and same name) in the dependency graph we have.
            Map.Entry<String, Map<String, Object>> existingDependencyGraphEntry = oldDependencyGraph.entrySet()
                    .stream()
                    .filter(entry -> entry.getKey().equals(name) && (entry.getValue().get("URL")).equals(url))
                    .findFirst()
                    .orElse(null);

            if (existingDependencyGraphEntry != null) {
                // we already have that mod!
                newDependencyGraph.put(existingDependencyGraphEntry.getKey(), existingDependencyGraphEntry.getValue());
            } else {
                // download file from mirror
                DatabaseUpdater.runWithRetry(() -> {
                    try (OutputStream os = new BufferedOutputStream(new FileOutputStream("mod-dependencytree.zip"))) {
                        IOUtils.copy(new BufferedInputStream(openStreamWithTimeout(new URL(mirrorUrl))), os);
                        return null; // to fullfill this stupid method signature
                    }
                });

                // check that its size makes sense
                long actualSize = new File("mod-dependencytree.zip").length();
                if (((int) mod.getValue().get("Size")) != actualSize) {
                    FileUtils.forceDelete(new File("mod-dependencytree.zip"));
                    throw new IOException("The announced file size (" + mod.getValue().get("Size") + ") does not match what we got (" + actualSize + ")");
                }

                // read its everest.yaml
                Map<String, String> dependencies = new HashMap<>();
                Map<String, String> optionalDependencies = new HashMap<>();
                try (ZipFile zipFile = new ZipFile(new File("mod-dependencytree.zip"))) {
                    ZipEntry everestYaml = zipFile.getEntry("everest.yaml");
                    if (everestYaml == null) {
                        everestYaml = zipFile.getEntry("everest.yml");
                    }
                    if (everestYaml == null) {
                        everestYaml = zipFile.getEntry("multimetadata.yml");
                    }

                    List<Map<String, Object>> everestYamlContents;
                    try (InputStream is = zipFile.getInputStream(everestYaml)) {
                        everestYamlContents = new Yaml().load(is);
                    }

                    // if the file has multiple entries, this will find the one that has the same ID as our mod.
                    Map<String, Object> matchingYamlEntry = everestYamlContents.stream()
                            .filter(s -> s.get("Name").equals(mod.getKey())).findFirst().orElse(null);

                    // extract the Dependencies!
                    if (matchingYamlEntry.containsKey("Dependencies")) {
                        for (Map<String, String> dependencyEntry : (List<Map<String, String>>) matchingYamlEntry.get("Dependencies")) {
                            dependencies.put(dependencyEntry.get("Name"), dependencyEntry.getOrDefault("Version", "NoVersion"));
                        }
                    }
                    if (matchingYamlEntry.containsKey("OptionalDependencies")) {
                        for (Map<String, String> dependencyEntry : (List<Map<String, String>>) matchingYamlEntry.get("OptionalDependencies")) {
                            optionalDependencies.put(dependencyEntry.get("Name"), dependencyEntry.getOrDefault("Version", "NoVersion"));
                        }
                    }

                    log.info("Found {} dependencies and {} optional dependencies for {}.", dependencies.size(), optionalDependencies.size(), mod.getKey());
                } catch (Exception e) {
                    // if a file cannot be read as a zip, no need to worry about it.
                    // we will just write an empty array.
                    log.warn("Could not analyze dependency tree from {}", mod.getKey(), e);
                }

                // save the entry we just got.
                Map<String, Object> graphEntry = new HashMap<>();
                graphEntry.put("URL", url);
                graphEntry.put("Dependencies", dependencies);
                graphEntry.put("OptionalDependencies", optionalDependencies);
                newDependencyGraph.put(mod.getKey(), graphEntry);

                FileUtils.forceDelete(new File("mod-dependencytree.zip"));
            }
        }

        // write it out!
        FileUtils.writeStringToFile(
                new File("uploads/moddependencygraph.yaml"),
                new Yaml().dumpAs(newDependencyGraph, null, DumperOptions.FlowStyle.BLOCK),
                StandardCharsets.UTF_8);
    }
}
