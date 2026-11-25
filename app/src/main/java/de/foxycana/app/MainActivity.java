package de.foxycana.app;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.provider.OpenableColumns;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Foxycana: WebView-Hülle für die App-Oberfläche (assets/www) plus native Helfer:
 *  - Texterkennung (ML Kit, offline) für den Kartenscanner
 *  - Netz-Proxy unter /net?u=..., damit Kartendaten und Preise ohne CORS-Probleme laden
 *  - Kamera-Freigabe, Dateiauswahl, Speichern in "Download/Foxycana", Ton und Vibration
 */
public class MainActivity extends Activity {

    private static final String HOST = "app.foxycana";
    private static final int REQ_CAMERA = 1;
    private static final int REQ_FILE = 2;
    private static final int REQ_PICK = 3;
    private String pickId;

    private WebView web;
    private TextRecognizer recognizer;
    private PermissionRequest pendingPermission;
    private ValueCallback<Uri[]> fileCallback;
    private ToneGenerator tone;
    private final ExecutorService pool = Executors.newFixedThreadPool(3);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        try { tone = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70); } catch (Exception ignored) { }

        web = new WebView(this);
        web.setBackgroundColor(0xFF0E1020);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setTextZoom(100);

        web.addJavascriptInterface(new Bridge(), "Native");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (!HOST.equals(u.getHost())) return null;
                String path = u.getPath();
                if ("/net".equals(path)) return proxy(u.getQueryParameter("u"));
                if (path == null || path.equals("/") || path.isEmpty()) path = "/index.html";
                try {
                    InputStream in = getAssets().open("www" + path);
                    WebResourceResponse r = new WebResourceResponse(mime(path), "utf-8", in);
                    Map<String, String> h = new HashMap<>();
                    h.put("Cache-Control", "no-cache");
                    r.setResponseHeaders(h);
                    return r;
                } catch (IOException e) {
                    return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found",
                            new HashMap<>(), new ByteArrayInputStream(new byte[0]));
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (HOST.equals(u.getHost())) return false;
                openExternal(u.toString());
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        request.grant(request.getResources());
                    } else {
                        pendingPermission = request;
                        requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
                    }
                });
            }

            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                try {
                    Intent i;
                    if (params.isCaptureEnabled()) { i = params.createIntent(); }
                    else {
                        i = new Intent(Intent.ACTION_GET_CONTENT);
                        i.addCategory(Intent.CATEGORY_OPENABLE);
                        i.setType("*/*");
                    }
                    startActivityForResult(i, REQ_FILE);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });

        web.loadUrl("https://" + HOST + "/index.html");
    }

    private static String mime(String path) {
        if (path.endsWith(".html")) return "text/html";
        if (path.endsWith(".js")) return "application/javascript";
        if (path.endsWith(".css")) return "text/css";
        if (path.endsWith(".json")) return "application/json";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }

    /** Lädt eine externe Adresse und reicht sie an die Web-Oberfläche weiter (folgt Weiterleitungen). */
    private WebResourceResponse proxy(String target) {
        Map<String, String> h = new HashMap<>();
        h.put("Access-Control-Allow-Origin", "*");
        h.put("Cache-Control", "no-store");
        try {
            if (target == null || !(target.startsWith("https://") || target.startsWith("http://")))
                throw new IOException("Ungültige Adresse");
            HttpURLConnection c = null;
            String url = target;
            for (int i = 0; i < 6; i++) {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(20000);
                c.setReadTimeout(90000);
                c.setRequestProperty("User-Agent", "Foxycana/1.0 (Android)");
                c.setRequestProperty("Accept", "*/*");
                int code = c.getResponseCode();
                if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
                    url = new URL(new URL(url), c.getHeaderField("Location")).toString();
                    c.disconnect();
                    continue;
                }
                break;
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in == null) in = new ByteArrayInputStream(new byte[0]);
            String ct = c.getContentType();
            String mime = ct == null ? "application/octet-stream" : ct.split(";")[0].trim();
            if (code >= 300 && code < 400) code = 502;
            return new WebResourceResponse(mime, "utf-8", code, code >= 400 ? "Error" : "OK", h, in);
        } catch (Exception e) {
            return new WebResourceResponse("text/plain", "utf-8", 502, "Bad Gateway", h,
                    new ByteArrayInputStream(String.valueOf(e).getBytes(StandardCharsets.UTF_8)));
        }
    }

    private void openExternal(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, "Kein Browser gefunden", Toast.LENGTH_SHORT).show();
        }
    }

    private void sendToJs(final String fn, final String id, final String payload) {
        runOnUiThread(() -> web.evaluateJavascript(
                "window." + fn + "&&window." + fn + "(" + JSONObject.quote(id) + "," + JSONObject.quote(payload) + ")", null));
    }

    private static String ocrJson(Text t, int w, int h) {
        try {
            JSONObject o = new JSONObject();
            o.put("w", w);
            o.put("h", h);
            JSONArray lines = new JSONArray();
            for (Text.TextBlock b : t.getTextBlocks()) {
                for (Text.Line l : b.getLines()) {
                    JSONObject j = new JSONObject();
                    j.put("t", l.getText());
                    Rect r = l.getBoundingBox();
                    if (r != null) {
                        j.put("x", r.left);
                        j.put("y", r.top);
                        j.put("w", r.width());
                        j.put("h", r.height());
                    }
                    lines.put(j);
                }
            }
            o.put("lines", lines);
            return o.toString();
        } catch (Exception e) {
            return "{\"error\":" + JSONObject.quote(String.valueOf(e)) + "}";
        }
    }

    /** Wird aus der Web-Oberfläche als window.Native aufgerufen. */
    private class Bridge {
        @JavascriptInterface
        public boolean isNative() { return true; }

        /* Datei auswählen und als Base64 an JS zurückgeben: window.__nativeFile(id, b64|null, name, fehler) */
        @JavascriptInterface
        public void pickFile(final String id) {
            runOnUiThread(() -> {
                pickId = id;
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                try { startActivityForResult(i, REQ_PICK); }
                catch (Exception e) {
                    try {
                        Intent g = new Intent(Intent.ACTION_GET_CONTENT);
                        g.addCategory(Intent.CATEGORY_OPENABLE);
                        g.setType("*/*");
                        startActivityForResult(g, REQ_PICK);
                    } catch (Exception e2) { fileResult(id, null, "", "Keine Dateiauswahl verfügbar"); }
                }
            });
        }

        @JavascriptInterface
        public String version() {
            try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
            catch (Exception e) { return "?"; }
        }

        /** Texterkennung auf einem JPEG (Data-URL). Ergebnis kommt über window.__ocrDone(id, json). */
        @JavascriptInterface
        public void ocr(final String id, final String dataUrl, final int rotation) {
            pool.execute(() -> {
                try {
                    String b64 = dataUrl.substring(dataUrl.indexOf(',') + 1);
                    byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
                    final Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    if (bmp == null) { sendToJs("__ocrDone", id, "{\"error\":\"Bild unlesbar\"}"); return; }
                    final int rot = ((rotation % 360) + 360) % 360;
                    final boolean swap = rot == 90 || rot == 270;
                    final int w = swap ? bmp.getHeight() : bmp.getWidth();
                    final int h = swap ? bmp.getWidth() : bmp.getHeight();
                    InputImage img = InputImage.fromBitmap(bmp, rot);
                    recognizer.process(img)
                            .addOnSuccessListener(t -> sendToJs("__ocrDone", id, ocrJson(t, w, h)))
                            .addOnFailureListener(e -> sendToJs("__ocrDone", id,
                                    "{\"error\":" + JSONObject.quote(String.valueOf(e)) + "}"));
                } catch (Throwable e) {
                    sendToJs("__ocrDone", id, "{\"error\":" + JSONObject.quote(String.valueOf(e)) + "}");
                }
            });
        }

        @JavascriptInterface
        public void vibrate(int ms) {
            try {
                Vibrator v = getSystemService(Vibrator.class);
                if (v != null) v.vibrate(VibrationEffect.createOneShot(Math.max(10, ms), VibrationEffect.DEFAULT_AMPLITUDE));
            } catch (Exception ignored) { }
        }

        @JavascriptInterface
        public void beep() {
            try { if (tone != null) tone.startTone(ToneGenerator.TONE_PROP_ACK, 120); } catch (Exception ignored) { }
        }

        @JavascriptInterface
        public void keepAwake(final boolean on) {
            runOnUiThread(() -> {
                if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
        }

        @JavascriptInterface
        public void openUrl(final String url) { runOnUiThread(() -> openExternal(url)); }

        @JavascriptInterface
        public void copyText(final String text) {
            runOnUiThread(() -> {
                ClipboardManager cm = getSystemService(ClipboardManager.class);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Foxycana", text));
            });
        }

        @JavascriptInterface
        public void toast(final String text) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, text, Toast.LENGTH_SHORT).show());
        }

        /** Wie saveFile, überschreibt aber eine vorhandene Datei gleichen Namens (für automatische Backups). */
        @JavascriptInterface
        public String saveFileReplace(String name, String mime, String content) {
            if (Build.VERSION.SDK_INT < 29) return saveFile(name, mime, content);
            try {
                byte[] data = content.getBytes(StandardCharsets.UTF_8);
                Uri found = null;
                String rel = Environment.DIRECTORY_DOWNLOADS + "/Foxycana/";
                try (android.database.Cursor cur = getContentResolver().query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        new String[]{MediaStore.Downloads._ID},
                        MediaStore.Downloads.DISPLAY_NAME + "=? AND " + MediaStore.Downloads.RELATIVE_PATH + "=?",
                        new String[]{name, rel}, null)) {
                    if (cur != null && cur.moveToFirst())
                        found = android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cur.getLong(0));
                }
                if (found != null) {
                    try (OutputStream os = getContentResolver().openOutputStream(found, "wt")) { os.write(data); }
                    return "Download/Foxycana/" + name;
                }
                return saveFile(name, mime, content);
            } catch (Exception e) {
                return saveFile(name, mime, content);
            }
        }

        /** Speichert Text als Datei in "Download/Foxycana". Gibt den Ort zurück oder "ERR:...". */
        @JavascriptInterface
        public String saveFile(String name, String mime, String content) {
            try {
                byte[] data = content.getBytes(StandardCharsets.UTF_8);
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
                    cv.put(MediaStore.Downloads.MIME_TYPE, mime);
                    cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Foxycana");
                    Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                    if (uri == null) return "ERR:Speichern nicht möglich";
                    try (OutputStream os = getContentResolver().openOutputStream(uri)) { os.write(data); }
                    return "Download/Foxycana/" + name;
                } else {
                    File dir = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "Foxycana");
                    dir.mkdirs();
                    File f = new File(dir, name);
                    try (FileOutputStream os = new FileOutputStream(f)) { os.write(data); }
                    return f.getAbsolutePath();
                }
            } catch (Exception e) {
                return "ERR:" + e.getMessage();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_CAMERA && pendingPermission != null) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED)
                pendingPermission.grant(pendingPermission.getResources());
            else pendingPermission.deny();
            pendingPermission = null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK) {
            final String id = pickId; pickId = null;
            final Uri uri = (resultCode == RESULT_OK && data != null) ? data.getData() : null;
            if (uri == null) { fileResult(id, null, "", ""); return; }
            pool.execute(() -> {
                String name = "";
                try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
                    if (c != null && c.moveToFirst()) {
                        int ix = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                        if (ix >= 0) name = c.getString(ix);
                    }
                } catch (Exception ignored) { }
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    if (in == null) throw new IOException("Datei nicht lesbar");
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[65536]; int n; long total = 0;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n); total += n;
                        if (total > 40L * 1024 * 1024) throw new IOException("Datei zu groß");
                    }
                    fileResult(id, Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP), name, "");
                } catch (Exception e) {
                    fileResult(id, null, name, e.getMessage() == null ? "Lesefehler" : e.getMessage());
                }
            });
            return;
        }
        if (requestCode == REQ_FILE && fileCallback != null) {
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            fileCallback = null;
        }
    }

    private void fileResult(String id, String b64, String name, String err) {
        final String js = "window.__nativeFile&&window.__nativeFile(" + JSONObject.quote(id == null ? "" : id) + ","
            + (b64 == null ? "null" : JSONObject.quote(b64)) + "," + JSONObject.quote(name == null ? "" : name) + ","
            + JSONObject.quote(err == null ? "" : err) + ")";
        runOnUiThread(() -> web.evaluateJavascript(js, null));
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.__androidBack?window.__androidBack():false", v -> {
            if (!"true".equals(v)) MainActivity.super.onBackPressed();
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        web.evaluateJavascript("window.__appPause&&window.__appPause()", null);
    }

    @Override
    protected void onDestroy() {
        try { recognizer.close(); } catch (Exception ignored) { }
        if (tone != null) tone.release();
        pool.shutdown();
        super.onDestroy();
    }
}
