package app.aether.companion;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import hev.htproxy.TProxyService;

public final class AetherVpnService extends VpnService {
    static final String ACTION_CONNECT = "app.aether.companion.CONNECT";
    static final String ACTION_DISCONNECT = "app.aether.companion.DISCONNECT";
    private static final String ACTION_PREFLIGHT_RESULT = "app.aether.companion.PREFLIGHT_RESULT";

    private static final String CHANNEL_ID = "aether_vpn";
    private static final int NOTIFICATION_ID = 1819;
    private static final int MTU = 1500;
    private static final int SOCKS_WAIT_SECONDS = 180;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean transition = new AtomicBoolean(false);
    private ScheduledExecutorService monitor;
    private ParcelFileDescriptor tunFd;
    private TermuxCommandClient termux;
    private volatile int activePort = 1819;
    private volatile int failedHealthChecks;
    private volatile CountDownLatch preflightLatch;
    private volatile int preflightExitCode = Integer.MIN_VALUE;
    private volatile String preflightOutput = "";
    private volatile int healthTick;

    @Override
    public void onCreate() {
        super.onCreate();
        termux = new TermuxCommandClient(this);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_PREFLIGHT_RESULT.equals(action)) {
            acceptPreflightResult(intent);
            return START_STICKY;
        }
        if (ACTION_DISCONNECT.equals(action)) {
            startForegroundCompat(notification("Disconnecting", "Closing the VPN safely"));
            worker.execute(() -> disconnect(false, "Disconnected"));
            return START_NOT_STICKY;
        }

        if (ACTION_CONNECT.equals(action) && tunFd != null && TProxyService.TProxyIsRunning()) {
            startForegroundCompat(notification("Connected", "Aether VPN is active"));
            return START_STICKY;
        }

