# SmartDialer

A native Android dialer app built with Kotlin and Jetpack Compose.

SmartDialer replaces the system dialer experience with a single app for
calling, contacts, recents and search: a Compose dialpad, T9 contact search,
favorites / speed dial, contact profiles, a full-screen in-call UI and
blocked-numbers management.

## Features

- **Dialer** — Compose dialpad with DTMF, call actions and contact lookup
  while dialing.
- **Phone / Home** — recent calls, favorites and quick actions in one place.
- **Contacts** — contact list, universal search and T9 search (`T9Trie`).
- **Favorites / Speed dial** — pinned favorite contacts for one-tap calling.
- **Recents** — incoming, outgoing and missed calls.
- **Contact profile** — per-contact details and call actions.
- **In-call UI** — full-screen in-call screen (avatar, controls, DTMF pad,
  header) backed by an `InCallService` implementation.
- **Call recording** — recording manager (requires the recording permission
  and device support; recording laws vary by region).
- **Settings & blocked numbers** — app settings and a blocked-numbers list.
- **Default dialer role** — the app requests the system dialer role so it
  can handle outgoing calls and the in-call UI.

## Tech stack

- Language: **Kotlin**
- UI: **Jetpack Compose (Material 3)**, Navigation Compose, ViewModel
- Min SDK 30, target / compile SDK 35
- Gradle 9.3.1 wrapper (see `gradle/wrapper/`), Android Gradle Plugin 8.9.2,
  Kotlin 2.2.21 — versions are pinned in `gradle/libs.versions.toml`
- Package: `com.smartdialerpro.android`, version 1.0 (versionCode 1)

## Project structure

```text
SmartDialer2/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/smartdialerpro/android/
│       │   ├── MainActivity.kt
│       │   ├── SmartDialerApplication.kt
│       │   ├── data/          # Models, telephony models
│       │   ├── repository/    # Call log and contacts repositories
│       │   ├── service/       # InCallService, call manager, recording, receiver
│       │   ├── ui/            # Screens: dialer, home, contacts, recents,
│       │   │                  #   profile, settings, in-call, theme
│       │   ├── util/          # T9 trie, helpers
│       │   └── viewmodel/     # App view models
│       └── res/               # Drawables, launcher icons, strings, XML rules
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradle/
│   ├── libs.versions.toml
│   └── wrapper/               # Gradle wrapper, including the jar (required)
└── file.sh                    # Helper script that regenerates Screen.kt
```

## Build

Open the project in Android Studio (or AndroidIDE/ACS on device), let Gradle
sync, then build:

```bash
./gradlew :app:assembleDebug
```

The debug APK is produced under `app/build/outputs/apk/debug/`.

Notes:

- The Gradle wrapper jar in `gradle/wrapper/` is committed on purpose.
  Without it, some on-device build setups fall back to an incompatible
  bundled Gradle version and the import/sync never completes.
- Build outputs (`build/`, `.gradle/`, `.kotlin/`) are git-ignored and are
  never committed.
- No API keys or secrets are stored in this repository.

## Permissions

The manifest requests the permissions a dialer needs: calling, reading
phone state/numbers, contacts, call log, notifications, microphone /
recording and foreground services for phone calls. Runtime permissions are
requested in the app before the related feature is used.

## Status

Early version (1.0). The source is the full app as built on device; changes
land here as commits on `main`.
