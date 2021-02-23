package com.max480.everest.updatechecker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

public class ModFilesDatabaseBuilder {
    private static final Logger log = LoggerFactory.getLogger(ModFilesDatabaseBuilder.class);

    private final Map<String, List<String>> modFilesDatabase = new HashMap<>();

    public void addMod(Object fileList) {
        // deal with mods with no file at all: in this case, GB sends out an empty list, not a map.
        // We should pay attention to this and handle this specifically.
        if (Collections.emptyList().equals(fileList)) return;

        for (Map<String, Object> file : ((Map<String, Map<String, Object>>) fileList).values()) {
            String fileUrl = ((String) file.get("_sDownloadUrl")).replace("dl", "mmdl");
            List<String> resolvedList = new LinkedList<>();

            if (file.containsKey("_aMetadata") && file.get("_aMetadata") instanceof Map) {
                Map<String, Object> metadata = (Map<String, Object>) file.get("_aMetadata");
                if (metadata.containsKey("_aArchiveFileTree")) {
                    recursiveCheck(resolvedList, "", metadata.get("_aArchiveFileTree"));
                }
            }

            if (!resolvedList.isEmpty()) {
                modFilesDatabase.put(fileUrl, resolvedList);
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
        // map ModSearchInfo's to Maps and save them.
        try (FileWriter writer = new FileWriter("uploads/modfilesdatabase.yaml")) {
            new Yaml().dump(modFilesDatabase, writer);
        }
    }
}
