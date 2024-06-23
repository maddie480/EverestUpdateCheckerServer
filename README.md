# Everest Update Checker Server

This server periodically goes through the mods posted in [the Celeste category on GameBanana](https://gamebanana.com/games/6460), and saves a few files that may be used by various services that need to know more information about mods.

I'm currently running this server, some of its features are made available through APIs and others are used internally.

## Mod update database

### What it does

When running, the bot generates a file named `uploads/everestupdate.yaml` containing information on all Celeste mods published on GameBanana including an `everest.yaml`. For example:
```yaml
SuperHotMod:
  URL: https://gamebanana.com/mmdl/430983
  GameBananaType: Mod
  Version: 2.0.0
  LastUpdate: 1567660611
  Size: 26223
  GameBananaId: 53639
  GameBananaFileId: 430983
  xxHash: [ab8e6117a0ef3cab]
  MirrorURL: https://celestemodupdater.0x0a.de/banana-mirror/430983.zip
```

### Where it is used

- [Everest](https://github.com/EverestAPI/Everest) uses this file for its "Check for Mod Updates" feature. For all mods someone has installed as a zip with an everest.yaml, Everest can check if the hash matches what is present in this file, to know if the mod is up-to-date (regardless of version number, since some modders don't update this one).
- The [Banana Mirror Browser](https://maddie480.ovh/celeste/banana-mirror-browser) uses it to get the list of files present in the mirror.

### Access

This file is publicly accessible at https://maddie480.ovh/celeste/everest_update.yaml.

## Mod search database

### What it does

A file is generated at `uploads/modsearchdatabase.yaml` with extensive info about all mods. It can be used as a database of mod names, authors and descriptions on GameBanana. A mod in this file looks like this:
```yaml
- PageURL: https://gamebanana.com/mods/150453
  GameBananaType: Mod
  GameBananaId: 150453
  Name: Glyph
  Author: marshall h
  Description: Maddie goes on a journey through the mind & memories of one that are
    not her own.
  Likes: 94
  Views: 202540
  Downloads: 5860
  Text: 'Hi thanks for playing this map! this was created by marshall h (marshall#9752
    on discord)<br><br>I do this for fun and personal development, but I aspire to
    do game development / music for a living someday, and I''d definitely appreciate
    support. You can purchase the soundtrack for this Mod on <a href="https://mwhatt.bandcamp.com/releases"
    target="_blank">Bandcamp</a>. [...]'
  CreatedDate: 1588357721
  ModifiedDate: 1641636742
  UpdatedDate: 1638481949
  Screenshots: ['https://images.gamebanana.com/img/ss/mods/60e5d6e408360.jpg']
  MirroredScreenshots: ['https://celestemodupdater.0x0a.de/banana-mirror-images/img_ss_mods_60e5d6e408360.png']
  Files:
    - {Description: v2.3.2, HasEverestYaml: true, Size: 29179347, CreatedDate: 1638481866,
       Downloads: 1471, URL: 'https://gamebanana.com/dl/703925', Name: glyph_d2f43.zip}
    - {Description: v2.3.1, HasEverestYaml: true, Size: 23273323, CreatedDate: 1629786878,
       Downloads: 466, URL: 'https://gamebanana.com/dl/643617', Name: glyph_d8867.zip}
    - {Description: v2.3.0, HasEverestYaml: true, Size: 23270154, CreatedDate: 1626888153,
       Downloads: 214, URL: 'https://gamebanana.com/dl/619609', Name: glyph_b4c4f.zip}
    - {Description: v2.2.0, HasEverestYaml: true, Size: 18559501, CreatedDate: 1610265120,
       Downloads: 2328, URL: 'https://gamebanana.com/dl/506897', Name: glyph_6ec44.zip}
  CategoryId: 6800
  CategoryName: Maps
  Featured: {Category: alltime, Position: 1}
```

### Where it is used

- [The GameBanana search API](https://github.com/maddie480/RandomStuffWebsite#the-gamebanana-search-api) uses it to find mods.
- [The GameBanana sorted list API (deprecated)](https://github.com/maddie480/RandomStuffWebsite#gamebanana-sorted-list-api-deprecated) uses it to be able to give a list of mod IDs sorted by likes, views, or downloads.
- The [Banana Mirror Browser](https://maddie480.ovh/celeste/banana-mirror-browser) takes all mod information it displays (name, description, stats, etc) from it.
- The [Custom Entity Catalog](https://maddie480.ovh/celeste/custom-entity-catalog) uses it to get category names for each listed mod.

### Access

This file is publicly accessible at https://maddie480.ovh/celeste/mod_search_database.yaml.

## Mod files database

### What it does

The mod files database is a folder contaning information about mods in the form of yaml files:
- `modfilesdatabase/list.yaml` is a list of all detected mods
- `modfilesdatabase/file_ids.yaml` is a list of all Celeste file IDs that exist on GameBanana. The ID is the number at the end of GameBanana file downloads: `https://gamebanana.com/dl/xxxxx`
- `modfilesdatabase/[itemtype]/[itemid]/info.yaml` is a file containing the name of the mod and its file list:
```yaml
Files: ['523435', '513691']
Name: Extended Variant Mode
```
- `modfilesdatabase/[itemtype]/[itemid]/[fileid].yaml` is a file listing all the zip contents. It will contain an empty list for anything that is not a zip.
- `modfilesdatabase/[itemtype]/[itemid]/ahorn_[fileid].yaml` is a file listing all Ahorn entity, trigger and effect IDs defined in `[fileid]`'s Ahorn plugins:
```yaml
Triggers: [MaxHelpingHand/AllBlackholesStrengthTrigger, MaxHelpingHand/AmbienceVolumeTrigger,
  MaxHelpingHand/CameraCatchupSpeedTrigger, MaxHelpingHand/ColorGradeFadeTrigger,
  ...]
Effects: [MaxHelpingHand/BlackholeCustomColors, MaxHelpingHand/CustomPlanets, MaxHelpingHand/CustomStars,
  MaxHelpingHand/HeatWaveNoColorGrade, MaxHelpingHand/NorthernLightsCustomColors,
  MaxHelpingHand/SnowCustomColors]
Entities: [MaxHelpingHand/CoreModeSpikesUp, MaxHelpingHand/CoreModeSpikesDown, MaxHelpingHand/CoreModeSpikesLeft,
  MaxHelpingHand/CoreModeSpikesRight, MaxHelpingHand/CustomizableCrumblePlatform,
  ...]
```
- `modfilesdatabase/[itemtype]/[itemid]/loenn_[fileid].yaml` is a file listing all Lönn entity, trigger and effect IDs defined in `[fileid]`'s Lönn plugins, in the same format as Ahorn plugins above.
This file only exists for zips that include a `Loenn/lang/en_gb.lang` file, since the detection is based on this file.
- `modfilesdatabase/ahorn_vanilla.yaml` is a list of all vanilla and Everest entities, triggers and effects defined in [Maple](https://github.com/CelestialCartographers/Maple), in the same format as above.
- `modfilesdatabase/loenn_vanilla.yaml` is a list of all vanilla and Everest entities, triggers and effects defined in [Lönn](https://github.com/CelestialCartographers/Loenn), in the same format as above.

### Where it is used

- The [Custom Entity Catalog](https://maddie480.ovh/celeste/custom-entity-catalog) uses it to get the file names of Ahorn plugins, and be able to list out what each mod contains.
- The [Mod Structure Verifier Discord bot](https://github.com/maddie480/RandomBackendStuff/tree/main/src/main/java/com/maddie480/randomstuff/backend/discord/modstructureverifier) uses it to know which assets each mod used as a dependency contains, and which entities helpers contain.

### Access

You can download the `modfilesdatabase` directory as a zip at https://maddie480.ovh/celeste/mod_files_database.zip.

## Banana Mirror

### What it does

The update checker uploads copies of the **latest versions** of all mods with an everest.yaml from GameBanana to a SFTP server, in order to have a backup in case of GameBanana issues or slowness.

It will do the same with the **2 first screenshots** of each Celeste mod on GameBanana, after converting them to PNG and shrinking them to 220x220. Combine those mirrored files with the [mod search database](#mod-search-database) to get enough info to make a backup website in case GameBanana goes down.

### Access

Files and images are uploaded to [0x0ade's server](https://celestemodupdater.0x0a.de/). You can download all files directly from there directly, but since files are named after their GameBanana file IDs, you can use this website to navigate in it with mod names, descriptions and 1-click install buttons: https://maddie480.ovh/celeste/banana-mirror-browser

[Everest](https://github.com/EverestAPI/Everest) and [Olympus](https://github.com/EverestAPI/Olympus) will also automatically use it as a substitute for GameBanana if it is down and you try to download or update a mod. Olympus uses the mirrored images instead of ones from GameBanana when they are in the `webp` format, since the mirror has them all converted to PNG.

## Mod dependency graph

### What it does

This file is generated at `uploads/moddependencygraph.yaml` and lists what each mod in `everestupdate.yaml` depends on:
```yaml
WitheredPassage:
  OptionalDependencies: {}
  Dependencies:
    FrostHelper: 1.17.8
    MoreDasheline: 1.6.0
    Everest: 1.1884.0
    MaxHelpingHand: 1.5.0
    ContortHelper: 1.0.0
    DJMapHelper: 1.8.13
  URL: https://gamebanana.com/mmdl/484636
```

### Where it is used

The [Custom Entity Catalog](https://maddie480.ovh/celeste/custom-entity-catalog) uses it to show how many mods depend on each helper.

This can also be used whenever it is needed to know which mods depend on a particular one, for example to evaluate impacts after a helper is deleted from GameBanana, or to check which mods have dependencies on big maps.

It is possible to download a mod and all dependencies, including transitive ones, by building a graph from this file.

### Access

This file is publicly accessible at [https://maddie480.ovh/celeste/mod_dependency_graph.yaml](https://maddie480.ovh/celeste/mod_dependency_graph.yaml), in an `everest.yaml`-like format:

```yaml
WitheredPassage:
  OptionalDependencies: []
  Dependencies:
  - Name: FrostHelper
    Version: 1.17.8
  - Name: MoreDasheline
    Version: 1.6.0
  - Name: Everest
    Version: 1.1884.0
  - Name: MaxHelpingHand
    Version: 1.5.0
  - Name: ContortHelper
    Version: 1.0.0
  - Name: DJMapHelper
    Version: 1.8.13
  URL: https://gamebanana.com/mmdl/484636
```

## Developing and running your own copy

You shouldn't need this unless Maddie vanishes from the Celeste community, but here it is anyway. 😅

### Getting the project

You can get the update checker server by checking [the Releases tab](https://github.com/maddie480/EverestUpdateCheckerServer/releases).

If you download this to take over Everest's update server, preferably ask Maddie for the latest yaml files to spare you some massive downloading from GameBanana.

### Building the project

Get Maven, then run the following command at the project root:

```
mvn clean package
```

This will build the project to `target/update-checker-0.7.2.jar`.

### Running the project

First, follow these steps to set it up:
* Get the JAR that was produced by Maven, or download it in [the Releases tab](https://github.com/maddie480/EverestUpdateCheckerServer/releases).
* Fill in the `update_checker_config.yaml` file present in this repository with Banana Mirror info, and drop it in the same directory as the JAR.

Then, to run the project, browse to where the JAR is in a terminal / command prompt, then run

```
java -jar update-checker-0.7.2.jar [minutes]
```

[minutes] is the wait delay in minutes between two GameBanana checks (defaults to 30). Be aware that the program makes ~13 API calls per check, and that the GameBanana API has a cap at 250 requests/hour.

### Handling special cases

Some mods may need editing the database manually: that is, all cases where a mod offers multiple downloads. These cases need manual editing of the database.

#### Multiple downloads with the same ID

Those mods be defined with two hashes in `everestupdate.yaml`, so that the updater can tell if the version the user has is _one of_ the up-to-date ones.

```yaml
DJMapHelper:
  Version: 1.6.4
  LastUpdate: 1552491224
  xxHash: [a7334bfe1b464ee6, e04c6c7d01fdfbe3]
  URL: https://gamebanana.com/mmdl/413533
```

_(Please note DJ Map Helper no longer has two separate downloads, and no mod currently needs this anymore, this is just an example if this happens again.)_

#### Mods with multiple entries in their everest.yaml

Mods that have multiple entries in their everest.yaml (for example, `Monika's D-Sides` and `Ruby'sEntities`) will be registered multiple times in the database.

To prevent that from happening, you can add the ID of the mod you don't want in `everestupdateexcluded.yaml`:
```yaml
Ruby'sEntities: Part of D-sides
```

### Libraries used

* [SnakeYAML](https://bitbucket.org/asomov/snakeyaml/src/default/), licensed under the [Apache License 2.0](https://bitbucket.org/asomov/snakeyaml/src/default/LICENSE.txt)
* [Apache Commons IO](http://commons.apache.org/proper/commons-io/)
* [Apache Commons Lang](https://commons.apache.org/proper/commons-lang/)
* [Logback](http://logback.qos.ch/), licensed under [GNU LGPL version 2.1](http://logback.qos.ch/license.html)
* [LZ4 Java](https://github.com/lz4/lz4-java), licensed under [Apache License 2.0](https://github.com/lz4/lz4-java/blob/master/LICENSE.txt), used for xxHash hash calculation
* [JSch](http://www.jcraft.com/jsch/), licensed under [a BSD-style license](http://www.jcraft.com/jsch/LICENSE.txt)
* [JSON in Java](https://github.com/stleary/JSON-java) ([license](https://github.com/stleary/JSON-java/blob/master/LICENSE))
* [Thumbnailator](https://github.com/coobird/thumbnailator), licensed under [the MIT license](https://github.com/coobird/thumbnailator/blob/master/LICENSE)
* [webp-imageio](https://github.com/sejda-pdf/webp-imageio), licensed under [the Apache License 2.0](https://github.com/sejda-pdf/webp-imageio/blob/master/LICENSE)
