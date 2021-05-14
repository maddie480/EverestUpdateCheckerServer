package com.max480.everest.updatechecker;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static com.max480.everest.updatechecker.DatabaseUpdater.openStreamWithTimeout;

public class ModFilesDatabaseBuilder {
    private static final Logger log = LoggerFactory.getLogger(ModFilesDatabaseBuilder.class);

    private final List<String> fullList = new LinkedList<>();
    private final List<String> fullFileIdList = new LinkedList<>();

    public ModFilesDatabaseBuilder() throws IOException {
        Path modFilesDatabaseDir = Paths.get("modfilesdatabase_temp");
        if (Files.isDirectory(modFilesDatabaseDir)) {
            FileUtils.deleteDirectory(modFilesDatabaseDir.toFile());
        }
    }

    public void addMod(String itemtype, int itemid, String modname, List<String> urls, List<Integer> expectedSizes) throws IOException {
        if (urls.isEmpty()) {
            // nothing to do at all!
            return;
        }

        // create the mod files database directory for this mod.
        Path modFilesDatabaseDir = Paths.get("modfilesdatabase_temp", itemtype, Integer.toString(itemid));
        if (!Files.isDirectory(modFilesDatabaseDir)) {
            Files.createDirectories(modFilesDatabaseDir);
        }
        fullList.add(itemtype + "/" + itemid);

        List<String> createdYamls = new LinkedList<>();

        int index = 0;
        for (String fileUrl : urls) {
            int expectedSize = expectedSizes.get(index++);

            // only handle valid GameBanana links, as we use the GameBanana URL format to name our file.
            if (!fileUrl.matches("https://gamebanana.com/mmdl/[0-9]+")) {
                continue;
            }

            // this is the path we are writing the list to.
            String fileid = fileUrl.substring("https://gamebanana.com/mmdl/".length());
            Path listPath = modFilesDatabaseDir.resolve(fileid + ".yaml");
            createdYamls.add(fileid);

            fullFileIdList.add(fileid);

            Path cachedFilesPath = Paths.get("modfilesdatabase", itemtype, Integer.toString(itemid), fileid + ".yaml");
            if (Files.exists(cachedFilesPath)) {
                // we already downloaded this file before! time to copy it over.
                log.debug("Copying file from {} for url {}", cachedFilesPath, fileUrl);
                Files.copy(cachedFilesPath, listPath);
            } else {
                log.debug("Downloading {} to get its file listing...", fileUrl);

                // download file
                DatabaseUpdater.runWithRetry(() -> {
                    try (OutputStream os = new BufferedOutputStream(new FileOutputStream("mod-filescan.zip"))) {
                        IOUtils.copy(new BufferedInputStream(openStreamWithTimeout(new URL(fileUrl))), os);
                        return null; // to fullfill this stupid method signature
                    }
                });

                // check that its size makes sense
                long actualSize = new File("mod-filescan.zip").length();
                if (expectedSize != actualSize) {
                    FileUtils.forceDelete(new File("mod-filescan.zip"));
                    throw new IOException("The announced file size (" + expectedSize + ") does not match what we got (" + actualSize + ")");
                }

                // go through it!
                List<String> filePaths = new LinkedList<>();
                try (ZipFile zipFile = new ZipFile(new File("mod-filescan.zip"))) {
                    final Enumeration<? extends ZipEntry> entriesEnum = zipFile.entries();
                    while (entriesEnum.hasMoreElements()) {
                        try {
                            ZipEntry entry = entriesEnum.nextElement();
                            if (!entry.isDirectory()) {
                                filePaths.add(entry.getName());
                            }
                        } catch (IllegalArgumentException e) {
                            log.warn("Encountered error while going through zip file", e);
                        }
                    }

                    log.info("Found {} file(s) in {}.", filePaths.size(), fileUrl);
                } catch (IOException | IllegalArgumentException e) {
                    // if a file cannot be read as a zip, no need to worry about it.
                    // we will just write an empty array.
                    log.warn("Could not analyze zip from {}", fileUrl, e);
                }

                FileUtils.forceDelete(new File("mod-filescan.zip"));

                // write the result.
                try (FileWriter writer = new FileWriter(listPath.toFile())) {
                    new Yaml().dump(filePaths, writer);
                }
            }
        }

        // write the mod name and file list in there.
        try (FileWriter writer = new FileWriter(modFilesDatabaseDir.resolve("info.yaml").toFile())) {
            Map<String, Object> data = new HashMap<>();
            data.put("Name", modname);
            data.put("Files", createdYamls);
            new Yaml().dump(data, writer);
        }
    }

    public void saveToDisk() throws IOException {
        // write the files list to disk.
        try (FileWriter writer = new FileWriter("modfilesdatabase_temp/file_ids.yaml")) {
            new Yaml().dump(fullFileIdList, writer);
        }

        try (FileWriter writer = new FileWriter("modfilesdatabase_temp/list.yaml")) {
            new Yaml().dump(fullList, writer);
        }

        checkForAhornPlugins();

        // delete modfilesdatabase and move modfilesdatabase_temp to replace it.
        Path databasePath = Paths.get("modfilesdatabase");
        Path databasePathTemp = Paths.get("modfilesdatabase_temp");

        if (Files.isDirectory(databasePath)) {
            FileUtils.deleteDirectory(databasePath.toFile());
        }
        if (Files.isDirectory(databasePathTemp)) {
            Files.move(databasePathTemp, databasePath);
        }
    }

