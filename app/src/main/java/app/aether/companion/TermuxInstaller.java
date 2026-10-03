package app.aether.companion;

import android.app.DownloadManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Environment;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.Locale;

final class TermuxInstaller {
    static final String DOWNLOAD_URL = "https://f-droid.org/repo/com.termux_1022.apk";
    static final String APK_SHA256 = "fdd476982cd74f2f00aac12d3683b1fa260a0b2d146411b94e09d773be3a7b56";
    static final String FDROID_SIGNER = "228FB2CFE90831C1499EC3CCAF61E96E8E1CE70766B9474672CE427334D41C42";
    static final String FILE_NAME = "com.termux_1022.apk";
    static final String ACTION_INSTALL_RESULT = "app.aether.companion.TERMUX_INSTALL_RESULT";

    private TermuxInstaller() {}

    static long enqueue(Context context) {
        File target = downloadedFile(context);
        if (target.exists()) target.delete();
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(DOWNLOAD_URL))
                .setTitle("Downloading trusted Termux")
                .setDescription("F-Droid Termux 0.119.0-beta.3")
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(true)
                .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, FILE_NAME);
        long id = context.getSystemService(DownloadManager.class).enqueue(request);
        prefs(context).edit()
                .putLong("termuxDownloadId", id)
                .putString("termuxInstallStatus", "downloading")
                .remove("termuxInstallError")
                .apply();
        return id;
    }

    static DownloadStatus query(Context context) {
        long id = prefs(context).getLong("termuxDownloadId", -1L);
        if (id < 0) return new DownloadStatus(0, 0, "");
        try (android.database.Cursor cursor = context.getSystemService(DownloadManager.class)
                .query(new DownloadManager.Query().setFilterById(id))) {
            if (cursor == null || !cursor.moveToFirst()) return new DownloadStatus(0, 0, "");
            int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            long downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(
                    DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
            long total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
            int reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
            int percent = total > 0 ? (int) Math.min(100, downloaded * 100 / total) : 0;
            return new DownloadStatus(status, percent, status == DownloadManager.STATUS_FAILED
                    ? "Download failed (code " + reason + ")" : "");
        } catch (Exception error) {
            return new DownloadStatus(DownloadManager.STATUS_FAILED, 0, error.getMessage());
        }
    }

    static File downloadedFile(Context context) {
        File directory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (directory == null) directory = context.getFilesDir();
        return new File(directory, FILE_NAME);
    }

    static void verifyAndRequestInstall(Context context) throws Exception {
        File apk = downloadedFile(context);
        if (!apk.isFile()) throw new IllegalStateException("Downloaded Termux APK is missing");
        String fileDigest;
        try (InputStream input = new FileInputStream(apk)) {
            fileDigest = sha256(input);
        }
        if (!APK_SHA256.equalsIgnoreCase(fileDigest)) {
            apk.delete();
            throw new SecurityException("Termux APK checksum verification failed");
        }

        PackageInfo archive = context.getPackageManager().getPackageArchiveInfo(
                apk.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
        if (archive == null || !TermuxCommandClient.TERMUX_PACKAGE.equals(archive.packageName)) {
            throw new SecurityException("Downloaded APK is not the expected Termux package");
        }
        Signature[] signers = archive.signingInfo == null ? new Signature[0]
                : archive.signingInfo.getApkContentsSigners();
        boolean trusted = false;
        for (Signature signer : signers) {
            if (FDROID_SIGNER.equalsIgnoreCase(sha256(signer.toByteArray()))) trusted = true;
        }
        if (!trusted) throw new SecurityException("Termux APK has an unexpected signing certificate");

        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(TermuxCommandClient.TERMUX_PACKAGE);
        int sessionId = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(sessionId)) {
            try (InputStream input = new FileInputStream(apk);
                 OutputStream output = session.openWrite("Termux", 0, apk.length())) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
                session.fsync(output);
            }
            Intent callback = new Intent(context, TermuxInstallReceiver.class)
                    .setAction(ACTION_INSTALL_RESULT);
            PendingIntent pending = PendingIntent.getBroadcast(context, sessionId, callback,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            prefs(context).edit().putString("termuxInstallStatus", "installing").apply();
            session.commit(pending.getIntentSender());
        }
    }

    static void setError(Context context, String message) {
        prefs(context).edit()
                .putString("termuxInstallStatus", "error")
                .putString("termuxInstallError", message == null ? "Unknown installation error" : message)
                .apply();
    }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(VpnStateStore.PREFS, Context.MODE_PRIVATE);
    }

    private static String sha256(InputStream input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[64 * 1024];
        int count;
        while ((count = input.read(buffer)) >= 0) digest.update(buffer, 0, count);
        return toHex(digest.digest());
    }

    private static String sha256(byte[] value) throws Exception {
        return toHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private static String toHex(byte[] value) {
        StringBuilder result = new StringBuilder();
        for (byte part : value) result.append(String.format(Locale.US, "%02x", part));
        return result.toString();
    }

    static final class DownloadStatus {
        final int status;
        final int percent;
        final String error;

        DownloadStatus(int status, int percent, String error) {
            this.status = status;
            this.percent = percent;
            this.error = error == null ? "" : error;
        }
    }
}
