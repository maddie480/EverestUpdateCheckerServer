package com.max480.everest.updatechecker;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.net.URL;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

class DatabaseUpdater {
    private static final Logger log = LoggerFactory.getLogger(DatabaseUpdater.class);

    private Map<String, Mod> database = new HashMap<>();
    private Map<String, String> databaseExcludedFiles = new HashMap<>();
    private int numberOfModsDownloaded = 0;
    private Set<String> existingFiles = new HashSet<>();

    void updateDatabaseYaml() throws IOException {
        log.info("=== Started searching for updates");
        long startMillis = System.currentTimeMillis();

        loadDatabaseFromYaml();

        int i = 1;
        while (loadPage(i)) {
            i++;
        }

        checkForModDeletion();

        saveDatabaseToYaml();

        log.info("=== Ended searching for updates. Downloaded {} mods while doing so. Total duration = {} ms.",
                numberOfModsDownloaded, System.currentTimeMillis() - startMillis);
    }

    /**
     * Reads the database from everestupdate.yaml if it exists.
     *
     * @throws IOException If the read operation fails.
     */
    private void loadDatabaseFromYaml() throws IOException {
        if (new File("uploads/everestupdate.yaml").exists()) {
            try (InputStream is = new FileInputStream("uploads/everestupdate.yaml")) {
                Map<String, Map<String, Object>> imported = new Yaml().load(is);

                for (Map.Entry<String, Map<String, Object>> entry : imported.entrySet()) {
                    Mod mod = new Mod(entry);
                    database.put(entry.getKey(), mod);
                }
            }
        }

        if (new File("uploads/everestupdateexcluded.yaml").exists()) {
            try (InputStream is = new FileInputStream("uploads/everestupdateexcluded.yaml")) {
                databaseExcludedFiles = new Yaml().load(is);
            }
        }
    }

    /**
     * Writes the database to everestupdate.yaml.
     *
     * @throws IOException If the write operation fails.
     */
    private void saveDatabaseToYaml() throws IOException {
        Map<String, Map<String, Object>> export = new HashMap<>();
        for (Map.Entry<String, Mod> entry : database.entrySet()) {
            export.put(entry.getKey(), entry.getValue().toMap());
        }

        try (FileWriter writer = new FileWriter("uploads/everestupdate.yaml")) {
            new Yaml().dump(export, writer);
        }
        try (FileWriter writer = new FileWriter("uploads/everestupdateexcluded.yaml")) {
            new Yaml().dump(databaseExcludedFiles, writer);
        }
    }

    /**
     * Loads all the mods from a page in GameBanana.
     *
     * @param page The page to load (1-based)
     * @return true if the page actually contains mods, false otherwise.
     * @throws IOException In case of connection or IO issues.
     */
    private boolean loadPage(int page) throws IOException {
        log.debug("Loading page {} of the list of mods from GameBanana", page);
        try (InputStream is = new URL("https://api.gamebanana.com/Core/List/New?page=" + page + "&gameid=6460&format=yaml").openStream()) {
            List<List<Object>> mods = new Yaml().load(is);

            if (mods.isEmpty()) return false;

            String urlModInfoFinal = getModInfoCallUrl(mods);
            loadPageModInfo(urlModInfoFinal);

            return true;
        }
    }

    /**
     * Builds the URL used to retrieve details on the files for all mods given in parameter.
     * (https://api.gamebanana.com/Core/Item/Data?itemtype[0]=Map&itemid[0]=204390&fields[0]=name,Files().aFiles() [...])
     *
     * @param mods The mods to get info for
     * @return The URL to call to get info on those mods
     */
    private String getModInfoCallUrl(List<List<Object>> mods) {
        StringBuilder urlModInfo = new StringBuilder("https://api.gamebanana.com/Core/Item/Data?");
        int index = 0;
        for (List<Object> mod : mods) {
            String type = (String) mod.get(0);
            int id = (int) mod.get(1);

            urlModInfo
                    .append("itemtype[").append(index).append("]=").append(type)
                    .append("&itemid[").append(index).append("]=").append(id)
                    .append("&fields[").append(index).append("]=name,Files().aFiles()&");
            index++;
        }

        urlModInfo.append("format=yaml");
        return urlModInfo.toString();
    }

