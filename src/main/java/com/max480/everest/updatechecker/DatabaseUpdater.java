package com.max480.everest.updatechecker;

import net.jpountz.xxhash.StreamingXXHash64;
import net.jpountz.xxhash.XXHashFactory;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static java.nio.charset.StandardCharsets.UTF_8;

class DatabaseUpdater {
    private static final Logger log = LoggerFactory.getLogger(DatabaseUpdater.class);

    // Model, Sound and Spray also accept files, but they aren't enabled for Celeste
    private static final String[] VALID_CATEGORIES = new String[]{"Mod", "Tool", "Wip"};

    private final Map<String, Mod> database = new HashMap<>();
    private Map<String, String> databaseExcludedFiles = new HashMap<>();
    private Set<String> databaseNoYamlFiles = new HashSet<>();
    private int numberOfModsDownloaded = 0;

    private static final Map<String, Integer> mostRecentUpdatedDates = new HashMap<>();
    private static int fullPageSize = 40;
    private static int incrementalPageSize = 0;

    private static final XXHashFactory xxHashFactory = XXHashFactory.fastestInstance();

    private final Pattern gamebananaLinkRegex = Pattern.compile(".*(https://gamebanana.com/mmdl/[0-9]+).*");

    private final ModSearchDatabaseBuilder modSearchDatabaseBuilder = new ModSearchDatabaseBuilder();
    private final ModFilesDatabaseBuilder modFilesDatabaseBuilder = new ModFilesDatabaseBuilder();

    DatabaseUpdater() throws IOException {
    }

    void updateDatabaseYaml(boolean full) throws IOException {
        log.info("=== Started searching for updates (full = {})", full);
        EventListener.handle(listener -> listener.startedSearchingForUpdates(full));
        long startMillis = System.currentTimeMillis();

        // GameBanana cache tends not to refresh properly, so we need to alternate page sizes to dodge the cache. ^^'
        incrementalPageSize++;
        if (incrementalPageSize > 50) incrementalPageSize = 1;
        fullPageSize++;
        if (fullPageSize > 50) fullPageSize = 40;

        // if doing an incremental update, we are only going to load the database if an update actually happened.
        if (full) {
            loadDatabaseFromYaml();
        }

        for (String category : VALID_CATEGORIES) {
            if (full) {
                crawlModsFromCategoryFully(category);
            } else {
                crawlModsFromCategoryIncrementally(category);
            }
        }

        // database not loaded = this was an incremental update and nothing changed, so there's nothing to save.
        if (!database.isEmpty()) {
            modFilesDatabaseBuilder.saveToDisk(full);
            modSearchDatabaseBuilder.saveSearchDatabase(full);
            checkForModDeletion();
            saveDatabaseToYaml();
        }

        long time = System.currentTimeMillis() - startMillis;
        log.info("=== Ended searching for updates. Downloaded {} mods while doing so. Total duration = {} ms.", numberOfModsDownloaded, time);
        EventListener.handle(listener -> listener.endedSearchingForUpdates(numberOfModsDownloaded, time));
    }

