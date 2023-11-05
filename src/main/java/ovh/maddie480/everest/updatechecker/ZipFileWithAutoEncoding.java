package ovh.maddie480.everest.updatechecker;

import org.apache.commons.io.EndianUtils;
import org.mozilla.universalchardet.UniversalDetector;

import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

public class ZipFileWithAutoEncoding {
    /**
     * Opens a ZIP file that is on disk.
     * If this fails due to the file not using UTF-8 file names, this will try detecting the encoding.
     */
    public static ZipFile open(String path) throws IOException {
        return open(path, null);
    }

    /**
     * Opens a ZIP file that is on disk.
     * If this fails due to the file not using UTF-8 file names, this will try detecting the encoding.
     * If gameBananaDownloadUrl is specified, event listeners will be called with it if the zip does not use UTF-8.
     */
    protected static ZipFile open(String path, String gameBananaDownloadUrl) throws IOException {
        try {
            return new ZipFile(path);
        } catch (IOException e) {
            if (e instanceof ZipException && e.getMessage().contains("bad entry name")) {
                UniversalDetector universalDetector = new UniversalDetector(null);
                for (byte[] fileName : getFileNames(path)) {
                    universalDetector.handleData(fileName, 0, fileName.length);
                }
                universalDetector.dataEnd();

                String encodingName = universalDetector.getDetectedCharset();
                if (encodingName != null) {
                    if (gameBananaDownloadUrl != null) {
                        EventListener.handle(event -> event.zipFileIsNotUTF8(gameBananaDownloadUrl, encodingName));
                    }
                    return new ZipFile(path, Charset.forName(encodingName));
                }
            }

            throw e;
        }
    }

    /**
     * Reads the raw bytes of file names from a ZIP file.
     */
    private static List<byte[]> getFileNames(String path) throws IOException {
        List<byte[]> result = new ArrayList<>();
        try (DataInputStream is = new DataInputStream(new FileInputStream(path))) {
            while (true) {
                // look for the local file header
                byte[] header = new byte[4];
                while (header[0] != 0x50 || header[1] != 0x4b || header[2] != 0x03 || header[3] != 0x04) {
                    if (is.read(header) != 4) {
                        // reached end of file!
                        return result;
                    }
                }

                // jump to compressed size
                is.skipBytes(14);

                int fileSize = EndianUtils.readSwappedInteger(is);

                // jump over uncompressed size, to file name length
                is.skipBytes(4);

                // read file name and extra field lengths
                byte[] fileName = new byte[EndianUtils.readSwappedShort(is)];
                short extraFieldLength = EndianUtils.readSwappedShort(is);

                // read the file name!
                if (is.read(fileName) != fileName.length) {
                    throw new IOException("End of data reached before end of file name!");
                }
                result.add(fileName);

                // jump over the extra field and the file itself
                is.skipBytes(extraFieldLength + fileSize);
            }
        }
    }
}
