package app.aether.companion;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class VpnStateStore {
    static final String PREFS = "aether";
    static final String DISCONNECTED = "disconnected";
    static final String CONNECTING = "connecting";
    static final String CONNECTED = "connected";
    static final String ERROR = "error";

    private static final int MAX_LOG_CHARS = 6000;

    private VpnStateStore() {}

    static Snapshot read(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String state = prefs.getString("vpnState", DISCONNECTED);
        long heartbeat = prefs.getLong("vpnHeartbeat", 0L);
        if (CONNECTED.equals(state) && heartbeat > 0L &&
                System.currentTimeMillis() - heartbeat > 15_000L) {
            String message = "VPN service is no longer responding";
            prefs.edit()
                    .putString("vpnState", ERROR)
                    .putString("vpnDetail", message)
                    .putString("vpnExitIp", "—")
                    .putLong("vpnConnectedAt", 0L)
                    .putBoolean("vpnDesired", false)
                    .apply();
            state = ERROR;
        }
        return new Snapshot(
                state,
                prefs.getString("vpnDetail", ""),
                prefs.getString("vpnExitIp", "—"),
                prefs.getLong("vpnConnectedAt", 0L),
                prefs.getString("vpnLogs", "")
        );
    }

    static void set(Context context, String state, String detail) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit()
                .putString("vpnState", state)
                .putString("vpnDetail", detail);
        if (!CONNECTED.equals(state)) {
            editor.putLong("vpnConnectedAt", 0L);
            editor.putLong("vpnHeartbeat", 0L);
            editor.putString("vpnExitIp", "—");
        }
        editor.apply();
    }

    static void connected(Context context, String exitIp) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("vpnState", CONNECTED)
                .putString("vpnDetail", "Device traffic is routed through Aether")
                .putString("vpnExitIp", exitIp == null || exitIp.isEmpty() ? "Verified" : exitIp)
                .putLong("vpnConnectedAt", System.currentTimeMillis())
                .putLong("vpnHeartbeat", System.currentTimeMillis())
                .apply();
    }

    static void heartbeat(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong("vpnHeartbeat", System.currentTimeMillis())
                .apply();
    }

    static void desired(Context context, boolean desired) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("vpnDesired", desired)
                .apply();
    }

    static boolean isDesired(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean("vpnDesired", false);
    }

    static synchronized void clearLogs(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("vpnLogs", "")
                .apply();
    }

    static synchronized void log(Context context, String message) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String old = prefs.getString("vpnLogs", "");
        String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        String updated = old + (old.isEmpty() ? "" : "\n") + time + "  " + message;
        if (updated.length() > MAX_LOG_CHARS) {
            updated = updated.substring(updated.length() - MAX_LOG_CHARS);
            int newline = updated.indexOf('\n');
            if (newline >= 0) updated = updated.substring(newline + 1);
        }
        prefs.edit().putString("vpnLogs", updated).apply();
    }

    static final class Snapshot {
        final String state;
        final String detail;
        final String exitIp;
        final long connectedAt;
        final String logs;

        Snapshot(String state, String detail, String exitIp, long connectedAt, String logs) {
            this.state = state;
            this.detail = detail;
            this.exitIp = exitIp;
            this.connectedAt = connectedAt;
            this.logs = logs;
        }
    }
}
