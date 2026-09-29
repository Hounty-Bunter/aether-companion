package app.aether.companion;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int REQUEST_TERMUX_PERMISSION = 41;
    private static final int REQUEST_NOTIFICATION_PERMISSION = 42;
    private static final int REQUEST_VPN_PERMISSION = 43;
    private static final String SETUP_COMMAND =
            "mkdir -p ~/.termux && grep -qxF 'allow-external-apps=true' ~/.termux/termux.properties 2>/dev/null || " +
            "echo 'allow-external-apps=true' >> ~/.termux/termux.properties; " +
            "termux-reload-settings 2>/dev/null || true; " +
            "curl -fsSL https://raw.githubusercontent.com/CluvexStudio/Aether/main/aether.sh -o ~/aether.sh && " +
            "chmod +x ~/aether.sh && ~/aether.sh install";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences preferences;
    private TermuxCommandClient termux;
    private boolean resumed;

    private View statusDot;
    private TextView statusTitle;
    private TextView statusDetail;
    private TextView exitIpValue;
    private TextView durationValue;
    private TextView logsValue;
    private Button primaryButton;
    private LinearLayout setupCard;
    private LinearLayout settingsCard;
    private LinearLayout masqueOptions;
    private Spinner protocolSpinner;
    private Spinner scanSpinner;
    private Spinner ipSpinner;
    private Spinner carrierSpinner;
    private Spinner camouflageSpinner;
    private CheckBox fragmentCheck;
    private CheckBox quickReconnectCheck;
    private EditText portInput;

    private final Runnable renderLoop = new Runnable() {
        @Override public void run() {
            render(VpnStateStore.read(MainActivity.this));
            if (resumed) handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        preferences = getSharedPreferences(VpnStateStore.PREFS, MODE_PRIVATE);
        termux = new TermuxCommandClient(this);
        bindViews();
        restoreSettings();
        wireActions();
        refreshSetupCard();
        render(VpnStateStore.read(this));
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        refreshSetupCard();
        handler.removeCallbacks(renderLoop);
        handler.post(renderLoop);
    }

    @Override
    protected void onPause() {
        resumed = false;
        handler.removeCallbacks(renderLoop);
        saveSettings(false);
        super.onPause();
    }

    private void bindViews() {
        statusDot = findViewById(R.id.statusDot);
        statusTitle = findViewById(R.id.statusTitle);
        statusDetail = findViewById(R.id.statusDetail);
        exitIpValue = findViewById(R.id.exitIpValue);
        durationValue = findViewById(R.id.durationValue);
        logsValue = findViewById(R.id.logsValue);
        primaryButton = findViewById(R.id.primaryButton);
        setupCard = findViewById(R.id.setupCard);
        settingsCard = findViewById(R.id.settingsCard);
        masqueOptions = findViewById(R.id.masqueOptions);
        protocolSpinner = findViewById(R.id.protocolSpinner);
        scanSpinner = findViewById(R.id.scanSpinner);
        ipSpinner = findViewById(R.id.ipSpinner);
        carrierSpinner = findViewById(R.id.carrierSpinner);
        camouflageSpinner = findViewById(R.id.camouflageSpinner);
        fragmentCheck = findViewById(R.id.fragmentCheck);
        quickReconnectCheck = findViewById(R.id.quickReconnectCheck);
        portInput = findViewById(R.id.portInput);
    }

    private void restoreSettings() {
        AetherSettings settings = AetherSettings.load(preferences);
        protocolSpinner.setSelection(settings.protocolIndex);
        scanSpinner.setSelection(settings.scanIndex);
        ipSpinner.setSelection(settings.ipIndex);
        carrierSpinner.setSelection(settings.carrierIndex);
        camouflageSpinner.setSelection(settings.camouflageIndex);
        fragmentCheck.setChecked(settings.fragment);
        quickReconnectCheck.setChecked(settings.quickReconnect);
        portInput.setText(String.valueOf(settings.port));
        updateConditionalSettings();
    }

    private void wireActions() {
        primaryButton.setOnClickListener(view -> {
            VpnStateStore.Snapshot state = VpnStateStore.read(this);
            if (VpnStateStore.CONNECTED.equals(state.state) ||
                    VpnStateStore.CONNECTING.equals(state.state)) disconnect();
            else beginConnect();
        });
        findViewById(R.id.copySetupButton).setOnClickListener(view -> copySetupCommand());
        findViewById(R.id.openTermuxButton).setOnClickListener(view -> openTermux());
        findViewById(R.id.permissionButton).setOnClickListener(view -> requestTermuxPermission());

        protocolSpinner.setOnItemSelectedListener(new SimpleSelectionListener() {
            @Override public void selected() { updateConditionalSettings(); saveSettings(false); }
        });
        carrierSpinner.setOnItemSelectedListener(new SimpleSelectionListener() {
            @Override public void selected() { updateConditionalSettings(); saveSettings(false); }
        });
    }

    private void beginConnect() {
        if (!termux.isInstalled()) {
            toast(R.string.termux_missing);
            setupCard.setVisibility(View.VISIBLE);
            return;
        }
        if (!termux.hasPermission()) {
            requestTermuxPermission();
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_NOTIFICATION_PERMISSION);
            return;
        }
        requestVpnPermission();
    }

    private void requestVpnPermission() {
        if (saveSettings(true) == null) return;
        Intent permission = VpnService.prepare(this);
        if (permission == null) startVpn();
        else startActivityForResult(permission, REQUEST_VPN_PERMISSION);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_VPN_PERMISSION) {
            if (resultCode == RESULT_OK) startVpn();
            else toast(R.string.vpn_permission_missing);
        }
    }

    private void startVpn() {
        Intent intent = new Intent(this, AetherVpnService.class).setAction(AetherVpnService.ACTION_CONNECT);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
        else startService(intent);
    }

    private void disconnect() {
        VpnStateStore.desired(this, false);
        Intent intent = new Intent(this, AetherVpnService.class).setAction(AetherVpnService.ACTION_DISCONNECT);
        startService(intent);
    }

    private AetherSettings saveSettings(boolean showErrors) {
        int port = parsePort(showErrors);
        if (port < 0) return null;
        AetherSettings settings = new AetherSettings();
        settings.protocolIndex = protocolSpinner.getSelectedItemPosition();
        settings.scanIndex = scanSpinner.getSelectedItemPosition();
        settings.ipIndex = ipSpinner.getSelectedItemPosition();
        settings.carrierIndex = carrierSpinner.getSelectedItemPosition();
        settings.camouflageIndex = camouflageSpinner.getSelectedItemPosition();
        settings.fragment = fragmentCheck.isChecked();
        settings.quickReconnect = quickReconnectCheck.isChecked();
        settings.port = port;
        settings.save(preferences);
        return settings;
    }

    private int parsePort(boolean showErrors) {
        try {
            int port = Integer.parseInt(portInput.getText().toString().trim());
            if (port >= 1024 && port <= 65535) return port;
        } catch (NumberFormatException ignored) {}
        if (showErrors) {
            portInput.setError(getString(R.string.invalid_port));
            portInput.requestFocus();
        }
        return -1;
    }

    private void render(VpnStateStore.Snapshot snapshot) {
        statusDetail.setText(snapshot.detail.isEmpty() ? getString(R.string.status_off_detail) : snapshot.detail);
        exitIpValue.setText(snapshot.exitIp);
        durationValue.setText(formatDuration(snapshot.connectedAt));
        logsValue.setText(snapshot.logs.isEmpty() ? getString(R.string.logs_empty) : snapshot.logs);

        boolean active = VpnStateStore.CONNECTED.equals(snapshot.state) ||
                VpnStateStore.CONNECTING.equals(snapshot.state);
        setSettingsEnabled(!active);
        primaryButton.setEnabled(true);
        primaryButton.setText(active ? R.string.disconnect : R.string.connect);

        if (VpnStateStore.CONNECTED.equals(snapshot.state)) {
            statusDot.setBackgroundResource(R.drawable.status_dot_on);
            statusTitle.setText(R.string.status_on);
        } else if (VpnStateStore.CONNECTING.equals(snapshot.state)) {
            statusDot.setBackgroundResource(R.drawable.status_dot_busy);
            statusTitle.setText(R.string.status_starting);
        } else if (VpnStateStore.ERROR.equals(snapshot.state)) {
            statusDot.setBackgroundResource(R.drawable.status_dot_error);
            statusTitle.setText(R.string.status_error);
        } else {
            statusDot.setBackgroundResource(R.drawable.status_dot_off);
            statusTitle.setText(R.string.status_off);
        }
    }

    private String formatDuration(long connectedAt) {
        if (connectedAt <= 0) return "00:00:00";
        long seconds = Math.max(0, (System.currentTimeMillis() - connectedAt) / 1000);
        return String.format(Locale.US, "%02d:%02d:%02d",
                seconds / 3600, (seconds % 3600) / 60, seconds % 60);
    }

    private void updateConditionalSettings() {
        int protocol = protocolSpinner.getSelectedItemPosition();
        boolean masque = protocol == 0 || protocol == 3;
        masqueOptions.setVisibility(masque ? View.VISIBLE : View.GONE);
        fragmentCheck.setEnabled(carrierSpinner.getSelectedItemPosition() == 1);
        if (!fragmentCheck.isEnabled()) fragmentCheck.setChecked(false);
    }

    private void setSettingsEnabled(boolean enabled) {
        settingsCard.setAlpha(enabled ? 1f : 0.55f);
        setChildrenEnabled(settingsCard, enabled);
        if (enabled) updateConditionalSettings();
    }

    private static void setChildrenEnabled(android.view.ViewGroup group, boolean enabled) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            child.setEnabled(enabled);
            if (child instanceof android.view.ViewGroup) {
                setChildrenEnabled((android.view.ViewGroup) child, enabled);
            }
        }
    }

    private void refreshSetupCard() {
        boolean acknowledged = preferences.getBoolean("setupAcknowledged", false);
        boolean ready = termux.isInstalled() && termux.hasPermission() && acknowledged;
        setupCard.setVisibility(ready ? View.GONE : View.VISIBLE);
    }

    private void copySetupCommand() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("Aether setup", SETUP_COMMAND));
        preferences.edit().putBoolean("setupAcknowledged", true).apply();
        toast(R.string.copied);
        refreshSetupCard();
    }

    private void openTermux() {
        Intent launch = termux.launchIntent();
        if (launch == null) {
            toast(R.string.termux_missing);
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://f-droid.org/packages/com.termux/")));
            return;
        }
        startActivity(launch);
    }

    private void requestTermuxPermission() {
        if (!termux.isInstalled()) {
            toast(R.string.termux_missing);
            return;
        }
        if (termux.hasPermission()) {
            refreshSetupCard();
            return;
        }
        requestPermissions(new String[]{TermuxCommandClient.RUN_COMMAND_PERMISSION},
                REQUEST_TERMUX_PERMISSION);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        if (requestCode == REQUEST_NOTIFICATION_PERMISSION) {
            if (granted) requestVpnPermission();
            else toast(R.string.notification_permission_missing);
        } else if (requestCode == REQUEST_TERMUX_PERMISSION) {
            if (granted) {
                refreshSetupCard();
                beginConnect();
            } else {
                toast(R.string.permission_missing);
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
            }
        }
    }

    private void toast(int stringId) {
        Toast.makeText(this, stringId, Toast.LENGTH_LONG).show();
    }

    private abstract static class SimpleSelectionListener implements AdapterView.OnItemSelectedListener {
        abstract void selected();
        @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { selected(); }
        @Override public void onNothingSelected(AdapterView<?> parent) {}
    }
}
