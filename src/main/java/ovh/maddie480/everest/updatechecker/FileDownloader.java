package ovh.maddie480.everest.updatechecker;

import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Provides a way to download files in different modules without redownloading them multiple times.
 */
public class FileDownloader {
    private static final Logger log = LoggerFactory.getLogger(FileDownloader.class);

    private static final Map<String, Path> alreadyDownloadedFiles = new HashMap<>();

    public static Path downloadFile(String url) throws IOException {
        return downloadFile(url, null, null);
    }

    public static Path downloadFile(String url, int expectedSize) throws IOException {
        return downloadFile(url, expectedSize, null);
    }

    public static Path downloadFile(String url, Collection<String> expectedHashes) throws IOException {
        return downloadFile(url, null, expectedHashes);
    }

    public static void cleanup() throws IOException {
        for (Path path : alreadyDownloadedFiles.values()) {
            log.debug("Cleaning up downloaded file {}", path);
            Files.delete(path);
        }
        alreadyDownloadedFiles.clear();
    }

    private static Path downloadFile(String url, Integer expectedSize, Collection<String> expectedHashes) throws IOException {
        if (alreadyDownloadedFiles.containsKey(url)) {
            Path path = alreadyDownloadedFiles.get(url);
            log.debug("File {} found in cache: {}", url, path.toAbsolutePath());
            return path;
        }

        final Path target = Paths.get("/tmp").resolve("updater_downloaded_file_" + System.currentTimeMillis());

        try {
            return ConnectionUtils.runWithRetry(() -> {
                log.debug("Starting download of {} to {}", url, target.toAbsolutePath());
                try (InputStream is = new BufferedInputStream(ConnectionUtils.openStreamWithTimeout(url));
                    OutputStream os = new BufferedOutputStream(Files.newOutputStream(target))) {

                    IOUtils.copy(is, os);
                }

                if (expectedSize != null) {
                    long actualSize = Files.size(target);
                    if (expectedSize != actualSize) {
                        throw new IOException("The announced file size (" + expectedSize + ") does not match what we got (" + actualSize + ")" +
                            " for file " + url);
                    }
                }

                if (expectedHashes != null) {
                    String xxHash = DatabaseUpdater.computeXXHash(target.toAbsolutePath().toString());
                    if (!expectedHashes.contains(xxHash)) {
                        throw new IOException("xxHash checksum failure on file " + url + "!");
                    }
                }

                log.debug("Download of {} to {} finished!", url, target.toAbsolutePath());
                alreadyDownloadedFiles.put(url, target);
                return target;
            });
        } catch (IOException e) {
            if (Files.exists(target)) Files.delete(target);
            throw e;
        }
    }
}