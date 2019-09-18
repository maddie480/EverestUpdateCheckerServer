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

    /**
     * Builds a Mod object based on all its information.
     */
    Mod(String name, String version, String url, int lastUpdate, List<String> xxHash) {
        this.name = name;
        this.version = version;
        this.url = url;
        this.lastUpdate = lastUpdate;
        this.xxHash = xxHash;
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
    }

    /**
     * Converts the Mod object to a map that can be exported to everestupdate.yaml.
     */
    Map<String, Object> toMap() {
        Map<String, Object> modMap = new HashMap<>();
        modMap.put("Version", version);
        modMap.put("URL", url);
        modMap.put("LastUpdate", lastUpdate);
        modMap.put("xxHash", xxHash);
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
                '}';
    }

    String getUrl() {
        return url;
    }

    int getLastUpdate() {
        return lastUpdate;
    }
}
