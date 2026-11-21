package com.termux.app.activities;

import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.termux.R;
import com.termux.app.activities.MirrorSettings.MirrorPreset;
import com.termux.app.activities.MirrorSettings.SyncResult;
import com.termux.shared.activity.media.AppCompatActivityUtils;
import com.termux.shared.theme.NightMode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Download-source (换下载源) settings.
 *
 * One global preset governs every distro and every AI agent install.
 * Switching presets applies immediately; the new preset's endpoints are
 * then probed in the background and the result is shown in the status
 * line only, never as a blocking dialog. The image registry override
 * (follow preset / DaoCloud / dockerproxy) is always selectable,
 * whatever preset is active.
 * Changing a preset never auto-syncs installed distros: the user is asked
 * (design decision), and "同步到已安装的发行版" re-applies on demand.
 */
public class MirrorSettingsActivity extends AppCompatActivity {

    private static final int[] PRESET_NAME_RES = {
            R.string.mirror_preset_official,
            R.string.mirror_preset_tuna,
            R.string.mirror_preset_ustc,
            R.string.mirror_preset_aliyun,
            R.string.mirror_preset_custom
    };

    private LinearLayout mPresetsContainer;
    private LinearLayout mRegistryContainer;
    private LinearLayout mCustomContainer;
    private TextView mCustomHeader;
    private TextView mStatusText;
    private TextView mVoidNote;
    private TextView mGithubProxyWarning;

    private MirrorPreset mPreset;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        AppCompatActivityUtils.setNightMode(this, NightMode.getAppNightMode().getName(), true);

        setContentView(R.layout.mirror_settings_layout);
        AppCompatActivityUtils.setToolbar(this, com.termux.shared.R.id.toolbar);
        AppCompatActivityUtils.setShowBackButtonInActionBar(this, true);

        mPreset = MirrorSettings.loadPreset(this);

        mPresetsContainer = findViewById(R.id.mirror_presets_container);
        mRegistryContainer = findViewById(R.id.mirror_registry_container);
        mCustomContainer = findViewById(R.id.mirror_custom_container);
        mCustomHeader = findViewById(R.id.mirror_custom_header);
        mStatusText = findViewById(R.id.mirror_status);
        mVoidNote = findViewById(R.id.mirror_void_note);
        mGithubProxyWarning = findViewById(R.id.mirror_github_proxy_warning);

        Button syncButton = findViewById(R.id.mirror_sync_button);
        syncButton.setOnClickListener(v -> syncToInstalled());
        Button testButton = findViewById(R.id.mirror_test_button);
        testButton.setOnClickListener(v -> testCurrentPreset());
        Button restoreButton = findViewById(R.id.mirror_restore_button);
        restoreButton.setOnClickListener(v -> showRestoreDialog());

