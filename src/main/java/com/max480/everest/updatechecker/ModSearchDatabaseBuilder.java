package com.max480.everest.updatechecker;

import org.apache.commons.io.IOUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.net.URL;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static java.nio.charset.StandardCharsets.UTF_8;

public class ModSearchDatabaseBuilder {
    private static final Logger log = LoggerFactory.getLogger(ModSearchDatabaseBuilder.class);

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
        private int categoryId;
        private String categoryName;
        private final long createdDate;
        private final List<String> screenshots;
        private final List<Map<String, Object>> files;

        public ModSearchInfo(String gameBananaType, int gameBananaId, String name,
                             String authorName, String description, String text,
                             int likes, int views, int downloads, int categoryId,
                             long createdDate, List<String> screenshots,
                             List<Map<String, Object>> files) {

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
            this.files = files;
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
            map.put("Files", files);
            if (categoryName != null) {
                map.put("CategoryId", categoryId);
                map.put("CategoryName", categoryName);
            }
            return map;
        }
    }

    private final List<ModSearchInfo> modSearchInfo = new LinkedList<>();

    /**
     * Adds this mod to the mod search info database.
     *
     * @param itemtype The GameBanana type
     * @param itemid   The GameBanana id
     * @param mod      The mod name
     */
    public void addMod(String itemtype, int itemid, JSONObject mod) {
        // parse screenshots and determine their URLs.
        List<String> screenshots = new ArrayList<>();
        JSONArray screenshotsJson = mod.getJSONArray("_aPreviewMedia");
        for (int i = 0; i < screenshotsJson.length(); i++) {
            JSONObject screenshotJson = screenshotsJson.getJSONObject(i);
            screenshots.add(screenshotJson.getString("_sBaseUrl") + "/" + screenshotJson.getString("_sFile"));
        }

        long modCreatedDate = mod.getLong("_tsDateAdded");

        List<Map<String, Object>> filesInMod = new ArrayList<>();
        if (!mod.isNull("_aFiles")) {
            filesInMod = StreamSupport.stream(mod.getJSONArray("_aFiles").spliterator(), false)
                    .map(item -> {
                        // map a file into a hash map.
                        JSONObject file = (JSONObject) item;
                        Map<String, Object> map = new HashMap<>();
                        map.put("URL", file.getString("_sDownloadUrl"));
                        map.put("Name", file.getString("_sFile"));
                        map.put("Size", file.getInt("_nFilesize"));
                        map.put("Downloads", file.getInt("_nDownloadCount"));

                        boolean hasYaml = false;
                        File modFilesDatabase = new File("modfilesdatabase/" + itemtype + "/" + itemid + "/" + file.getInt("_idRow") + ".yaml");
                        if (modFilesDatabase.exists()) {
                            try (FileInputStream is = new FileInputStream(modFilesDatabase)) {
                                List<String> files = new Yaml().load(is);
                                hasYaml = files.contains("multimetadata.yml") || files.contains("everest.yml") || files.contains("everest.yaml");
                            } catch (IOException e) {
                                log.error("Could not read files database at " + modFilesDatabase.getPath() + "!", e);
                            }
                        }
                        map.put("HasEverestYaml", hasYaml);
                        return map;
                    }).collect(Collectors.toList());
        }

        ModSearchInfo newModSearchInfo = new ModSearchInfo(itemtype, itemid, mod.getString("_sName"),
                mod.getJSONObject("_aSubmitter").getString("_sName"), mod.getString("_sDescription"), mod.getString("_sText"),
                mod.getInt("_nLikeCount"), mod.getInt("_nViewCount"), mod.getInt("_nDownloadCount"),
                mod.getJSONObject("_aCategory").getInt("_idRow"), modCreatedDate, screenshots, filesInMod);

        modSearchInfo.add(newModSearchInfo);
    }

    /**
     * Saves the mod search database to uploads/modsearchdatabase.yaml.
     *
     * @throws IOException If the file couldn't be written, or something went wrong with getting author/mod category names.
     */
    public void saveSearchDatabase() throws IOException {
        // get the list of categories from GameBanana
        JSONArray listOfCategories = DatabaseUpdater.runWithRetry(() -> {
            try (InputStream is = new URL("https://gamebanana.com/apiv6/ModCategory/ByGame?_aGameRowIds[]=6460&" +
                    "_csvProperties=_idRow,_idParentCategoryRow,_sName&_sOrderBy=_idRow,ASC&_nPage=1&_nPerpage=50").openStream()) {

                return new JSONArray(IOUtils.toString(is, UTF_8));
            }
        });

        // parse it in a convenient way
        Map<Integer, String> categoryNames = new HashMap<>();
        Map<Integer, Integer> categoryToParent = new HashMap<>();

        for (Object categoryRaw : listOfCategories) {
            JSONObject category = (JSONObject) categoryRaw;

            if (category.getInt("_idParentCategoryRow") == 0) {
                // this is a root category!
                categoryNames.put(category.getInt("_idRow"), category.getString("_sName"));
            } else {
                // this is a subcategory.
                categoryToParent.put(category.getInt("_idRow"), category.getInt("_idParentCategoryRow"));
            }
        }

        // associate each mod to its root category.
        for (ModSearchInfo info : modSearchInfo) {
            if (info.gameBananaType.equals("Mod")) {
                int category = info.categoryId;

                // go up to the root category!
                while (categoryToParent.containsKey(category)) {
                    category = categoryToParent.get(category);
                }

                // assign it.
                info.categoryId = category;
                info.categoryName = categoryNames.get(category);
            }
        }

        // map ModSearchInfo's to Maps and save them.
        try (FileWriter writer = new FileWriter("uploads/modsearchdatabase.yaml")) {
            new Yaml().dump(modSearchInfo.stream().map(ModSearchInfo::toMap).collect(Collectors.toList()), writer);
        }
    }
}
