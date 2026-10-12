package dev.linuxlab.helper;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * New versions of this app, from the releases of its repository on GitHub. The latest release has
 * app.json: the version, the APK and its size and SHA-256. The APK is downloaded on request and handed
 * to the package installer of Android, which asks the person and takes it only when it is signed with
 * the key of the app installed now; a download from anyone else is refused there.
 */
public final class Updates {
    private static final String TAG = "LinuxLabHelper";
    static final String REPOSITORY = "SpazzRabbit/linuxlab-helper";
    private static final String LATEST = "https://github.com/" + REPOSITORY + "/releases/latest/download/app.json";
    private static final int TIMEOUT_MS = 20000;

    /** A release newer than this app. */
    static final class Release {
        int versionCode;
        String versionName;
        String url;
        long size;
        String sha256;
        String notes;
    }

    private Updates() {
    }

    /** The latest release when it is newer than this app (or as new, when @same is true), else null. Network: not on the main thread. */
    static Release check(Context context, boolean same) throws IOException {
        JSONObject json;
        try {
            json = new JSONObject(new String(fetch(LATEST, 65536), "UTF-8"));
            Release release = new Release();
            release.versionCode = json.getInt("versionCode");
            release.versionName = json.getString("versionName");
            String apk = json.getString("apk");
            if (apk.contains("/")) {
                throw new IOException("a strange file name: " + apk);
            }
            release.url = "https://github.com/" + REPOSITORY + "/releases/download/" + json.getString("tag") + "/" + apk;
            release.size = json.getLong("size");
            release.sha256 = json.getString("sha256").toLowerCase(Locale.ROOT);
            JSONObject notes = json.optJSONObject("notes");
            release.notes = notes == null ? "" : notes.optString(Locale.getDefault().getLanguage().equals("ru") ? "ru" : "en", "");
            int mine = mine(context);
            Log.i(TAG, "update: latest " + release.versionCode + ", this " + mine);
            return release.versionCode > mine || (same && release.versionCode == mine) ? release : null;
        } catch (JSONException e) {
            throw new IOException("app.json: " + e.getMessage());
        }
    }

    static int mine(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return (int) info.getLongVersionCode();
        } catch (PackageManager.NameNotFoundException e) {
            return Integer.MAX_VALUE;
        }
    }

    /** Downloads the APK of @release into the cache and checks it. Network: not on the main thread. */
    static File download(Context context, Release release) throws IOException {
        File file = new File(context.getCacheDir(), "update.apk");
        HttpURLConnection connection = open(release.url);
        MessageDigest digest = sha256();
        long got = 0;
        try (InputStream in = connection.getInputStream(); OutputStream out = new FileOutputStream(file)) {
            byte[] buffer = new byte[65536];
            for (int n; (n = in.read(buffer)) > 0; ) {
                got += n;
                if (got > release.size) {
                    throw new IOException("larger than said");
                }
                digest.update(buffer, 0, n);
                out.write(buffer, 0, n);
            }
        } finally {
            connection.disconnect();
        }
        if (got != release.size || !hex(digest.digest()).equals(release.sha256)) {
            throw new IOException("the download is not the APK of the release");
        }
        return file;
    }

    /** Hands @apk to the package installer of Android, which asks the person to confirm. */
    static void install(Context context, File apk) throws IOException {
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(context.getPackageName());
        params.setSize(apk.length());
        int id = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(id)) {
            try (InputStream in = new FileInputStream(apk); OutputStream out = session.openWrite("base.apk", 0, apk.length())) {
                byte[] buffer = new byte[65536];
                for (int n; (n = in.read(buffer)) > 0; ) {
                    out.write(buffer, 0, n);
                }
                session.fsync(out);
            }
            Intent status = new Intent(context, StatusReceiver.class);
            // mutable: the installer adds its answer to it
            PendingIntent pending = PendingIntent.getBroadcast(context, id, status,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            session.commit(pending.getIntentSender());
        }
    }

    /** The answers of the package installer: the confirmation to show, or how it ended. */
    public static final class StatusReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(confirm);
                }
            } else if (status != PackageInstaller.STATUS_SUCCESS) {
                String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                Log.w(TAG, "update: install ended with " + status + ", " + message);
                if (status != PackageInstaller.STATUS_FAILURE_ABORTED) {
                    Toast.makeText(context, context.getString(R.string.update_failed, String.valueOf(message)), Toast.LENGTH_LONG).show();
                }
            }
        }
    }

    private static byte[] fetch(String url, int limit) throws IOException {
        HttpURLConnection connection = open(url);
        try (InputStream in = connection.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int n; (n = in.read(buffer)) > 0; ) {
                out.write(buffer, 0, n);
                if (out.size() > limit) {
                    throw new IOException(url + " is too large");
                }
            }
            return out.toByteArray();
        } finally {
            connection.disconnect();
        }
    }

    /** GitHub answers a download with a redirect to another host, which HttpURLConnection follows. */
    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        connection.setRequestProperty("User-Agent", "linuxlab-helper");
        int code = connection.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK) {
            connection.disconnect();
            throw new IOException("HTTP " + code + " for " + url);
        }
        return connection;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder();
        for (byte b : bytes) {
            text.append(String.format(Locale.ROOT, "%02x", b));
        }
        return text.toString();
    }
}
