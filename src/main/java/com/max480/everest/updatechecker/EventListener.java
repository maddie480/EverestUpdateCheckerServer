package com.max480.everest.updatechecker;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public abstract class EventListener {
    private static List<EventListener> eventListeners = new ArrayList<>();

    public static void addEventListener(EventListener eventListener) {
        eventListeners.add(eventListener);
    }

    public static void removeEventListener(EventListener eventListener) {
        eventListeners.remove(eventListener);
    }

    static void handle(Consumer<EventListener> functionCall) {
        for (EventListener listener : eventListeners) {
            functionCall.accept(listener);
        }
    }

    // === info

    public abstract void startedSearchingForUpdates(boolean full);

    public abstract void endedSearchingForUpdates(int modDownloadedCount, long timeTakenMilliseconds);

    public abstract void uploadedModToBananaMirror(String fileName);

    public abstract void deletedModFromBananaMirror(String fileName);

    public abstract void uploadedImageToBananaMirror(String fileName);

    public abstract void deletedImageFromBananaMirror(String fileName);

    public abstract void uploadedRichPresenceIconToBananaMirror(String fileName, String originatingFileId);

    public abstract void deletedRichPresenceIconFromBananaMirror(String fileName, String originatingFileId);

    public abstract void savedNewInformationToDatabase(Mod mod);

    public abstract void scannedZipContents(String fileUrl, int fileCount);

    public abstract void scannedAhornEntities(String fileUrl, int entityCount, int triggerCount, int effectCount);

    public abstract void scannedLoennEntities(String fileUrl, int entityCount, int triggerCount, int effectCount);

    public abstract void scannedModDependencies(String modId, int dependencyCount, int optionalDependencyCount);

    public abstract void modUpdatedIncrementally(String gameBananaType, int gameBananaId, String modName);


    // === warn

    public abstract void modHasNoYamlFile(String gameBananaType, int gameBananaId, String fileUrl);

    public abstract void zipFileIsNotUTF8(String downloadUrl, String detectedEncoding);

    public abstract void zipFileIsUnreadable(String gameBananaType, int gameBananaId, String fileUrl, IOException e);

    public abstract void zipFileIsUnreadableForFileListing(String gameBananaType, int gameBananaId, String fileUrl, Exception e);

    public abstract void moreRecentFileAlreadyExists(String gameBananaType, int gameBananaId, String fileUrl, Mod otherMod);

    public abstract void currentVersionBelongsToAnotherMod(String gameBananaType, int gameBananaId, String fileUrl, Mod otherMod);

    public abstract void modIsExcludedByName(Mod mod);

    public abstract void yamlFileIsUnreadable(String gameBananaType, int gameBananaId, String fileUrl, Exception e);

    public abstract void modWasDeletedFromDatabase(Mod mod);

    public abstract void modWasDeletedFromExcludedFileList(String fileUrl);

    public abstract void modWasDeletedFromNoYamlFileList(String fileUrl);

    public abstract void retriedIOException(IOException e);

    public abstract void dependencyTreeScanException(String modId, Exception e);

    public abstract void zipFileWalkthroughError(String gameBananaType, int gameBananaId, String fileUrl, Exception e);

    public abstract void ahornPluginScanError(String fileUrl, Exception e);

    public abstract void loennPluginScanError(String fileUrl, Exception e);


    // === error

    public abstract void uncaughtError(Exception e);
}