    /**
     * Loads a page of mod info by calling the given url and downloading the updated files.
     *
     * @param modInfoUrl The url to call to get the mod info
     * @throws IOException In case of connection or IO issues.
     */
    private void loadPageModInfo(String modInfoUrl) throws IOException {
        log.debug("Loading mod details from GameBanana");

        try (InputStream is = new URL(modInfoUrl).openStream()) {
            List<List<Object>> mods = new Yaml().load(is);

            for (List<Object> mod : mods) {
                String name = (String) mod.get(0);

                ModInfoParser parsedModInfo = new ModInfoParser().invoke(mod);
                existingFiles.addAll(parsedModInfo.allFileUrls);

                if (parsedModInfo.mostRecentFileTimestamp == 0) {
                    log.trace("{} => skipping, no everest.yml", name);
                } else {
                    log.trace("{} => URL of most recent file (uploaded at {}) is {}", name, parsedModInfo.mostRecentFileTimestamp, parsedModInfo.mostRecentFileUrl);
                    updateDatabase(parsedModInfo.mostRecentFileTimestamp, parsedModInfo.mostRecentFileUrl);
                }
            }
        }
    }

    /**
     * Method object that gets the most recent file upload date and URL for a given mod.
     */
    private static class ModInfoParser {
        int mostRecentFileTimestamp = 0;
        String mostRecentFileUrl = null;
        Set<String> allFileUrls = new HashSet<>();

        ModInfoParser invoke(List<Object> mod) {
            for (Map<String, Object> file : ((Map<String, Map<String, Object>>) mod.get(1)).values()) {
                // get the obvious info about the file (URL and upload date)
                int fileDate = (int) file.get("_tsDateAdded");
                String fileUrl = ((String) file.get("_sDownloadUrl")).replace("dl", "mmdl");
                allFileUrls.add(fileUrl);

                if (mostRecentFileTimestamp < fileDate) {
                    // before using this file, check if it has an everest.yaml in it (_aMetadata._aArchiveFileTree should contain everest.yaml)
                    Map<String, Object> metadata = (Map<String, Object>) file.get("_aMetadata");
                    if (metadata != null && metadata.containsKey("_aArchiveFileTree")) {
                        Collection<Object> fileTree;

                        // for some reason, _aArchiveFileTree is sometimes a map, sometimes a list
                        if (metadata.get("_aArchiveFileTree") instanceof List) {
                            fileTree = (List<Object>) metadata.get("_aArchiveFileTree");
                        } else {
                            fileTree = ((Map<String, Object>) metadata.get("_aArchiveFileTree")).values();
                        }

                        if (fileTree.stream().anyMatch(fileInZip -> fileInZip instanceof String
                                && (fileInZip.equals("everest.yaml") || fileInZip.equals("everest.yml") || fileInZip.equals("multimetadata.yml")))) {

                            // take this file, it has an everest.yaml in it
                            mostRecentFileTimestamp = fileDate;
                            mostRecentFileUrl = fileUrl;
                        }
                    }
                }
            }
            return this;
        }
    }