        startForegroundCompat(notification("Connecting", "Starting Aether"));
        if (ACTION_CONNECT.equals(action) || (intent == null && VpnStateStore.isDesired(this))) {
            VpnStateStore.desired(this, true);
            worker.execute(this::connect);
        }
        return START_STICKY;
    }

    private void connect() {
        if (!transition.compareAndSet(false, true)) return;
        try {
            if (tunFd != null && TProxyService.TProxyIsRunning()) return;
            VpnStateStore.clearLogs(this);
            updateConnecting("Checking required components");

            if (!termux.isInstalled()) throw new IllegalStateException("Termux is not installed");
            if (!termux.hasPermission()) throw new SecurityException("Termux command permission is missing");
            verifyAetherInstallation();

            AetherSettings settings = AetherSettings.load(
                    getSharedPreferences(VpnStateStore.PREFS, MODE_PRIVATE));
            activePort = settings.port;

            if (SocksProbe.isListening(activePort)) {
                VpnStateStore.log(this, "Clearing a previous companion-owned Aether session");
                termux.stopAether();
                sleep(1800);
                if (SocksProbe.isListening(activePort)) {
                    throw new IllegalStateException("SOCKS port " + activePort +
                            " is already used by a process this app does not own");
                }
            }

            updateConnecting("Starting Aether in Termux");
            termux.startAether(settings.commandArguments());
            waitForSocks();

            updateConnecting("Verifying encrypted internet access");
            SocksHttpsProbe.Result probe = SocksHttpsProbe.verify(activePort);
            VpnStateStore.log(this, "Aether exit verified: " + probe.ip +
                    (probe.warp.isEmpty() ? "" : " (warp=" + probe.warp + ")"));

            updateConnecting("Creating Android VPN tunnel");
            establishVpn();
            startTun2Socks();
            if (!TProxyService.TProxyIsRunning()) {
                throw new IllegalStateException("tun2socks stopped during startup");
            }

            VpnStateStore.connected(this, probe.ip);
            VpnStateStore.log(this, "VPN connected; IPv4, IPv6 and DNS routes are active");
            updateNotification("Connected", "Exit IP " + probe.ip);
            startHealthMonitor();
        } catch (Exception error) {
            if (!VpnStateStore.isDesired(this)) {
                cleanupTunnel();
                try { termux.stopAether(); } catch (Exception ignored) {}
                VpnStateStore.set(this, VpnStateStore.DISCONNECTED, "Disconnected");
                VpnStateStore.log(this, "Connection cancelled");
                stopForeground(true);
                stopSelf();
            } else {
                fail(error);
            }
        } finally {
            transition.set(false);
        }
    }

    private void waitForSocks() throws Exception {
        VpnStateStore.log(this, "Waiting for SOCKS5 on 127.0.0.1:" + activePort);
        for (int i = 0; i < SOCKS_WAIT_SECONDS; i++) {
            if (!VpnStateStore.isDesired(this)) throw new InterruptedException("Connection cancelled");
            if (SocksProbe.isListening(activePort)) {
                VpnStateStore.log(this, "Aether SOCKS5 listener is ready");
                return;
            }
            sleep(1000);
        }
        throw new IllegalStateException("Aether did not become ready within three minutes");
    }

    private void verifyAetherInstallation() throws Exception {
        updateConnecting("Checking the Aether installation");
        preflightExitCode = Integer.MIN_VALUE;
        preflightOutput = "";
        preflightLatch = new CountDownLatch(1);
        Intent result = new Intent(this, AetherVpnService.class).setAction(ACTION_PREFLIGHT_RESULT);
        PendingIntent pending = PendingIntent.getService(this, 91, result,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        termux.checkAetherInstallation(pending);
        if (!preflightLatch.await(15, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Termux did not answer. Run the first-time setup and use Termux 0.109 or newer");
        }
        if (preflightExitCode == 20 || preflightOutput.contains("AETHER_MISSING")) {
            throw new IllegalStateException("Aether is not installed in Termux; run the first-time setup");
        }
        if (preflightExitCode != 0) {
            throw new IllegalStateException("Termux could not verify Aether" +
                    (preflightOutput.isEmpty() ? "" : ": " + preflightOutput));
        }
        VpnStateStore.log(this, "Aether executable verified");
    }

    private void acceptPreflightResult(Intent intent) {
        Bundle result = intent.getBundleExtra("result");
        if (result != null) {
            preflightExitCode = result.getInt("exitCode", Integer.MIN_VALUE);
            String stdout = result.getString("stdout", "").trim();
            String stderr = result.getString("stderr", "").trim();
            String error = result.getString("errmsg", "").trim();
            preflightOutput = !stdout.isEmpty() ? stdout : (!stderr.isEmpty() ? stderr : error);
        }
        CountDownLatch latch = preflightLatch;
        if (latch != null) latch.countDown();
    }

    private void establishVpn() throws Exception {
        Builder builder = new Builder()
                .setSession("Aether Companion")
                .setMtu(MTU)
                .setBlocking(false)
                .addAddress("198.18.0.1", 30)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .addAddress("fd00:1:fd00:1::1", 64)
                .addRoute("::", 0)
                .addDnsServer("2606:4700:4700::1111");

        // The native bridge must reach localhost outside its own VPN, and every
        // Aether transport socket belongs to Termux's UID. Android only supports
        // per-application exclusions, not per-process exclusions inside Termux.
        builder.addDisallowedApplication(getPackageName());
        builder.addDisallowedApplication(TermuxCommandClient.TERMUX_PACKAGE);

        Intent configure = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, configure,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        builder.setConfigureIntent(pending);
        tunFd = builder.establish();
        if (tunFd == null) throw new IllegalStateException("Android refused to create the VPN interface");
        VpnStateStore.log(this, "Android TUN interface established");
    }

    private void startTun2Socks() throws Exception {
        File config = new File(getCacheDir(), "hev-socks5-tunnel.yml");
        String yaml = "tunnel:\n" +
                "  mtu: " + MTU + "\n" +
                "  ipv4: 198.18.0.1\n" +
                "  ipv6: 'fd00:1:fd00:1::1'\n" +
                "  icmp: 'reply'\n" +
                "socks5:\n" +
                "  address: 127.0.0.1\n" +
                "  port: " + activePort + "\n" +
                "  udp: 'udp'\n" +
                "misc:\n" +
                "  task-stack-size: 86016\n" +
                "  connect-timeout: 10000\n" +
                "  tcp-read-write-timeout: 300000\n" +
                "  udp-read-write-timeout: 60000\n" +
                "  log-file: null\n";
        try (FileOutputStream output = new FileOutputStream(config, false)) {
            output.write(yaml.getBytes(StandardCharsets.UTF_8));
        }
        if (!TProxyService.TProxyStartService(config.getAbsolutePath(), tunFd.getFd())) {
            throw new IllegalStateException("tun2socks could not start");
        }
        VpnStateStore.log(this, "hev-socks5-tunnel 2.18.0 started");
    }

    private void startHealthMonitor() {
        stopHealthMonitor();
        failedHealthChecks = 0;
        healthTick = 0;
        monitor = Executors.newSingleThreadScheduledExecutor();
        monitor.scheduleWithFixedDelay(() -> {
            boolean healthy = TProxyService.TProxyIsRunning() && SocksProbe.isListening(activePort);
            if (healthy && ++healthTick % 5 == 0) {
                try {
                    SocksHttpsProbe.Result result = SocksHttpsProbe.verify(activePort);
                    getSharedPreferences(VpnStateStore.PREFS, MODE_PRIVATE).edit()
                            .putString("vpnExitIp", result.ip)
                            .apply();
                } catch (Exception ignored) {
                    healthy = false;
                }
            }
            if (healthy) {
                failedHealthChecks = 0;
                VpnStateStore.heartbeat(this);
                return;
            }
            if (++failedHealthChecks >= 3) {
                VpnStateStore.log(this, "Health check failed three times; closing VPN");
                worker.execute(() -> disconnect(true, "Aether stopped unexpectedly"));
            }
        }, 3, 3, TimeUnit.SECONDS);
    }

    private void fail(Exception error) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) message = error.getClass().getSimpleName();
        VpnStateStore.log(this, "ERROR: " + message);
        VpnStateStore.set(this, VpnStateStore.ERROR, message);
        updateNotification("Connection error", message);
        cleanupTunnel();
        try { termux.stopAether(); } catch (Exception ignored) {}
        VpnStateStore.desired(this, false);
        stopForeground(true);
        stopSelf();
    }

    private void disconnect(boolean error, String detail) {
        if (!transition.compareAndSet(false, true)) {
            VpnStateStore.desired(this, false);
            return;
        }
        try {
            VpnStateStore.desired(this, false);
            VpnStateStore.log(this, "Disconnect requested");
            cleanupTunnel();
            try { termux.stopAether(); } catch (Exception ignored) {}
            VpnStateStore.set(this, error ? VpnStateStore.ERROR : VpnStateStore.DISCONNECTED, detail);
            VpnStateStore.log(this, error ? "Disconnected after an error" : "Disconnected cleanly");
        } finally {
            transition.set(false);
            stopForeground(true);
            stopSelf();
        }
    }

    private void cleanupTunnel() {
        stopHealthMonitor();
        try {
            if (TProxyService.TProxyIsRunning()) TProxyService.TProxyStopService();
        } catch (Throwable ignored) {}
        if (tunFd != null) {
            try { tunFd.close(); } catch (Exception ignored) {}
            tunFd = null;
        }
    }

    private void stopHealthMonitor() {
        if (monitor != null) {
            monitor.shutdownNow();
            monitor = null;
        }
    }

    private void updateConnecting(String detail) {
        VpnStateStore.set(this, VpnStateStore.CONNECTING, detail);
        VpnStateStore.log(this, detail);
        updateNotification("Connecting", detail);
    }

    private void updateNotification(String title, String detail) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.notify(NOTIFICATION_ID, notification(title, detail));
    }

    private Notification notification(String title, String detail) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent content = PendingIntent.getActivity(this, 1, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, AetherVpnService.class).setAction(ACTION_DISCONNECT);
        PendingIntent disconnect = PendingIntent.getService(this, 2, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(title)
                .setContentText(detail)
                .setContentIntent(content)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Disconnect", disconnect)
                .build();
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Aether VPN",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Shows the active Aether VPN connection");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private void startForegroundCompat(Notification notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private static void sleep(long millis) throws InterruptedException {
        Thread.sleep(millis);
    }

    @Override
    public void onRevoke() {
        worker.execute(() -> disconnect(false, "VPN permission was revoked"));
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        if (tunFd != null || TProxyService.TProxyIsRunning()) cleanupTunnel();
        try { termux.stopAether(); } catch (Exception ignored) {}
        worker.shutdownNow();
        super.onDestroy();
    }
}
