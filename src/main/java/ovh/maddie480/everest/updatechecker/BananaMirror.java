package ovh.maddie480.everest.updatechecker;

import com.jcraft.jsch.*;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * This service mirrors all mods that are present in everest_update.yaml.
 */
public class BananaMirror {
    private static final Logger log = LoggerFactory.getLogger(BananaMirror.class);

    static void run() throws IOException {
        // load the list of existing mods.
        log.debug("Loading updater database...");
        Map<String, Map<String, Object>> everestUpdateYaml;
        try (InputStream stream = Files.newInputStream(Paths.get("uploads/everestupdate.yaml"))) {
            everestUpdateYaml = YamlUtil.load(stream);
        }

        // load the list of files that are already in the mirror.
        List<String> bananaMirrorList = listFiles();
        Set<String> toDelete = new HashSet<>(bananaMirrorList);

        for (Map<String, Object> mod : everestUpdateYaml.values()) {
            // get the mod URL and hash.
            String modUrl = mod.get(Main.serverConfig.mainServerIsMirror ? "MirrorURL" : "URL").toString();
            List<String> modHashes = (List<String>) mod.get("xxHash");

            // extract the file ID: only handle valid GameBanana links, as we use the GameBanana URL format to name our file.
            if (!modUrl.matches("https://gamebanana.com/mmdl/[0-9]+")) {
                log.warn("Not mirroring {} as it doesn't match the GameBanana URL pattern!", modUrl);
                continue;
            }
            String fileId = modUrl.substring("https://gamebanana.com/mmdl/".length());

            if (bananaMirrorList.contains(fileId)) {
                log.trace("File {} is already mirrored and will be kept", fileId);
                toDelete.remove(fileId);
            } else {
                log.info("File {} is not currently mirrored! Doing that now.", fileId);
                downloadFile(modUrl, fileId, modHashes, bananaMirrorList);
            }
        }

        // delete all files that disappeared from the database.
        for (String file : toDelete) {
            log.info("File {} is mirrored but doesn't exist anymore! Deleting it now.", file);
            deleteFile(file, bananaMirrorList);
        }
    }

    private static void downloadFile(String modUrl, String fileId, List<String> modHashes, List<String> fileList) throws IOException {
        Path file = FileDownloader.downloadFile(modUrl, modHashes);
        uploadFile(file, fileId, fileList);
    }

    private static List<String> listFiles() throws IOException {
        try (FileInputStream is = new FileInputStream("banana_mirror.yaml")) {
            return YamlUtil.load(is);
        }
    }

    private static void uploadFile(Path filePath, String fileId, List<String> fileList) throws IOException {
        // actually upload the file
        makeSftpAction(Main.serverConfig.bananaMirrorConfig.directory, channel -> channel.put(filePath.toAbsolutePath().toString(), fileId + ".zip"));

        // add the file to the list of files that are actually on the mirror, and write it to disk.
        fileList.add(fileId);
        try (FileOutputStream os = new FileOutputStream("banana_mirror.yaml")) {
            YamlUtil.dump(fileList, os);
        }

        log.info("Uploaded {}.zip to Banana Mirror", fileId);
        EventListener.handle(listener -> listener.uploadedModToBananaMirror(fileId + ".zip"));
    }

    private static void deleteFile(String fileId, List<String> fileList) throws IOException {
        makeSftpAction(Main.serverConfig.bananaMirrorConfig.directory, channel -> channel.rm(fileId + ".zip"));

        // delete the file from the list of files that are actually on the mirror, and write it to disk.
        fileList.remove(fileId);
        try (FileOutputStream os = new FileOutputStream("banana_mirror.yaml")) {
            YamlUtil.dump(fileList, os);
        }

        log.info("Deleted {}.zip from Banana Mirror", fileId);
        EventListener.handle(listener -> listener.deletedModFromBananaMirror(fileId + ".zip"));
    }

    // simple interface for a method that takes a ChannelSftp **and throws a SftpException**.
    interface SftpAction {
        void doSftpAction(ChannelSftp channel) throws SftpException;
    }

    static void makeSftpAction(String directory, SftpAction action) throws IOException {
        ConnectionUtils.runWithRetry(() -> {
            Session session = null;
            try {
                // connect
                JSch jsch = new JSch();
                jsch.setKnownHosts(Main.serverConfig.bananaMirrorConfig.knownHosts);
                session = jsch.getSession(Main.serverConfig.bananaMirrorConfig.username, Main.serverConfig.bananaMirrorConfig.serverAddress);
                session.setPassword(Main.serverConfig.bananaMirrorConfig.password);
                session.connect();

                // do the action
                ChannelSftp sftp = (ChannelSftp) session.openChannel("sftp");
                sftp.connect();
                sftp.cd(directory);
                action.doSftpAction(sftp);
                sftp.exit();

                // disconnect
                session.disconnect();
                session = null;
            } catch (JSchException | SftpException e) {
                throw new IOException(e);
            } finally {
                if (session != null) {
                    session.disconnect();
                }
            }

            return null;
        });
    }
}
