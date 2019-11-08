package com.max480.everest.updatechecker;

import net.jpountz.xxhash.StreamingXXHash64;
import net.jpountz.xxhash.XXHashFactory;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.net.URL;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

class DatabaseUpdater {
    private static final Logger log = LoggerFactory.getLogger(DatabaseUpdater.class);

    private Map<String, Mod> database = new HashMap<>();
    private Map<String, String> databaseExcludedFiles = new HashMap<>();
    private Set<String> databaseNoYamlFiles = new HashSet<>();
    private int numberOfModsDownloaded = 0;
    private Set<String> existingFiles = new HashSet<>();

    private XXHashFactory xxHashFactory = XXHashFactory.fastestInstance();

    private Pattern gamebananaLinkRegex = Pattern.compile(".*(https://gamebanana.com/mmdl/[0-9]+).*");

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

        if (new File("uploads/everestupdatenoyaml.yaml").exists()) {
            try (InputStream is = new FileInputStream("uploads/everestupdatenoyaml.yaml")) {
                List<String> noYamlFilesList = new Yaml().load(is);
                databaseNoYamlFiles = new HashSet<>(noYamlFilesList);
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
        try (FileWriter writer = new FileWriter("uploads/everestupdatenoyaml.yaml")) {
            new Yaml().dump(new ArrayList<>(databaseNoYamlFiles), writer);
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

        List<List<Object>> mods = runWithRetry(() -> {
            try (InputStream is = new URL("https://api.gamebanana.com/Core/List/New?page=" + page + "&gameid=6460&format=yaml").openStream()) {
                return new Yaml().load(is);
            }
        });

        if (mods.isEmpty()) return false;

        String urlModInfoFinal = getModInfoCallUrl(mods);
        loadPageModInfo(urlModInfoFinal);

        return true;
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

        log.trace("Mod info URL: {}", modInfoUrl);
        List<List<Object>> mods = runWithRetry(() -> {
            try (InputStream is = new URL(modInfoUrl).openStream()) {
                return new Yaml().load(is);
            }
        });

        for (List<Object> mod : mods) {
            String name = (String) mod.get(0);

            ModInfoParser parsedModInfo = new ModInfoParser().invoke(mod, databaseNoYamlFiles);
            existingFiles.addAll(parsedModInfo.allFileUrls);

            if (parsedModInfo.mostRecentFileTimestamp == 0) {
                log.trace("{} => skipping, no everest.yml", name);
            } else {
                log.trace("{} => URL of most recent file (uploaded at {}) is {}", name, parsedModInfo.mostRecentFileTimestamp, parsedModInfo.mostRecentFileUrl);
                updateDatabase(parsedModInfo.mostRecentFileTimestamp, parsedModInfo.mostRecentFileUrl, parsedModInfo.mostRecentFileSize);
            }
        }
    }

    /**
     * Method object that gets the most recent file upload date and URL for a given mod.
     */
    private static class ModInfoParser {
        int mostRecentFileTimestamp = 0;
        String mostRecentFileUrl = null;
        int mostRecentFileSize = 0;
        Set<String> allFileUrls = new HashSet<>();

        ModInfoParser invoke(List<Object> mod, Set<String> databaseNoYamlFiles) {
            // deal with mods with no file at all: in this case, GB sends out an empty list, not a map.
            // We should pay attention to this and handle this specifically.
            if (Collections.emptyList().equals(mod.get(1))) return this;

            for (Map<String, Object> file : ((Map<String, Map<String, Object>>) mod.get(1)).values()) {
                // get the obvious info about the file (URL and upload date)
                int fileDate = (int) file.get("_tsDateAdded");
                int filesize = (int) file.get("_nFilesize");
                String fileUrl = ((String) file.get("_sDownloadUrl")).replace("dl", "mmdl");
                allFileUrls.add(fileUrl);

                // if this file is the most recent one, we will download it to check it
                if (mostRecentFileTimestamp < fileDate && !databaseNoYamlFiles.contains(fileUrl)) {
                    mostRecentFileTimestamp = fileDate;
                    mostRecentFileUrl = fileUrl;
                    mostRecentFileSize = filesize;
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
    private void updateDatabase(int mostRecentFileTimestamp, String mostRecentFileUrl, int expectedSize)
            throws IOException {

        if (databaseExcludedFiles.containsKey(mostRecentFileUrl)) {
            log.trace("=> file was skipped because it is in the excluded list.");
        } else if (database.values().stream().anyMatch(mod -> mod.getUrl().equals(mostRecentFileUrl))) {
            log.trace("=> already up to date");
        } else {
            // download the mod
            numberOfModsDownloaded++;

            runWithRetry(() -> {
                try (OutputStream os = new BufferedOutputStream(new FileOutputStream("mod.zip"))) {
                    IOUtils.copy(new BufferedInputStream(new URL(mostRecentFileUrl).openStream()), os);
                    return null; // to fullfill this stupid method signature
                }
            });

            long actualSize = new File("mod.zip").length();
            if (expectedSize != actualSize) {
                FileUtils.forceDelete(new File("mod.zip"));
                throw new IOException("The announced file size (" + expectedSize + ") does not match what we got (" + actualSize + ")");
            }

            // compute its xxHash checksum
            String xxHash;
            try (InputStream is = new FileInputStream("mod.zip")) {
                StreamingXXHash64 hash64 = xxHashFactory.newStreamingHash64(0);
                byte[] buf = new byte[8192];
                while (true) {
                    int read = is.read(buf);
                    if (read == -1) break;
                    hash64.update(buf, 0, read);
                }
                xxHash = Long.toHexString(hash64.getValue());

                // pad it with zeroes
                while (xxHash.length() < 16) xxHash = "0" + xxHash;
            }

            try (ZipInputStream zip = new ZipInputStream(new FileInputStream("mod.zip"))) {
                boolean everestYamlFound = false;

                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if (!entry.isDirectory() && (entry.getName().equals("everest.yaml")
                            || entry.getName().equals("everest.yml") || entry.getName().equals("multimetadata.yml"))) {

                        parseEverestYamlFromZipFile(zip, xxHash, mostRecentFileUrl, mostRecentFileTimestamp);
                        everestYamlFound = true;
                        break;
                    }
                }

                if (!everestYamlFound) {
                    log.warn("=> {} has no yaml file. Adding to the no yaml files list.", mostRecentFileUrl);
                    databaseNoYamlFiles.add(mostRecentFileUrl);
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
     * @param xxHash        The zip's xxHash checksum
     * @param fileUrl       The file URL on GameBanana
     * @param fileTimestamp The timestamp the file was uploaded at on GameBanana
     */
    private void parseEverestYamlFromZipFile(ZipInputStream zip, String xxHash, String fileUrl, int fileTimestamp) {
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

                Mod mod = new Mod(modName, modVersion, fileUrl, fileTimestamp, Collections.singletonList(xxHash));

                if (database.containsKey(modName) && database.get(modName).getLastUpdate() > fileTimestamp) {
                    log.warn("=> database already contains more recent file {}. Adding to the excluded files list.", database.get(modName));
                    databaseExcludedFiles.put(fileUrl, "File " + database.get(modName).getUrl() + " has same mod ID and is more recent");
                } else if (databaseExcludedFiles.containsKey(modName)) {
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
        // === 1. Mod database
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


        // === 2. Excluded files list
        deletedMods.clear();

        for (Map.Entry<String, String> databaseEntry : databaseExcludedFiles.entrySet()) {
            // if the entry is an URL, check if the file still exists
            if (databaseEntry.getKey().startsWith("http://") || databaseEntry.getKey().startsWith("https://")) {
                if (!existingFiles.contains(databaseEntry.getKey())) {
                    deletedMods.add(databaseEntry.getKey());
                }
            }

            // if the description contains a GB URL, check if it still exists
            Matcher descriptionMatcher = gamebananaLinkRegex.matcher(databaseEntry.getValue());
            if (descriptionMatcher.matches()) {
                String gbLink = descriptionMatcher.group(1);
                if (!existingFiles.contains(gbLink)) {
                    deletedMods.add(databaseEntry.getKey());
                }
            }
        }

        // now, actually delete the mods from the excluded files list.
        for (String deletedMod : deletedMods) {
            databaseExcludedFiles.remove(deletedMod);
            log.warn("File {} was deleted from the excluded files list", deletedMod);
        }


        // === 3. No yaml files list
        deletedMods.clear();

        for (String file : databaseNoYamlFiles) {
            // check if the URL was encountered when checking all GB mods
            if (!existingFiles.contains(file)) {
                // it was not: save the mod for deletion
                deletedMods.add(file);
            }
        }

        // now, actually delete the mods from the no yaml files list.
        if (!deletedMods.isEmpty()) {
            databaseNoYamlFiles.removeAll(deletedMods);
            log.warn("Files {} were deleted from the no yaml files list", deletedMods);
        }
    }

    /**
     * Runs a task (typically a network operation), retrying up to 3 times if it throws an IOException.
     *
     * @param task The task to run and retry
     * @param <T>  The return type for the task
     * @return What the task returned
     * @throws IOException If the task failed 3 times
     */
    private <T> T runWithRetry(NetworkingOperation<T> task) throws IOException {
        for (int i = 1; i < 3; i++) {
            try {
                return task.run();
            } catch (IOException e) {
                log.warn("I/O exception while doing networking operation (try {}/3).", i, e);

                // wait a bit before retrying
                try {
                    log.debug("Waiting {} seconds before next try.", i * 5);
                    Thread.sleep(i * 5000);
                } catch (InterruptedException e2) {
                    log.warn("Sleep interrupted", e2);
                }
            }
        }

        // 3rd try: this time, if it crashes, let it crash
        return task.run();
    }
}
