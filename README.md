# BOSS Fluck Browser Plugin

**PRIVATE** - This plugin is proprietary and not open source.

A dynamic plugin for BOSS that provides a full-featured embedded web browser panel.

## Features

- Full web browser with URL navigation
- Back/forward/reload controls
- Page title and favicon updates
- Integration with host's download manager
- Integration with host's secret/credential system
- Password manager: suggests strong passwords and offers to save the ones you type

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
- **It does not hibernate a fullscreen video.** A tab whose video is playing fullscreen in its own
  window is waited out like an audible one, muted or not - the audio check alone could not see it,
  because it asks the page and the page does not know which window it is being shown in. Known
  gap, deliberate: a tab whose exit from fullscreen failed twice is allowed to hibernate rather
  than stay exempt for the rest of its life, since disposing the browser is what detaches the
  stuck window. That one costs a reload.
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

## Password manager

Two halves, both wired to the host's Secret Manager. Each has a switch in
`Settings > Browser > Secret Manager`, and both are on by default.

### Suggesting a password

Put the caret in a password box on a signup or change-password form and a card appears beside it
with a generated password, plus Regenerate and Copy. Taking it fills the box **and its confirm
twin**, so the form stays submittable, and stores the credential immediately - a "Saved for
<site>" confirmation follows, with an Edit button in case the username needs correcting.

Saving on use rather than on submit is deliberate: a signup that succeeds while a save prompt goes
unnoticed would leave the only copy of a generated password on a page that is about to navigate
away.

Two site rules are honoured, because a stored password that differs from the account's real one is
worse than no suggestion at all:

- `maxlength` is respected, and what actually landed in the field is what gets saved. A form that
  caps at 12 characters truncates silently.
- `pattern` is matched in full. A site that rejects punctuation gets a letters-and-digits password,
  and the card says so.

A box too short to hold a decent password (under 12 characters) gets no offer rather than a weak
one. If you wave the card away and want it back, right-click the field and choose **Suggest Strong
Password**. Taking a suggestion on a site where you already have a saved login for that account
updates it rather than adding a second entry.

**Copy does not expire.** The card's Copy button puts the password on the system clipboard and
nothing clears it afterwards, unlike most password managers, which drop it after some seconds. Use
it knowing that.

### Saving a password you typed

Sign in normally and, once the login looks like it worked, a bar offers to **Save** the credential -
or **Update** it, when a stored secret for that site holds a different password. "Never for this
site" suppresses it for the rest of the tab's life.

What counts as "looked like it worked" is the login form being **gone from the page**, not the URL
changing. A wrong password commonly re-renders the same form at a new URL, and the second screen of
a two-step sign-in is another login form rather than a success, so a URL change is not evidence of
anything. The form being gone covers both, including a single-page login that never navigates.

Nothing is offered when the credential is already stored unchanged, so a site you sign into daily
never produces a prompt.

### What crosses the boundary, and when

A password reaches the plugin **only** on a submit you performed, through a page-event script the
host installs at document start (`BrowserHandle.setPageEventScript`, api 1.0.83). The separate poll
that positions the saved-logins list runs several times a second and reports only *whether* a field
has a value, never the value - a periodic read that returned page text would be a keylogger.

A captured credential is held in memory, never written to disk, and is dropped after 90 seconds if
the login never resolves.

The bridge the script posts through is handed to it as a parameter and never left on `window`, so
no page script can replace it and receive the credential, forge a submission, or detect BOSS by
probing for it. What does arrive is treated as untrusted, and the site a credential is attributed to
comes from the URL the host reads off the posting document, never from the payload.

## Requirements

- BOSS Console 9.4.23 or later (the password manager needs the host's page-event channel)
- Plugin API 1.0.83 or later

## License

Licensed under the [Apache License, Version 2.0](LICENSE).

Copyright 2025-2026 Risa Labs Inc.