    /**
     * Reads the database from everestupdate.yaml if it exists.
     *
     * @throws IOException If the read operation fails.
     */
    private void loadDatabaseFromYaml() throws IOException {
        if (new File("uploads/everestupdate.yaml").exists()) {
            try (InputStream is = Files.newInputStream(Paths.get("uploads/everestupdate.yaml"))) {
                Map<String, Map<String, Object>> imported = new Yaml().load(is);

                for (Map.Entry<String, Map<String, Object>> entry : imported.entrySet()) {
                    Mod mod = new Mod(entry);
                    database.put(entry.getKey(), mod);
                }
            }
        }

        if (new File("uploads/everestupdateexcluded.yaml").exists()) {
            try (InputStream is = Files.newInputStream(Paths.get("uploads/everestupdateexcluded.yaml"))) {
                databaseExcludedFiles = new Yaml().load(is);
            }
        }

        if (new File("uploads/everestupdatenoyaml.yaml").exists()) {
            try (InputStream is = Files.newInputStream(Paths.get("uploads/everestupdatenoyaml.yaml"))) {
                List<String> noYamlFilesList = new Yaml().load(is);
                databaseNoYamlFiles = new TreeSet<>(noYamlFilesList);
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

        // update the file mirror
        BananaMirror.run();
        BananaMirrorImages.run();

        // update the dependency graph with new entries.
        DependencyGraphBuilder.updateDependencyGraph();
    }

    /**
     * Checks all mods from a specific category (itemtype).
     *
     * @param category The category to check
     * @throws IOException If a I/O error occurs while communicating with GameBanana
     */
    private void crawlModsFromCategoryFully(String category) throws IOException {
        // update the last modified date for incremental updates
        int lastModifiedDate = runWithRetry(() -> {
            try (InputStream is = openStreamWithTimeout(new URL("https://gamebanana.com/apiv10/" + category + "/Index?_nPage=1&_nPerpage=" + incrementalPageSize +
                    "&_aFilters[Generic_Game]=6460&_sSort=Generic_LatestModified"))) {

                return new JSONObject(IOUtils.toString(is, UTF_8)).getJSONArray("_aRecords").getJSONObject(0).getInt("_tsDateModified");
            } catch (JSONException e) {
                // turn JSON parse errors into IOExceptions to trigger a retry.
                throw new IOException(e);
            }
        });
        mostRecentUpdatedDates.put(category, lastModifiedDate);

        int page = 1;
        while (true) {
            // load a page of mods.
            final int thisPage = page;
            JSONArray pageContents = runWithRetry(() -> {
                try (InputStream is = openStreamWithTimeout(new URL("https://gamebanana.com/apiv8/" + category + "/ByGame?_aGameRowIds[]=6460&" +
                        "_csvProperties=_idRow,_sName,_aFiles,_aSubmitter,_sDescription,_sText,_nLikeCount,_nViewCount,_nDownloadCount,_aCategory," +
                        "_tsDateAdded,_tsDateModified,_tsDateUpdated,_aPreviewMedia,_sProfileUrl" +
                        "&_sOrderBy=_idRow,ASC&_nPage=" + thisPage + "&_nPerpage=" + fullPageSize))) {

                    return new JSONArray(IOUtils.toString(is, UTF_8));
                } catch (JSONException e) {
                    // turn JSON parse errors into IOExceptions to trigger a retry.
                    throw new IOException(e);
                }
            });

            // process it.
            for (Object item : pageContents) {
                readModInfo(category, (JSONObject) item);
            }

            // if we just got an empty page, this means we reached the end of the list!
            if (pageContents.isEmpty()) {
                break;
            }

            // otherwise, go on.
            page++;
        }
    }

    /**
     * Checks most recent mods from a specific category (itemtype), until we reach the point we stopped at during the last update.
     *
     * @param category The category to check
     * @throws IOException If a I/O error occurs while communicating with GameBanana
     */
    private void crawlModsFromCategoryIncrementally(String category) throws IOException {
        int lastModified = mostRecentUpdatedDates.get(category);

        int page = 1;
        while (true) {
            // load a page of mods.
            final int thisPage = page;
            JSONArray pageContents = runWithRetry(() -> {
                try (InputStream is = openStreamWithTimeout(new URL("https://gamebanana.com/apiv10/" + category + "/Index?_nPage=" + thisPage +
                        "&_nPerpage=" + incrementalPageSize + "&_aFilters[Generic_Game]=6460&_sSort=Generic_LatestModified"))) {

                    return new JSONObject(IOUtils.toString(is, UTF_8)).getJSONArray("_aRecords");
                } catch (JSONException e) {
                    // turn JSON parse errors into IOExceptions to trigger a retry.
                    throw new IOException(e);
                }
            });

            // process it.
            for (Object item : pageContents) {
                JSONObject mod = (JSONObject) item;

                if (mostRecentUpdatedDates.get(category) < mod.getInt("_tsDateModified")) {
                    // mod was updated after last refresh! get all info on it, then update it.
                    lastModified = Math.max(lastModified, mod.getInt("_tsDateModified"));

                    JSONObject modInfo = runWithRetry(() -> {
                        try (InputStream is = openStreamWithTimeout(new URL("https://gamebanana.com/apiv8/" + category + "/" + mod.getInt("_idRow") + "?" +
                                "_csvProperties=_idRow,_sName,_aFiles,_aSubmitter,_sDescription,_sText,_nLikeCount,_nViewCount,_nDownloadCount,_aCategory," +
                                "_tsDateAdded,_tsDateModified,_tsDateUpdated,_aPreviewMedia,_sProfileUrl&ts=" + System.currentTimeMillis()))) {

                            return new JSONObject(IOUtils.toString(is, UTF_8));
                        } catch (JSONException e) {
                            // turn JSON parse errors into IOExceptions to trigger a retry.
                            throw new IOException(e);
                        }
                    });

                    // load the database if this was not done yet!
                    if (database.isEmpty()) {
                        loadDatabaseFromYaml();
                    }

                    readModInfo(category, modInfo);
                    EventListener.handle(listener -> listener.modUpdatedIncrementally(category, modInfo.getInt("_idRow"), modInfo.getString("_sName")));
                } else {
                    // we reached the end of new mods, so stop right here.
                    mostRecentUpdatedDates.put(category, lastModified);
                    return;
                }
            }

            // if we just got an empty page, this means we reached the end of the list!
            if (pageContents.isEmpty()) {
                break;
            }

            // otherwise, go on.
            page++;
        }
    }

    /**
     * Parses a mod, and updates the database as needed.
     *
     * @param category The category to check
     * @throws IOException If a I/O error occurs while communicating with GameBanana
     */
    private void readModInfo(String category, JSONObject mod) throws IOException {
        String name = mod.getString("_sName");

        // if the mod has no file, _aFiles will be null.
        ModInfoParser parsedModInfo = new ModInfoParser();
        if (!mod.isNull("_aFiles")) {
            parsedModInfo.invoke(mod.getJSONArray("_aFiles"), databaseNoYamlFiles);
        }

        if (parsedModInfo.mostRecentFileUrl == null) {
            log.trace("{} => skipping, no suitable file found", name);
        } else {
            log.trace("{} => URL of most recent file (uploaded at {}) is {}", name, parsedModInfo.mostRecentFileTimestamp, parsedModInfo.mostRecentFileUrl);
            for (int i = 0; i < parsedModInfo.allFileUrls.size(); i++) {
                updateDatabase(parsedModInfo.allFileTimestamps.get(i), parsedModInfo.allFileUrls.get(i), parsedModInfo.allFileSizes.get(i),
                        category, mod.getInt("_idRow"));
            }
        }

        // save the info about this mod in the mod search and files databases.
        modFilesDatabaseBuilder.addMod(category, mod.getInt("_idRow"), name,
                parsedModInfo.allFileUrls, parsedModInfo.allFileSizes);
        modSearchDatabaseBuilder.addMod(category, mod.getInt("_idRow"), mod);
    }

    /**
     * Method object that gets the most recent file upload date and URL for a given mod.
     */
    private static class ModInfoParser {
        int mostRecentFileTimestamp = 0;
        String mostRecentFileUrl = null;
        List<String> allFileUrls = new ArrayList<>();
        List<Integer> allFileSizes = new ArrayList<>();
        List<Integer> allFileTimestamps = new ArrayList<>();

        void invoke(JSONArray fileList, Set<String> databaseNoYamlFiles) {
            for (Object fileRaw : fileList) {
                JSONObject file = (JSONObject) fileRaw;

                // get the obvious info about the file (URL and upload date)
                int fileDate = file.getInt("_tsDateAdded");
                int filesize = file.getInt("_nFilesize");
                String fileUrl = file.getString("_sDownloadUrl").replace("dl", "mmdl");
                allFileUrls.add(fileUrl);
                allFileSizes.add(filesize);
                allFileTimestamps.add(fileDate);

                // if this file is the most recent one, we will download it to check it
                if (mostRecentFileTimestamp < fileDate && !databaseNoYamlFiles.contains(fileUrl)) {
                    mostRecentFileTimestamp = fileDate;
                    mostRecentFileUrl = fileUrl;
                }
            }
        }
    }

    /**
     * Checks if the database has to be updated to include the specified mod. If it does, downloads the mod
     * and extracts all required info from it, then includes it in the database.
     *
     * @param fileTimestamp The timestamp for the file
     * @param fileUrl       The file download URL
     * @param gbType        The mod type on GameBanana
     * @param gbId          The mod ID on GameBanana
     * @throws IOException In case of connection or IO issues.
     */
    private void updateDatabase(int fileTimestamp, String fileUrl, int expectedSize, String gbType, int gbId)
            throws IOException {

        if (databaseExcludedFiles.containsKey(fileUrl)) {
            log.trace("=> file was skipped because it is in the excluded list.");
        } else if (databaseNoYamlFiles.contains(fileUrl)) {
            log.trace("=> file was skipped because it is in the no yaml file list.");
        } else if (database.values().stream().anyMatch(mod -> mod.getUrl().equals(fileUrl))) {
            log.trace("=> already up to date");

            // be sure to sync up GameBanana type and id.
            database.values().stream()
                    .filter(mod -> mod.getUrl().equals(fileUrl))
                    .forEach(mod -> mod.updateGameBananaIds(gbType, gbId, expectedSize));
        } else {
            // download the mod
            numberOfModsDownloaded++;

            runWithRetry(() -> {
                try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(Paths.get("mod.zip")))) {
                    IOUtils.copy(new BufferedInputStream(openStreamWithTimeout(new URL(fileUrl))), os);
                    return null; // to fulfill this stupid method signature
                }
            });

            long actualSize = new File("mod.zip").length();
            if (expectedSize != actualSize) {
                FileUtils.forceDelete(new File("mod.zip"));
                throw new IOException("The announced file size (" + expectedSize + ") does not match what we got (" + actualSize + ")" +
                        " for file " + fileUrl + " belonging to " + gbType + " " + gbId);
            }

            // compute its xxHash checksum
            String xxHash = computeXXHash("mod.zip");

            try (ZipFile zipFile = new ZipFile(new File("mod.zip"))) {
                checkZipSignature(new File("mod.zip").toPath());

                ZipEntry everestYaml = zipFile.getEntry("everest.yaml");
                if (everestYaml == null) {
                    everestYaml = zipFile.getEntry("everest.yml");
                }
                if (everestYaml == null) {
                    everestYaml = zipFile.getEntry("multimetadata.yml");
                }

                if (everestYaml == null) {
                    log.warn("=> {} has no yaml file. Adding to the no yaml files list.", fileUrl);
                    EventListener.handle(listener -> listener.modHasNoYamlFile(gbType, gbId, fileUrl));
                    databaseNoYamlFiles.add(fileUrl);
                } else {
                    parseEverestYamlFromZipFile(zipFile.getInputStream(everestYaml), xxHash, fileUrl, fileTimestamp, gbType, gbId, expectedSize);
                }
            } catch (IOException e) {
                log.warn("=> could not read zip file from {}. Adding to the excluded files list.", fileUrl, e);
                EventListener.handle(listener -> listener.zipFileIsUnreadable(gbType, gbId, fileUrl, e));
                databaseExcludedFiles.put(fileUrl, ExceptionUtils.getStackTrace(e));
            }

            FileUtils.forceDelete(new File("mod.zip"));
        }
    }

