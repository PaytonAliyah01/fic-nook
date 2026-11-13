# Fic Nook

A comfy Android reader for fics you downloaded from AO3. Open a PDF, EPUB or HTML download and read it with your own text size, font, line spacing and theme (light, sepia, dark or night). It remembers where you stopped and keeps your fics on a shelf. Everything stays on your phone.

## Get the app

1. On your Android phone, open the **[Releases page](../../releases/latest)** (or go straight to [FicNook.apk](https://github.com/PaytonAliyah01/fic-nook/releases/latest/download/FicNook.apk)).
2. Tap **FicNook.apk** to download it.
3. Open the downloaded file. If your phone asks, allow installing apps from your browser or Files app, then tap **Install**.

Each time the code changes, GitHub builds a fresh APK automatically. From version 1.3 on, the app notices new versions by itself and shows an **Update now** button (on the shelf in 1.3, under **Me** from 1.4). Updating keeps your shelf and reading spots.

## How it's built

- `app/src/main/assets/www/index.html` is the whole reader (one web page). PDF.js and JSZip are bundled in `www/lib/` so it works offline.
- `MainActivity.java` shows that page full screen and connects the phone's file picker and Back button.
- `.github/workflows/build-apk.yml` builds the APK on every push to `main` and puts it on the Releases page.
