# BOSS Fluck Browser Plugin

**PRIVATE** - This plugin is proprietary and not open source.

A dynamic plugin for BOSS that provides a full-featured embedded web browser panel.

## Features

- Full web browser with URL navigation
- Back/forward/reload controls
- Page title and favicon updates
- Integration with host's download manager
- Integration with host's secret/credential system

## Building

```bash
./gradlew build
```

The plugin JAR will be created at `build/libs/boss-plugin-fluck-browser-<version>.jar`.

## Installation

Copy the built JAR to your BOSS plugins directory:

```bash
cp build/libs/boss-plugin-fluck-browser-*.jar ~/.boss/plugins/
```

Or use the Plugin Manager within BOSS to install from the plugin repository.

## Requirements

- BOSS Console 8.16.0 or later
- Plugin API 1.0.5 or later
- Browser API 1.0.3 or later

## License

Proprietary - Risa Labs Inc. All rights reserved.
