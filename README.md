# Everest Update Checker Server

This server periodically goes through the mods posted in [the Celeste category on GameBanana](https://gamebanana.com/games/6460), and saves a few files that may be used by various services that need to know more information about mods.

I'm currently running this server, some of its features are made available through APIs and others are used internally.

## Mod update database

### What it does

When running, the bot generates a file named `uploads/everestupdate.yaml` containing information on all Celeste mods published on GameBanana including an `everest.yaml`. For example:
```yaml
SuperHotMod:
  GameBananaType: Gamefile
  Version: 2.0.0
  LastUpdate: 1567660611
  GameBananaId: 7936
  xxHash: [ab8e6117a0ef3cab]
  URL: https://gamebanana.com/mmdl/430983
```

### Where it is used

- [Everest](https://github.com/EverestAPI/Everest) uses this file for its "Check for Mod Updates" feature. For all mods someone has installed as a zip with an everest.yaml, Everest can check if the hash matches what is present in this file, to know if the mod is up-to-date (regardless of version number, since some modders don't update this one).
- The [Banana Mirror Browser](https://max480-random-stuff.herokuapp.com/banana-mirror-browser) uses it to get the list of files present in the mirror.

### Access

This file is publicly accessible at https://max480-random-stuff.appspot.com/celeste/everest_update.yaml.

## Mod search database

### What it does

A file is generated at `uploads/modsearchdatabase.yaml` with extensive info about all mods. It can be used as a database of mod names, authors and descriptions on GameBanana. A mod in this file looks like this:
```yaml
- GameBananaType: Map
  GameBananaId: 213863
  Name: Old Site but... weird
  Authors: [vitellary, Celeste Devs]
  Description: It's been flipped!
  Text: Had this dumb idea a couple months ago, been working on it on and off and
    finally decided to finish it. Dependencies are Communal Helper and Max's Helping
    Hand. Difficulty is a bit harder than the vanilla equivalent of each level (though
    it's a decent bit more puzzly), and only advanced tech required is wallbounces
    for the C-Side.
  Likes: 2
  Views: 911
  Downloads: 44
```

### Where it is used

- [The GameBanana search API](https://github.com/max4805/RandomStuffWebsite#the-gamebanana-search-api) uses it to find mods.
- [The GameBanana sorted list API (deprecated)](https://github.com/max4805/RandomStuffWebsite#gamebanana-sorted-list-api-deprecated) uses it to be able to give a list of mod IDs sorted by likes, views, or downloads.
- The [Banana Mirror Browser](https://max480-random-stuff.herokuapp.com/banana-mirror-browser) uses it to link a mod ID to its name on GameBanana.
- The [Custom Entity Catalog](https://max480-random-stuff.appspot.com/celeste/custom-entity-catalog) uses it to get category names for each listed mod.

### Access

This file is **not** publicly accessible.

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
This file only exists for zips that have a Ahorn folder in them.
- `modfilesdatabase/ahorn_vanilla.yaml` is a list of all vanilla and Everest entities, triggers and effects defined in [Maple](https://github.com/CelestialCartographers/Maple), in the same format as above.

### Where it is used

- The [Custom Entity Catalog](https://max480-random-stuff.appspot.com/celeste/custom-entity-catalog) uses it to get the file names of Ahorn plugins, and be able to list out what each mod contains.
- The [Mod Structure Verifier Discord bot](https://github.com/max4805/RandomDiscordBots) uses it to know which assets each mod used as a dependency contains, and which entities helpers contain.

### Access

The only file made available publicly is the `modfilesdatabase/file_ids.yaml` file, at https://max480-random-stuff.appspot.com/celeste/file_ids.yaml.

## Banana Mirror

### What it does

The update checker uploads copies of the **latest versions** of all mods with an everest.yaml from GameBanana to a SFTP server, in order to have a backup in case of GameBanana issues or slowness.

### Access

Files are uploaded to [0x0ade's server](https://celestemodupdater.0x0a.de/banana-mirror). You can download all files directly from there directly, but since files are named after their GameBanana file IDs, you can use this website to navigate in it with mod names and 1-click install buttons: https://max480-random-stuff.herokuapp.com/banana-mirror-browser

[Everest](https://github.com/EverestAPI/Everest) and [Olympus](https://github.com/EverestAPI/Olympus) will also automatically use it as a substitute for GameBanana if it is down and you try to download or update a mod.

## Developing and running your own copy

You shouldn't need this unless max480 vanishes from the Celeste community, but here it is anyway. 😅

### Getting the project

You can get the update checker server by checking [the Releases tab](https://github.com/max4805/EverestUpdateCheckerServer/releases).

If you download this to take over Everest's update server, preferably ask max480 for the latest yaml files to spare you some massive downloading from GameBanana.

### Building the project

Get Maven, then run the following command at the project root:

```
mvn clean package
```

This will build the project to `target/update-checker-0.0.29.jar`.

### Running the project

First, follow these steps to set it up:
* Get the JAR that was produced by Maven, or download it in [the Releases tab](https://github.com/max4805/EverestUpdateCheckerServer/releases).
* If you want to give control over the database to other people, in order for them to handle the few specific cases where automatic update doesn't work (this is really rare though, see [Handling special cases](#handling-special-cases)), create a `code.txt` file next to the JAR. Put a code in it, then share it with the people you want to be allowed to edit the database.
  * If you don't create a `code.txt` file, the "edit database remotely" feature will be disabled.
* If you want to activate the mirror, fill in the connection details in the `BananaMirror.java` class.

Then, to run the project, browse to where the JAR is in a terminal / command prompt, then run

```
java -jar update-checker-0.0.29.jar [port] [minutes]
```

[port] is the HTTP port for the server. If you don't provide any, there won't be any server hosted (useful if you already have something else hosting the files).

[minutes] is the wait delay in minutes between two GameBanana checks (defaults to 30). Be aware that the program makes ~13 API calls per check, and that the GameBanana API has a cap at 250 requests/hour.

### HTTP server usage

**Note: the max480-random-stuff.appspot.com setup does not use this.**

The server uses [NanoHttpd](https://github.com/NanoHttpd/nanohttpd) to provide the database over HTTP.

It supports two methods:
```
GET /everestupdate.yaml 
```
provides the up-to-date everestupdate.yaml.

```
POST /everestupdate.yaml 
```
allows to overwrite the current database. This aims to give control over the database to people that do not host the server, to handle the special cases.

To be able to use this method, you have to pass the content of the `code.txt` file in the Authorization header.

You can also use these two methods with those two other files:
* `/everestupdateexcluded.yaml`: this file lists all downloads that should be skipped on GameBanana for any reason. Corrupted zips or duplicates (f.e. Gauntlet is an older duplicate of Gauntlet Revamped) are automatically added to this. Those files won't be checked again by the update checker server. _If the blacklist reason contains a link to a GameBanana file, the entry will be deleted automatically if that file is deleted._ This is useful in cases where file A obsoletes file B, but should take over if file B is deleted.
* `/everestupdatenoyaml.yaml`: this file holds the list of all zips that have been downloaded and don't contain any everest.yaml, so that they aren't downloaded again.

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
* [NanoHTTPD](https://github.com/NanoHttpd/nanohttpd), licensed under the [BSD 3-Clause "New" License](https://github.com/NanoHttpd/nanohttpd/blob/master/LICENSE.md)
* [LZ4 Java](https://github.com/lz4/lz4-java), licensed under [Apache License 2.0](https://github.com/lz4/lz4-java/blob/master/LICENSE.txt), used for xxHash hash calculation
* [Jsoup](https://github.com/jhy/jsoup/), licensed under the [MIT License](https://github.com/jhy/jsoup/blob/master/LICENSE)
* [JSch](http://www.jcraft.com/jsch/), licensed under [a BSD-style license](http://www.jcraft.com/jsch/LICENSE.txt)
