package com.max480.everest.updatechecker;

import com.google.api.gax.paging.Page;
import com.google.cloud.storage.*;
import com.google.common.collect.ImmutableMap;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BananaMirror {
    private static final Logger log = LoggerFactory.getLogger(BananaMirror.class);
    private static XXHashFactory xxHashFactory = XXHashFactory.fastestInstance();

    public static void main(String[] args) throws IOException {
        Storage storage = StorageOptions.newBuilder().setProjectId("max480-random-stuff").build().getService();

        Map<String, Map<String, Object>> everestUpdateYaml;
        try (InputStream stream = new FileInputStream("uploads/everestupdate.yaml")) {
            everestUpdateYaml = new Yaml().load(stream);
        }

        Map<String, String> bananaMirrorList = listFiles(storage);

        for (String modId : everestUpdateYaml.keySet()) {
            String modUrl = everestUpdateYaml.get(modId).get("URL").toString();
            List<String> modHashes = (List<String>) everestUpdateYaml.get(modId).get("xxHash");

            if (bananaMirrorList.containsKey(modId + ".zip")) {
                String cachedModURL = bananaMirrorList.get(modId + ".zip");
                if (!modUrl.equals(cachedModURL)) {
                    // file changed!
                    downloadFile(storage, modId, modUrl, modHashes);
                }

                // this file should be kept.
                bananaMirrorList.remove(modId + ".zip");
            } else {
                // file is new!
                downloadFile(storage, modId, modUrl, modHashes);
            }
        }

        // delete all files that disappeared from the database.
        for (String file : bananaMirrorList.keySet()) {
            deleteFile(storage, file);
        }
    }

    private static void downloadFile(Storage storage, String modId, String modUrl, List<String> modHashes) throws IOException {
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
            throw new IOException("xxHash checksum failure on " + modId + " @ " + modUrl + "!");
        }

        // upload to Google Cloud Storage
        uploadFile(storage, Paths.get("mod-for-cloud.zip"), modId + ".zip", modUrl);
        FileUtils.forceDelete(new File("mod-for-cloud.zip"));
    }

    private static Map<String, String> listFiles(Storage storage) {
        Map<String, String> result = new HashMap<>();

        final Page<Blob> page = storage.list("max480-banana-mirror");
        for (Blob blob : page.iterateAll()) {
            result.put(blob.getName(), blob.getMetadata().get("original-url"));
        }

        return result;
    }

    private static void uploadFile(Storage storage, Path filePath, String fileName, String url) throws IOException {
        BlobId blobId = BlobId.of("max480-banana-mirror", fileName);
        BlobInfo blobInfo = BlobInfo.newBuilder(blobId).setMetadata(ImmutableMap.of("original-url", url)).build();
        storage.createFrom(blobInfo, filePath, 4096);
        storage.createAcl(blobId, Acl.of(Acl.User.ofAllUsers(), Acl.Role.READER));

        log.info("Uploaded {} (from {}) to Banana Mirror", fileName, url);
    }

    private static void deleteFile(Storage storage, String fileName) {
        storage.delete("max480-banana-mirror", fileName);
        log.info("Deleted {} from Banana Mirror", fileName);
    }
}
