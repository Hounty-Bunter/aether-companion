package app.aether.companion;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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

    private static final String HOME = "/data/data/com.termux/files/home";
    private static final String PREFIX = "/data/data/com.termux/files/usr";
    private static final String BASH = PREFIX + "/bin/bash";
    private static final String AETHER = PREFIX + "/bin/aether";

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

    Intent launchIntent() {
        return context.getPackageManager().getLaunchIntentForPackage(TERMUX_PACKAGE);
    }

    private void sendBackground(String path, String[] arguments, String label) {
        Intent intent = new Intent();
        intent.setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE);
        intent.setAction(ACTION_RUN_COMMAND);
        intent.putExtra(EXTRA_PATH, path);
        intent.putExtra(EXTRA_ARGUMENTS, arguments);
        intent.putExtra(EXTRA_WORKDIR, HOME);
        intent.putExtra(EXTRA_BACKGROUND, true);
        intent.putExtra(EXTRA_LABEL, label);
        context.startService(intent);
    }
}
