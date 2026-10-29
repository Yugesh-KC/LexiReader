# Lexi Reader

An Android e-book reader built around looking words up. Tap any word while reading to see its meaning,
offline, without leaving the page. Highlight passages, write notes, and tag characters, places and themes
to follow them through a book.

**[Download the latest APK](https://github.com/Yugesh-KC/LexiReader/releases/latest)** (Android 7.0 or newer)

## Features

- **Formats:** EPUB, PDF, FB2, TXT, Markdown and HTML. Open files from the in-app library, or from any
  file manager or browser with "Open with".
- **Dictionary on tap:** a bundled WordNet 3.1 database gives definitions with no connection, and it handles
  inflected forms ("running", "geese", "better"). When you're online it can also check the Free Dictionary
  API, Wiktionary and Wikipedia (handy for names and places). Online results are cached, so they keep
  working offline. Pronounce words with the device's text-to-speech.
- **Highlights and notes:** long-press text to highlight it in one of several colours and attach a note.
  Notes can link tags with `[[Name]]`.
- **Tags:** tag a character, place or theme to collect your tagged passages into a timeline, and see every
  mention of it in the book.
- **Notebook:** browse and filter all notes and tags for a book, and export them as a Markdown file through
  the share sheet.
- **Reading settings:** Light, Sepia, Dusk, Dark and Black themes; serif, sans, book and mono fonts; text
  size, line spacing, margins and justification; paged or scrolling layout. Optionally turn pages by
  tapping the screen edges or with the volume keys, and keep the screen on.
- **Navigation:** table of contents, full-text search, go-to-page, and "back to where you were" after a jump.

## Install

1. Download `LexiReader-<version>.apk` from [Releases](https://github.com/Yugesh-KC/LexiReader/releases).
2. Open it on your phone. Android will ask you to allow installs from that app (browser or file manager)
   the first time.

## Building

Requirements: Android Studio (or JDK 17+ with the Android SDK). The project targets SDK 37 and supports
SDK 24 and newer.

```sh
./gradlew assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
./gradlew test              # unit tests
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk
```

### Release signing

Release builds are signed when signing details are available, either as environment variables:

```
LEXI_STORE_FILE  LEXI_STORE_PASSWORD  LEXI_KEY_ALIAS  LEXI_KEY_PASSWORD
```

or in a `keystore.properties` file at the project root (git-ignored):

```properties
storeFile=keystore/lexi-release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Without either, `assembleRelease` produces an unsigned APK.

## How it works

- Kotlin and Jetpack Compose for the UI (library, panels, dictionary card, note editor).
- Every format except PDF is converted to HTML chapters (`app/src/main/java/.../book/`) and rendered in a
  WebView by a small reader engine (`app/src/main/assets/reader/`). PDFs are rendered with
  [pdf.js](https://mozilla.github.io/pdf.js/) 3.x (`app/src/main/assets/pdfjs/`).
- Notes, tags, reading progress and cached definitions are stored in a local SQLite database.
- The only network access is the optional online dictionary lookup, which you can turn off in reading
  settings.

## Credits

- [WordNet 3.1](https://wordnet.princeton.edu/), Princeton University, for the offline dictionary
- [pdf.js](https://github.com/mozilla/pdf.js) (Apache 2.0)
- [jsoup](https://jsoup.org/) (MIT)
- [Free Dictionary API](https://dictionaryapi.dev/), [Wiktionary](https://www.wiktionary.org/) and
  [Wikipedia](https://www.wikipedia.org/) for online definitions
