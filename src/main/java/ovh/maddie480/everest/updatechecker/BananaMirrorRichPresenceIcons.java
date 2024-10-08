package ovh.maddie480.everest.updatechecker;

import org.apache.commons.io.FileUtils;
import org.json.JSONArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * This service mirrors the Rich Presence icons of all files on GameBanana, based on the mod file database.
 */
public class BananaMirrorRichPresenceIcons {
    private static final Logger log = LoggerFactory.getLogger(BananaMirrorRichPresenceIcons.class);

    // all files that exist in here were completely processed
    private final Map<String, Set<String>> filesToHashes;

    // all hashes that exist in here were uploaded to Banana Mirror
    private final Map<String, Set<String>> hashesToFiles;

    private boolean changesHappened = false;

    public BananaMirrorRichPresenceIcons() throws IOException {
        log.debug("Loading already mirrored icons...");
        Map<String, Map<String, List<String>>> map;
        try (FileInputStream is = new FileInputStream("banana_mirror_rich_presence_icons.yaml")) {
            map = YamlUtil.load(is);
        }

        filesToHashes = listToSet(map.get("FilesToHashes"));
        hashesToFiles = listToSet(map.get("HashesToFiles"));
    }

    public void update() throws IOException {
        Set<String> deletedFileIds = new HashSet<>(filesToHashes.keySet());

        // load mod list
        List<String> mods;
        try (InputStream is = Files.newInputStream(Paths.get("modfilesdatabase/list.yaml"))) {
            mods = YamlUtil.load(is);
        }

        Set<String> nsfwMods = new HashSet<>();
        try (InputStream is = Files.newInputStream(Paths.get("uploads/nsfw_mods.yaml"))) {
            nsfwMods.addAll(YamlUtil.<List<String>>load(is));
        }

        for (String mod : mods) {
            // do NOT upload Rich Presence icons for mods tagged as NSFW!
            if (nsfwMods.contains(mod)) {
                log.trace("Skipping mod {} as it is NSFW", mod);
                continue;
            }

            // load file list for the mod
            List<String> files;
            try (InputStream is = Files.newInputStream(Paths.get("modfilesdatabase/" + mod + "/info.yaml"))) {
                Map<String, Object> info = YamlUtil.load(is);
                files = (List<String>) info.get("Files");
            }

            for (String file : files) {
                if (filesToHashes.containsKey(file)) {
                    log.trace("File {} was already checked, moving on", file);
                    deletedFileIds.remove(file);
                    continue;
                }

                // load file listing for the mod, so that we know if it has any map icons
                List<String> fileList;
                try (InputStream is = Files.newInputStream(Paths.get("modfilesdatabase/" + mod + "/" + file + ".yaml"))) {
                    fileList = YamlUtil.load(is);
                }

                List<String> richPresenceIcons = fileList.stream()
                        .filter(fileName -> fileName.startsWith("Graphics/Atlases/Gui/")
                                && fileName.endsWith(".png")
                                && (fileName.startsWith("Graphics/Atlases/Gui/areas/")
                                    || fileList.contains(fileName.substring(0, fileName.length() - 4) + "_back.png"))
                                && !fileName.endsWith("_back.png")
                                && !fileName.endsWith("hover.png"))
                        .collect(Collectors.toList());

                log.trace("Icons detected in file {}: {}", file, richPresenceIcons);

                if (!richPresenceIcons.isEmpty()) {
                    processNewFile(file, richPresenceIcons);
                }
            }
        }

        for (String file : deletedFileIds) {
            log.info("File {} was deleted, deleting its icons!", file);
            processDeletedFile(file);
        }

        if (changesHappened) {
            File tempFile = new File("/tmp/file_list.json");
            FileUtils.writeStringToFile(tempFile, new JSONArray(hashesToFiles.keySet()).toString(), StandardCharsets.UTF_8);
            BananaMirror.makeSftpAction(Main.serverConfig.bananaMirrorConfig.richPresenceIconsDirectory,
                    channel -> channel.put(tempFile.getAbsolutePath(), "list.json"));
            FileUtils.forceDelete(tempFile);
        }
    }

