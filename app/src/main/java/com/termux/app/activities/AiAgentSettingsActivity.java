package com.termux.app.activities;

import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.termux.R;
import com.termux.shared.activity.media.AppCompatActivityUtils;
import com.termux.shared.theme.NightMode;

import java.util.List;

/**
 * AI 助手 settings (Settings > AI 助手).
 *
 * Only two things for now, both about the web UI:
 * which browser opens a web link, and whether tapping a web
 * button opens the browser automatically at all. Both are stored
 * in the ai_agents SharedPreferences by AiAgentUtils and are read
 * by every web launch through AiAgentUtils.openWebUrl, so the
 * choice applies to all four web agents (OpenCode, Pi, dsh,
 * Hermes). The browser list is built live from the PackageManager
 * each time this screen opens.
 *
 * The UI is built in code (no layout file): a scrollable column
 * with a browser radio group, an auto-open switch and a first-run
 * note. Choices save immediately.
 */
public class AiAgentSettingsActivity extends AppCompatActivity {

    private boolean mBuildingUi;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        AppCompatActivityUtils.setNightMode(this, NightMode.getAppNightMode().getName(), true);

        ScrollView scrollView = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16,
            getResources().getDisplayMetrics());
        root.setPadding(padding, padding, padding, padding);
        scrollView.addView(root, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scrollView);
        AppCompatActivityUtils.setToolbar(this, com.termux.shared.R.id.toolbar);
        AppCompatActivityUtils.setShowBackButtonInActionBar(this, true);

        mBuildingUi = true;

        TextView browserTitle = new TextView(this);
        browserTitle.setText(R.string.aiagent_settings_browser_title);
        browserTitle.setTypeface(Typeface.DEFAULT_BOLD);
        browserTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        root.addView(browserTitle);

        List<AiAgentUtils.BrowserChoice> browsers = AiAgentUtils.listWebBrowsers(this);
        String savedPackage = AiAgentUtils.getWebBrowserPackage(this);
        String defaultPackage = AiAgentUtils.getSystemDefaultBrowserPackage(this);
        RadioGroup browserGroup = new RadioGroup(this);
        browserGroup.setOrientation(RadioGroup.VERTICAL);
        root.addView(browserGroup);

        RadioButton systemButton = new RadioButton(this);
        systemButton.setText(R.string.aiagent_settings_browser_system);
        systemButton.setTag("");
        browserGroup.addView(systemButton);
        // A saved package that is the current system default is shown
        // as "system default": the default browser is no longer listed
        // by name, so without this no row would be checked.
        if (savedPackage == null || savedPackage.isEmpty()
                || savedPackage.equals(defaultPackage)) {
            systemButton.setChecked(true);
        }
        for (AiAgentUtils.BrowserChoice browser : browsers) {
            RadioButton button = new RadioButton(this);
            button.setText(browser.label);
            button.setTag(browser.packageName);
            browserGroup.addView(button);
            if (browser.packageName.equals(savedPackage)) {
                button.setChecked(true);
            }
        }
        browserGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (mBuildingUi) return;
            RadioButton checked = group.findViewById(checkedId);
            if (checked == null) return;
            Object tag = checked.getTag();
            AiAgentUtils.setWebBrowserPackage(this, tag == null ? "" : tag.toString());
            Toast.makeText(this, R.string.aiagent_settings_saved, Toast.LENGTH_SHORT).show();
        });

        TextView autoOpenTitle = new TextView(this);
        autoOpenTitle.setText(R.string.aiagent_settings_auto_open_title);
        autoOpenTitle.setTypeface(Typeface.DEFAULT_BOLD);
        autoOpenTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleParams.topMargin = padding;
        root.addView(autoOpenTitle, titleParams);

        Switch autoOpenSwitch = new Switch(this);
        autoOpenSwitch.setText(R.string.aiagent_settings_auto_open_summary);
        autoOpenSwitch.setChecked(AiAgentUtils.isWebAutoOpen(this));
        root.addView(autoOpenSwitch);
        autoOpenSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (mBuildingUi) return;
            AiAgentUtils.setWebAutoOpen(this, checked);
            Toast.makeText(this, R.string.aiagent_settings_saved, Toast.LENGTH_SHORT).show();
        });

        TextView note = new TextView(this);
        note.setText(R.string.aiagent_settings_first_run_note);
        LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        noteParams.topMargin = padding;
        root.addView(note, noteParams);

        mBuildingUi = false;
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }
}
