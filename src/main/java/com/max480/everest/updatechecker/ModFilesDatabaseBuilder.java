package com.max480.everest.updatechecker;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class ModFilesDatabaseBuilder {
    private static final Logger log = LoggerFactory.getLogger(ModFilesDatabaseBuilder.class);

    public ModFilesDatabaseBuilder() throws IOException {
        Path modFilesDatabaseDir = Paths.get("uploads", "modfilesdatabase_temp");
        if (Files.isDirectory(modFilesDatabaseDir)) {
            FileUtils.deleteDirectory(modFilesDatabaseDir.toFile());
        }
    }

    public void addMod(Object fileList) throws IOException {
        // deal with mods with no file at all: in this case, GB sends out an empty list, not a map.
        // We should pay attention to this and handle this specifically.
        if (Collections.emptyList().equals(fileList)) return;

        for (Map<String, Object> file : ((Map<String, Map<String, Object>>) fileList).values()) {
            // only handle valid GameBanana links, as we use the GameBanana URL format to name our file.
            String fileUrl = ((String) file.get("_sDownloadUrl")).replace("dl", "mmdl");
            if (!fileUrl.matches("https://gamebanana.com/mmdl/[0-9]+")) {
                continue;
            }

            // crawl the file.
            List<String> resolvedList = new LinkedList<>();
            if (file.containsKey("_aMetadata") && file.get("_aMetadata") instanceof Map) {
                Map<String, Object> metadata = (Map<String, Object>) file.get("_aMetadata");
                if (metadata.containsKey("_aArchiveFileTree")) {
                    recursiveCheck(resolvedList, "", metadata.get("_aArchiveFileTree"));
                }
            }

            if (!resolvedList.isEmpty()) {
                // create temp mod files database if it does not exist
                Path modFilesDatabaseDir = Paths.get("uploads", "modfilesdatabase_temp");
                if (!Files.isDirectory(modFilesDatabaseDir)) {
                    Files.createDirectories(modFilesDatabaseDir);
                }

                // write this file listing in a new file named <fileid>.yaml
                try (FileWriter writer = new FileWriter(modFilesDatabaseDir
                        .resolve(fileUrl.substring("https://gamebanana.com/mmdl/".length()) + ".yaml").toFile())) {

                    new Yaml().dump(resolvedList, writer);
                }
            }
        }
    }

    private static void recursiveCheck(List<String> resolvedList, String currentDirectory, Object thing) {
        if (thing instanceof List) {
            // we are in a folder with no subfolders.
            for (Object s : (List<Object>) thing) {
                resolvedList.add(currentDirectory + s);
            }
        } else if (thing instanceof Map) {
            // we are in a folder **with** subfolders.
            for (Map.Entry<Object, Object> entry : ((Map<Object, Object>) thing).entrySet()) {
                if (entry.getValue() instanceof String) {
                    // simple file
                    resolvedList.add(currentDirectory + entry.getValue());
                } else {
                    // probably a subfolder!
                    recursiveCheck(resolvedList, currentDirectory + entry.getKey() + "/", entry.getValue());
                }
            }
        } else {
            log.debug("Found unidentified thing while crawling files on a zip: {}", thing);
        }
    }

    public void saveToDisk() throws IOException {
        // delete modfilesdatabase and move modfilesdatabase_temp to replace it.
        Path databasePath = Paths.get("uploads", "modfilesdatabase");
        Path databasePathTemp = Paths.get("uploads", "modfilesdatabase_temp");

        if (Files.isDirectory(databasePath)) {
            FileUtils.deleteDirectory(databasePath.toFile());
        }
        if (Files.isDirectory(databasePathTemp)) {
            Files.move(databasePathTemp, databasePath);
        }
    }
}
