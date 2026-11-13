package com.ficnook.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.view.KeyEvent;
import android.util.Base64;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Shows the Fic Nook page (bundled in assets/www) full screen, plus folder access and self-updates. */
public class MainActivity extends Activity {
    private static final int PICK_FILE = 41;
    private static final int PICK_FOLDER = 42;
    private static final String HOST = "appassets.androidplatform.net";
    // A stable https address for the bundled files, so the shelf (IndexedDB) and settings persist.
    private static final String HOME = "https://" + HOST + "/assets/www/index.html";
    private static final String RELEASES = "https://api.github.com/repos/PaytonAliyah01/fic-nook/releases/latest";

    private WebView web;
    private SharedPreferences prefs;
    private ValueCallback<Uri[]> pendingPick;
    private boolean checkedUpdate = false;
    private long lastUpdateCheck = 0;
    private volatile String updateUrl;
    private static final int SAVE_BACKUP = 43;
    private TextToSpeech tts;
    private volatile boolean ttsReady = false;
    private volatile int ttsLast = -1;
    private volatile boolean volumeKeys = false;
    private volatile String pendingBackup;
    private volatile String launchAction;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable sleepTask = () -> {
        if (tts != null) tts.stop();
        js("window.ficNookTts && window.ficNookTts('sleep', -1)");
    };

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getIntent() != null && ReadingWidget.ACTION_RESUME.equals(getIntent().getAction())) launchAction = "resume";
        prefs = getSharedPreferences("ficnook", MODE_PRIVATE);
        web = new WebView(this);
        web.setBackgroundColor(0xFF17171C);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setTextZoom(100);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web.addJavascriptInterface(new Bridge(), "FicNookAndroid");
        setupTts();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (HOST.equals(u.getHost()) && u.getPath() != null && u.getPath().startsWith("/fn/")) {
                    return serveFolder(u);
                }
                return loader.shouldInterceptRequest(u);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (HOST.equals(u.getHost())) return false;
                // Links inside fics open in the phone's browser.
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (!checkedUpdate) {
                    checkedUpdate = true;
                    checkForUpdate();
                }
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (pendingPick != null) pendingPick.onReceiveValue(null);
                pendingPick = callback;
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
                try {
                    startActivityForResult(i, PICK_FILE);
                } catch (Exception e) {
                    pendingPick = null;
                    return false;
                }
                return true;
            }
        });

        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(HOME);
    }

    /* ---------- talking to the page ---------- */

    private void js(String code) {
        runOnUiThread(() -> web.evaluateJavascript(code, null));
    }

    private class Bridge {
        @JavascriptInterface
        public String folderName() {
            String t = prefs.getString("tree", null);
            if (t == null) return "";
            try {
                String id = DocumentsContract.getTreeDocumentId(Uri.parse(t));
                int i = id.indexOf(':');
                String vol = i >= 0 ? id.substring(0, i) : "";
                String rest = i >= 0 ? id.substring(i + 1) : id;
                if (!rest.isEmpty()) return rest;
                return "primary".equals(vol) ? "Phone storage" : (vol.isEmpty() ? "Your folder" : vol);
            } catch (Exception e) {
                return "Your folder";
            }
        }

        @JavascriptInterface
        public void pickFolder() {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                try { startActivityForResult(i, PICK_FOLDER); } catch (Exception ignored) { }
            });
        }

        @JavascriptInterface
        public String version() {
            return BuildConfig.VERSION_NAME;
        }

        @JavascriptInterface
        public void installUpdate() {
            startUpdate();
        }

        /* Read aloud: the page sends the paragraphs, Android's text-to-speech reads them in order. */
        @JavascriptInterface
        public void ttsSpeak(String json, int start, float rate) {
            if (tts == null || !ttsReady) {
                js("window.ficNookTts && window.ficNookTts('error', -1)");
                return;
            }
            try {
                JSONArray a = new JSONArray(json);
                tts.stop();
                tts.setSpeechRate(rate);
                ttsLast = a.length() - 1;
                for (int i = Math.max(0, start); i < a.length(); i++) {
                    tts.speak(a.getString(i), i == start ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD, null, "u" + i);
                }
            } catch (Exception e) {
                js("window.ficNookTts && window.ficNookTts('error', -1)");
            }
        }

        @JavascriptInterface
        public void ttsStop() {
            if (tts != null) tts.stop();
        }

        @JavascriptInterface
        public void ttsSleep(long ms) {
            handler.removeCallbacks(sleepTask);
            if (ms > 0) handler.postDelayed(sleepTask, ms);
        }

        @JavascriptInterface
        public void setVolumeKeys(boolean on) {
            volumeKeys = on;
        }

        /* Home screen widget: the page tells us which fic you're reading. */
        @JavascriptInterface
        public void setWidget(String title, String info) {
            prefs.edit().putString("w_title", title == null ? "" : title).putString("w_info", info == null ? "" : info).apply();
            try { ReadingWidget.refresh(MainActivity.this); } catch (Exception ignored) { }
        }

        /* Opened from the widget? Answered once, then forgotten. */
        @JavascriptInterface
        public String takeLaunch() {
            String a = launchAction;
            launchAction = null;
            return a == null ? "" : a;
        }

        /* Quote cards: save the picture and open Android's share menu (Photos, Drive, messages...). */
        @JavascriptInterface
        public void shareImage(String base64, String filename) {
            try {
                File dir = new File(getCacheDir(), "shared");
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                String safe = filename == null ? "quote.png" : filename.replaceAll("[^A-Za-z0-9 ._-]", "");
                if (!safe.endsWith(".png")) safe = safe + ".png";
                File f = new File(dir, safe);
                try (OutputStream out = new FileOutputStream(f)) {
                    out.write(Base64.decode(base64, Base64.DEFAULT));
                }
                Uri uri = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".files", f);
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("image/png");
                send.putExtra(Intent.EXTRA_STREAM, uri);
                send.setClipData(ClipData.newRawUri("", uri));
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                Intent chooser = Intent.createChooser(send, "Share your quote");
                chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                runOnUiThread(() -> startActivity(chooser));
            } catch (Exception e) {
                js("window.ficNookToast && window.ficNookToast('Couldn\\'t share the picture.')");
            }
        }

        /* Backup: ask where to save (Google Drive shows up in the list), then write the file there. */
        @JavascriptInterface
        public void saveBackup(String json, String filename) {
            pendingBackup = json;
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("application/json");
                i.putExtra(Intent.EXTRA_TITLE, filename);
                try {
                    startActivityForResult(i, SAVE_BACKUP);
                } catch (Exception e) {
                    pendingBackup = null;
                    js("window.ficNookBackupDone && window.ficNookBackupDone(false)");
                }
            });
        }
    }

    /* ---------- read aloud ---------- */

    private void setupTts() {
        tts = new TextToSpeech(this, status -> {
            ttsReady = status == TextToSpeech.SUCCESS;
            if (!ttsReady) return;
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String id) {
                    js("window.ficNookTts && window.ficNookTts('start'," + num(id) + ")");
                }

                @Override
                public void onDone(String id) {
                    if (num(id) == ttsLast) js("window.ficNookTts && window.ficNookTts('end'," + num(id) + ")");
                }

                @Override
                @SuppressWarnings("deprecation")
                public void onError(String id) {
                    js("window.ficNookTts && window.ficNookTts('error'," + num(id) + ")");
                }
            });
        });
    }

    private static int num(String id) {
        try { return Integer.parseInt(id.substring(1)); } catch (Exception e) { return -1; }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (volumeKeys && (k == KeyEvent.KEYCODE_VOLUME_DOWN || k == KeyEvent.KEYCODE_VOLUME_UP)) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                js("window.ficNookVolume && window.ficNookVolume(" + (k == KeyEvent.KEYCODE_VOLUME_DOWN ? 1 : -1) + ")");
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(sleepTask);
        if (tts != null) tts.shutdown();
        super.onDestroy();
    }

    /* ---------- the fic folder ---------- */

    private WebResourceResponse serveFolder(Uri u) {
        try {
            String tree = prefs.getString("tree", null);
            if (tree == null) return httpError(404);
            Uri treeUri = Uri.parse(tree);
            String treeId = DocumentsContract.getTreeDocumentId(treeUri);
            if ("/fn/list".equals(u.getPath())) {
                JSONArray arr = new JSONArray();
                walk(treeUri, treeId, 0, arr);
                return new WebResourceResponse("application/json", "utf-8",
                        new ByteArrayInputStream(arr.toString().getBytes(StandardCharsets.UTF_8)));
            }
            if ("/fn/file".equals(u.getPath())) {
                String doc = u.getQueryParameter("u");
                if (doc == null) return httpError(400);
                Uri d = Uri.parse(doc);
                // Only files inside the folder the reader picked.
                if (!treeUri.getAuthority().equals(d.getAuthority())
                        || !treeId.equals(DocumentsContract.getTreeDocumentId(d))) return httpError(403);
                InputStream in = getContentResolver().openInputStream(d);
                if (in == null) return httpError(404);
                return new WebResourceResponse("application/octet-stream", null, in);
            }
        } catch (Exception ignored) { }
        return httpError(404);
    }

    private void walk(Uri tree, String docId, int depth, JSONArray out) throws Exception {
        Uri kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        String[] cols = {Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
                Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED};
        try (Cursor c = getContentResolver().query(kids, cols, null, null, null)) {
            if (c == null) return;
            while (c.moveToNext()) {
                String id = c.getString(0), name = c.getString(1), mime = c.getString(2);
                if (Document.MIME_TYPE_DIR.equals(mime)) {
                    if (depth < 3 && out.length() < 3000) walk(tree, id, depth + 1, out);
                    continue;
                }
                if (name == null || !name.toLowerCase(Locale.ROOT).matches(".*\\.(pdf|epub|html?)$")) continue;
                JSONObject o = new JSONObject();
                o.put("uri", DocumentsContract.buildDocumentUriUsingTree(tree, id).toString());
                o.put("name", name);
                o.put("size", c.isNull(3) ? 0 : c.getLong(3));
                o.put("modified", c.isNull(4) ? 0 : c.getLong(4));
                out.put(o);
            }
        }
    }

    private static WebResourceResponse httpError(int code) {
        return new WebResourceResponse("text/plain", "utf-8", code, "Error", null, new ByteArrayInputStream(new byte[0]));
    }

    /* ---------- updates ---------- */

    private void checkForUpdate() {
        lastUpdateCheck = System.currentTimeMillis();
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(RELEASES).openConnection();
                c.setRequestProperty("Accept", "application/vnd.github+json");
                c.setConnectTimeout(10000);
                c.setReadTimeout(10000);
                if (c.getResponseCode() != 200) return;
                JSONObject o = new JSONObject(readAll(c.getInputStream()));
                int remote = Integer.parseInt(o.optString("tag_name").replaceAll("[^0-9]", ""));
                if (remote <= BuildConfig.VERSION_CODE) return;
                JSONArray assets = o.getJSONArray("assets");
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    if (a.optString("name").endsWith(".apk")) updateUrl = a.optString("browser_download_url");
                }
                if (updateUrl == null) return;
                js("window.ficNookUpdateAvailable && window.ficNookUpdateAvailable(" + JSONObject.quote("1." + remote) + ")");
            } catch (Exception ignored) { }
        }).start();
    }

    private void updateStatus(String msg, boolean canTap) {
        js("window.ficNookUpdateStatus && window.ficNookUpdateStatus(" + JSONObject.quote(msg) + "," + canTap + ")");
    }

    private void startUpdate() {
        if (updateUrl == null) return;
        if (!getPackageManager().canRequestPackageInstalls()) {
            updateStatus("Allow it, then tap here again", true);
            runOnUiThread(() -> {
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
                } catch (Exception ignored) { }
            });
            return;
        }
        new Thread(() -> {
            try {
                updateStatus("Downloading…", false);
                File dir = new File(getCacheDir(), "updates");
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                File apk = new File(dir, "FicNook.apk");
                HttpURLConnection c = (HttpURLConnection) new URL(updateUrl).openConnection();
                c.setInstanceFollowRedirects(true);
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                int total = c.getContentLength();
                try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(apk)) {
                    byte[] buf = new byte[64 * 1024];
                    long done = 0;
                    int n, lastPct = -1;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        done += n;
                        if (total > 0) {
                            int pct = (int) (done * 100 / total);
                            if (pct / 10 != lastPct / 10) { lastPct = pct; updateStatus("Downloading… " + pct + "%", false); }
                        }
                    }
                }
                Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", apk);
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(uri, "application/vnd.android.package-archive");
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                runOnUiThread(() -> startActivity(i));
                updateStatus("Install again", true);
            } catch (Exception e) {
                updateStatus("Download failed. Try again", true);
            }
        }).start();
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close();
        return b.toString("UTF-8");
    }

    /* ---------- activity plumbing ---------- */

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == SAVE_BACKUP) {
            final String json = pendingBackup;
            pendingBackup = null;
            if (resultCode != RESULT_OK || data == null || data.getData() == null || json == null) {
                js("window.ficNookBackupDone && window.ficNookBackupDone(false)");
                return;
            }
            final Uri target = data.getData();
            new Thread(() -> {
                boolean ok = false;
                try (OutputStream out = getContentResolver().openOutputStream(target, "wt")) {
                    if (out != null) {
                        out.write(json.getBytes(StandardCharsets.UTF_8));
                        ok = true;
                    }
                } catch (Exception ignored) { }
                js("window.ficNookBackupDone && window.ficNookBackupDone(" + ok + ")");
            }).start();
            return;
        }
        if (requestCode == PICK_FOLDER) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                Uri tree = data.getData();
                try {
                    getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception ignored) { }
                prefs.edit().putString("tree", tree.toString()).apply();
                js("window.ficNookFolderChanged && window.ficNookFolderChanged()");
            }
            return;
        }
        if (requestCode != PICK_FILE || pendingPick == null) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        Uri[] result = null;
        if (resultCode == RESULT_OK && data != null) {
            ClipData clip = data.getClipData();
            if (clip != null && clip.getItemCount() > 0) {
                result = new Uri[clip.getItemCount()];
                for (int i = 0; i < clip.getItemCount(); i++) result[i] = clip.getItemAt(i).getUri();
            } else if (data.getData() != null) {
                result = new Uri[]{data.getData()};
            }
        }
        pendingPick.onReceiveValue(result);
        pendingPick = null;
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        // Let the page handle Back first (close a panel, or go from a fic back to the shelf).
        web.evaluateJavascript("(window.ficNookBack && window.ficNookBack()) ? 'yes' : 'no'", value -> {
            if (value == null || !value.contains("yes")) MainActivity.super.onBackPressed();
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && ReadingWidget.ACTION_RESUME.equals(intent.getAction())) {
            js("window.ficNookOpenLast && window.ficNookOpenLast()");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Look for a new version again if the app has been open a while (it used to check only at start).
        if (checkedUpdate && updateUrl == null && System.currentTimeMillis() - lastUpdateCheck > 20 * 60 * 1000) checkForUpdate();
        // Pick up fics that were downloaded while the app was in the background.
        web.evaluateJavascript("window.ficNookResume && window.ficNookResume()", null);
    }

    @Override
    protected void onPause() {
        // Make sure the reading spot is saved when the app goes to the background.
        web.evaluateJavascript("window.dispatchEvent(new Event('pagehide'))", null);
        super.onPause();
    }
}
