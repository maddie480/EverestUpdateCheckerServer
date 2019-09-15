# Everest Update Checker Server

This small Java server generates a file named `uploads/everestupdate.yaml` containing information on all Celeste mods published on GameBanana including an `everest.yaml`. For example:
```yaml
SuperHotMod:
  SHA256: [7025599b27cb4c2502025cbae448dfa22dc645746e99ebe6b378d7cf4707d4cf]
  Version: 2.0.0
  LastUpdate: 1567660611
  URL: https://gamebanana.com/mmdl/430983
```

This YAML file can then be hosted, and used by Everest or code mods to check if an update is available on a mod (by comparing the SHA256 hash).

## Building the project

Get Maven, then run the following command at the project root:

```
mvn clean package
```

This will build the project to `target/update-checker-0.0.1.jar`.

## Running the project

To run the project, browse to where the JAR is, then run

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

To be able to use this method, you have to pass the content of the `code.txt` file in the Authorization header.

## Handling special cases

Some mods may need editing the database manually: that is, all cases where a mod offers multiple downloads. These cases need manual editing of the database.

### Multiple downloads with the same ID (f.e. DJMapHelper)

Those mods be defined with two SHA256 hashes in `everestupdate.yaml`, so that the updater can tell if the version the user has is _one of_ the up-to-date ones.

```yaml
DJMapHelper:
  SHA256: [ac4629178e57aec2daecd2faf8021cbedad18c5c633416a60bb9079bbe108395, 4a036681fdd191ee47196e1f364beebf687f8dfc57b4ab30d37b44f7ba28daaa]
  Version: 1.6.4
  LastUpdate: 1552491224
  URL: https://gamebanana.com/mmdl/413533
```

### Multiple downloads with different IDs (f.e. Simpleste)

All the versions of the download should be added to the database with their respective everest.yaml IDs, so that the update checker can check against the version the user has downloaded.

### List of mods to handle manually

* Ruby's Entities: Remove it from the database. Ships with D-sides.
* GhostMod: Remove it from the database. Ships with GhostNet.
* DJ Map Helper: Add the two sha256 hashes, for the Windows and Linux version.
* Simpleste: Get the 3 versions of it, and add them all to the database.