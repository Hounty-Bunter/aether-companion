package app.aether.companion;

import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

public final class AetherSettingsTest {
    @Test
    public void masqueBuildsNonInteractiveCommand() {
        AetherSettings settings = base();
        assertArrayEquals(new String[]{
                "--masque", "--scan", "balanced", "-4", "--noize", "firewall",
                "--bind", "127.0.0.1:1819", "--quick-reconnect", "--h3"
        }, settings.commandArguments());
    }

    @Test
    public void wireGuardMapsStrongerCamouflage() {
        AetherSettings settings = base();
        settings.protocolIndex = 1;
        settings.camouflageIndex = 1;
        settings.quickReconnect = false;
        assertArrayEquals(new String[]{
                "--wg", "--scan", "balanced", "-4", "--noize", "aggressive",
                "--bind", "127.0.0.1:1819", "--no-quick-reconnect"
        }, settings.commandArguments());
    }

    @Test
    public void masqueHttp2CanFragment() {
        AetherSettings settings = base();
        settings.carrierIndex = 1;
        settings.fragment = true;
        assertArrayEquals(new String[]{
                "--masque", "--scan", "balanced", "-4", "--noize", "firewall",
                "--bind", "127.0.0.1:1819", "--quick-reconnect", "--h2", "--fragment"
        }, settings.commandArguments());
    }

    @Test
    public void doubleMasqueUsesMasqueCarrierOptions() {
        AetherSettings settings = base();
        settings.protocolIndex = 3;
        assertArrayEquals(new String[]{
                "--mim", "--scan", "balanced", "-4", "--noize", "firewall",
                "--bind", "127.0.0.1:1819", "--quick-reconnect", "--h3"
        }, settings.commandArguments());
    }

    private static AetherSettings base() {
        AetherSettings settings = new AetherSettings();
        settings.protocolIndex = 0;
        settings.scanIndex = 0;
        settings.ipIndex = 0;
        settings.carrierIndex = 0;
        settings.camouflageIndex = 0;
        settings.fragment = false;
        settings.quickReconnect = true;
        settings.port = 1819;
        return settings;
    }
}
