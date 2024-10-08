package ovh.maddie480.everest.updatechecker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static ovh.maddie480.everest.updatechecker.DatabaseUpdater.checkZipSignature;

public class DependencyGraphBuilder {
    private static final Logger log = LoggerFactory.getLogger(DependencyGraphBuilder.class);

    static void updateDependencyGraph() throws IOException {
        log.debug("Loading mod databases...");
        Map<String, Map<String, Object>> oldDependencyGraph;
        try (InputStream is = Files.newInputStream(Paths.get("uploads/moddependencygraph.yaml"))) {
            oldDependencyGraph = YamlUtil.load(is);
        }

        Map<String, Map<String, Object>> everestUpdate;
        try (InputStream is = Files.newInputStream(Paths.get("uploads/everestupdate.yaml"))) {
            everestUpdate = YamlUtil.load(is);
        }

        Map<String, Map<String, Object>> newDependencyGraph = new HashMap<>();

        // go across every entry in everest_update.yaml.
        for (Map.Entry<String, Map<String, Object>> mod : everestUpdate.entrySet()) {
            String name = mod.getKey();
            String url = (String) mod.getValue().get(Main.serverConfig.mainServerIsMirror ? "MirrorURL" : "URL");

            // try to find a matching entry (same URL and same name) in the dependency graph we have.
            Map.Entry<String, Map<String, Object>> existingDependencyGraphEntry = oldDependencyGraph.entrySet()
                    .stream()
                    .filter(entry -> entry.getKey().equals(name) && (entry.getValue().get("URL")).equals(url))
                    .findFirst()
                    .orElse(null);

            if (existingDependencyGraphEntry != null) {
                log.trace("Mod {} was already in the dependency graph, copying its data.", name);
                newDependencyGraph.put(existingDependencyGraphEntry.getKey(), existingDependencyGraphEntry.getValue());
            } else {
                // download file
                Path file = FileDownloader.downloadFile(url, (int) mod.getValue().get("Size"));

                // read its everest.yaml
                Map<String, String> dependencies = new HashMap<>();
                Map<String, String> optionalDependencies = new HashMap<>();
                try (ZipFile zipFile = ZipFileWithAutoEncoding.open(file.toAbsolutePath().toString())) {
                    checkZipSignature(file);

                    ZipEntry everestYaml = zipFile.getEntry("everest.yaml");
                    if (everestYaml == null) {
                        everestYaml = zipFile.getEntry("everest.yml");
                    }

                    List<Map<String, Object>> everestYamlContents;
                    try (InputStream is = zipFile.getInputStream(everestYaml)) {
                        everestYamlContents = YamlUtil.loadNoFloats(is);
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

                    log.info("Found {} dependencies and {} optional dependencies for for {}.",
                            dependencies.size(), optionalDependencies.size(), mod.getKey());
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
            }
        }

        // write it out!
        log.debug("Writing graph...");
        try (OutputStream os = new FileOutputStream("uploads/moddependencygraph.yaml")) {
            YamlUtil.dump(newDependencyGraph, os);
        }
    }

    private static void addDependenciesFromList(Map<String, String> addTo, List<Map<String, Object>> toAdd, List<Map<String, Object>> everestYamlContents) {
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