    static String computeXXHash(String fileName) throws IOException {
        StringBuilder xxHash;

        try (InputStream is = Files.newInputStream(Paths.get(fileName));
             StreamingXXHash64 hash64 = xxHashFactory.newStreamingHash64(0)) {

            byte[] buf = new byte[8192];
            while (true) {
                int read = is.read(buf);
                if (read == -1) break;
                hash64.update(buf, 0, read);
            }
            xxHash = new StringBuilder(Long.toHexString(hash64.getValue()));

            // pad it with zeroes
            while (xxHash.length() < 16) xxHash.insert(0, "0");
        }

        return xxHash.toString();
    }

    /**
     * Parses the everest.yaml from the mod zip, then builds a Mod object from it and adds it to the database.
     *
     * @param yamlInputStream The input stream the everest.yaml file can be read from
     * @param xxHash          The zip's xxHash checksum
     * @param fileUrl         The file URL on GameBanana
     * @param fileTimestamp   The timestamp the file was uploaded at on GameBanana
     * @param gbType          The mod type on GameBanana
     * @param gbId            The mod ID on GameBanana
     * @param fileSize        The size of the file
     */
    private void parseEverestYamlFromZipFile(InputStream yamlInputStream, String xxHash, String fileUrl, int fileTimestamp,
                                             String gbType, int gbId, int fileSize) {
        try {
            List<Map<String, Object>> info = new Yaml().load(yamlInputStream);

            for (Map<String, Object> infoMod : info) {
                String modName = infoMod.get("Name").toString();

                // some mods have no Version field, it would be a shame to exclude them though
                String modVersion;
                if (infoMod.containsKey("Version")) {
                    modVersion = infoMod.get("Version").toString();
                } else {
                    modVersion = "NoVersion";
                }

                Mod mod = new Mod(modName, modVersion, fileUrl, fileTimestamp, Collections.singletonList(xxHash), gbType, gbId, fileSize);

                if (database.containsKey(modName) && database.get(modName).getLastUpdate() > fileTimestamp) {
                    log.warn("=> database already contains more recent file {}. Adding to the excluded files list.", database.get(modName));
                    EventListener.handle(listener -> listener.moreRecentFileAlreadyExists(gbType, gbId, fileUrl, database.get(modName)));
                    databaseExcludedFiles.put(fileUrl, "File " + database.get(modName).getUrl() + " has same mod ID and is more recent");

                } else if (database.containsKey(modName) &&
                        (!database.get(modName).getGameBananaType().equals(gbType) || database.get(modName).getGameBananaId() != gbId)) {

                    log.warn("=> The current version of {} in the database belongs to another mod. Adding to the excluded files list.", fileUrl);
                    EventListener.handle(listener -> listener.currentVersionBelongsToAnotherMod(gbType, gbId, fileUrl, database.get(modName)));
                    databaseExcludedFiles.put(fileUrl, "File " + database.get(modName).getUrl() + " is already in the database and belongs to another mod");

                } else if (databaseExcludedFiles.containsKey(modName)) {
                    log.warn("=> Mod was skipped because it is in the exclude list: " + mod);
                    EventListener.handle(listener -> listener.modIsExcludedByName(mod));
                } else {
                    database.put(modName, mod);
                    log.info("=> Saved new information to database: " + mod);
                    EventListener.handle(listener -> listener.savedNewInformationToDatabase(mod));
                }
            }
        } catch (Exception e) {
            log.warn("=> error while reading the YAML file from {}. Adding to the excluded files list.", fileUrl, e);
            EventListener.handle(listener -> listener.yamlFileIsUnreadable(gbType, gbId, fileUrl, e));
            databaseExcludedFiles.put(fileUrl, ExceptionUtils.getStackTrace(e));
        }
    }

