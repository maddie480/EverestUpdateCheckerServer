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
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static com.max480.everest.updatechecker.DatabaseUpdater.checkZipSignature;
import static com.max480.everest.updatechecker.DatabaseUpdater.openStreamWithTimeout;

public class DependencyGraphBuilder {
    private static final Logger log = LoggerFactory.getLogger(DependencyGraphBuilder.class);

    static void updateDependencyGraph() throws IOException {
        Map<String, Map<String, Object>> oldDependencyGraph;
        try (InputStream is = Files.newInputStream(Paths.get("uploads/moddependencygraph.yaml"))) {
            oldDependencyGraph = new Yaml().load(is);
        }

        Map<String, Map<String, Object>> everestUpdate;
        try (InputStream is = Files.newInputStream(Paths.get("uploads/everestupdate.yaml"))) {
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
                    try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(Paths.get("mod-dependencytree.zip")))) {
                        IOUtils.copy(new BufferedInputStream(openStreamWithTimeout(new URL(mirrorUrl))), os);
                        return null; // to fulfill this stupid method signature
                    }
                });

                // check that its size makes sense
                long actualSize = new File("mod-dependencytree.zip").length();
                if (((int) mod.getValue().get("Size")) != actualSize) {
                    FileUtils.forceDelete(new File("mod-dependencytree.zip"));
                    throw new IOException("The announced file size (" + mod.getValue().get("Size") + ") does not match what we got (" + actualSize + ")" +
                            " for file " + mirrorUrl);
                }

                // read its everest.yaml
                Map<String, String> dependencies = new HashMap<>();
                Map<String, String> optionalDependencies = new HashMap<>();
                try (ZipFile zipFile = new ZipFile(new File("mod-dependencytree.zip"))) {
                    checkZipSignature(new File("mod-dependencytree.zip").toPath());

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

                    // merge the Dependencies and OptionalDependencies of all mods defined in the everest.yaml
                    for (Map<String, Object> yamlEntry : everestYamlContents) {
                        if (yamlEntry.containsKey("Dependencies")) {
                            addDependenciesFromList(dependencies, (List<Map<String, Object>>) yamlEntry.get("Dependencies"), everestYamlContents);
                        }
                        if (yamlEntry.containsKey("OptionalDependencies")) {
                            addDependenciesFromList(optionalDependencies, (List<Map<String, Object>>) yamlEntry.get("OptionalDependencies"), everestYamlContents);
                        }
                    }

                    log.info("Found {} dependencies and {} optional dependencies for {}.", dependencies.size(), optionalDependencies.size(), mod.getKey());
                    EventListener.handle(listener -> listener.scannedModDependencies(mod.getKey(), dependencies.size(), optionalDependencies.size()));
                } catch (Exception e) {
                    // if a file cannot be read as a zip, no need to worry about it.
                    // we will just write an empty array.
                    log.warn("Could not analyze dependency tree from {}", mod.getKey(), e);
                    EventListener.handle(listener -> listener.dependencyTreeScanException(mod.getKey(), e));
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

    private static void addDependenciesFromList(Map<String, Object> addTo, List<Map<String, Object>> toAdd, List<Map<String, Object>> everestYamlContents) {
        for (Map<String, Object> dependencyEntry : toAdd) {
            String name = dependencyEntry.get("Name").toString();
            String version = dependencyEntry.getOrDefault("Version", "NoVersion").toString();

            // only keep the dependencies if they weren't already added, and they aren't defined in the same yaml file.
            if (!addTo.containsKey(name) && everestYamlContents.stream().noneMatch(entry -> name.equals(entry.get("Name").toString()))) {
                addTo.put(name, version);
            }
        }
    }
}
