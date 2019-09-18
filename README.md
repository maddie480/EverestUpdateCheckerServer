# Everest Update Checker Server

This small Java server generates a file named `uploads/everestupdate.yaml` containing information on all Celeste mods published on GameBanana including an `everest.yaml`. For example:
```yaml
SuperHotMod:
  Version: 2.0.0
  LastUpdate: 1567660611
  xxHash: [ab8e6117a0ef3cab]
  URL: https://gamebanana.com/mmdl/430983
```

This YAML file can then be hosted, and used by Everest or code mods to check if an update is available on a mod (by comparing the hash).

## Building the project

Get Maven, then run the following command at the project root:

```
mvn clean package
```

This will build the project to `target/update-checker-0.0.1.jar`.

## Running the project

First, follow these steps to set it up:
* Get the JAR that was produced by Maven.
* Download the `uploads` directory on this repository, and put it next to the JAR: that will prevent the server from rebuilding the database, which implies _downloading the entirety of GameBanana_.
* If you want to give control over the database to other people, in order for them to handle the few specific cases where automatic update doesn't work, create a `code.txt` file next to the JAR. Put a code in it, then share it with the people you want to be allowed to edit the database.
  * If you don't create a `code.txt` file, the "edit database remotely" feature will be disabled.

Then, to run the project, browse to where the JAR is, then run

```
java -jar update-checker-0.0.1.jar [port] [minutes]
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

You can also use these two methods with `/everestupdateexcluded.yaml`. This file lists all downloads that should be skipped on GameBanana for any reason. Corrupted zips or duplicates (f.e. Gauntlet is an older duplicate of Gauntlet Revamped) are automatically added to this.

To be able to use this method, you have to pass the content of the `code.txt` file in the Authorization header.

## Handling special cases

Some mods may need editing the database manually: that is, all cases where a mod offers multiple downloads. These cases need manual editing of the database.

### Multiple downloads with the same ID (f.e. DJMapHelper)

Those mods be defined with two hashes in `everestupdate.yaml`, so that the updater can tell if the version the user has is _one of_ the up-to-date ones.

```yaml
DJMapHelper:
  Version: 1.6.4
  LastUpdate: 1552491224
  xxHash: [a7334bfe1b464ee6, e04c6c7d01fdfbe3]
  URL: https://gamebanana.com/mmdl/413533
```

### Multiple downloads with different IDs (f.e. Simpleste)

All the versions of the download should be added to the database with their respective everest.yaml IDs, so that the update checker can check against the version the user has downloaded.

### List of mods to handle manually

_Please note that the `everestupdate.yaml` file uploaded to this repository already takes these cases into account. You don't need to edit it._

* Ruby's Entities: Remove it from the database. Ships with D-sides.
* GhostMod: Remove it from the database. Ships with GhostNet.
* DJ Map Helper: Add the two hashes, for the Windows and Linux version.
* Simpleste: Get the 3 versions of it, and add them all to the database.