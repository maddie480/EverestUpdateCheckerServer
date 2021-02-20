package com.max480.everest.updatechecker;

import org.json.simple.JSONArray;
import org.json.simple.parser.ContainerFactory;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
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
        private final int likes;
        private final int views;
        private final int downloads;

        public ModSearchInfo(String gameBananaType, int gameBananaId, String name,
                             List<String> authors, String description, String text,
                             int likes, int views, int downloads) {

            this.gameBananaType = gameBananaType;
            this.gameBananaId = gameBananaId;
            this.name = name;
            this.authorId = -1;
            this.authors = authors;
            this.description = description;
            this.text = Jsoup.parseBodyFragment(text).text(); // strip HTML
            this.likes = likes;
            this.views = views;
            this.downloads = downloads;
        }

        public ModSearchInfo(String gameBananaType, int gameBananaId, String name,
                             int authorId, String description, String text,
                             int likes, int views, int downloads) {

            this.gameBananaType = gameBananaType;
            this.gameBananaId = gameBananaId;
            this.name = name;
            this.authorId = authorId;
            this.description = description;
            this.text = Jsoup.parseBodyFragment(text).text(); // strip HTML
            this.likes = likes;
            this.views = views;
            this.downloads = downloads;
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
            map.put("Likes", likes);
            map.put("Views", views);
            map.put("Downloads", downloads);
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
     * @throws IOException In case an error occurs while parsing the mod authors field
     */
    public void addMod(String itemtype, int itemid, List<Object> mod) throws IOException {
        try {
            ModSearchInfo newModSearchInfo = new ModSearchInfo(itemtype, itemid, mod.get(0).toString(),
                    Integer.parseInt(mod.get(2).toString()), mod.get(3).toString(), mod.get(4).toString(),
                    (int) mod.get(5), (int) mod.get(6), (int) mod.get(7));

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
        Map<String, Object> authorCategories = parseJSONObjectKeepingOrder(authors);
        for (Map.Entry<String, Object> category : authorCategories.entrySet()) {
            JSONArray authorsListForCategory = (JSONArray) category.getValue();

            // then each author is a JSON array [name, userid, role, ???]
            // and we want to keep the name.
            for (Object author : authorsListForCategory) {
                JSONArray authorArray = (JSONArray) author;
                authorsParsed.add(authorArray.get(0).toString());
            }
        }

        modSearchInfo.add(new ModSearchInfo(itemtype, itemid, mod.get(0).toString(), authorsParsed,
                mod.get(3).toString(), mod.get(4).toString(),
                (int) mod.get(5), (int) mod.get(6), (int) mod.get(7)));
    }

    /**
     * Parses the given JSON as a LinkedHashMap <b>thus keeping the order</b>.
     * Order is not supposed to matter in JSON, but it does matter in the GameBanana author list.
     *
     * @param json The JSON to parse
     * @return The JSON parsed as a LinkedHashMap
     * @throws IOException In case a parse error occurs
     */
    public Map<String, Object> parseJSONObjectKeepingOrder(String json) throws IOException {
        JSONParser parser = new JSONParser();
        ContainerFactory containerFactory = new ContainerFactory() {
            @Override
            public Map createObjectContainer() {
                return new LinkedHashMap();
            }

            @Override
            public List creatArrayContainer() {
                return null;
            }
        };

        try {
            return (Map<String, Object>) parser.parse(json, containerFactory);
        } catch (ParseException e) {
            throw new IOException("Failed parsing JSON for authors list", e);
        }
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
                    return Optional.ofNullable(new Yaml().<List<List<Object>>>load(is))
                            .orElseThrow(() -> new IOException("Ended up with a null value when loading a mod page"));
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
