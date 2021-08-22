package com.max480.everest.updatechecker;

import net.jpountz.xxhash.StreamingXXHash64;
import net.jpountz.xxhash.XXHashFactory;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.net.URL;
import java.net.URLConnection;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static java.nio.charset.StandardCharsets.UTF_8;

class DatabaseUpdater {
    private static final Logger log = LoggerFactory.getLogger(DatabaseUpdater.class);

    private Map<String, Mod> database = new HashMap<>();
    private Map<String, String> databaseExcludedFiles = new HashMap<>();
    private Set<String> databaseNoYamlFiles = new HashSet<>();
    private int numberOfModsDownloaded = 0;
    private Set<String> existingFiles = new HashSet<>();

    private XXHashFactory xxHashFactory = XXHashFactory.fastestInstance();

    private Pattern gamebananaLinkRegex = Pattern.compile(".*(https://gamebanana.com/mmdl/[0-9]+).*");

    private final ModSearchDatabaseBuilder modSearchDatabaseBuilder = new ModSearchDatabaseBuilder();
    private final ModFilesDatabaseBuilder modFilesDatabaseBuilder = new ModFilesDatabaseBuilder();

    DatabaseUpdater() throws IOException {
    }

    void updateDatabaseYaml() throws IOException {
        log.info("=== Started searching for updates");
        long startMillis = System.currentTimeMillis();

        loadDatabaseFromYaml();

        /*
            List of GameBanana types with whether or not they accept files:
            App - NO
            Article - NO
            Blog - NO
            Castaway - YES
            Club - NO
            Concept - NO
            Contest - NO
            Crafting - YES
            Effect - YES
            Event - NO
            Gamefile - YES
            Gui - YES
            Idea - NO
            Jam - NO
            Map - YES
            Mod - YES
            Model - YES
            News - NO
            Poll - NO
            PositionAvailable - NO
            Prefab - YES
            Project - NO
            Question - NO
            Request - NO
            Review - NO
            Script - NO
            Skin - YES
            Sound - YES
            Spray - YES
            Studio - NO
            Texture - YES
            Thread - NO
            Tool - YES
            Tutorial - NO
            Ware - NO
            Wiki - NO
            Wip - YES
         */

        // only sections that can have files are here
        // old merged sections (Skins, Maps, Gamefiles, Effects, GUIs, Textures, Prefabs, Castaways & Craftings) still return stuff so they're excluded as well
        for (String category : Arrays.asList("Mod", "Model", "Sound", "Spray", "Tool", "Wip")) {
            crawlModsFromCategory(category);
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

        // also write the mod search and files databases to disk.
        modSearchDatabaseBuilder.saveSearchDatabase();
        modFilesDatabaseBuilder.saveToDisk();

        // update the file mirror
        BananaMirror.main(null);
        BananaMirrorImages.main(null);
    }

    /**
     * Checks all mods from a specific category (itemtype).
     *
     * @param category The category to check
     * @throws IOException If a I/O error occurs while communicating with GameBanana
     */
    private void crawlModsFromCategory(String category) throws IOException {
        int page = 1;
        while (true) {
            // load a page of mods.
            final int thisPage = page;
            JSONArray pageContents = runWithRetry(() -> {
                try (InputStream is = new URL("https://gamebanana.com/apiv5/" + category + "/ByGame?_aGameRowIds[]=6460&" +
                        "_csvProperties=_idRow,_sName,_aFiles,_aSubmitter,_sDescription,_sText,_nLikeCount,_nViewCount,_nDownloadCount,_aCategory,_tsDateAdded,_aPreviewMedia" +
                        "&_sOrderBy=_idRow,ASC&_nPage=" + thisPage + "&_nPerpage=50").openStream()) {

                    return new JSONArray(IOUtils.toString(is, UTF_8));
                }
            });

            // process it.
            crawlModInfo(category, pageContents);

            // if we just got an empty page, this means we reached the end of the list!
            if (pageContents.isEmpty()) {
                break;
            }

            // otherwise, go on.
            page++;
        }
    }

    /**
     * Parses a page of mods, and updates the database as needed.
     *
     * @param category The category to check
     * @throws IOException If a I/O error occurs while communicating with GameBanana
     */
    private void crawlModInfo(String category, JSONArray pageContents) throws IOException {
        for (Object itemRaw : pageContents) {
            JSONObject mod = (JSONObject) itemRaw;

            String name = mod.getString("_sName");

            // if the mod has no file, _aFiles will be null.
            ModInfoParser parsedModInfo = new ModInfoParser();
            if (!mod.isNull("_aFiles")) {
                parsedModInfo.invoke(mod.getJSONArray("_aFiles"), databaseNoYamlFiles);
            }
            existingFiles.addAll(parsedModInfo.allFileUrls);

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
            modSearchDatabaseBuilder.addMod(category, mod.getInt("_idRow"), mod);
            modFilesDatabaseBuilder.addMod(category, mod.getInt("_idRow"), name,
                    parsedModInfo.allFileUrls, parsedModInfo.allFileSizes);
        }
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

        ModInfoParser invoke(JSONArray fileList, Set<String> databaseNoYamlFiles) {
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
            return this;
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
                    .forEach(mod -> mod.updateGameBananaIds(gbType, gbId));
        } else {
            // download the mod
            numberOfModsDownloaded++;

            runWithRetry(() -> {
                try (OutputStream os = new BufferedOutputStream(new FileOutputStream("mod.zip"))) {
                    IOUtils.copy(new BufferedInputStream(openStreamWithTimeout(new URL(fileUrl))), os);
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

            try (ZipFile zipFile = new ZipFile(new File("mod.zip"))) {
                ZipEntry everestYaml = zipFile.getEntry("everest.yaml");
                if (everestYaml == null) {
                    everestYaml = zipFile.getEntry("everest.yml");
                }
                if (everestYaml == null) {
                    everestYaml = zipFile.getEntry("multimetadata.yml");
                }

                if (everestYaml == null) {
                    log.warn("=> {} has no yaml file. Adding to the no yaml files list.", fileUrl);
                    databaseNoYamlFiles.add(fileUrl);
                } else {
                    parseEverestYamlFromZipFile(zipFile.getInputStream(everestYaml), xxHash, fileUrl, fileTimestamp, gbType, gbId);
                }
            } catch (IOException e) {
                log.warn("=> could not read zip file from {}. Adding to the excluded files list.", fileUrl, e);
                databaseExcludedFiles.put(fileUrl, ExceptionUtils.getStackTrace(e));
            }

            FileUtils.forceDelete(new File("mod.zip"));
        }
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
     */
    private void parseEverestYamlFromZipFile(InputStream yamlInputStream, String xxHash, String fileUrl, int fileTimestamp,
                                             String gbType, int gbId) {
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

                Mod mod = new Mod(modName, modVersion, fileUrl, fileTimestamp, Collections.singletonList(xxHash), gbType, gbId);

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
    static <T> T runWithRetry(NetworkingOperation<T> task) throws IOException {
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
}
