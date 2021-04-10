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
import java.net.URLConnection;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

class DatabaseUpdater {
    private static final Logger log = LoggerFactory.getLogger(DatabaseUpdater.class);

    // the update checker will load X pages, then get the mod info for those X pages in 1 call.
    private static final int PAGE_GROUP_COUNT = 3;

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

        int i = 1;
        while (loadPage(i)) {
            i += PAGE_GROUP_COUNT;
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
    }

    /**
     * A small object to hold an itemtype/itemid pair (this identifies a mod uniquely on GameBanana).
     */
    private static class QueriedModInfo {
        private String itemtype;
        private int itemid;

        private QueriedModInfo(String itemtype, int itemid) {
            this.itemtype = itemtype;
            this.itemid = itemid;
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

        boolean reachedEmptyPage = false;

        // we are going to get [PAGE_GROUP_COUNT] pages and merge them all in a mods list.
        List<List<Object>> mods = new LinkedList<>();
        for (int i = 0; i < PAGE_GROUP_COUNT && !reachedEmptyPage; i++) {
            final int pageOffset = page + i;
            List<List<Object>> modsPage = runWithRetry(() -> {
                try (InputStream is = openStreamWithTimeout(new URL("https://api.gamebanana.com/Core/List/New?page=" + pageOffset + "&gameid=6460&format=yaml"))) {
                    return Optional.ofNullable(new Yaml().<List<List<Object>>>load(is))
                            .orElseThrow(() -> new IOException("Ended up with a null value when loading a mod page"));
                }
            });
            mods.addAll(modsPage);

            if (modsPage.isEmpty()) {
                reachedEmptyPage = true;
            }
        }

        // stop here if no mod was retrieved.
        if (mods.isEmpty()) return !reachedEmptyPage;

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

        // map this list of arrays into a more Java-friendly object, and keep only item types that should have files attached to it.
        List<QueriedModInfo> queriedModInfo = mods.stream()
                .map(info -> new QueriedModInfo((String) info.get(0), (int) info.get(1)))
                .filter(info -> Arrays.asList("Castaway", "Crafting", "Effect", "Gamefile", "Gui", "Map", "Model", "Prefab", "Skin",
                        "Sound", "Spray", "Texture", "Tool", "Wip").contains(info.itemtype))
                .collect(Collectors.toList());

        String urlModInfo = getModInfoCallUrl(queriedModInfo);
        loadPageModInfo(urlModInfo, queriedModInfo);

        return !reachedEmptyPage;
    }

    /**
     * Builds the URL used to retrieve details on the files for all mods given in parameter.
     * (https://api.gamebanana.com/Core/Item/Data?itemtype[0]=Map&itemid[0]=204390&fields[0]=name,Files().aFiles() [...])
     *
     * @param mods The mods to get info for
     * @return The URL to call to get info on those mods
     */
    private String getModInfoCallUrl(List<QueriedModInfo> mods) {
        StringBuilder urlModInfo = new StringBuilder("https://api.gamebanana.com/Core/Item/Data?");
        int index = 0;
        for (QueriedModInfo mod : mods) {
            urlModInfo
                    .append("itemtype[").append(index).append("]=").append(mod.itemtype)
                    .append("&itemid[").append(index).append("]=").append(mod.itemid)
                    .append("&fields[").append(index).append("]=name,Files().aFiles(),");
            if (mod.itemtype.equals("Wip")) {
                urlModInfo.append("userid");
            } else {
                urlModInfo.append("authors");
            }
            urlModInfo.append(",description,text,likes,views,downloads&");
            index++;
        }

        urlModInfo.append("format=yaml");
        return urlModInfo.toString();
    }

    /**
     * Loads a page of mod info by calling the given url and downloading the updated files.
     *
     * @param modInfoUrl     The url to call to get the mod info
     * @param queriedModInfo The list of mods the URL gets info for
     * @throws IOException In case of connection or IO issues.
     */
    private void loadPageModInfo(String modInfoUrl, List<QueriedModInfo> queriedModInfo) throws IOException {
        log.debug("Loading mod details from GameBanana");

        log.trace("Mod info URL: {}", modInfoUrl);
        List<List<Object>> mods = runWithRetry(() -> {
            try (InputStream is = openStreamWithTimeout(new URL(modInfoUrl))) {
                return Optional.ofNullable(new Yaml().<List<List<Object>>>load(is))
                        .orElseThrow(() -> new IOException("Ended up with a null value when loading mod info"));
            }
        });

        Iterator<QueriedModInfo> queriedModInfoIterator = queriedModInfo.iterator();

        for (List<Object> mod : mods) {
            // we asked for name,Files().aFiles(),authors,description,text
            String name = (String) mod.get(0);

            ModInfoParser parsedModInfo = new ModInfoParser().invoke(mod.get(1), databaseNoYamlFiles);
            existingFiles.addAll(parsedModInfo.allFileUrls);

            QueriedModInfo thisModInfo = queriedModInfoIterator.next();

            if (parsedModInfo.mostRecentFileUrl == null) {
                log.trace("{} => skipping, no suitable file found", name);
            } else {
                log.trace("{} => URL of most recent file (uploaded at {}) is {}", name, parsedModInfo.mostRecentFileTimestamp, parsedModInfo.mostRecentFileUrl);
                for (int i = 0; i < parsedModInfo.allFileUrls.size(); i++) {
                    updateDatabase(parsedModInfo.allFileTimestamps.get(i), parsedModInfo.allFileUrls.get(i), parsedModInfo.allFileSizes.get(i),
                            thisModInfo.itemtype, thisModInfo.itemid);
                }
            }

            // save the info about this mod in the mod search and files databases.
            modSearchDatabaseBuilder.addMod(thisModInfo.itemtype, thisModInfo.itemid, mod);
            modFilesDatabaseBuilder.addMod(thisModInfo.itemtype, thisModInfo.itemid, name,
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

        ModInfoParser invoke(Object fileList, Set<String> databaseNoYamlFiles) {
            // deal with mods with no file at all: in this case, GB sends out an empty list, not a map.
            // We should pay attention to this and handle this specifically.
            if (Collections.emptyList().equals(fileList)) return this;

            // the file list can either be a map (fileid => file info), or just a list of file info.
            Collection<Map<String, Object>> fileListCasted;
            if (fileList instanceof Map) {
                fileListCasted = ((Map<String, Map<String, Object>>) fileList).values();
            } else {
                fileListCasted = (List<Map<String, Object>>) fileList;
            }

            for (Map<String, Object> file : fileListCasted) {
                // get the obvious info about the file (URL and upload date)
                int fileDate = (int) file.get("_tsDateAdded");
                int filesize = (int) file.get("_nFilesize");
                String fileUrl = ((String) file.get("_sDownloadUrl")).replace("dl", "mmdl");
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
