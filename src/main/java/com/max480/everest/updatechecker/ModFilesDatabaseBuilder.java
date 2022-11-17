package com.max480.everest.updatechecker;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static com.max480.everest.updatechecker.DatabaseUpdater.*;

public class ModFilesDatabaseBuilder {
    private static final Logger log = LoggerFactory.getLogger(ModFilesDatabaseBuilder.class);

    private final List<String> fullList = new LinkedList<>();
    private final List<String> fullFileIdList = new LinkedList<>();

    ModFilesDatabaseBuilder() throws IOException {
        Path modFilesDatabaseDir = Paths.get("modfilesdatabase_temp");
        if (Files.isDirectory(modFilesDatabaseDir)) {
            FileUtils.deleteDirectory(modFilesDatabaseDir.toFile());
        }
    }

    void addMod(String itemtype, int itemid, String modname, List<String> urls, List<Integer> expectedSizes) throws IOException {
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
                    try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(Paths.get("mod-filescan.zip")))) {
                        IOUtils.copy(new BufferedInputStream(openStreamWithTimeout(new URL(fileUrl))), os);
                        return null; // to fulfill this stupid method signature
                    }
                });

                // check that its size makes sense
                long actualSize = new File("mod-filescan.zip").length();
                if (expectedSize != actualSize) {
                    FileUtils.forceDelete(new File("mod-filescan.zip"));
                    throw new IOException("The announced file size (" + expectedSize + ") does not match what we got (" + actualSize + ")" +
                            " for file" + fileUrl + " belonging to " + itemtype + " " + itemid);
                }

                // go through it!
                List<String> filePaths = new LinkedList<>();
                try (ZipFile zipFile = ZipFileWithAutoEncoding.open("mod-filescan.zip", fileUrl)) {
                    checkZipSignature(new File("mod-filescan.zip").toPath());

                    final Enumeration<? extends ZipEntry> entriesEnum = zipFile.entries();
                    while (entriesEnum.hasMoreElements()) {
                        try {
                            ZipEntry entry = entriesEnum.nextElement();
                            if (!entry.isDirectory()) {
                                filePaths.add(entry.getName());
                            }
                        } catch (IllegalArgumentException e) {
                            log.warn("Encountered error while going through zip file", e);
                            EventListener.handle(listener -> listener.zipFileWalkthroughError(itemtype, itemid, fileUrl, e));
                        }
                    }

                    log.info("Found {} file(s) in {}.", filePaths.size(), fileUrl);
                    EventListener.handle(listener -> listener.scannedZipContents(fileUrl, filePaths.size()));
                } catch (IOException | IllegalArgumentException e) {
                    // if a file cannot be read as a zip, no need to worry about it.
                    // we will just write an empty array.
                    log.warn("Could not analyze zip from {}", fileUrl, e);
                    EventListener.handle(listener -> listener.zipFileIsUnreadableForFileListing(itemtype, itemid, fileUrl, e));
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

    List<String> getFileIds() {
        return fullFileIdList;
    }

    void saveToDisk(boolean full) throws IOException {
        if (!full) {
            fillInGapsForIncrementalUpdate();
        }

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

    private void fillInGapsForIncrementalUpdate() throws IOException {
        List<String> mods;
        try (InputStream is = Files.newInputStream(Paths.get("modfilesdatabase/list.yaml"))) {
            mods = new Yaml().load(is);
        }

        for (String mod : mods) {
            if (fullList.contains(mod)) {
                // this mod was updated incrementally, so we don't need to get it.
                continue;
            }

            // load this mod's info
            Map<String, Object> fileInfo;
            try (InputStream is = Files.newInputStream(Paths.get("modfilesdatabase/" + mod + "/info.yaml"))) {
                fileInfo = new Yaml().load(is);
            }

            // carry over all information from the old mod files database
            Files.createDirectories(Paths.get("modfilesdatabase_temp/" + mod).getParent());
            FileUtils.copyDirectory(new File("modfilesdatabase/" + mod), new File("modfilesdatabase_temp/" + mod));
            fullFileIdList.addAll((List<String>) fileInfo.get("Files"));
            fullList.add(mod);
        }
    }

    private void checkForAhornPlugins() throws IOException {
        {
            List<String> ahornEntities = runWithRetry(() -> {
                try (InputStream is = openStreamWithTimeout(new URL("https://raw.githubusercontent.com/CelestialCartographers/Maple/master/src/entity.jl"))) {
                    List<String> entities = new LinkedList<>();
                    extractAhornEntities(entities, null, null, "Ahorn/entities/vanilla.jl", is);
                    return entities;
                }
            });
            List<String> ahornTriggers = runWithRetry(() -> {
                try (InputStream is = openStreamWithTimeout(new URL("https://raw.githubusercontent.com/CelestialCartographers/Maple/master/src/trigger.jl"))) {
                    List<String> triggers = new LinkedList<>();
                    extractAhornEntities(null, triggers, null, "Ahorn/triggers/vanilla.jl", is);
                    return triggers;
                }
            });
            List<String> ahornEffects = runWithRetry(() -> {
                try (InputStream is = openStreamWithTimeout(new URL("https://raw.githubusercontent.com/CelestialCartographers/Maple/master/src/style.jl"))) {
                    List<String> effects = new LinkedList<>();
                    extractAhornEntities(null, null, effects, "Ahorn/effects/vanilla.jl", is);
                    return effects;
                }
            });

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
            try (InputStream is = Files.newInputStream(modFolder.resolve("info.yaml"))) {
                versions = new Yaml().load(is);
            }

            for (String version : (List<String>) versions.get("Files")) {
                checkAhornFilesDatabase(mod, modFolder, version);
                checkLoennFilesDatabase(mod, modFolder, version);
            }
        }
    }

    private void checkAhornFilesDatabase(String mod, Path modFolder, String version) throws IOException {
        Path oldPath = Paths.get("modfilesdatabase/" + mod + "/ahorn_" + version + ".yaml");
        Path targetPath = modFolder.resolve("ahorn_" + version + ".yaml");

        if (Files.exists(oldPath)) {
            // this zip was already scanned!
            if (!Files.exists(targetPath)) {
                Files.copy(oldPath, targetPath);
            }
        } else {
            List<String> fileList;
            try (InputStream is = Files.newInputStream(modFolder.resolve(version + ".yaml"))) {
                fileList = new Yaml().load(is);
            }

            if (fileList.stream().anyMatch(f -> f.startsWith("Ahorn/"))) {
                List<String> ahornEntities = new LinkedList<>();
                List<String> ahornTriggers = new LinkedList<>();
                List<String> ahornEffects = new LinkedList<>();

                // download file
                DatabaseUpdater.runWithRetry(() -> {
                    try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(Paths.get("mod-ahornscan.zip")))) {
                        IOUtils.copy(new BufferedInputStream(openStreamWithTimeout(new URL("https://gamebanana.com/mmdl/" + version))), os);
                        return null; // to fulfill this stupid method signature
                    }
                });

                // scan its contents, opening Ahorn plugin files
                try (ZipFile zipFile = ZipFileWithAutoEncoding.open("mod-ahornscan.zip")) {
                    checkZipSignature(new File("mod-ahornscan.zip").toPath());

                    for (String file : fileList) {
                        if (file.startsWith("Ahorn/") && file.endsWith(".jl")) {
                            InputStream inputStream = zipFile.getInputStream(zipFile.getEntry(file));
                            extractAhornEntities(ahornEntities, ahornTriggers, ahornEffects, file, inputStream);
                        }
                    }

                    log.info("Found {} Ahorn entities, {} triggers, {} effects in https://gamebanana.com/mmdl/{}.",
                            ahornEntities.size(), ahornTriggers.size(), ahornEffects.size(), version);
                    EventListener.handle(listener -> listener.scannedAhornEntities("https://gamebanana.com/mmdl/" + version,
                            ahornEntities.size(), ahornTriggers.size(), ahornEffects.size()));
                } catch (IOException | IllegalArgumentException e) {
                    // if a file cannot be read as a zip, no need to worry about it.
                    // we will just write an empty array.
                    log.warn("Could not analyze Ahorn plugins from https://gamebanana.com/mmdl/{}", version, e);
                    EventListener.handle(listener -> listener.ahornPluginScanError("https://gamebanana.com/mmdl/" + version, e));
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

    private void extractAhornEntities(List<String> ahornEntities, List<String> ahornTriggers, List<String> ahornEffects,
                                      String file, InputStream inputStream) throws IOException {

        Pattern mapdefMatcher = Pattern.compile(".*@mapdef(?:data)? [A-Za-z]+ \"([^\"]+)\".*");
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

    private void checkLoennFilesDatabase(String mod, Path modFolder, String version) throws IOException {
        Path oldPath = Paths.get("modfilesdatabase/" + mod + "/loenn_" + version + ".yaml");
        Path targetPath = modFolder.resolve("loenn_" + version + ".yaml");

        if (Files.exists(oldPath)) {
            // this zip was already scanned!
            if (!Files.exists(targetPath)) {
                Files.copy(oldPath, targetPath);
            }
        } else {
            List<String> fileList;
            try (InputStream is = Files.newInputStream(modFolder.resolve(version + ".yaml"))) {
                fileList = new Yaml().load(is);
            }

            if (fileList.contains("Loenn/lang/en_gb.lang")) {
                Set<String> loennEntities = new HashSet<>();
                Set<String> loennTriggers = new HashSet<>();
                Set<String> loennEffects = new HashSet<>();

                // download file
                DatabaseUpdater.runWithRetry(() -> {
                    try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(Paths.get("mod-loennscan.zip")))) {
                        IOUtils.copy(new BufferedInputStream(openStreamWithTimeout(new URL("https://gamebanana.com/mmdl/" + version))), os);
                        return null; // to fulfill this stupid method signature
                    }
                });

                // extract the en_gb.lang file
                try (ZipFile zipFile = ZipFileWithAutoEncoding.open("mod-loennscan.zip");
                     InputStream inputStream = zipFile.getInputStream(zipFile.getEntry("Loenn/lang/en_gb.lang"));
                     BufferedReader br = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {

                    checkZipSignature(new File("mod-loennscan.zip").toPath());

                    // read line per line, and extract the entity ID from each line starting with entities., triggers. or style.effects.
                    Pattern regex = Pattern.compile("^(entities|triggers|style\\.effects)\\.([^.]+)\\..*$");

                    String line;
                    while ((line = br.readLine()) != null) {
                        Matcher match = regex.matcher(line);
                        if (match.matches()) {
                            String entityName = match.group(2);
                            switch (match.group(1)) {
                                case "entities":
                                    loennEntities.add(entityName);
                                    break;
                                case "triggers":
                                    loennTriggers.add(entityName);
                                    break;
                                case "style.effects":
                                    loennEffects.add(entityName);
                                    break;
                            }
                        }
                    }

                    log.info("Found {} Lönn entities, {} triggers, {} effects in https://gamebanana.com/mmdl/{}.",
                            loennEntities.size(), loennTriggers.size(), loennEffects.size(), version);
                    EventListener.handle(listener -> listener.scannedLoennEntities("https://gamebanana.com/mmdl/" + version,
                            loennEntities.size(), loennTriggers.size(), loennEffects.size()));
                } catch (IOException | IllegalArgumentException e) {
                    // if a file cannot be read as a zip, no need to worry about it.
                    // we will just write an empty array.
                    log.warn("Could not analyze Lönn plugins from https://gamebanana.com/mmdl/{}", version, e);
                    EventListener.handle(listener -> listener.loennPluginScanError("https://gamebanana.com/mmdl/" + version, e));
                }

                // write the result.
                try (FileWriter writer = new FileWriter(targetPath.toFile())) {
                    Map<String, List<String>> loennPlugins = new HashMap<>();
                    loennPlugins.put("Entities", new ArrayList<>(loennEntities));
                    loennPlugins.put("Triggers", new ArrayList<>(loennTriggers));
                    loennPlugins.put("Effects", new ArrayList<>(loennEffects));
                    new Yaml().dump(loennPlugins, writer);
                }

                FileUtils.forceDelete(new File("mod-loennscan.zip"));
            }
        }
    }
}