    /**
     * Checks if any mod has been deleted (that is, the URL was not found in any mod on GameBanana).
     * If so, deletes it from the database.
     */
    private void checkForModDeletion() {
        List<String> existingFiles = modFilesDatabaseBuilder.getFileIds().stream()
                .map(file -> "https://gamebanana.com/mmdl/" + file)
                .collect(Collectors.toList());

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
            EventListener.handle(listener -> listener.modWasDeletedFromDatabase(mod));
        }


        // === 2. Excluded files list
        deletedMods.clear();

        for (Map.Entry<String, String> databaseEntry : databaseExcludedFiles.entrySet()) {
            // if the entry is a URL, check if the file still exists
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
            EventListener.handle(listener -> listener.modWasDeletedFromExcludedFileList(deletedMod));
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

            for (String mod : deletedMods) {
                EventListener.handle(listener -> listener.modWasDeletedFromNoYamlFileList(mod));
            }
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
    static <T> T runWithRetry(NetworkingOperation<T> task) throws IOException {
        for (int i = 1; i < 3; i++) {
            try {
                return task.run();
            } catch (IOException e) {
                log.warn("I/O exception while doing networking operation (try {}/3).", i, e);
                EventListener.handle(listener -> listener.retriedIOException(e));

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

    /**
     * Opens a stream to the specified URL, getting sure timeouts are set
     * (connect timeout = 10 seconds, read timeout = 30 seconds).
     *
     * @param url The URL to connect to
     * @return A stream to this URL
     * @throws IOException If an exception occured while trying to connect
     */
    static InputStream openStreamWithTimeout(URL url) throws IOException {
        URLConnection con = url.openConnection();
        con.setConnectTimeout(10000);
        con.setReadTimeout(60000);
        return con.getInputStream();
    }

    static void checkZipSignature(Path path) throws IOException {
        try (InputStream is = Files.newInputStream(path)) {
            byte[] signature = new byte[4];
            int readBytes = is.read(signature);

            if (readBytes < 4
                    || signature[0] != 0x50
                    || signature[1] != 0x4B
                    || signature[2] != 0x03
                    || signature[3] != 0x04) {

                throw new IOException("Bad ZIP signature!");
            }
        }
    }
}
