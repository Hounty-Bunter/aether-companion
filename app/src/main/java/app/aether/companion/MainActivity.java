package app.aether.companion;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
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

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MainActivity extends Activity {
    private static final int REQUEST_TERMUX_PERMISSION = 41;
    private static final String SETUP_COMMAND =
            "mkdir -p ~/.termux && grep -qxF 'allow-external-apps=true' ~/.termux/termux.properties 2>/dev/null || " +
            "echo 'allow-external-apps=true' >> ~/.termux/termux.properties; " +
            "termux-reload-settings 2>/dev/null || true; " +
            "curl -fsSL https://raw.githubusercontent.com/CluvexStudio/Aether/main/aether.sh -o ~/aether.sh && " +
            "chmod +x ~/aether.sh && ~/aether.sh install";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService probeExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean probeInFlight = new AtomicBoolean(false);

    private SharedPreferences preferences;
    private TermuxCommandClient termux;
    private boolean resumed;
    private UiState state = UiState.OFF;

    private View statusDot;
    private TextView statusTitle;
    private TextView statusDetail;
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

    private final Runnable probeLoop = new Runnable() {
        @Override public void run() {
            probeOnce();
            if (resumed) handler.postDelayed(this, 1800);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        preferences = getSharedPreferences("aether", MODE_PRIVATE);
        termux = new TermuxCommandClient(this);
        bindViews();
        restoreSettings();
        wireActions();
        refreshSetupCard();
        render(UiState.OFF);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        refreshSetupCard();
        handler.removeCallbacks(probeLoop);
        handler.post(probeLoop);
    }

    @Override
    protected void onPause() {
        resumed = false;
        handler.removeCallbacks(probeLoop);
        saveSettings(false);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        probeExecutor.shutdownNow();
        super.onDestroy();
    }

    private void bindViews() {
        statusDot = findViewById(R.id.statusDot);
        statusTitle = findViewById(R.id.statusTitle);
        statusDetail = findViewById(R.id.statusDetail);
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
            if (state == UiState.ON || state == UiState.STARTING) stopAether(); else startAether();
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

    private void startAether() {
        if (!termux.isInstalled()) {
            toast(R.string.termux_missing);
            setupCard.setVisibility(View.VISIBLE);
            return;
        }
        if (!termux.hasPermission()) {
            requestTermuxPermission();
            return;
        }
        AetherSettings settings = saveSettings(true);
        if (settings == null) return;
        try {
            termux.startAether(settings.commandArguments());
            render(UiState.STARTING);
        } catch (Exception error) {
            render(UiState.OFF);
            Toast.makeText(this, getString(R.string.start_failed, readable(error)), Toast.LENGTH_LONG).show();
        }
    }

    private void stopAether() {
        try {
            termux.stopAether();
            render(UiState.STOPPING);
            toast(R.string.stop_sent);
        } catch (Exception error) {
            Toast.makeText(this, getString(R.string.start_failed, readable(error)), Toast.LENGTH_LONG).show();
        }
    }

    private void probeOnce() {
        int port = parsePort(false);
        if (port < 0 || !probeInFlight.compareAndSet(false, true)) return;
        probeExecutor.execute(() -> {
            boolean listening = SocksProbe.isListening(port);
            probeInFlight.set(false);
            handler.post(() -> {
                if (!resumed) return;
                if (listening) render(UiState.ON);
                else if (state == UiState.ON || state == UiState.STOPPING) render(UiState.OFF);
            });
        });
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

    private void updateConditionalSettings() {
        boolean masque = protocolSpinner.getSelectedItemPosition() == 0;
        masqueOptions.setVisibility(masque ? View.VISIBLE : View.GONE);
        fragmentCheck.setEnabled(carrierSpinner.getSelectedItemPosition() == 1);
        if (!fragmentCheck.isEnabled()) fragmentCheck.setChecked(false);
    }

    private void refreshSetupCard() {
        boolean setupAcknowledged = preferences.getBoolean("setupAcknowledged", false);
        boolean ready = termux.isInstalled() && termux.hasPermission() && setupAcknowledged;
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
        requestPermissions(new String[]{TermuxCommandClient.RUN_COMMAND_PERMISSION}, REQUEST_TERMUX_PERMISSION);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_TERMUX_PERMISSION) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            refreshSetupCard();
        } else {
            toast(R.string.permission_missing);
            Intent settingsIntent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(settingsIntent);
        }
    }

    private void render(UiState newState) {
        state = newState;
        int port = parsePort(false);
        if (port < 0) port = 1819;
        switch (newState) {
            case ON:
                statusDot.setBackgroundResource(R.drawable.status_dot_on);
                statusTitle.setText(R.string.status_on);
                statusDetail.setText(getString(R.string.status_on_detail, port));
                primaryButton.setText(R.string.stop);
                primaryButton.setEnabled(true);
                setSettingsEnabled(false);
                break;
            case STARTING:
                statusDot.setBackgroundResource(R.drawable.status_dot_busy);
                statusTitle.setText(R.string.status_starting);
                statusDetail.setText(R.string.status_starting_detail);
                primaryButton.setText(R.string.stop);
                primaryButton.setEnabled(true);
                setSettingsEnabled(false);
                break;
            case STOPPING:
                statusDot.setBackgroundResource(R.drawable.status_dot_busy);
                statusTitle.setText(R.string.status_stopping);
                statusDetail.setText(R.string.status_off_detail);
                primaryButton.setText(R.string.stop);
                primaryButton.setEnabled(false);
                setSettingsEnabled(false);
                break;
            default:
                statusDot.setBackgroundResource(R.drawable.status_dot_off);
                statusTitle.setText(R.string.status_off);
                statusDetail.setText(R.string.status_off_detail);
                primaryButton.setText(R.string.start);
                primaryButton.setEnabled(true);
                setSettingsEnabled(true);
        }
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

    private void toast(int stringId) {
        Toast.makeText(this, stringId, Toast.LENGTH_LONG).show();
    }

    private static String readable(Exception error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? error.getClass().getSimpleName() : message;
    }

    private enum UiState { OFF, STARTING, ON, STOPPING }

    private abstract static class SimpleSelectionListener implements AdapterView.OnItemSelectedListener {
        abstract void selected();
        @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { selected(); }
        @Override public void onNothingSelected(AdapterView<?> parent) {}
    }
}
