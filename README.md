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

## Tab hibernation (memory saver)

A browser tab left in the background long enough disposes its live browser, releasing its Chromium
process tree. The tab, its URL, title and history all survive; returning to it recreates the
browser at the same address. A 40-tab session collapses to a handful of live browsers.

**On by default.** It used to be opt-in behind an environment variable, which meant in practice it
never ran.

### What it will and will not do

- **It never cuts audio.** A tab playing video or audio is re-checked rather than hibernated, and
  a tab still playing after about three hours is left alone entirely. Known gaps: playback inside
  a cross-origin iframe, and pure Web Audio with no media element, are not visible to the check.
- **It does reload the page.** Anything the page has not saved - a half-written form, scroll
  position, in-page state - is discarded, the same way Chrome's own memory saver behaves. If that
  matters for how you work, turn it off with the variable below.

### Configuration

| Setting | Default | Meaning |
|---|---|---|
| `BOSS_TAB_HIBERNATION` | on | Set to `false`/`0`/`no`/`off` to disable entirely |
| `BOSS_TAB_HIBERNATION_IDLE_MS` | host tier, else 10 min | How long a background tab waits |
| `BOSS_TAB_HIBERNATION_PRESSURE_IDLE_MS` | 60000 | Shortened wait while memory is scarce |
| `BOSS_TAB_HIBERNATION_PRESSURE_FRACTION` | 0.15 | Available-memory fraction counted as scarce |

The idle timeout normally comes from the host's resource tier, which the host publishes as
`boss.browser.hibernationEnabled` and `boss.browser.hibernationIdleMs`:

| Host tier | Idle timeout |
|---|---|
| Full | 30 minutes |
| Lite | 10 minutes |
| Ultra Lite | 2 minutes |

An environment variable outranks the tier; a host that publishes nothing falls back to 10 minutes.

## Requirements

- BOSS Console 8.16.0 or later
- Plugin API 1.0.5 or later
- Browser API 1.0.3 or later

## License

Licensed under the [Apache License, Version 2.0](LICENSE).

Copyright 2025-2026 Risa Labs Inc.
