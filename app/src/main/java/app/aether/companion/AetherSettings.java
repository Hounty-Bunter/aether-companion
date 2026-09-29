package app.aether.companion;

import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

final class AetherSettings {
    static final String[] PROTOCOLS = {"masque", "wg", "gool"};
    static final String[] SCANS = {"balanced", "turbo", "thorough", "stealth", "ironclad"};
    static final String[] IPS = {"4", "6", "dual"};

    int protocolIndex;
    int scanIndex;
    int ipIndex;
    int carrierIndex;
    int camouflageIndex;
    boolean fragment;
    boolean quickReconnect;
    int port;

    static AetherSettings load(SharedPreferences preferences) {
        AetherSettings value = new AetherSettings();
        value.protocolIndex = bounded(preferences.getInt("protocol", 0), PROTOCOLS.length);
        value.scanIndex = bounded(preferences.getInt("scan", 0), SCANS.length);
        value.ipIndex = bounded(preferences.getInt("ip", 0), IPS.length);
        value.carrierIndex = bounded(preferences.getInt("carrier", 0), 2);
        value.camouflageIndex = bounded(preferences.getInt("camouflage", 0), 4);
        value.fragment = preferences.getBoolean("fragment", false);
        value.quickReconnect = preferences.getBoolean("quickReconnect", true);
        value.port = preferences.getInt("port", 1819);
        return value;
    }

    void save(SharedPreferences preferences) {
        preferences.edit()
                .putInt("protocol", protocolIndex)
                .putInt("scan", scanIndex)
                .putInt("ip", ipIndex)
                .putInt("carrier", carrierIndex)
                .putInt("camouflage", camouflageIndex)
                .putBoolean("fragment", fragment)
                .putBoolean("quickReconnect", quickReconnect)
                .putInt("port", port)
                .apply();
    }

    String[] commandArguments() {
        List<String> args = new ArrayList<>();
        args.add("--" + PROTOCOLS[protocolIndex]);
        args.add("--scan");
        args.add(SCANS[scanIndex]);
        args.add(ipFlag());
        args.add("--noize");
        args.add(noizeProfile());
        args.add("--bind");
        args.add("127.0.0.1:" + port);
        args.add(quickReconnect ? "--quick-reconnect" : "--no-quick-reconnect");
        if (protocolIndex == 0 && carrierIndex == 1) {
            args.add("--h2");
            if (fragment) args.add("--fragment");
        } else if (protocolIndex == 0) {
            args.add("--h3");
        }
        return args.toArray(new String[0]);
    }

    private String ipFlag() {
        if (ipIndex == 0) return "-4";
        if (ipIndex == 1) return "-6";
        return "--dual";
    }

    private String noizeProfile() {
        if (protocolIndex == 0) {
            String[] masqueProfiles = {"firewall", "gfw", "off", "off"};
            return masqueProfiles[camouflageIndex];
        }
        String[] wireGuardProfiles = {"balanced", "aggressive", "light", "off"};
        return wireGuardProfiles[camouflageIndex];
    }

    private static int bounded(int value, int size) {
        return value >= 0 && value < size ? value : 0;
    }
}
