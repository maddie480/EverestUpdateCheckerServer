package com.max480.everest.updatechecker;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Mod {
    private String name;
    private String version;
    private String url;
    private int lastUpdate;
    private List<String> xxHash;
    private String gameBananaType;
    private int gameBananaId;
    private int size;

    /**
     * Builds a Mod object based on all its information.
     */
    Mod(String name, String version, String url, int lastUpdate, List<String> xxHash,
        String gameBananaType, int gameBananaId, int size) {

        this.name = name;
        this.version = version;
        this.url = url;
        this.lastUpdate = lastUpdate;
        this.xxHash = xxHash;
        this.gameBananaType = gameBananaType;
        this.gameBananaId = gameBananaId;
        this.size = size;
    }

    /**
     * Creates a Mod object based on a map entry loaded from everestupdate.yaml.
     */
    Mod(Map.Entry<String, Map<String, Object>> yamlDatabaseEntry) {
        name = yamlDatabaseEntry.getKey();
        version = (String) yamlDatabaseEntry.getValue().get("Version");
        url = (String) yamlDatabaseEntry.getValue().get(Main.serverConfig.mainServerIsMirror ? "MirrorURL" : "URL");
        lastUpdate = (int) yamlDatabaseEntry.getValue().get("LastUpdate");
        xxHash = (List<String>) yamlDatabaseEntry.getValue().get("xxHash");
        gameBananaType = (String) yamlDatabaseEntry.getValue().get("GameBananaType");
        gameBananaId = (int) yamlDatabaseEntry.getValue().get("GameBananaId");
        size = (int) yamlDatabaseEntry.getValue().get("Size");
    }

    /**
     * Converts the Mod object to a map that can be exported to everestupdate.yaml.
     */
    Map<String, Object> toMap() {
        // extract the file ID from the GameBanana link, to build the mirror URL.
        if (!url.matches("https://gamebanana.com/mmdl/[0-9]+")) {
            throw new RuntimeException("URL is in an invalid format: " + url);
        }
        String fileId = url.substring("https://gamebanana.com/mmdl/".length());

        Map<String, Object> modMap = new HashMap<>();
        modMap.put("Version", version);
        modMap.put(Main.serverConfig.mainServerIsMirror ? "MirrorURL" : "URL", url);
        modMap.put(Main.serverConfig.mainServerIsMirror ? "URL" : "MirrorURL", "https://celestemodupdater.0x0a.de/banana-mirror/" + fileId + ".zip");
        modMap.put("LastUpdate", lastUpdate);
        modMap.put("xxHash", xxHash);
        modMap.put("GameBananaType", gameBananaType);
        modMap.put("GameBananaId", gameBananaId);
        modMap.put("Size", size);
        return modMap;
    }

    public void updateGameBananaIds(String gameBananaType, int gameBananaId, int size) {
        this.gameBananaType = gameBananaType;
        this.gameBananaId = gameBananaId;
        this.size = size;
    }

    @Override
    public String toString() {
        return "Mod{" +
                "name='" + name + '\'' +
                ", version='" + version + '\'' +
                ", url='" + url + '\'' +
                ", lastUpdate=" + lastUpdate +
                ", xxHash=" + xxHash +
                ", gameBananaType='" + gameBananaType + '\'' +
                ", gameBananaId=" + gameBananaId +
                ", size=" + size +
                '}';
    }

    String getUrl() {
        return url;
    }

    int getLastUpdate() {
        return lastUpdate;
    }
}
