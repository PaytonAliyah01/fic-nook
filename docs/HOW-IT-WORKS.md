# How Fic Nook works

This is for anyone changing the code. For how to use the app, see the [user guide](USER-GUIDE.md).

## The ground rule

Fic Nook only works on files the reader downloaded themselves. It never scrapes, logs in to, or downloads from AO3. The only network request the app makes is to the GitHub Releases API to check for updates.

## The pieces

```
web/fic-nook.html                     The reader: one self-contained web page (HTML, CSS, JS). Source of truth.
tools/make-app-page.py                Turns web/fic-nook.html into the app's copy (below).
app/src/main/assets/www/index.html    The app's copy of the reader. Generated, don't edit by hand.
app/src/main/assets/www/lib/          PDF.js 3.11.174 and JSZip 3.10.1, bundled so the app works offline.
app/src/main/java/.../MainActivity.java   The Android shell: a full-screen WebView plus the phone features.
app/src/main/java/.../ReadingWidget.java  The "Keep reading" home screen widget.
app/src/main/res/                     Theme, widget layout, file-sharing paths, strings.
app/ficnook.keystore                  Fixed signing key, so every build installs over the last one.
.github/workflows/build-apk.yml       Builds and releases the APK on every push to main.
```

The same page also runs on its own in a browser (it was first published as a Claude artifact). In the browser it loads PDF.js and JSZip from cdnjs instead of `lib/`, and every phone-only feature hides itself when the Android bridge isn't there.

### Making a change

1. Edit `web/fic-nook.html`.
2. Run `python3 tools/make-app-page.py` to refresh the app copy.
3. If you added something that needs the phone (files, speech, sharing...), add a method to the `Bridge` class in `MainActivity.java`.
4. Push to `main`. GitHub builds the APK and every installed copy offers the update.

## Opening fics

All parsing happens on the phone, in the page.

