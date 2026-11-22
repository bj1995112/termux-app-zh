package com.termux.app.activities;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.termux.settings.preferences.TermuxAPIAppSharedPreferences;
import com.termux.shared.termux.settings.preferences.TermuxFloatAppSharedPreferences;
import com.termux.shared.termux.settings.preferences.TermuxTaskerAppSharedPreferences;
import com.termux.shared.termux.settings.preferences.TermuxWidgetAppSharedPreferences;
import com.termux.shared.activity.media.AppCompatActivityUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.theme.NightMode;

public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        AppCompatActivityUtils.setNightMode(this, NightMode.getAppNightMode().getName(), true);

        setContentView(R.layout.activity_settings);
        if (savedInstanceState == null) {
            getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.settings, new RootPreferencesFragment())
                .commit();
        }

        AppCompatActivityUtils.setToolbar(this, com.termux.shared.R.id.toolbar);
        AppCompatActivityUtils.setShowBackButtonInActionBar(this, true);
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }

    public static class RootPreferencesFragment extends PreferenceFragmentCompat {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            Context context = getContext();
            if (context == null) return;

            setPreferencesFromResource(R.xml.root_preferences, rootKey);

            new Thread() {
                @Override
                public void run() {
                    configureTermuxAPIPreference(context);
                    configureTermuxFloatPreference(context);
                    configureTermuxTaskerPreference(context);
                    configureTermuxWidgetPreference(context);
                    configurePromptStylePreference(context);
                    configureDownloadMirrorPreference(context);
                    configureStoragePreference(context);
                    configureModelConfigPreference(context);
                    configureAiAgentSettingsPreference(context);
                }
            }.start();
        }

        @Override
        public void onResume() {
            super.onResume();
            refreshDownloadMirrorSummary();
        }

        /** The download-source summary shows the current preset name, not
         *  the static value from the XML. Refreshed every time the
         *  settings screen is shown (including coming back from the
         *  mirror screen after switching presets). */
        private void refreshDownloadMirrorSummary() {
            Context context = getContext();
            if (context == null) return;
            Preference downloadMirrorPreference = findPreference("download_mirror");
            if (downloadMirrorPreference != null) {
                MirrorSettings.MirrorPreset preset = MirrorSettings.loadPreset(context);
                downloadMirrorPreference.setSummary(MirrorSettings.presetNameResId(preset.id));
            }
        }

        private void configureTermuxAPIPreference(@NonNull Context context) {
            Preference termuxAPIPreference = findPreference("termux_api");
            if (termuxAPIPreference != null) {
                TermuxAPIAppSharedPreferences preferences = TermuxAPIAppSharedPreferences.build(context, false);
                // If failed to get app preferences, then likely app is not installed, so do not show its preference
                termuxAPIPreference.setVisible(preferences != null);
            }
        }

        private void configureTermuxFloatPreference(@NonNull Context context) {
            Preference termuxFloatPreference = findPreference("termux_float");
            if (termuxFloatPreference != null) {
                TermuxFloatAppSharedPreferences preferences = TermuxFloatAppSharedPreferences.build(context, false);
                // If failed to get app preferences, then likely app is not installed, so do not show its preference
                termuxFloatPreference.setVisible(preferences != null);
            }
        }

        private void configureTermuxTaskerPreference(@NonNull Context context) {
            Preference termuxTaskerPreference = findPreference("termux_tasker");
            if (termuxTaskerPreference != null) {
                TermuxTaskerAppSharedPreferences preferences = TermuxTaskerAppSharedPreferences.build(context, false);
                // If failed to get app preferences, then likely app is not installed, so do not show its preference
                termuxTaskerPreference.setVisible(preferences != null);
            }
        }

        private void configureTermuxWidgetPreference(@NonNull Context context) {
            Preference termuxWidgetPreference = findPreference("termux_widget");
            if (termuxWidgetPreference != null) {
                TermuxWidgetAppSharedPreferences preferences = TermuxWidgetAppSharedPreferences.build(context, false);
                // If failed to get app preferences, then likely app is not installed, so do not show its preference
                termuxWidgetPreference.setVisible(preferences != null);
            }
        }

        private void configurePromptStylePreference(@NonNull Context context) {
            Preference promptStylePreference = findPreference("prompt_style");
            if (promptStylePreference != null) {
                promptStylePreference.setOnPreferenceClickListener(preference -> {
                    context.startActivity(new Intent(context, PromptStyleActivity.class));
                    return true;
                });
            }
        }

        private void configureDownloadMirrorPreference(@NonNull Context context) {
            Preference downloadMirrorPreference = findPreference("download_mirror");
            if (downloadMirrorPreference != null) {
                downloadMirrorPreference.setOnPreferenceClickListener(preference -> {
                    context.startActivity(new Intent(context, MirrorSettingsActivity.class));
                    return true;
                });
            }
        }

        private void configureStoragePreference(@NonNull Context context) {
            Preference storagePreference = findPreference("storage");
            if (storagePreference != null) {
                // ~/storage exists once setup has run after granting access.
                if (TermuxConstants.TERMUX_STORAGE_HOME_DIR.exists()) {
                    storagePreference.setSummary(R.string.storage_preference_summary_granted);
                }
                storagePreference.setOnPreferenceClickListener(preference -> {
                    Intent intent = new Intent(context, TermuxActivity.class);
                    intent.putExtra(TermuxActivity.EXTRA_REQUEST_STORAGE_PERMISSION, true);
                    context.startActivity(intent);
                    return true;
                });
            }
        }

        private void configureModelConfigPreference(@NonNull Context context) {
            Preference modelConfigPreference = findPreference("model_config");
            if (modelConfigPreference != null) {
                modelConfigPreference.setOnPreferenceClickListener(preference -> {
                    context.startActivity(new Intent(context, ModelConfigActivity.class));
                    return true;
                });
            }
        }

        private void configureAiAgentSettingsPreference(@NonNull Context context) {
            Preference aiAgentSettingsPreference = findPreference("ai_agent_settings");
            if (aiAgentSettingsPreference != null) {
                aiAgentSettingsPreference.setOnPreferenceClickListener(preference -> {
                    context.startActivity(new Intent(context, AiAgentSettingsActivity.class));
                    return true;
                });
            }
        }
    }

}
