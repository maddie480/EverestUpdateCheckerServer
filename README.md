# Everest Update Checker Server

This is the server powering [Everest](https://github.com/EverestAPI/Everest)'s "Check for Mod Updates" feature.

It basically generates a file named `uploads/everestupdate.yaml` containing information on all Celeste mods published on GameBanana including an `everest.yaml`. For example:
```yaml
SuperHotMod:
  GameBananaType: Gamefile
  Version: 2.0.0
  LastUpdate: 1567660611
  GameBananaId: 7936
  xxHash: [ab8e6117a0ef3cab]
  URL: https://gamebanana.com/mmdl/430983
```

Everest can then download this file from the server, and check if an update is available on a mod (by comparing the hash).

The server used by Everest is currently hosted by max480 (max480#4596 on [the "Mt. Celeste Climbing Association" Discord server](https://discord.gg/celeste)).

Additionally, this server generates a `modsearchdatabase.yaml` file that can be used as a database of mod names, authors and descriptions on GameBanana. This is used by [the GameBanana search API](https://github.com/max4805/RandomStuffWebsite#the-gamebanana-search-api) to find mods. A mod in this file looks like this:
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

It also generates a "mod files database" that allows checking the zip contents without downloading them:
- `modfilesdatabase/list.yaml` is a list of all detected mods
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

The update checker also uploads copies of the latest versions of all mods from GameBanana to a SFTP server, in order to have a backup in case of GameBanana issues.
The configuration used by the server is defined in the constants for `BananaMirror.java`. If this configuration is not filled out (which is the case in the released jar), mirror updating will be disabled.

## Getting the project

You can get the update checker server by checking [the Releases tab](https://github.com/max4805/EverestUpdateCheckerServer/releases).

If you download this to take over Everest's update server, preferably ask max480 for the latest yaml files to spare you some massive downloading from GameBanana.

## Building the project

Get Maven, then run the following command at the project root:

```
mvn clean package
```

This will build the project to `target/update-checker-0.0.26.jar`.

## Running the project

First, follow these steps to set it up:
* Get the JAR that was produced by Maven, or download it in [the Releases tab](https://github.com/max4805/EverestUpdateCheckerServer/releases).
* If you want to give control over the database to other people, in order for them to handle the few specific cases where automatic update doesn't work (this is really rare though, see [Handling special cases](#handling-special-cases)), create a `code.txt` file next to the JAR. Put a code in it, then share it with the people you want to be allowed to edit the database.
  * If you don't create a `code.txt` file, the "edit database remotely" feature will be disabled.

Then, to run the project, browse to where the JAR is in a terminal / command prompt, then run

```
java -jar update-checker-0.0.26.jar [port] [minutes]
```

[port] is the HTTP port for the server. If you don't provide any, there won't be any server hosted (useful if you already have something else hosting the files).

[minutes] is the wait delay in minutes between two GameBanana checks (defaults to 30). Be aware that the program makes ~13 API calls per check, and that the GameBanana API has a cap at 250 requests/hour.

## HTTP server usage

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
* `/everestupdateexcluded.yaml`: this file lists all downloads that should be skipped on GameBanana for any reason. Corrupted zips or duplicates (f.e. Gauntlet is an older duplicate of Gauntlet Revamped) are automatically added to this. Those files won't be checked again by the update checker server.
* `/everestupdatenoyaml.yaml`: this file holds the list of all zips that have been downloaded and don't contain any everest.yaml, so that they aren't downloaded again.

## Handling special cases

Some mods may need editing the database manually: that is, all cases where a mod offers multiple downloads. These cases need manual editing of the database.

### Multiple downloads with the same ID

Those mods be defined with two hashes in `everestupdate.yaml`, so that the updater can tell if the version the user has is _one of_ the up-to-date ones.

```yaml
DJMapHelper:
  Version: 1.6.4
  LastUpdate: 1552491224
  xxHash: [a7334bfe1b464ee6, e04c6c7d01fdfbe3]
  URL: https://gamebanana.com/mmdl/413533
```

_(Please note DJ Map Helper no longer has two separate downloads, and no mod currently needs this anymore, this is just an example if this happens again.)_

### List of mods to handle manually

_Please note that the `everestupdate.yaml` file uploaded to this repository already takes these cases into account. You don't need to edit it, just get the `uploads` directory and run the server._

* Ruby's Entities: Remove it from the database and blacklist it. Ships with D-sides.
* GhostMod: Remove it from the database and blacklist it. Ships with GhostNet.
* CrowControl-WS: Remove it from the database and blacklist it. Ships with Crow Control.

## Libraries used

* [SnakeYAML](https://bitbucket.org/asomov/snakeyaml/src/default/), licensed under the [Apache License 2.0](https://bitbucket.org/asomov/snakeyaml/src/default/LICENSE.txt)
* [Apache Commons IO](http://commons.apache.org/proper/commons-io/)
* [Apache Commons Lang](https://commons.apache.org/proper/commons-lang/)
* [Logback](http://logback.qos.ch/), licensed under [GNU LGPL version 2.1](http://logback.qos.ch/license.html)
* [NanoHTTPD](https://github.com/NanoHttpd/nanohttpd), licensed under the [BSD 3-Clause "New" License](https://github.com/NanoHttpd/nanohttpd/blob/master/LICENSE.md)
* [LZ4 Java](https://github.com/lz4/lz4-java), licensed under [Apache License 2.0](https://github.com/lz4/lz4-java/blob/master/LICENSE.txt), used for xxHash hash calculation
* [Jsoup](https://github.com/jhy/jsoup/), licensed under the [MIT License](https://github.com/jhy/jsoup/blob/master/LICENSE)
* [JSch](http://www.jcraft.com/jsch/), licensed under [a BSD-style license](http://www.jcraft.com/jsch/LICENSE.txt)
