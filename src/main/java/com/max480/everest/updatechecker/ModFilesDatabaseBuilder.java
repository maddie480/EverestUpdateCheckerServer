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
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static com.max480.everest.updatechecker.DatabaseUpdater.openStreamWithTimeout;

public class ModFilesDatabaseBuilder {
    private static final Logger log = LoggerFactory.getLogger(ModFilesDatabaseBuilder.class);

    private final List<String> fullList = new LinkedList<>();

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
        try (FileWriter writer = new FileWriter("modfilesdatabase_temp/list.yaml")) {
            new Yaml().dump(fullList, writer);
        }

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
}