        refreshUi();
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }

    // ---------- UI ----------

    private void refreshUi() {
        buildPresetRows();
        buildRegistryRows();
        buildCustomRows();
        boolean isCustom = MirrorSettings.PRESET_CUSTOM.equals(mPreset.id);
        mCustomHeader.setVisibility(isCustom ? View.VISIBLE : View.GONE);
        mCustomContainer.setVisibility(isCustom ? View.VISIBLE : View.GONE);
        // Void is only mirrored on TUNA (or a custom host); other presets
        // keep its official source.
        boolean voidMirrored = MirrorSettings.PRESET_TUNA.equals(mPreset.id) || isCustom;
        mVoidNote.setVisibility(voidMirrored ? View.GONE : View.VISIBLE);
        mGithubProxyWarning.setVisibility(isCustom ? View.VISIBLE : View.GONE);
    }

    private View inflateRow(LinearLayout parent) {
        return LayoutInflater.from(this).inflate(R.layout.mirror_preset_row, parent, false);
    }

    private void buildPresetRows() {
        mPresetsContainer.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (int i = 0; i < MirrorSettings.PRESET_IDS.size(); i++) {
            String id = MirrorSettings.PRESET_IDS.get(i);
            MirrorPreset preset = MirrorSettings.PRESET_CUSTOM.equals(id)
                    ? MirrorSettings.loadCustomPreset(this)
                    : MirrorSettings.presetForId(id);
            // The selected row shows the effective registry (override
            // included); other rows show the preset's default.
            if (id.equals(mPreset.id)) preset.registryOverride = mPreset.registryOverride;
            View row = inflater.inflate(R.layout.mirror_preset_row, mPresetsContainer, false);
            TextView title = row.findViewById(R.id.mirror_row_title);
            TextView subtitle = row.findViewById(R.id.mirror_row_subtitle);
            TextView check = row.findViewById(R.id.mirror_row_check);
            title.setText(PRESET_NAME_RES[i]);
            subtitle.setText(presetSubtitle(preset));
            check.setText(id.equals(mPreset.id) ? "✓" : "");
            row.setOnClickListener(v -> selectPreset(id));
            mPresetsContainer.addView(row);
        }
    }

    private String presetSubtitle(MirrorPreset preset) {
        String registry = MirrorSettings.effectiveRegistry(preset);
        if (registry.isEmpty()) registry = "Docker Hub";
        if (preset.distroHost.isEmpty()) return registry;
        return preset.distroHost + " · " + registry;
    }

    private void buildRegistryRows() {
        mRegistryContainer.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        String effective = MirrorSettings.effectiveRegistry(mPreset);
        if (effective.isEmpty()) effective = "Docker Hub";

        addRegistryRow(inflater, getString(R.string.mirror_registry_follow),
                getString(R.string.mirror_registry_follow_summary, effective),
                MirrorSettings.REGISTRY_FOLLOW.equals(mPreset.registryOverride),
                MirrorSettings.REGISTRY_FOLLOW);
        addRegistryRow(inflater, "DaoCloud", MirrorSettings.REGISTRY_DAOCLOUD,
                MirrorSettings.REGISTRY_DAOCLOUD.equals(mPreset.registryOverride),
                MirrorSettings.REGISTRY_DAOCLOUD);
        addRegistryRow(inflater, "dockerproxy", MirrorSettings.REGISTRY_DOCKERPROXY,
                MirrorSettings.REGISTRY_DOCKERPROXY.equals(mPreset.registryOverride),
                MirrorSettings.REGISTRY_DOCKERPROXY);
    }

    private void addRegistryRow(LayoutInflater inflater, String title, String subtitle,
                                boolean checked, String override) {
        View row = inflater.inflate(R.layout.mirror_preset_row, mRegistryContainer, false);
        TextView titleView = row.findViewById(R.id.mirror_row_title);
        TextView subtitleView = row.findViewById(R.id.mirror_row_subtitle);
        TextView checkView = row.findViewById(R.id.mirror_row_check);
        titleView.setText(title);
        subtitleView.setText(subtitle);
        checkView.setText(checked ? "✓" : "");
        row.setOnClickListener(v -> setRegistryOverride(override));
        mRegistryContainer.addView(row);
    }

    private void buildCustomRows() {
        mCustomContainer.removeAllViews();
        if (!MirrorSettings.PRESET_CUSTOM.equals(mPreset.id)) return;
        addCustomRow(R.string.mirror_custom_distro_host,
                mPreset.distroHost.isEmpty() ? getString(R.string.mirror_not_set) : mPreset.distroHost,
                Field.DISTRO_HOST);
        addCustomRow(R.string.mirror_custom_registry,
                mPreset.registry.isEmpty() ? "Docker Hub" : mPreset.registry,
                Field.REGISTRY);
        addCustomRow(R.string.mirror_custom_npm, mPreset.npm, Field.NPM);
        addCustomRow(R.string.mirror_custom_pypi, mPreset.pypi, Field.PYPI);
        addCustomRow(R.string.mirror_custom_github_proxy,
                mPreset.githubProxy.isEmpty() ? getString(R.string.mirror_not_set) : mPreset.githubProxy,
                Field.GITHUB_PROXY);
    }

    private void addCustomRow(int titleRes, String value, Field field) {
        View row = inflateRow(mCustomContainer);
        TextView titleView = row.findViewById(R.id.mirror_row_title);
        TextView subtitleView = row.findViewById(R.id.mirror_row_subtitle);
        titleView.setText(titleRes);
        subtitleView.setText(value);
        row.setOnClickListener(v -> showEditFieldDialog(field));
        mCustomContainer.addView(row);
    }

    // ---------- actions ----------

    private void setRegistryOverride(String override) {
        if (override.equals(mPreset.registryOverride)) return;
        mPreset.registryOverride = override;
        MirrorSettings.savePreset(this, mPreset);
        refreshUi();
    }

    private void selectPreset(String id) {
        if (id.equals(mPreset.id)) return;
        MirrorPreset candidate = MirrorSettings.PRESET_CUSTOM.equals(id)
                ? MirrorSettings.loadCustomPreset(this)
                : MirrorSettings.presetForId(id);
        candidate.registryOverride = mPreset.registryOverride;
        // Switch immediately; the probe only reports reachability in the
        // status line and never blocks the switch.
        applyPreset(candidate);
        setStatus(getString(R.string.mirror_test_checking));
        new Thread(() -> {
            List<String> unreachable = MirrorSettings.probePreset(candidate);
            runOnUiThread(() -> {
                if (unreachable.isEmpty()) {
                    setStatus(getString(R.string.mirror_probe_ok));
                } else {
                    setStatus(getString(R.string.mirror_probe_failed, joinLabels(unreachable)));
                }
            });
        }).start();
    }

    private void applyPreset(MirrorPreset candidate) {
        MirrorSettings.savePreset(this, candidate);
        mPreset = MirrorSettings.loadPreset(this);
        refreshUi();
        List<String> containers = new ArrayList<>(DistroUtils.scanInstalledContainers());
        if (containers.isEmpty()) return;
        new AlertDialog.Builder(this)
                .setMessage(getString(R.string.mirror_sync_confirm, containers.size()))
                .setPositiveButton(android.R.string.ok, (d, w) -> syncToInstalled())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void syncToInstalled() {
        List<String> containers = new ArrayList<>(DistroUtils.scanInstalledContainers());
        if (containers.isEmpty()) {
            Toast.makeText(this, R.string.mirror_no_distros, Toast.LENGTH_SHORT).show();
            return;
        }
        MirrorPreset preset = mPreset;
        new Thread(() -> {
            SyncResult result = MirrorSettings.syncToAllDistros(this, preset);
            runOnUiThread(() -> {
                if (result.failed == 0) {
                    Toast.makeText(this, getString(R.string.mirror_synced, result.synced),
                            Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, getString(R.string.mirror_synced_partial,
                            result.synced, result.failed), Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    private void testCurrentPreset() {
        MirrorPreset preset = mPreset;
        setStatus(getString(R.string.mirror_test_checking));
        new Thread(() -> {
            List<String> unreachable = MirrorSettings.probePreset(preset);
            runOnUiThread(() -> {
                setStatus(null);
                if (unreachable.isEmpty()) {
                    Toast.makeText(this, R.string.mirror_probe_ok, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, getString(R.string.mirror_probe_failed,
                            joinLabels(unreachable)), Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    private void showRestoreDialog() {
        new AlertDialog.Builder(this)
                .setMessage(R.string.mirror_restore_confirm)
                .setPositiveButton(android.R.string.ok, (d, w) -> restoreOfficial())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void restoreOfficial() {
        MirrorPreset official = MirrorSettings.presetForId(MirrorSettings.PRESET_OFFICIAL);
        official.registryOverride = MirrorSettings.REGISTRY_FOLLOW;
        MirrorSettings.savePreset(this, official);
        mPreset = MirrorSettings.loadPreset(this);
        refreshUi();
        new Thread(() -> {
            SyncResult result = MirrorSettings.restoreOfficialInAllDistros(this);
            runOnUiThread(() -> {
                if (result.failed == 0) {
                    Toast.makeText(this, R.string.mirror_restored, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, getString(R.string.mirror_synced_partial,
                            result.synced, result.failed), Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    // ---------- custom fields ----------

    private enum Field {
        DISTRO_HOST, REGISTRY, NPM, PYPI, GITHUB_PROXY
    }

    private void showEditFieldDialog(Field field) {
        int titleRes;
        String current;
        switch (field) {
            case DISTRO_HOST:
                titleRes = R.string.mirror_custom_distro_host;
                current = mPreset.distroHost;
                break;
            case REGISTRY:
                titleRes = R.string.mirror_custom_registry;
                current = mPreset.registry;
                break;
            case NPM:
                titleRes = R.string.mirror_custom_npm;
                current = mPreset.npm;
                break;
            case PYPI:
                titleRes = R.string.mirror_custom_pypi;
                current = mPreset.pypi;
                break;
            default:
                titleRes = R.string.mirror_custom_github_proxy;
                current = mPreset.githubProxy;
                break;
        }
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setText(current);
        input.setSelection(current.length());
        new AlertDialog.Builder(this)
                .setTitle(titleRes)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (d, w) -> saveCustomField(field, input.getText().toString()))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void saveCustomField(Field field, String rawValue) {
        String distroHost = mPreset.distroHost;
        String registry = mPreset.registry;
        String npm = mPreset.npm;
        String pypi = mPreset.pypi;
        String githubProxy = mPreset.githubProxy;
        switch (field) {
            case DISTRO_HOST:
                distroHost = MirrorSettings.normalizeHost(rawValue);
                if (!distroHost.isEmpty() && !isValidHost(distroHost)) {
                    Toast.makeText(this, R.string.mirror_invalid_url, Toast.LENGTH_SHORT).show();
                    return;
                }
                break;
            case REGISTRY:
                registry = MirrorSettings.normalizeHost(rawValue);
                if (!registry.isEmpty() && !isValidHost(registry)) {
                    Toast.makeText(this, R.string.mirror_invalid_url, Toast.LENGTH_SHORT).show();
                    return;
                }
                break;
            case NPM:
                npm = MirrorSettings.normalizeUrl(rawValue);
                if (npm.isEmpty()) npm = MirrorSettings.NPM_MIRROR_URL;
                if (!MirrorSettings.isValidHttpsUrl(npm)) {
                    Toast.makeText(this, R.string.mirror_invalid_url, Toast.LENGTH_SHORT).show();
                    return;
                }
                break;
            case PYPI:
                pypi = MirrorSettings.normalizeUrl(rawValue);
                if (pypi.isEmpty()) pypi = MirrorSettings.PYPI_OFFICIAL_URL;
                if (!MirrorSettings.isValidHttpsUrl(pypi)) {
                    Toast.makeText(this, R.string.mirror_invalid_url, Toast.LENGTH_SHORT).show();
                    return;
                }
                break;
            default:
                githubProxy = MirrorSettings.normalizeUrl(rawValue);
                if (!githubProxy.isEmpty() && !MirrorSettings.isValidHttpsUrl(githubProxy)) {
                    Toast.makeText(this, R.string.mirror_invalid_url, Toast.LENGTH_SHORT).show();
                    return;
                }
                break;
        }
        MirrorPreset updated = new MirrorPreset(MirrorSettings.PRESET_CUSTOM,
                distroHost, registry, npm, pypi, githubProxy);
        updated.registryOverride = mPreset.registryOverride;
        MirrorSettings.savePreset(this, updated);
        mPreset = MirrorSettings.loadPreset(this);
        refreshUi();
    }

    private static boolean isValidHost(String host) {
        if (host.contains(" ") || host.contains("/")) return false;
        return host.contains(".");
    }

    // ---------- helpers ----------

    private void setStatus(String text) {
        if (text == null) {
            mStatusText.setVisibility(View.GONE);
        } else {
            mStatusText.setText(text);
            mStatusText.setVisibility(View.VISIBLE);
        }
    }

    private String joinLabels(List<String> labels) {
        List<String> names = new ArrayList<>();
        for (String label : labels) {
            switch (label) {
                case "distro":
                    names.add(getString(R.string.mirror_probe_distro));
                    break;
                case "npm":
                    names.add(getString(R.string.mirror_custom_npm));
                    break;
                case "pypi":
                    names.add(getString(R.string.mirror_custom_pypi));
                    break;
                default:
                    names.add(getString(R.string.mirror_registry_title));
                    break;
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) sb.append("、");
            sb.append(names.get(i));
        }
        return sb.toString();
    }
}
