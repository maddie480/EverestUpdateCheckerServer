package ovh.maddie480.everest.updatechecker;

import net.coobird.thumbnailator.Thumbnails;
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

public class BananaMirrorImages {
    private static final Logger log = LoggerFactory.getLogger(BananaMirrorImages.class);

    static void run() throws IOException {
        // load the list of existing mods.
        List<Map<String, Object>> modSearchDatabase;
        try (InputStream stream = Files.newInputStream(Paths.get("uploads/modsearchdatabase.yaml"))) {
            modSearchDatabase = YamlUtil.load(stream);
        }

        // load the list of files that are already in the mirror.
        List<String> bananaMirrorList = listFiles();
        Set<String> toDelete = new HashSet<>(bananaMirrorList);

        for (Map<String, Object> mod : modSearchDatabase) {
            List<String> screenshots = (List<String>) mod.get("Screenshots");

            // we want to only mirror the 2 first screenshots.
            for (int i = 0; i < screenshots.size() && i < 2; i++) {
                String screenshotUrl = screenshots.get(i);
                String screenshotId = screenshotUrl.substring("https://images.gamebanana.com/".length(), screenshotUrl.lastIndexOf(".")).replace("/", "_") + ".png";

                if (bananaMirrorList.contains(screenshotId)) {
                    // existing file! this file should be kept.
                    toDelete.remove(screenshotId);
                } else {
                    // file is new!
                    downloadFile(screenshotUrl, screenshotId, bananaMirrorList);
                }
            }
        }

        // delete all files that disappeared from the database.
        for (String file : toDelete) {
            deleteFile(file, bananaMirrorList);
        }
    }

    private static void downloadFile(String screenshotUrl, String screenshotId, List<String> fileList) throws IOException {
        // download the screenshot
        ConnectionUtils.runWithRetry(() -> {
            try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(Paths.get("image_to_read")))) {
                IOUtils.copy(new BufferedInputStream(ConnectionUtils.openStreamWithTimeout(screenshotUrl)), os);
                return null; // to fulfill this stupid method signature
            }
        });

        // minimize it to 220px
        Thumbnails.of(new File("image_to_read"))
                .size(220, 220)
                .outputFormat("png")
                .toFile("thumb.png");

        // upload to Banana Mirror
        uploadFile(Paths.get("thumb.png"), screenshotId, fileList);
        FileUtils.forceDelete(new File("image_to_read"));
        FileUtils.forceDelete(new File("thumb.png"));
    }

    private static List<String> listFiles() throws IOException {
        try (FileInputStream is = new FileInputStream("banana_mirror_images.yaml")) {
            return YamlUtil.load(is);
        }
    }

    private static void uploadFile(Path filePath, String fileId, List<String> fileList) throws IOException {
        // actually upload the file
        BananaMirror.makeSftpAction(Main.serverConfig.bananaMirrorConfig.imagesDirectory,
                channel -> channel.put(filePath.toAbsolutePath().toString(), fileId));

        // add the file to the list of files that are actually on the mirror, and write it to disk.
        fileList.add(fileId);
        try (FileOutputStream os = new FileOutputStream("banana_mirror_images.yaml")) {
            YamlUtil.dump(fileList, os);
        }

        log.info("Uploaded {} to Banana Mirror", fileId);
        EventListener.handle(listener -> listener.uploadedImageToBananaMirror(fileId));
    }

    private static void deleteFile(String fileId, List<String> fileList) throws IOException {
        BananaMirror.makeSftpAction(Main.serverConfig.bananaMirrorConfig.imagesDirectory, channel -> channel.rm(fileId));

        // delete the file from the list of files that are actually on the mirror, and write it to disk.
        fileList.remove(fileId);
        try (FileOutputStream os = new FileOutputStream("banana_mirror_images.yaml")) {
            YamlUtil.dump(fileList, os);
        }

        log.info("Deleted {} from Banana Mirror", fileId);
        EventListener.handle(listener -> listener.deletedImageFromBananaMirror(fileId));
    }
}