    private void processNewFile(String fileId, List<String> filesToProcess) throws IOException {
        // download the mod
        Path file = FileDownloader.downloadFile("https://gamebanana.com/mmdl/" + fileId);

        Set<String> hashes = new HashSet<>();

        // get the files to process from it!
        try (ZipFile zip = ZipFileWithAutoEncoding.open(file.toAbsolutePath().toString())) {
            for (String fileToProcess : filesToProcess) {
                ZipEntry entry = zip.getEntry(fileToProcess);

                // compute the hash to check if we already have the icon.
                String hash;
                try (InputStream is = zip.getInputStream(entry)) {
                    hash = DatabaseUpdater.computeXXHash(is);
                }
                hashes.add(hash);

                // check if it is new or not, send it if it is!
                if (!hashesToFiles.containsKey(hash)) {
                    log.info("New file icon {} with hash {}! Uploading it.", fileToProcess, hash);
                    sendNewFile(fileId, zip, entry, hash);
                } else {
                    log.debug("Already existing file icon {} with hash {}. Saving it.", fileToProcess, hash);
                    hashesToFiles.get(hash).add(fileId);
                    saveData();
                }
            }
        }

        // save all the hashes that are in the file
        filesToHashes.put(fileId, hashes);
        saveData();
    }

    private void sendNewFile(String fileId, ZipFile zip, ZipEntry entry, String hash) throws IOException {
        // extract file from zip
        Path filePath = Paths.get("/tmp/" + hash + ".png");
        try (InputStream is = zip.getInputStream(entry)) {
            FileUtils.copyInputStreamToFile(is, filePath.toFile());
        }

        // send it
        BananaMirror.makeSftpAction(Main.serverConfig.bananaMirrorConfig.richPresenceIconsDirectory,
                channel -> channel.put(filePath.toAbsolutePath().toString(), filePath.getFileName().toString()));
        EventListener.handle(listener -> listener.uploadedRichPresenceIconToBananaMirror(filePath.getFileName().toString(), fileId));
        changesHappened = true;

        // register it in our data file
        Set<String> fileSet = new HashSet<>();
        fileSet.add(fileId);
        hashesToFiles.put(hash, fileSet);
        saveData();

        // delete it from temp directory
        FileUtils.forceDelete(filePath.toFile());
    }

    private void processDeletedFile(String fileId) throws IOException {
        for (String hash : filesToHashes.get(fileId)) {
            if (!hashesToFiles.containsKey(hash)) {
                log.warn("Hash {} is already gone from the mirror, there probably was a crash deleting a later file!", hash);
                continue;
            }

            hashesToFiles.get(hash).remove(fileId);
            log.trace("Remaining files for hash {}: {}", hash, hashesToFiles.get(hash));

            if (hashesToFiles.get(hash).isEmpty()) {
                log.info("Hash {} is now unused! Deleting it.", hash);
                BananaMirror.makeSftpAction(Main.serverConfig.bananaMirrorConfig.richPresenceIconsDirectory, channel -> channel.rm(hash + ".png"));
                EventListener.handle(listener -> listener.deletedRichPresenceIconFromBananaMirror(hash + ".png", fileId));
                changesHappened = true;

                hashesToFiles.remove(hash);
                saveData();
            }
        }

        filesToHashes.remove(fileId);
        saveData();
    }

    private void saveData() throws IOException {
        Map<String, Map<String, List<String>>> map = new HashMap<>();
        map.put("FilesToHashes", setToList(filesToHashes));
        map.put("HashesToFiles", setToList(hashesToFiles));

        try (FileOutputStream os = new FileOutputStream("banana_mirror_rich_presence_icons.yaml")) {
            YamlUtil.dump(map, os);
        }
    }

    private Map<String, Set<String>> listToSet(Map<String, List<String>> list) {
        Map<String, Set<String>> result = new HashMap<>();
        for (Map.Entry<String, List<String>> entry : list.entrySet()) {
            result.put(entry.getKey(), new HashSet<>(entry.getValue()));
        }
        return result;
    }

    private Map<String, List<String>> setToList(Map<String, Set<String>> set) {
        Map<String, List<String>> result = new HashMap<>();
        for (Map.Entry<String, Set<String>> entry : set.entrySet()) {
            result.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        return result;
    }
}
