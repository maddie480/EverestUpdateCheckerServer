package com.max480.everest.updatechecker;

import com.jcraft.jsch.*;
import net.jpountz.xxhash.StreamingXXHash64;
import net.jpountz.xxhash.XXHashFactory;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class BananaMirror {
    private static final Logger log = LoggerFactory.getLogger(BananaMirror.class);
    private static XXHashFactory xxHashFactory = XXHashFactory.fastestInstance();

    private static final String KNOWN_HOSTS = "REPLACEME";
    private static final String SERVER_ADDRESS = "REPLACEME";
    private static final String USERNAME = "REPLACEME";
    private static final String PASSWORD = "REPLACEME";
    private static final String DIRECTORY = "REPLACEME";

    public static void main(String[] args) throws IOException {
        // load the list of existing mods.
        Map<String, Map<String, Object>> everestUpdateYaml;
        try (InputStream stream = new FileInputStream("uploads/everestupdate.yaml")) {
            everestUpdateYaml = new Yaml().load(stream);
        }

        // load the list of files that are already in the mirror.
        List<String> bananaMirrorList = listFiles();
        Set<String> toDelete = new HashSet<>(bananaMirrorList);

        for (Map<String, Object> mod : everestUpdateYaml.values()) {
            // get the mod URL and hash.
            String modUrl = mod.get("URL").toString();
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
            try (OutputStream os = new BufferedOutputStream(new FileOutputStream("mod-for-cloud.zip"))) {
                IOUtils.copy(new BufferedInputStream(DatabaseUpdater.openStreamWithTimeout(new URL(modUrl))), os);
                return null; // to fullfill this stupid method signature
            }
        });

        // compute its xxHash checksum
        String xxHash;
        try (InputStream is = new FileInputStream("mod-for-cloud.zip")) {
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
            return new Yaml().load(is);
        }
    }

    private static void uploadFile(Path filePath, String fileId, List<String> fileList) throws IOException {
        // actually upload the file
        makeSftpAction(channel -> channel.put(filePath.toAbsolutePath().toString(), fileId + ".zip"));

        // add the file to the list of files that are actually on the mirror, and write it to disk.
        fileList.add(fileId);
        try (FileOutputStream os = new FileOutputStream("banana_mirror.yaml")) {
            IOUtils.write(new Yaml().dump(fileList), os, "UTF-8");
        }

        log.info("Uploaded {}.zip to Banana Mirror", fileId);
    }

    private static void deleteFile(String fileId, List<String> fileList) throws IOException {
        makeSftpAction(channel -> channel.rm(fileId + ".zip"));

        // delete the file from the list of files that are actually on the mirror, and write it to disk.
        fileList.remove(fileId);
        try (FileOutputStream os = new FileOutputStream("banana_mirror.yaml")) {
            IOUtils.write(new Yaml().dump(fileList), os, "UTF-8");
        }

        log.info("Deleted {}.zip from Banana Mirror", fileId);
    }

    // simple interface for a method that takes a ChannelSftp **and throws a SftpException**.
    private interface SftpAction {
        void doSftpAction(ChannelSftp channel) throws SftpException;
    }

    private static void makeSftpAction(SftpAction action) throws IOException {
        Session session = null;
        try {
            // connect
            JSch jsch = new JSch();
            jsch.setKnownHosts(KNOWN_HOSTS);
            session = jsch.getSession(USERNAME, SERVER_ADDRESS);
            session.setPassword(PASSWORD);
            session.connect();

            // do the action
            ChannelSftp sftp = (ChannelSftp) session.openChannel("sftp");
            sftp.connect();
            sftp.cd(DIRECTORY);
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
    }
}