- **HTML and EPUB** (AO3's own download formats): the preface (`#preface`, `dl.tags`) becomes the info page and the tag fields. Chapters split on `h2.heading`. Author's notes become foldable `<details class="fn-note">` boxes. Markup is cleaned down to a safe set of tags.
- **PDF**: PDF.js gives positioned text pieces. Fic Nook groups them into lines and paragraphs using the body font size, the left margin, line gaps, first-line indents and short last lines. It then spots AO3's labels (Summary, Notes, Chapter Notes, End Notes), the chapter headings and the tag fields (Rating, Fandom, Relationship...). Repeating headers, footers and page numbers are removed (but never big text or "Chapter N" lines, since every chapter starts on a new page). A chapter starts at a "Chapter N" line or at a big heading at the top of a page, which is how AO3 prints named chapters. `PARSER_V` in the page is bumped whenever this gets better, so the app re-reads the fic folder once.
  - **Fonts (1.5)**: after `getTextContent()`, the page also runs `getOperatorList()` so PDF.js loads each font, then reads its real name (for example `LiberationSerif-Italic` or `DejaVuSansMono`) from `page.commonObjs`. The font with the most text is the body font. AO3's PDFs (made with wkhtmltopdf) use names like `NimbusRomNo9L-Regu`, `-ReguItal` (italic), `-Medi` (bold) and `-MediItal`, so the style part after the dash is matched loosely (`ital`, `obl`, `medi`, `semi`, `bold`...). Text in an italic, bold, monospace or different-family font becomes `<em>`, `<strong>`, `<span class="f-mono">`, `<span class="f-sans">` or `<span class="f-alt">`.
- **Same fic, new chapters**: every fic gets an id from its AO3 work number (`w12345`, read from the work URL inside the file), or else from a hash of title and author. A re-download that matches an existing fic by id, work number, or title and author replaces the text but keeps the id, so reading spot, highlights and everything else stay attached. `lib[id].newCh` counts the new chapters for the card label.

## Where things are kept

Everything is on the device, in the WebView's storage.

- **IndexedDB** database `ficnook`, store `fics`: the fics themselves (`{ id, title, author, about, fields, chapters: [{ title, html }], words, workId, added, ... }`).
- **localStorage**, every key starting with `ficnook:`:

| Key | What it holds |
|---|---|
| `settings` | Theme, font, size, line spacing, width, page mode, volume keys, reading speed, read-aloud speed, night light, auto-scroll speed |
| `lib` | Per fic: shelf status `s`, `fav`, finish time `fin`, `newCh`, `stars`, private `note`, custom `shelves` |
| `shelves` | Names of the reader's own shelves |
| `pos:<id>` | Reading spot `{ ch, frac }` |
| `opened:<id>` | When the fic was last opened (for sorting and the Reading button) |
| `hl:<id>` | Highlights `{ id, ch, b, s, e, text, note, at }` (block index plus text offsets) |
| `bm:<id>` | Bookmarks `{ id, ch, frac, text, at }` |
| `stats` | `days` (seconds read and words per day), `read` (chapters counted), `fin` (finished fics, with fandom, ships and tags for the recap) |
| `goal` | `{ n, type }`, where type is week, month, year or day |
| `filters`, `tab`, `sort` | Library view choices |
| `folderSeen` | Files in the fic folder already imported (name, size, modified) |
| `setupDone`, `hintReader`, `sampleRemoved`, `qcStyle` | One-time flags and small preferences |

**Backups** are one JSON file: `{ app: "ficnook", version: 1, created, fics, ls }`, where `ls` is every `ficnook:` key. Restoring adds fics and merges keys (stats take the larger numbers, highlights, bookmarks and shelves are combined), so it never wipes anything.

## The Android bridge

`MainActivity` serves the page from `https://appassets.androidplatform.net/assets/www/` with `WebViewAssetLoader` and adds a JavaScript object `FicNookAndroid`.

Page to phone (`FicNookAndroid.*`):

| Method | Does |
|---|---|
| `folderName()`, `pickFolder()` | The chosen fic folder (Storage Access Framework tree) |
| `version()`, `installUpdate()` | App version, and download plus install the waiting update |
| `ttsSpeak(json, start, rate)`, `ttsStop()`, `ttsSleep(ms)` | Read aloud with Android's text-to-speech |
| `setVolumeKeys(on)` | Volume buttons turn pages while reading |
| `saveBackup(json, filename)` | Opens the system "save as" screen (Drive shows up there) |
| `setWidget(title, info)` | Updates the home screen widget |
| `takeLaunch()` | Returns `"resume"` once if the app was opened from the widget |
| `shareImage(base64Png, filename)` | Shares a quote card through the Android share menu |

Phone to page (functions on `window`): `ficNookFolderChanged`, `ficNookResume`, `ficNookUpdateAvailable`, `ficNookUpdateStatus`, `ficNookTts(type, index)`, `ficNookVolume(direction)`, `ficNookBackupDone(ok)`, `ficNookBack()` (returns true if the page handled Back), `ficNookOpenLast()`, `ficNookToast(message)`.

The fic folder is read through two made-up addresses the activity answers itself: `/fn/list` (JSON list of PDF, EPUB and HTML files, up to three folders deep) and `/fn/file?u=<document uri>` (the file's bytes).

## Builds, releases and updates

- `app/build.gradle`: `minSdk 26`, `targetSdk 34`, Android Gradle Plugin 8.7.3, `androidx.webkit` and `androidx.core`. The version code is the `ficVersion` property and the version name is `1.<ficVersion>`.
- The workflow runs `gradle assembleRelease -PficVersion=<run number>` with Gradle 8.11.1 and Java 17, then creates a release `v<run number>` with `FicNook.apk` and marks it latest. The stable download link is `releases/latest/download/FicNook.apk`.
- The app asks the GitHub API for the latest release at launch, on resume (at most every 2 minutes), every 30 minutes while open, and when **Check for updates** is tapped (`checkUpdate()`, answered by `ficNookUpdateAvailable` or `ficNookUpdateNone(ok)`). If its number is higher than the installed version code, the page shows **Update now**. Tapping it downloads the APK into the app's cache and hands it to the Android installer through a `FileProvider`. That needs the "install unknown apps" permission, which Android asks for the first time.
- All builds are signed with the committed keystore (password `ficnook`). If it ever changes, updates won't install over old copies.

## Testing

There's no phone in the loop yet. Changes are checked by loading the page in headless Chromium (Playwright) with a fake `FicNookAndroid` object and test fics (AO3-style HTML, an EPUB and PDFs printed from them, including one with italics, bold and text-message fonts). The scripts walk through setup, folder import, re-download updates, shelves, filters, the reader, highlights, read aloud, auto-scroll, quote cards, goals, backups and widget launches. The Java side is checked by the GitHub build.
