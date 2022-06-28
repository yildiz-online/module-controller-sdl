# Yildiz-Engine module-controller-sdl.

This is the official repository of the Controller SDL Module, part of the Yildiz-Engine project.
This module is the the SDL implementation for the controller module.

## Features

* Multiple controller support.
* Vibration support.
* Controller state.
* Controller events
* ...

## Requirements

To build this module, you will need the latest Java JDK, and Maven 3.

## Coding Style and other information

Project website:
https://engine.yildiz-games.be

Issue tracker:
https://yildiz.atlassian.net

Wiki:
https://yildiz.atlassian.net/wiki

Quality report:
https://sonarcloud.io/dashboard/index/be.yildiz-games:module-controller-sdl

## License

All source code files are licensed under the permissive MIT license
(http://opensource.org/licenses/MIT) unless marked differently in a particular folder/file.

## Build instructions

Go to your root directory, where you POM file is located.

Then invoke maven

	mvn clean install

This will compile the source code, then run the unit tests, and finally build a jar file.

## Usage

In your maven project, add the dependency

```xml

<dependency>
  <groupId>be.yildiz-games</groupId>
  <artifactId>module-controller-jampad</artifactId>
  <version>${version}</version>
</dependency>
```

Replace LATEST with the version number to use.

## Contact

Owner of this repository: Grégory Van den Borre