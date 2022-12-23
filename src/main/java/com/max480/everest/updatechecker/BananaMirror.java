package com.max480.everest.updatechecker;

import com.jcraft.jsch.*;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class BananaMirror {
    private static final Logger log = LoggerFactory.getLogger(BananaMirror.class);

    static void run() throws IOException {
        // load the list of existing mods.
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
                continue;
            }
            String fileId = modUrl.substring("https://gamebanana.com/mmdl/".length());

            if (bananaMirrorList.contains(fileId)) {
                // existing file! this file should be kept.
                toDelete.remove(fileId);
            } else {
                // file is new!
                downloadFile(modUrl, fileId, modHashes, bananaMirrorList);
            }
        }

        // delete all files that disappeared from the database.
        for (String file : toDelete) {
            deleteFile(file, bananaMirrorList);
        }
    }

    private static void downloadFile(String modUrl, String fileId, List<String> modHashes, List<String> fileList) throws IOException {
        // download the mod
        DatabaseUpdater.runWithRetry(() -> {
            try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(Paths.get("mod-for-cloud.zip")))) {
                IOUtils.copy(new BufferedInputStream(DatabaseUpdater.openStreamWithTimeout(new URL(modUrl))), os);
                return null; // to fulfill this stupid method signature
            }
        });

        // compute its xxHash checksum
        String xxHash = DatabaseUpdater.computeXXHash("mod-for-cloud.zip");

        // check that it matches
        if (!modHashes.contains(xxHash)) {
            throw new IOException("xxHash checksum failure on file with id " + fileId + " @ " + modUrl + "!");
        }

        // upload to Banana Mirror
        uploadFile(Paths.get("mod-for-cloud.zip"), fileId, fileList);
        FileUtils.forceDelete(new File("mod-for-cloud.zip"));
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
        DatabaseUpdater.runWithRetry(() -> {
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
