package ovh.maddie480.everest.updatechecker;

import org.apache.commons.lang3.StringEscapeUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

public class ModSearchDatabaseBuilder {
    private static final Logger log = LoggerFactory.getLogger(ModSearchDatabaseBuilder.class);

    /**
     * This object holds the name, author, description and text of a GameBanana mod.
     */
    private static class ModSearchInfo {
        private final String url;
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
        private Integer subcategoryId;
        private String subcategoryName;
        private final long createdDate;
        private final long modifiedDate;
        private final long updatedDate;
        private final List<String> screenshots;
        private final List<Map<String, Object>> files;
        private Map<String, Object> featured;

        public ModSearchInfo(String url, String gameBananaType, int gameBananaId, String name,
                             String authorName, String description, String text,
                             int likes, int views, int downloads, int categoryId,
                             long createdDate, long modifiedDate, long updatedDate, List<String> screenshots,
                             List<Map<String, Object>> files) {

            this.url = url;
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
            this.modifiedDate = modifiedDate;
            this.updatedDate = updatedDate;
            this.screenshots = screenshots;
            this.files = files;
            this.featured = null;
        }

        public void setFeatured(String category, int position) {
            log.trace("Setting {} {} as featured for category {}, at position {}", gameBananaType, gameBananaId, category, position);
            featured = new HashMap<>();
            featured.put("Category", category);
            featured.put("Position", position);
        }

