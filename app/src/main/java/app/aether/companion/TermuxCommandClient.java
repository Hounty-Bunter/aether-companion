package app.aether.companion;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class TermuxCommandClient {
    static final String TERMUX_PACKAGE = "com.termux";
    static final String RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND";

    private static final String RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService";
    private static final String ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND";
    private static final String EXTRA_PATH = "com.termux.RUN_COMMAND_PATH";
    private static final String EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS";
    private static final String EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR";
    private static final String EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND";
    private static final String EXTRA_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL";
    private static final String EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT";

    private static final String HOME = "/data/data/com.termux/files/home";
    private static final String PREFIX = "/data/data/com.termux/files/usr";
    private static final String BASH = PREFIX + "/bin/bash";
    private static final String AETHER = PREFIX + "/bin/aether";
    private static final String AETHER_INSTALLER_COMMIT =
            "ff74eebb77f9fde126d7dd6bc524874e7be7bed9";
    private static final String AETHER_INSTALLER_SHA256 =
            "337f45594fa33f20a0c83c8acd4551587dcae32c9e1531c551335063cf3974ad";
    private static final Set<String> TRUSTED_TERMUX_SIGNERS = new HashSet<>(Arrays.asList(
            "228FB2CFE90831C1499EC3CCAF61E96E8E1CE70766B9474672CE427334D41C42",
            "B6DA01480EEFD5FBF2CD3771B8D1021EC791304BDD6C4BF41D3FAABAD48EE5E1",
            "F7A038EB551F1BE8FDF388686B784ABAB4552A5D82DF423E3D8F1B5CBE1C69AE"
    ));

    private static final String START_WRAPPER =
            "umask 077; \"$@\" & p=$!; " +
            "printf '%s\\n' \"$p\" > \"$HOME/.aether-companion.pid\"; " +
            "trap 'kill -TERM \"$p\" 2>/dev/null' TERM INT; " +
            "wait \"$p\"; s=$?; rm -f \"$HOME/.aether-companion.pid\"; exit \"$s\"";

    private static final String STOP_WRAPPER =
            "f=\"$HOME/.aether-companion.pid\"; " +
            "if [ -r \"$f\" ]; then p=\"$(cat \"$f\")\"; " +
            "case \"$p\" in (*[!0-9]*|'') ;; (*) " +
            "c=\"$(tr '\\0' ' ' < \"/proc/$p/cmdline\" 2>/dev/null)\"; " +
            "case \"$c\" in (*aether*) kill -TERM \"$p\" 2>/dev/null ;; esac ;; esac; " +
            "rm -f \"$f\"; fi";

    private final Context context;

    TermuxCommandClient(Context context) {
        this.context = context.getApplicationContext();
    }

    boolean isInstalled() {
        try {
            context.getPackageManager().getPackageInfo(TERMUX_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException ignored) {
            return false;
        }
    }

    boolean hasPermission() {
        return context.checkSelfPermission(RUN_COMMAND_PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    boolean hasTrustedSignature() {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(
                    TERMUX_PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES);
            SigningInfo signing = info.signingInfo;
            if (signing == null) return false;
            Signature[] signatures = signing.hasMultipleSigners()
                    ? signing.getApkContentsSigners() : signing.getSigningCertificateHistory();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Signature signature : signatures) {
                StringBuilder value = new StringBuilder();
                for (byte part : digest.digest(signature.toByteArray())) {
                    value.append(String.format(Locale.US, "%02X", part));
                }
                if (TRUSTED_TERMUX_SIGNERS.contains(value.toString())) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    void startAether(String[] aetherArguments) {
        List<String> arguments = new ArrayList<>();
        arguments.add("-lc");
        arguments.add(START_WRAPPER);
        arguments.add("aether-companion");
        arguments.add(AETHER);
        arguments.addAll(Arrays.asList(aetherArguments));
        sendBackground(BASH, arguments.toArray(new String[0]), "Aether connection");
    }

    void stopAether() {
        sendBackground(BASH, new String[]{"-lc", STOP_WRAPPER}, "Stop Aether");
    }

    void checkAetherInstallation(PendingIntent result) {
        String check = "if [ ! -x '" + AETHER + "' ]; then echo AETHER_MISSING; exit 20; fi; " +
                "echo AETHER_READY";
        sendBackground(BASH, new String[]{"-lc", check}, "Check Aether", result);
    }

    void installDependencies(PendingIntent result) {
        String script = "set -e; echo INSTALLING_DEPENDENCIES; pkg update -y; " +
                "pkg install -y curl ca-certificates coreutils tar grep sed; echo DEPENDENCIES_READY";
        sendBackground(BASH, new String[]{"-lc", script}, "Install Aether dependencies", result);
    }

    void installAether(PendingIntent result) {
        String url = "https://raw.githubusercontent.com/CluvexStudio/Aether/" +
                AETHER_INSTALLER_COMMIT + "/aether.sh";
        String script = "set -e; d=\"$HOME/.cache/aether-companion\"; mkdir -p \"$d\"; " +
                "if [ -x '" + AETHER + "' ]; then echo AETHER_PRESERVED; exit 0; fi; " +
                "echo DOWNLOADING_AETHER_INSTALLER; curl -fsSL '" + url + "' -o \"$d/aether.sh\"; " +
                "printf '%s  %s\\n' '" + AETHER_INSTALLER_SHA256 + "' \"$d/aether.sh\" | sha256sum -c -; " +
                "chmod 700 \"$d/aether.sh\"; echo INSTALLING_AETHER; \"$d/aether.sh\" install; " +
                "test -x '" + AETHER + "'; echo AETHER_READY";
        sendBackground(BASH, new String[]{"-lc", script}, "Install Aether", result);
    }

    Intent launchIntent() {
        return context.getPackageManager().getLaunchIntentForPackage(TERMUX_PACKAGE);
    }

    private void sendBackground(String path, String[] arguments, String label) {
        sendBackground(path, arguments, label, null);
    }

    private void sendBackground(String path, String[] arguments, String label, PendingIntent result) {
        Intent intent = new Intent();
        intent.setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE);
        intent.setAction(ACTION_RUN_COMMAND);
        intent.putExtra(EXTRA_PATH, path);
        intent.putExtra(EXTRA_ARGUMENTS, arguments);
        intent.putExtra(EXTRA_WORKDIR, HOME);
        intent.putExtra(EXTRA_BACKGROUND, true);
        intent.putExtra(EXTRA_LABEL, label);
        if (result != null) intent.putExtra(EXTRA_PENDING_INTENT, result);
        context.startService(intent);
    }
}
