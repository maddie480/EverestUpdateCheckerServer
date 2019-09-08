package com.max480.everest.updatechecker;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Mod {
    private String name;
    private String version;
    private String url;
    private int lastUpdate;
    private List<String> sha256;

    /**
     * Builds a Mod object based on all its information.
     */
    Mod(String name, String version, String url, int lastUpdate, List<String> sha256) {
        this.name = name;
        this.version = version;
        this.url = url;
        this.lastUpdate = lastUpdate;
        this.sha256 = sha256;
    }

    /**
     * Creates a Mod object based on a map entry loaded from everestupdate.yaml.
     */
    Mod(Map.Entry<String, Map<String, Object>> yamlDatabaseEntry) {
        name = yamlDatabaseEntry.getKey();
        version = (String) yamlDatabaseEntry.getValue().get("Version");
        url = (String) yamlDatabaseEntry.getValue().get("URL");
        lastUpdate = (int) yamlDatabaseEntry.getValue().get("LastUpdate");
        sha256 = (List<String>) yamlDatabaseEntry.getValue().get("SHA256");
    }

    /**
     * Converts the Mod object to a map that can be exported to everestupdate.yaml.
     */
    Map<String, Object> toMap() {
        Map<String, Object> modMap = new HashMap<>();
        modMap.put("Version", version);
        modMap.put("URL", url);
        modMap.put("LastUpdate", lastUpdate);
        modMap.put("SHA256", sha256);
        return modMap;
    }

    @Override
    public String toString() {
        return "Mod{" +
                "name='" + name + '\'' +
                ", version='" + version + '\'' +
                ", url='" + url + '\'' +
                ", lastUpdate=" + lastUpdate +
                ", sha256=" + sha256 +
                '}';
    }

    String getUrl() {
        return url;
    }
}