        /**
         * Gets this object as a map, which can be written to YAML more easily.
         *
         * @return This mod as a HashMap
         */
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("PageURL", url);
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
            map.put("ModifiedDate", modifiedDate);
            map.put("UpdatedDate", updatedDate);
            map.put("Screenshots", screenshots);
            List<String> mirroredScreenshots = new ArrayList<>();
            for (int i = 0; i < 2 && i < screenshots.size(); i++) {
                String screenshotUrl = screenshots.get(i);
                mirroredScreenshots.add("https://celestemodupdater.0x0a.de/banana-mirror-images/" +
                        screenshotUrl.substring("https://images.gamebanana.com/".length(), screenshotUrl.lastIndexOf(".")).replace("/", "_") + ".png");
            }
            map.put("MirroredScreenshots", mirroredScreenshots);
            map.put("Files", files);
            map.put("CategoryId", categoryId);
            map.put("CategoryName", categoryName);
            if (featured != null) {
                map.put("Featured", featured);
            }
            if (subcategoryId != null) {
                map.put("SubcategoryId", subcategoryId);
                map.put("SubcategoryName", subcategoryName);
            }
            return map;
        }
    }

    private final List<ModSearchInfo> modSearchInfo = new LinkedList<>();
    private final Set<String> nsfwMods = new HashSet<>();

    public Set<String> getNsfwMods() {
        return nsfwMods;
    }

    /**
     * Adds this mod to the mod search info database.
     *
     * @param itemtype The GameBanana type
     * @param itemid   The GameBanana id
     * @param mod      The mod name
     */
    void addMod(String itemtype, int itemid, JSONObject mod) throws IOException {
        String contentWarningPrefix = "";
        boolean redactScreenshots = false;

        if (mod.getBoolean("_bIsNsfw")) {
            // mod has content warnings! we need to check which ones.
            try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://gamebanana.com/apiv11/" + itemtype + "/" + itemid + "/ProfilePage")) {
                JSONObject o = new JSONObject(new JSONTokener(is));
                redactScreenshots = !"show".equals(o.getString("_sInitialVisibility"));

                List<String> contentWarnings = new ArrayList<>();
                for (String key : o.getJSONObject("_aContentRatings").keySet()) {
                    contentWarnings.add(o.getJSONObject("_aContentRatings").getString(key));
                }

                contentWarningPrefix = "<b>Content Warning" + (contentWarnings.size() == 1 ? "" : "s") + ": "
                        + StringEscapeUtils.escapeHtml4(String.join(", ", contentWarnings)) + "</b><br><br>";
            }
        }

        // parse screenshots and determine their URLs.
        List<String> screenshots;

        if (redactScreenshots) {
            screenshots = Collections.singletonList("https://images.gamebanana.com/static/img/DefaultEmbeddables/nsfw.jpg");
            nsfwMods.add(itemtype + "/" + itemid);
        } else {
            screenshots = new ArrayList<>();
            JSONArray screenshotsJson = mod.getJSONObject("_aPreviewMedia").getJSONArray("_aImages");
            for (int i = 0; i < screenshotsJson.length(); i++) {
                JSONObject screenshotJson = screenshotsJson.getJSONObject(i);
                screenshots.add(screenshotJson.getString("_sBaseUrl") + "/" + screenshotJson.getString("_sFile"));
            }
        }

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
                        map.put("CreatedDate", file.getInt("_tsDateAdded"));
                        map.put("Downloads", file.getInt("_nDownloadCount"));
                        map.put("Description", file.has("_sDescription") ? file.getString("_sDescription") : "");

                        boolean hasYaml = false;
                        File modFilesDatabase = new File("modfilesdatabase_temp/" + itemtype + "/" + itemid + "/" + file.getInt("_idRow") + ".yaml");
                        if (modFilesDatabase.exists()) {
                            try (FileInputStream is = new FileInputStream(modFilesDatabase)) {
                                List<String> files = YamlUtil.load(is);
                                hasYaml = files.contains("everest.yml") || files.contains("everest.yaml");
                            } catch (IOException e) {
                                log.error("Could not read files database at " + modFilesDatabase.getPath() + "!", e);
                            }
                        }
                        map.put("HasEverestYaml", hasYaml);
                        return map;
                    }).collect(Collectors.toList());
        }

        ModSearchInfo newModSearchInfo = new ModSearchInfo(mod.getString("_sProfileUrl"), itemtype, itemid, mod.getString("_sName"),
                mod.getJSONObject("_aSubmitter").getString("_sName"), mod.getString("_sDescription"), contentWarningPrefix + mod.getString("_sText"),
                mod.getInt("_nLikeCount"), mod.getInt("_nViewCount"), mod.getInt("_nDownloadCount"),
                mod.getJSONObject("_aCategory").getInt("_idRow"), mod.getLong("_tsDateAdded"), mod.getLong("_tsDateModified"),
                mod.getLong("_tsDateUpdated"), screenshots, filesInMod);

        modSearchInfo.add(newModSearchInfo);
    }

    /**
     * Saves the mod search database to uploads/modsearchdatabase.yaml.
     *
     * @throws IOException If the file couldn't be written, or something went wrong with getting author/mod category names.
     */
    void saveSearchDatabase(boolean full) throws IOException {
        // assign category names to mods from all itemtypes...
        for (String itemtype : modSearchInfo.stream().map(m -> m.gameBananaType).collect(Collectors.toSet())) {
            assignCategoryNamesToMods(itemtype);
        }

        // ... then check that we did not miss any. If we did, just fill in the category name with "Unknown"
        // (this means the mod is part of an unapproved/unlisted category).
        for (ModSearchInfo mod : modSearchInfo) {
            if (mod.categoryName == null) {
                log.warn("No category found for {} {}", mod.gameBananaType, mod.gameBananaId);
                mod.categoryName = "Unknown";
            }
        }

        // get featured mods and fill in the info for mods accordingly.
        log.debug("Getting list of featured mods...");
        JSONObject featured = ConnectionUtils.runWithRetry(() -> {
            try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://gamebanana.com/apiv8/Game/6460/TopSubs")) {
                return new JSONObject(new JSONTokener(is));
            } catch (JSONException e) {
                // turn JSON parse errors into IOExceptions to trigger a retry.
                throw new IOException(e);
            }
        });
        for (String category : featured.keySet()) {
            int position = 0;
            for (Object mod : featured.getJSONArray(category)) {
                JSONObject modObject = (JSONObject) mod;
                String itemtype = modObject.getString("_sModelName");
                int itemid = modObject.getInt("_idRow");

                int thisPosition = position;
                modSearchInfo.stream()
                        .filter(i -> i.gameBananaType.equals(itemtype) && i.gameBananaId == itemid)
                        .forEach(i -> i.setFeatured(category, thisPosition));
                position++;
            }
        }

        List<Map<String, Object>> modSearchDatabase = modSearchInfo.stream().map(ModSearchInfo::toMap).collect(Collectors.toList());

        if (!full) {
            fillInGapsForIncrementalUpdate(modSearchDatabase);
        }

        log.debug("Saving mod search database");

        // map ModSearchInfo's to Maps and save them.
        try (OutputStream os = new FileOutputStream("uploads/modsearchdatabase.yaml")) {
            YamlUtil.dump(modSearchDatabase, os);
        }

        // save the NSFW mod list, because we will need it for incremental updates
        try (OutputStream os = new FileOutputStream("uploads/nsfw_mods.yaml")) {
            YamlUtil.dump(new ArrayList<>(nsfwMods), os);
        }

        // we don't need this list anymore, free up its memory.
        modSearchInfo.clear();
    }

    private void assignCategoryNamesToMods(String itemtype) throws IOException {
        // get the list of categories from GameBanana
        log.debug("Getting {} category names...", itemtype);
        JSONArray listOfCategories = ConnectionUtils.runWithRetry(() -> {
            try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://gamebanana.com/apiv8/" + itemtype + "Category/ByGame?_aGameRowIds[]=6460&" +
                    "_csvProperties=_idRow,_idParentCategoryRow,_sName&_sOrderBy=_idRow,ASC&_nPage=1&_nPerpage=50")) {

                return new JSONArray(new JSONTokener(is));
            } catch (JSONException e) {
                // turn JSON parse errors into IOExceptions to trigger a retry.
                throw new IOException(e);
            }
        });

        // parse it in a convenient way
        Map<Integer, String> categoryNames = new HashMap<>();
        Map<Integer, Integer> categoryToParent = new HashMap<>();

        for (Object categoryRaw : listOfCategories) {
            JSONObject category = (JSONObject) categoryRaw;
            categoryNames.put(category.getInt("_idRow"), category.getString("_sName"));

            if (category.getInt("_idParentCategoryRow") == 0) {
                // this is a root category!
                log.trace("{} ({}) is a root category", category.getInt("_idRow"), category.getString("_sName"));
            } else {
                // this is a subcategory.
                log.trace("{} ({}) is the child of category {}", category.getInt("_idRow"), category.getString("_sName"), category.getInt("_idParentCategoryRow"));
                categoryToParent.put(category.getInt("_idRow"), category.getInt("_idParentCategoryRow"));
            }
        }

        // associate each mod to its root category.
        for (ModSearchInfo info : modSearchInfo) {
            if (info.gameBananaType.equals(itemtype)) {
                int category = info.categoryId;

                // go up to the root category!
                while (categoryToParent.containsKey(category)) {
                    category = categoryToParent.get(category);
                }

                // assign it.
                if (info.categoryId != category) {
                    log.trace("Assigning subcategory of {} {} to {} ({})", info.gameBananaType, info.gameBananaId, info.categoryId, categoryNames.get(info.categoryId));
                    info.subcategoryId = info.categoryId;
                    info.subcategoryName = categoryNames.get(info.categoryId);
                }

                log.trace("Reassigning category {} of {} {} to {} ({})", info.categoryId, info.gameBananaType, info.gameBananaId, category, categoryNames.get(category));
                info.categoryId = category;
                info.categoryName = categoryNames.get(category);
            }
        }
    }

    private void fillInGapsForIncrementalUpdate(List<Map<String, Object>> database) throws IOException {
        log.debug("Loading old mod search database...");
        List<Map<String, Object>> previousModInfo;
        try (InputStream is = Files.newInputStream(Paths.get("uploads/modsearchdatabase.yaml"))) {
            previousModInfo = YamlUtil.load(is);
        }

        for (Map<String, Object> oldMod : previousModInfo) {
            if (database.stream().noneMatch(newMod ->
                    newMod.get("GameBananaType").equals(oldMod.get("GameBananaType"))
                            && newMod.get("GameBananaId").equals(oldMod.get("GameBananaId")))) {

                // mod is not in new database => carry it over from old database
                log.trace("Carrying over {} {} from old database", oldMod.get("GameBananaType"), oldMod.get("GameBananaId"));
                database.add(oldMod);
            }
        }

        // retrieve the list of mods that were previously tagged as NSFW (which we might not have retrieved this time)
        try (InputStream is = Files.newInputStream(Paths.get("uploads/nsfw_mods.yaml"))) {
            nsfwMods.addAll(YamlUtil.<List<String>>load(is));
        }
    }
}
