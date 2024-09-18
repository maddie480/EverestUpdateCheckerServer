package ovh.maddie480.everest.updatechecker;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.tuple.Triple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static ovh.maddie480.everest.updatechecker.DatabaseUpdater.checkZipSignature;

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
            log.trace("{} {} doesn't have any file, skipping.", itemtype, itemid);
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
                log.warn("File URL {} doesn't match GameBanana naming pattern! Skipping.", fileUrl);
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
                Path file = FileDownloader.downloadFile(fileUrl, expectedSize);

                // go through it!
                List<String> filePaths = new LinkedList<>();
                try (ZipFile zipFile = ZipFileWithAutoEncoding.open(file.toAbsolutePath().toString(), fileUrl)) {
                    checkZipSignature(file);

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

                // write the result.
                try (OutputStream os = new FileOutputStream(listPath.toFile())) {
                    YamlUtil.dump(filePaths, os);
                }
            }
        }

        // write the mod name and file list in there.
        try (OutputStream os = new FileOutputStream(modFilesDatabaseDir.resolve("info.yaml").toFile())) {
            Map<String, Object> data = new HashMap<>();
            data.put("Name", modname);
            data.put("Files", createdYamls);
            YamlUtil.dump(data, os);
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
        log.debug("Writing mod files database indices to disk...");
        try (OutputStream os = new FileOutputStream("modfilesdatabase_temp/file_ids.yaml")) {
            YamlUtil.dump(fullFileIdList, os);
        }

        try (OutputStream os = new FileOutputStream("modfilesdatabase_temp/list.yaml")) {
            YamlUtil.dump(fullList, os);
        }

        checkForAhornPlugins();

        // delete modfilesdatabase and move modfilesdatabase_temp to replace it.
        Path databasePath = Paths.get("modfilesdatabase");
        Path databasePathTemp = Paths.get("modfilesdatabase_temp");

        log.debug("Committing...");
        if (Files.isDirectory(databasePath)) {
            FileUtils.deleteDirectory(databasePath.toFile());
        }
        if (Files.isDirectory(databasePathTemp)) {
            Files.move(databasePathTemp, databasePath);
        }

        // we don't need this list anymore, free up its memory.
        fullList.clear();
    }

    private void fillInGapsForIncrementalUpdate() throws IOException {
        log.debug("Copying all other files for incremental update...");
        List<String> mods;
        try (InputStream is = Files.newInputStream(Paths.get("modfilesdatabase/list.yaml"))) {
            mods = YamlUtil.load(is);
        }

        for (String mod : mods) {
            if (fullList.contains(mod)) {
                log.trace("File {} was updated incrementally already, skipping.", mod);
                continue;
            }

            // load this mod's info
            Map<String, Object> fileInfo;
            try (InputStream is = Files.newInputStream(Paths.get("modfilesdatabase/" + mod + "/info.yaml"))) {
                fileInfo = YamlUtil.load(is);
            }

            // carry over all information from the old mod files database
            log.trace("Copying all info for mod {}...", mod);
            Files.createDirectories(Paths.get("modfilesdatabase_temp/" + mod).getParent());
            FileUtils.copyDirectory(new File("modfilesdatabase/" + mod), new File("modfilesdatabase_temp/" + mod));
            fullFileIdList.addAll((List<String>) fileInfo.get("Files"));
            fullList.add(mod);
        }
    }

    private void checkForAhornPlugins() throws IOException {
        {
            log.debug("Loading vanilla map editor plugin info...");

            List<String> ahornEntities = ConnectionUtils.runWithRetry(() -> {
                try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://raw.githubusercontent.com/CelestialCartographers/Maple/master/src/entity.jl")) {
                    List<String> entities = new LinkedList<>();
                    extractAhornEntities(entities, null, null, "Ahorn/entities/vanilla.jl", is);
                    return entities;
                }
            });
            List<String> ahornTriggers = ConnectionUtils.runWithRetry(() -> {
                try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://raw.githubusercontent.com/CelestialCartographers/Maple/master/src/trigger.jl")) {
                    List<String> triggers = new LinkedList<>();
                    extractAhornEntities(null, triggers, null, "Ahorn/triggers/vanilla.jl", is);
                    return triggers;
                }
            });
            List<String> ahornEffects = ConnectionUtils.runWithRetry(() -> {
                try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://raw.githubusercontent.com/CelestialCartographers/Maple/master/src/style.jl")) {
                    List<String> effects = new LinkedList<>();
                    extractAhornEntities(null, null, effects, "Ahorn/effects/vanilla.jl", is);
                    return effects;
                }
            });

            try (OutputStream os = new FileOutputStream(Paths.get("modfilesdatabase_temp/ahorn_vanilla.yaml").toFile())) {
                Map<String, List<String>> ahornPlugins = new HashMap<>();
                ahornPlugins.put("Entities", ahornEntities);
                ahornPlugins.put("Triggers", ahornTriggers);
                ahornPlugins.put("Effects", ahornEffects);
                YamlUtil.dump(ahornPlugins, os);
            }

            ConnectionUtils.runWithRetry(() -> {
                try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://raw.githubusercontent.com/CelestialCartographers/Loenn/master/src/lang/en_gb.lang");
                     BufferedReader br = new BufferedReader(new InputStreamReader(is));
                     OutputStream os = Files.newOutputStream(Paths.get("modfilesdatabase_temp/loenn_vanilla.yaml"))) {

                    Triple<Set<String>, Set<String>, Set<String>> extractedLoennEntities = extractLoennEntitiesFromLangFile(br);

                    Map<String, List<String>> loennPlugins = new HashMap<>();
                    loennPlugins.put("Entities", new ArrayList<>(extractedLoennEntities.getLeft()));
                    loennPlugins.put("Triggers", new ArrayList<>(extractedLoennEntities.getMiddle()));
                    loennPlugins.put("Effects", new ArrayList<>(extractedLoennEntities.getRight()));
                    YamlUtil.dump(loennPlugins, os);

                    return null;
                }
            });
        }

        for (String mod : fullList) {
            // get the versions list
            Path modFolder = Paths.get("modfilesdatabase_temp/" + mod);
            Map<String, Object> versions;
            try (InputStream is = Files.newInputStream(modFolder.resolve("info.yaml"))) {
                versions = YamlUtil.load(is);
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
                log.trace("Copying Ahorn information from {}", oldPath.toAbsolutePath());
                Files.copy(oldPath, targetPath);
            }
        } else {
            List<String> fileList;
            try (InputStream is = Files.newInputStream(modFolder.resolve(version + ".yaml"))) {
                fileList = YamlUtil.load(is);
            }

            if (fileList.stream().anyMatch(f -> f.startsWith("Ahorn/"))) {
                List<String> ahornEntities = new LinkedList<>();
                List<String> ahornTriggers = new LinkedList<>();
                List<String> ahornEffects = new LinkedList<>();

                // download file
                Path zipFilePath = FileDownloader.downloadFile("https://gamebanana.com/mmdl/" + version);

                // scan its contents, opening Ahorn plugin files
                try (ZipFile zipFile = ZipFileWithAutoEncoding.open(zipFilePath.toAbsolutePath().toString())) {
                    checkZipSignature(zipFilePath);

                    for (String file : fileList) {
                        if (file.startsWith("Ahorn/") && file.endsWith(".jl")) {
                            log.debug("Analyzing file {}", file);
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
                try (OutputStream os = new FileOutputStream(targetPath.toFile())) {
                    Map<String, List<String>> ahornPlugins = new HashMap<>();
                    ahornPlugins.put("Entities", ahornEntities);
                    ahornPlugins.put("Triggers", ahornTriggers);
                    ahornPlugins.put("Effects", ahornEffects);
                    YamlUtil.dump(ahornPlugins, os);
                }
            } else {
                log.trace("File {} of mod {} doesn't have any Ahorn plugin, skipping.", version, modFolder.toAbsolutePath());
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
                log.trace("Copying Loenn information from {}", oldPath.toAbsolutePath());
                Files.copy(oldPath, targetPath);
            }
        } else {
            List<String> fileList;
            try (InputStream is = Files.newInputStream(modFolder.resolve(version + ".yaml"))) {
                fileList = YamlUtil.load(is);
            }

            if (fileList.stream().anyMatch(f -> f.startsWith("Loenn/"))) {
                Set<String> loennEntities = new HashSet<>();
                Set<String> loennTriggers = new HashSet<>();
                Set<String> loennEffects = new HashSet<>();

                // download file
                Path zipFilePath = FileDownloader.downloadFile("https://gamebanana.com/mmdl/" + version);

                // extract the en_gb.lang file
                try (ZipFile zipFile = ZipFileWithAutoEncoding.open(zipFilePath.toAbsolutePath().toString())) {
                    checkZipSignature(zipFilePath);

                    for (String file : fileList) {
                        if (file.startsWith("Loenn/") && file.endsWith(".lua")) {
                            log.debug("Analyzing file {}", file);
                            InputStream inputStream = zipFile.getInputStream(zipFile.getEntry(file));
                            extractLoennEntitiesFromPlugin(loennEntities, loennTriggers, loennEffects, file, inputStream);
                        }

                        if (file.equals("Loenn/lang/en_gb.lang")) {
                            try (InputStream inputStream = zipFile.getInputStream(zipFile.getEntry("Loenn/lang/en_gb.lang"));
                                BufferedReader br = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {

                                Triple<Set<String>, Set<String>, Set<String>> extractedLoennEntities = extractLoennEntitiesFromLangFile(br);
                                loennEntities.addAll(extractedLoennEntities.getLeft());
                                loennTriggers.addAll(extractedLoennEntities.getMiddle());
                                loennEffects.addAll(extractedLoennEntities.getRight());
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
                try (OutputStream os = Files.newOutputStream(targetPath)) {
                    Map<String, List<String>> loennPlugins = new HashMap<>();
                    loennPlugins.put("Entities", new ArrayList<>(loennEntities));
                    loennPlugins.put("Triggers", new ArrayList<>(loennTriggers));
                    loennPlugins.put("Effects", new ArrayList<>(loennEffects));
                    YamlUtil.dump(loennPlugins, os);
                }
            } else {
                log.trace("File {} of mod {} doesn't have any Loenn plugin, skipping.", version, modFolder.toAbsolutePath());
            }
        }
    }

    public static Triple<Set<String>, Set<String>, Set<String>> extractLoennEntitiesFromLangFile(BufferedReader inputReader) throws IOException {
        Set<String> loennEntities = new HashSet<>();
        Set<String> loennTriggers = new HashSet<>();
        Set<String> loennEffects = new HashSet<>();

        // read line per line, and extract the entity ID from each line starting with entities., triggers. or style.effects.
        Pattern regex = Pattern.compile("^(entities|triggers|style\\.effects)\\.([^.]+)\\..*$");

        String line;
        while ((line = inputReader.readLine()) != null) {
            Matcher match = regex.matcher(line);
            if (match.matches()) {
                String entityName = match.group(2);
                switch (match.group(1)) {
                    case "entities" -> loennEntities.add(entityName);
                    case "triggers" -> loennTriggers.add(entityName);
                    case "style.effects" -> loennEffects.add(entityName);
                }
            }
        }

        return Triple.of(loennEntities, loennTriggers, loennEffects);
    }

    private void extractLoennEntitiesFromPlugin(Set<String> loennEntities, Set<String> loennTriggers, Set<String> loennEffects,
                                               String file, InputStream inputStream) throws IOException {

        // match on: name = "[something]/[something]" :david_goodenough:
        Pattern nameMatcher = Pattern.compile(".*name = \"([^/\" ]+\\/[^\" ]+)\".*");

        try (BufferedReader br = new BufferedReader(new InputStreamReader(inputStream))) {
            String line;
            while ((line = br.readLine()) != null) {
                Matcher nameMatch = nameMatcher.matcher(line);
                if (nameMatch.matches()) {
                    String entityID = nameMatch.group(1);

                    if (file.startsWith("Loenn/effects/")) {
                        loennEffects.add(entityID);
                    } else if (file.startsWith("Loenn/entities/")) {
                        loennEntities.add(entityID);
                    } else if (file.startsWith("Loenn/triggers/")) {
                        loennTriggers.add(entityID);
                    }
                }
            }
        }
    }
}
