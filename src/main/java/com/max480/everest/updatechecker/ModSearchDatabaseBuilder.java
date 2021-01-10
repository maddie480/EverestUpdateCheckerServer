package com.max480.everest.updatechecker;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
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
        public List<String> authors;
        public final int authorId;
        private final String description;
        private final String text;

        public ModSearchInfo(String gameBananaType, int gameBananaId, String name,
                             List<String> authors, String description, String text) {

            this.gameBananaType = gameBananaType;
            this.gameBananaId = gameBananaId;
            this.name = name;
            this.authorId = -1;
            this.authors = authors;
            this.description = description;
            this.text = Jsoup.parseBodyFragment(text).text(); // strip HTML
        }

        public ModSearchInfo(String gameBananaType, int gameBananaId, String name,
                             int authorId, String description, String text) {

            this.gameBananaType = gameBananaType;
            this.gameBananaId = gameBananaId;
            this.name = name;
            this.authorId = authorId;
            this.description = description;
            this.text = Jsoup.parseBodyFragment(text).text(); // strip HTML
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
            map.put("Authors", authors);
            map.put("Description", description);
            map.put("Text", text);
            return map;
        }
    }

    private final List<ModSearchInfo> modSearchInfo = new LinkedList<>();
    private final Set<Integer> authorIds = new HashSet<>();

    /**
     * Adds this mod to the mod search info database.
     *
     * @param itemtype The GameBanana type
     * @param itemid   The GameBanana id
     * @param mod      The mod name
     */
    public void addMod(String itemtype, int itemid, List<Object> mod) {
        try {
            ModSearchInfo newModSearchInfo = new ModSearchInfo(itemtype, itemid, mod.get(0).toString(),
                    Integer.parseInt(mod.get(2).toString()), mod.get(3).toString(), mod.get(4).toString());

            modSearchInfo.add(newModSearchInfo);
            authorIds.add(newModSearchInfo.authorId);
            return;
        } catch (NumberFormatException e) {
            // this mod has an author list rather than an author id, which is objectively better.
        }

        // the authors list is actually JSON. :faintshiro:
        String authors = mod.get(2).toString();
        List<String> authorsParsed = new LinkedList<>();

        // each author category is an entry in a JSON object.
        JSONObject authorCategories = new JSONObject(authors);
        for (String category : authorCategories.keySet()) {
            JSONArray authorsListForCategory = authorCategories.getJSONArray(category);

            // then each author is a JSON array [name, userid, role, ???]
            // and we want to keep the name.
            for (Object author : authorsListForCategory) {
                JSONArray authorArray = (JSONArray) author;
                authorsParsed.add(authorArray.get(0).toString());
            }
        }

        modSearchInfo.add(new ModSearchInfo(itemtype, itemid, mod.get(0).toString(), authorsParsed,
                mod.get(3).toString(), mod.get(4).toString()));
    }

    /**
     * Saves the mod search database to uploads/modsearchdatabase.yaml.
     *
     * @throws IOException If the file couldn't be written, or something went wrong with getting author names.
     */
    public void saveSearchDatabase() throws IOException {
        fetchAuthorNames();

        // map ModSearchInfo's to Maps and save them.
        try (FileWriter writer = new FileWriter("uploads/modsearchdatabase.yaml")) {
            new Yaml().dump(modSearchInfo.stream().map(ModSearchInfo::toMap).collect(Collectors.toList()), writer);
        }
    }

    /**
     * Fetches all author names based on their IDs, and replaces them in the mod search database.
     * (Most mods use credits instead, but WIPs don't, so this is for them.)
     *
     * @throws IOException If something went wrong when querying GameBanana for the author list.
     */
    private void fetchAuthorNames() throws IOException {
        // turn the Set into a List to be able to access it by index.
        List<Integer> authorsAsList = new ArrayList<>(authorIds);

        List<List<Object>> authorNames;
        {
            // build the URL that is going to get all author names at once.
            // that is one big URL, but since only WIPs are affected here, this should be fine.
            StringBuilder urlUserInfo = new StringBuilder("https://api.gamebanana.com/Core/Item/Data?");
            int index = 0;
            for (Integer author : authorsAsList) {
                urlUserInfo
                        .append("itemtype[").append(index).append("]=Member&itemid[").append(index).append("]=").append(author)
                        .append("&fields[").append(index).append("]=name&");
                index++;
            }
            String url = urlUserInfo.append("format=yaml").toString();

            // run the request, parse the result, and add this result to the list.
            authorNames = DatabaseUpdater.runWithRetry(() -> {
                try (InputStream is = DatabaseUpdater.openStreamWithTimeout(new URL(url))) {
                    return new Yaml().load(is);
                }
            });
        }

        // go through the results.
        int authorIndex = 0;
        for (List<Object> authorInfo : authorNames) {
            int authorId = authorsAsList.get(authorIndex);
            String authorName = authorInfo.get(0).toString();

            // apply the author name to every mod that has it as an author ID.
            for (ModSearchInfo mod : modSearchInfo) {
                if (mod.authorId == authorId) {
                    mod.authors = Collections.singletonList(authorName);
                }
            }

            authorIndex++;
        }
    }
}