    /**
     * Checks if the database has to be updated to include the specified mod. If it does, downloads the mod
     * and extracts all required info from it, then includes it in the database.
     *
     * @param mostRecentFileTimestamp The timestamp for the file
     * @param mostRecentFileUrl       The file download URL
     * @throws IOException In case of connection or IO issues.
     */
    private void updateDatabase(int mostRecentFileTimestamp, String mostRecentFileUrl) throws IOException {
        if (databaseExcludedFiles.containsKey(mostRecentFileUrl)) {
            log.trace("=> file was skipped because it is in the excluded list.");
        } else if (database.values().stream().anyMatch(mod -> mod.getUrl().equals(mostRecentFileUrl))) {
            log.trace("=> already up to date");
        } else {
            // download the mod
            numberOfModsDownloaded++;
            try (OutputStream os = new BufferedOutputStream(new FileOutputStream("mod.zip"))) {
                IOUtils.copy(new BufferedInputStream(new URL(mostRecentFileUrl).openStream()), os);
            }

            // compute its SHA256 checksum
            String sha256;
            try (InputStream is = new FileInputStream("mod.zip")) {
                sha256 = DigestUtils.sha256Hex(is);
            }

            try (ZipInputStream zip = new ZipInputStream(new FileInputStream("mod.zip"))) {
                boolean everestYamlFound = false;

                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if (!entry.isDirectory() && (entry.getName().equals("everest.yaml")
                            || entry.getName().equals("everest.yml") || entry.getName().equals("multimetadata.yml"))) {

                        parseEverestYamlFromZipFile(zip, sha256, mostRecentFileUrl, mostRecentFileTimestamp);
                        everestYamlFound = true;
                        break;
                    }
                }

                if (!everestYamlFound) {
                    log.warn("=> no everest.yaml found in the file for {}. Adding to the excluded files list.", mostRecentFileUrl);
                    databaseExcludedFiles.put(mostRecentFileUrl, "No everest.yaml found");
                }
            } catch (IOException e) {
                log.warn("=> could not read zip file from {}. Adding to the excluded files list.", mostRecentFileUrl, e);
                databaseExcludedFiles.put(mostRecentFileUrl, ExceptionUtils.getStackTrace(e));
            }

            FileUtils.forceDelete(new File("mod.zip"));
        }
    }

    /**
     * Parses the everest.yaml from the mod zip, then builds a Mod object from it and adds it to the database.
     *
     * @param zip           The zip input stream, positioned on the everest.yaml file
     * @param sha256        The zip's SHA256 checksum
     * @param fileUrl       The file URL on GameBanana
     * @param fileTimestamp The timestamp the file was uploaded at on GameBanana
     */
    private void parseEverestYamlFromZipFile(ZipInputStream zip, String sha256, String fileUrl, int fileTimestamp) {
        try {
            List<Map<String, Object>> info = new Yaml().load(zip);

            for (Map<String, Object> infoMod : info) {
                String modName = infoMod.get("Name").toString();

                // some mods have no Version field, it would be a shame to exclude them though
                String modVersion;
                if (infoMod.containsKey("Version")) {
                    modVersion = infoMod.get("Version").toString();
                } else {
                    modVersion = "NoVersion";
                }

                Mod mod = new Mod(modName, modVersion, fileUrl, fileTimestamp, Collections.singletonList(sha256));

                if (database.containsKey(modName) && database.get(modName).getLastUpdate() > fileTimestamp) {
                    log.warn("=> database already contains more recent file {}. Adding to the excluded files list.", database.get(modName));
                    databaseExcludedFiles.put(fileUrl, "File " + database.get(modName).getUrl() + " has same mod ID and is more recent");
                } else if(databaseExcludedFiles.containsKey(modName)) {
                    log.warn("=> Mod was skipped because it is in the exclude list: " + mod.toString());
                } else {
                    database.put(modName, mod);
                    log.info("=> Saved new information to database: " + mod.toString());
                }
            }
        } catch (Exception e) {
            log.warn("=> error while reading the YAML file from {}. Adding to the excluded files list.", fileUrl, e);
            databaseExcludedFiles.put(fileUrl, ExceptionUtils.getStackTrace(e));
        }
    }

    /**
     * Checks if any mod has been deleted (that is, the URL was not found in any mod on GameBanana).
     * If so, deletes it from the database.
     */
    private void checkForModDeletion() {
        Set<String> deletedMods = new HashSet<>();

        for (Map.Entry<String, Mod> databaseEntry : database.entrySet()) {
            // check if the URL was encountered when checking all GB mods
            if (!existingFiles.contains(databaseEntry.getValue().getUrl())) {
                // it was not: save the mod for deletion
                deletedMods.add(databaseEntry.getKey());
            }
        }

        // now, actually delete the mods from the database.
        // (modifying the map while iterating through it is a bad idea.)
        for (String deletedMod : deletedMods) {
            Mod mod = database.remove(deletedMod);
            log.warn("Mod {} was deleted from the database", mod.toString());
        }
    }
}
