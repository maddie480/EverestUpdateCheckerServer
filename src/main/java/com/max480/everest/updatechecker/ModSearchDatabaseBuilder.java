package com.max480.everest.updatechecker;

import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.*;
import java.util.stream.Collectors;

public class ModSearchDatabaseBuilder {
    private static final Logger log = LoggerFactory.getLogger(DatabaseUpdater.class);

    /**
     * This object holds the name, author, description and text of a GameBanana mod.
     */
    private static class ModSearchInfo {
        private final String gameBananaType;
        private final int gameBananaId;
        private final String name;
        public final String authorName;
        private final String description;
        private final String text;
        private final int likes;
        private final int views;
        private final int downloads;
        private final int categoryId;
        private String categoryName;
        private final long createdDate;
        private final List<String> screenshots;

        public ModSearchInfo(String gameBananaType, int gameBananaId, String name,
                             String authorName, String description, String text,
                             int likes, int views, int downloads, int categoryId,
                             long createdDate, List<String> screenshots) {

            this.gameBananaType = gameBananaType;
            this.gameBananaId = gameBananaId;
            this.name = name;
            this.authorName = authorName;
            this.description = description;
            this.text = text;
            this.likes = likes;
            this.views = views;
            this.downloads = downloads;
            this.categoryId = categoryId;
            this.createdDate = createdDate;
            this.screenshots = screenshots;
        }

        /**
         * Gets this object as a map, which can be written to YAML more easily.
         *
         * @return This mod as a HashMap
         */
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("GameBananaType", gameBananaType);
            map.put("GameBananaId", gameBananaId);
            map.put("Name", name);
            map.put("Author", authorName);
            map.put("Description", description);
            map.put("Likes", likes);
            map.put("Views", views);
            map.put("Downloads", downloads);
            map.put("Text", text);
            map.put("CreatedDate", createdDate);
            map.put("Screenshots", screenshots);
            if (categoryName != null) {
                map.put("CategoryId", categoryId);
                map.put("CategoryName", categoryName);
            }
            return map;
        }
    }

    private final List<ModSearchInfo> modSearchInfo = new LinkedList<>();
    private final Set<Integer> categoryIds = new HashSet<>();

    /**
     * Adds this mod to the mod search info database.
     *
     * @param itemtype The GameBanana type
     * @param itemid   The GameBanana id
     * @param mod      The mod name
     * @throws IOException In case an error occurs while parsing the mod authors field
     */
    public void addMod(String itemtype, int itemid, List<Object> mod) throws IOException {
        // parse screenshots and determine their URLs.
        List<String> screenshots = new ArrayList<>();
        JSONArray screenshotsJson = new JSONArray(mod.get(10).toString());
        for (int i = 0; i < screenshotsJson.length(); i++) {
            JSONObject screenshotJson = screenshotsJson.getJSONObject(i);
            screenshots.add("https://images.gamebanana.com/" + screenshotJson.getString("_sRelativeImageDir") + "/" + screenshotJson.getString("_sFile"));
        }

        // depending on its length, the created date field can be interpreted as an integer or as a long.
        long modCreatedDate;
        if (mod.get(9) instanceof Integer) {
            modCreatedDate = (int) mod.get(9);
        } else {
            modCreatedDate = (long) mod.get(9);
        }

        ModSearchInfo newModSearchInfo = new ModSearchInfo(itemtype, itemid, mod.get(0).toString(),
                mod.get(2).toString(), mod.get(3).toString(), mod.get(4).toString(),
                (int) mod.get(5), (int) mod.get(6), (int) mod.get(7), (int) mod.get(8), modCreatedDate, screenshots);

        modSearchInfo.add(newModSearchInfo);

        if ("Mod".equals(itemtype)) {
            // "Mod" is a generic itemtype, and we should get a more precise category instead.
            categoryIds.add((int) mod.get(8));
        }
    }

    /**
     * Saves the mod search database to uploads/modsearchdatabase.yaml.
     *
     * @throws IOException If the file couldn't be written, or something went wrong with getting author/mod category names.
     */
    public void saveSearchDatabase() throws IOException {
        // get authors and category names
        fetchNamesAndUpdate(categoryIds, "ModCategory", (mod, id, name) -> {
            if (mod.categoryId == id) {
                mod.categoryName = name;
            }
        });

        // map ModSearchInfo's to Maps and save them.
        try (FileWriter writer = new FileWriter("uploads/modsearchdatabase.yaml")) {
            new Yaml().dump(modSearchInfo.stream().map(ModSearchInfo::toMap).collect(Collectors.toList()), writer);
        }
    }

    /**
     * Like BiConsumer but triple
     *
     * @param <A> The type of the first argument
     * @param <B> The type of the second argument
     * @param <C> The type of the third argument
     * @see java.util.function.BiConsumer
     */
    private interface TriConsumer<A, B, C> {
        void accept(A a, B b, C c);
    }

    /**
     * Fetches all names based on their IDs, and replaces them in the mod search database.
     * Used for both authors and mod categories.
     *
     * @param ids           The list of ids to get
     * @param itemtype      The GameBanana itemtype (Member for authors, ModCategory for mod categories)
     * @param handleMapping The function that takes a mod, an id and the corresponding name and updates the name in the mod
     *                      if the id matches.
     * @throws IOException If something went wrong when querying GameBanana for the list.
     */
    private void fetchNamesAndUpdate(Set<Integer> ids, String itemtype, TriConsumer<ModSearchInfo, Integer, String> handleMapping) throws IOException {
        // turn the Set into a List to be able to access it by index.
        List<Integer> idsAsList = new ArrayList<>(ids);

        List<List<Object>> names;
        {
            // build the URL that is going to get all names matching IDs at once.
            // that is one big URL, but this should be fine.
            StringBuilder urlUserInfo = new StringBuilder("https://api.gamebanana.com/Core/Item/Data?");
            int index = 0;
            for (Integer id : idsAsList) {
                urlUserInfo
                        .append("itemtype[").append(index).append("]=").append(itemtype).append("&itemid[").append(index).append("]=").append(id)
                        .append("&fields[").append(index).append("]=name&");
                index++;
            }
            String url = urlUserInfo.append("format=yaml").toString();

            // run the request, parse the result, and add this result to the list.
            names = DatabaseUpdater.runWithRetry(() -> {
                try (InputStream is = DatabaseUpdater.openStreamWithTimeout(new URL(url))) {
                    return Optional.ofNullable(new Yaml().<List<List<Object>>>load(is))
                            .orElseThrow(() -> new IOException("Ended up with a null value when loading a mod page"));
                }
            });
        }

        // go through the results.
        int index = 0;
        for (List<Object> info : names) {
            int id = idsAsList.get(index);
            String name = info.get(0).toString();

            // apply the name to every mod that has it as an ID.
            for (ModSearchInfo mod : modSearchInfo) {
                handleMapping.accept(mod, id, name);
            }

            index++;
        }
    }
}
