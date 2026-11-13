# Fic Nook

A comfy Android reader for fics you downloaded from AO3. Fic Nook opens your PDF, EPUB or HTML downloads and lets you read them the way you like. It also keeps your library tidy: shelves, favourites, ratings, series, highlights, bookmarks, stats and more. Everything stays on your phone, and Fic Nook never visits AO3 for you. It only reads files you downloaded yourself.

## Get the app

1. On your Android phone, open [FicNook.apk](https://github.com/PaytonAliyah01/fic-nook/releases/latest/download/FicNook.apk) in **Chrome** (the Claude app and some other apps can't download files).
2. If Chrome warns that the file might be harmful, tap **Download anyway**.
3. Open the downloaded file. If your phone asks, allow installing apps from Chrome or your Files app, then tap **Install**.

Already have Fic Nook 1.3 or newer? You don't need to download anything. The app notices new versions by itself and shows an **Update now** button at the top of your Library and under **Me**. Updating keeps your library, shelves, highlights and reading spots.

## Documentation

| Document | What's in it |
|---|---|
| [User guide](docs/USER-GUIDE.md) | How to use every feature, in plain words |
| [What's new](docs/CHANGELOG.md) | Every version and what it added |
| [How it works](docs/HOW-IT-WORKS.md) | For anyone changing the code: how the app is put together, where data is kept, how builds and updates work |

## Quick overview

- `app/src/main/assets/www/index.html` is the whole reader (one web page). PDF.js and JSZip are bundled in `www/lib/` so it works offline.
- `MainActivity.java` shows that page full screen and connects it to the phone: your fic folder, read aloud, volume buttons, backups, sharing pictures and app updates.
- `ReadingWidget.java` is the "Keep reading" home screen widget.
- `.github/workflows/build-apk.yml` builds the APK on every push to `main` and puts it on the Releases page, which is where the in-app updater looks.