    private void checkForAhornPlugins() throws IOException {
        {
            List<String> ahornEntities = new LinkedList<>();
            List<String> ahornTriggers = new LinkedList<>();
            List<String> ahornEffects = new LinkedList<>();

            try (InputStream is = new URL("https://raw.githubusercontent.com/CelestialCartographers/Maple/master/src/entity.jl").openStream()) {
                extractAhornEntities(ahornEntities, ahornTriggers, ahornEffects, "Ahorn/entities/vanilla.jl", is);
            }
            try (InputStream is = new URL("https://raw.githubusercontent.com/CelestialCartographers/Maple/master/src/trigger.jl").openStream()) {
                extractAhornEntities(ahornEntities, ahornTriggers, ahornEffects, "Ahorn/triggers/vanilla.jl", is);
            }
            try (InputStream is = new URL("https://raw.githubusercontent.com/CelestialCartographers/Maple/master/src/style.jl").openStream()) {
                extractAhornEntities(ahornEntities, ahornTriggers, ahornEffects, "Ahorn/effects/vanilla.jl", is);
            }

            try (FileWriter writer = new FileWriter(Paths.get("modfilesdatabase_temp/ahorn_vanilla.yaml").toFile())) {
                Map<String, List<String>> ahornPlugins = new HashMap<>();
                ahornPlugins.put("Entities", ahornEntities);
                ahornPlugins.put("Triggers", ahornTriggers);
                ahornPlugins.put("Effects", ahornEffects);
                new Yaml().dump(ahornPlugins, writer);
            }
        }

        for (String mod : fullList) {
            // get the versions list
            Path modFolder = Paths.get("modfilesdatabase_temp/" + mod);
            Map<String, Object> versions;
            try (InputStream is = new FileInputStream(modFolder.resolve("info.yaml").toFile())) {
                versions = new Yaml().load(is);
            }

            for (String version : (List<String>) versions.get("Files")) {
                Path oldPath = Paths.get("modfilesdatabase/" + mod + "/ahorn_" + version + ".yaml");
                Path targetPath = modFolder.resolve("ahorn_" + version + ".yaml");

                if (Files.exists(oldPath)) {
                    // this zip was already scanned!
                    Files.copy(oldPath, targetPath);
                } else {
                    List<String> fileList;
                    try (InputStream is = new FileInputStream(modFolder.resolve(version + ".yaml").toFile())) {
                        fileList = new Yaml().load(is);
                    }

                    if (fileList.stream().anyMatch(f -> f.startsWith("Ahorn/"))) {
                        List<String> ahornEntities = new LinkedList<>();
                        List<String> ahornTriggers = new LinkedList<>();
                        List<String> ahornEffects = new LinkedList<>();

                        // download file
                        DatabaseUpdater.runWithRetry(() -> {
                            try (OutputStream os = new BufferedOutputStream(new FileOutputStream("mod-ahornscan.zip"))) {
                                IOUtils.copy(new BufferedInputStream(openStreamWithTimeout(new URL("https://gamebanana.com/mmdl/" + version))), os);
                                return null; // to fullfill this stupid method signature
                            }
                        });

                        // scan its contents, opening Ahorn plugin files
                        try (ZipFile zipFile = new ZipFile(new File("mod-ahornscan.zip"))) {
                            for (String file : fileList) {
                                if (file.startsWith("Ahorn/") && file.endsWith(".jl")) {
                                    InputStream inputStream = zipFile.getInputStream(zipFile.getEntry(file));
                                    extractAhornEntities(ahornEntities, ahornTriggers, ahornEffects, file, inputStream);
                                }
                            }

                            log.info("Found {} Ahorn entities, {} triggers, {} effects in https://gamebanana.com/mmdl/{}.",
                                    ahornEntities.size(), ahornTriggers.size(), ahornEffects.size(), version);
                        } catch (IOException | IllegalArgumentException e) {
                            // if a file cannot be read as a zip, no need to worry about it.
                            // we will just write an empty array.
                            log.warn("Could not analyze Ahorn plugins from https://gamebanana.com/mmdl/{}", version, e);
                        }

                        // write the result.
                        try (FileWriter writer = new FileWriter(targetPath.toFile())) {
                            Map<String, List<String>> ahornPlugins = new HashMap<>();
                            ahornPlugins.put("Entities", ahornEntities);
                            ahornPlugins.put("Triggers", ahornTriggers);
                            ahornPlugins.put("Effects", ahornEffects);
                            new Yaml().dump(ahornPlugins, writer);
                        }

                        FileUtils.forceDelete(new File("mod-ahornscan.zip"));
                    }
                }
            }
        }
    }

    private void extractAhornEntities(List<String> ahornEntities, List<String> ahornTriggers, List<String> ahornEffects,
                                      String file, InputStream inputStream) throws IOException {

        Pattern mapdefMatcher = Pattern.compile(".*@mapdef [A-Za-z]+ \"([^\"]+)\".*");
        Pattern pardefMatcher = Pattern.compile(".*Entity\\(\"([^\"]+)\".*");

        try (BufferedReader br = new BufferedReader(new InputStreamReader(inputStream))) {
            String line;
            while ((line = br.readLine()) != null) {
                String entityID = null;

                Matcher mapdefMatch = mapdefMatcher.matcher(line);
                if (mapdefMatch.matches()) {
                    entityID = mapdefMatch.group(1);
                }
                Matcher pardefMatch = pardefMatcher.matcher(line);
                if (pardefMatch.matches()) {
                    entityID = pardefMatch.group(1);
                }

                if (entityID != null) {
                    if (file.startsWith("Ahorn/effects/")) {
                        ahornEffects.add(entityID);
                    } else if (file.startsWith("Ahorn/entities/")) {
                        ahornEntities.add(entityID);
                    } else if (file.startsWith("Ahorn/triggers/")) {
                        ahornTriggers.add(entityID);
                    }
                }
            }
        }
    }
}
