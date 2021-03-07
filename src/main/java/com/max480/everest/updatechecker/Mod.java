package com.max480.everest.updatechecker;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
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

    /**
     * Builds a Mod object based on all its information.
     */
    Mod(String name, String version, String url, int lastUpdate, List<String> xxHash,
        String gameBananaType, int gameBananaId) {

        this.name = name;
        this.version = version;
        this.url = url;
        this.lastUpdate = lastUpdate;
        this.xxHash = xxHash;
        this.gameBananaType = gameBananaType;
        this.gameBananaId = gameBananaId;
    }

    /**
     * Creates a Mod object based on a map entry loaded from everestupdate.yaml.
     */
    Mod(Map.Entry<String, Map<String, Object>> yamlDatabaseEntry) {
        name = yamlDatabaseEntry.getKey();
        version = (String) yamlDatabaseEntry.getValue().get("Version");
        url = (String) yamlDatabaseEntry.getValue().get("URL");
        lastUpdate = (int) yamlDatabaseEntry.getValue().get("LastUpdate");
        xxHash = (List<String>) yamlDatabaseEntry.getValue().get("xxHash");
        gameBananaType = (String) yamlDatabaseEntry.getValue().get("GameBananaType");
        gameBananaId = (int) yamlDatabaseEntry.getValue().get("GameBananaId");
    }

    /**
     * Converts the Mod object to a map that can be exported to everestupdate.yaml.
     */
    Map<String, Object> toMap() throws UnsupportedEncodingException {
        Map<String, Object> modMap = new HashMap<>();
        modMap.put("Version", version);
        modMap.put("URL", url);
        modMap.put("MirrorURL", "https://storage.googleapis.com/max480-banana-mirror/" +
                URLEncoder.encode(name, "UTF-8")
                        .replaceAll("\\+", "%20")
                        .replaceAll("\\%21", "!")
                        .replaceAll("\\%27", "'")
                        .replaceAll("\\%28", "(")
                        .replaceAll("\\%29", ")")
                        .replaceAll("\\%7E", "~") + ".zip");
        modMap.put("LastUpdate", lastUpdate);
        modMap.put("xxHash", xxHash);
        modMap.put("GameBananaType", gameBananaType);
        modMap.put("GameBananaId", gameBananaId);
        return modMap;
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
                '}';
    }

    String getUrl() {
        return url;
    }

    int getLastUpdate() {
        return lastUpdate;
    }
}
