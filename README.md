# Abyssal Files

Dark file manager for Android with pixel-art icons — Kotlin + Jetpack Compose, no third-party libraries.

Created by Jh0nzxx and Unknown Maker

![Abyssal Files](logo.png)

**Download:** grab `explorer.apk` from the [latest release](../../releases/latest).

## Build
- `./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk` (signed with the debug key, ready to sideload)
- Or open the folder in Android Studio (Ladybug or newer) and run it on a device or emulator (API 24+).
- First launch: grant "All files access".

## Features
- Bottom bar on every screen: Home, Favorites, History
- Home: internal storage + SD card / USB drives (same GB numbers as Android's Settings), favorites, categories, recent folders, quick access
- Images and Videos open as a gallery: one album per folder (cover = newest item, name, count, SD badge); tap an album for a thumbnail grid, long-press an album to open its folder
- The other categories (Audio, Documents, …) are grouped by the folder that holds the files
- Search looks through the current folder **and all its subfolders**
- Sort by name, date, size or type, in either direction (newest/oldest first, A→Z/Z→A…)
- Date and time on every row; **Properties** with size, contents, path, access, dimensions, duration…
- Photo and video thumbnails
- Built-in image viewer: swipe through the folder/album, pinch or double-tap to zoom, share, open with, properties, delete
- Built-in video player: play/pause, seek bar, double-tap the sides for ±10 s, previous/next video, speed (0.5×–2×), rotate to landscape, full screen; pauses when the app goes to the background
- Archive browser: tap a .zip / .jar / .mcpack / .mcaddon / .mcworld / .mctemplate to look inside without extracting; files open straight from it (images, videos, audio, text, nested archives)
- Copy, cut/paste, rename, delete, zip, share, open with
- Extract (.zip, .mcpack, .mcaddon, .mcworld, .jar…) from the archive browser's **Extract** button or by long-pressing the archive: **here** (next to the archive), **to "name/"** (new folder) or **to…** (pick any folder); long-press an item inside an archive to extract just that file or folder. Existing files are never overwritten (new copies get " (1)")
- Built-in code editor (IDE-style):
  - syntax colours for ~30 languages (Kotlin, Java, JS/TS, Python, C/C++, C#, Go, Rust, PHP, HTML, CSS, JSON, YAML, SQL, Shell, Markdown, Minecraft `.mcfunction`…)
  - autocomplete popup: keywords, functions, words from the file and snippets (`main`, `fun`, `for`, `html5`, `execute`, `tellraw`…)
  - line numbers, auto-indent, auto-closing brackets/quotes and HTML tags, undo/redo, find & replace
  - extra keys above the keyboard (Tab, arrows, `{ } ( ) ; = < > /`…), word wrap and font size in the ⋮ menu
  - "Open with" sends the file to another app instead
  - **Bedrock mode** (switch in the editor's ⋮ menu or in Settings) for Minecraft Bedrock add-ons:
    - JS/TS: Script API autocomplete for `@minecraft/server` and `@minecraft/server-ui` that follows the code — `world.afterEvents.…`, event callback parameters (`({ player }) => player.…`), variables (`const dim = world.getDimension(…)`), `getComponent("inventory").container.…`, enums, and string values (item/entity ids, effects, sounds, particles, commands, modules), plus snippets (forms, events, `runInterval`, custom components…)
    - JSON: keys and values by position in the file (manifest, entities, items, blocks, recipes, loot tables, spawn rules, client entities, filters…), with ready-to-edit default values; in an empty file, whole templates (`manifest_bp`, `manifest_rp`, `manifest_script`, `entity`, `item`, `block`, `recipe_shaped`, `loot_table`) with freshly generated UUIDs
- Built-in audio player
- New files with any extension
- Accent colour picker: the whole dark theme **and the icons** follow it

## Icons
Every file type gets its own pixel-art icon, VS Code style (JS, PY, KT, PDF, DOC, JSON, Minecraft `.mcworld`/`.mcpack`/`.mcaddon`, ROMs, fonts, …), and well-known folders do too (Download, DCIM, Music, Android, src, games, minecraft…).

- **Icon colours** (Settings): *Theme colour* tints every icon with the accent; *Colourful* gives each type its own colour.
- Icons are text files in `app/src/main/assets/icons/*.txt`. Each character is one pixel, and the header of each file explains the colours (`@accent`, `@type`, `#RRGGBB`, `*0.5` darker, `+0.5` lighter).
- **On the phone, without rebuilding:** Settings → *Copy icons* copies them all to `Internal storage/AbyssalFiles/icons/`. Edit any `.txt` there (the built-in editor works), or drop a `name.png` with the same name, then Settings → *Reload icons*.
- Languages, keywords and snippets live in `CodeLang.kt` (`Langs`); the editor itself is `CodeEditor.kt`; Bedrock mode data (Script API types, events, add-on JSON schema) is in `Bedrock.kt`.
- `tools/gen_icons.py` regenerates all the default icons. It **overwrites** any edits you made to them.
- Which icon a file gets is decided in `IconMap` (`Icons.kt`): by full name (`package.json`, `Dockerfile`…), then by extension, then by folder name.

The logo is in `logo.png` (512 px) and `app/src/main/res/drawable-nodpi/logo.png` (32×32 source). The launcher icon is in `mipmap-*` and `drawable-nodpi/ic_launcher_fg.png`.

## Tests
`./gradlew testReleaseUnitTest` runs the editor tests (every language's highlighter, auto-indent, pairs, HTML tags, autocomplete, undo), the Bedrock mode tests (Script API type resolution, JSON keys/values, manifest UUIDs) and the archive tests (listing, full/partial extraction, name conflicts, zip-slip protection).

## Notes
- Bedrock mode targets the stable Script API 2.x; adjust `"version"` in the manifest dependencies to match your Minecraft version.
- The package is `com.abyssal.files`. Versions up to 1.1 were `com.example.lightfiles` ("Light Files"); uninstall that one first.
- Writing to SD cards needs Android 11+ (all-files access). On Android 7–10 the SD card can be browsed but not modified.
- Search stops after 3000 results. The editor edits files up to 1 MB (colours up to 300 KB) and shows larger ones read-only.
- Google Play restricts MANAGE_EXTERNAL_STORAGE to file-manager apps and requires a declaration form.

## License
MIT, see [LICENSE](LICENSE).
