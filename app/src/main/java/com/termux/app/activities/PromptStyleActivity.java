// Command-line style settings: 24 bash prompt (PS1) themes with a prompt
// colour picker, plus terminal display options (termux.properties).
//
// How it works:
// - The prompt theme + colour are stored in a private SharedPreferences file
//   ("prompt_style"). The chosen theme is materialised as
//   ~/.termux/prompt-theme.sh by copying the bundled asset
//   prompt-theme-base.sh with __THEME_ID__ / __ACCENT__ substituted, and
//   ~/.bashrc gets a guarded marker block sourcing it (appended once, never
//   clobbering existing content). New sessions pick the theme up.
//   "Reset to default" removes the marker block and deletes prompt-theme.sh.
// - Terminal display options are written to ~/.termux/termux.properties
//   (primary file; other lines are preserved byte-for-byte) and applied with
//   the same com.termux.app.reload_style broadcast TermuxStyleActivity uses,
//   with EXTRA_RECREATE_ACTIVITY=false so TermuxActivity only reloads
//   properties/margins/extra keys instead of recreating itself:
//   TermuxActivity.reloadActivityStyling(false) calls reloadProperties().
//   The broadcast is sent on every display change and again in onDestroy()
//   (the activity is full-screen, so TermuxActivity is stopped while this
//   screen is open and its receiver is unregistered until it resumes).
// - v1 is bash only. The asset is the single source of truth for the PS1
//   templates; the preview below renders them by interpreting the template
//   with sample values (see PromptPreview).
//
// Bash facts the code relies on (verified in tests/, see design.md):
// - ~/.bashrc is sourced by $PREFIX/etc/profile at every new Termux session,
//   and ~/.bashrc may not exist yet (bootstrap does not create it).
// - \[ and \] are only recognised when literally present in the PS1 string
//   value; escapes arriving via ${...} expansion at display time are NOT
//   interpreted. So templates carry escapes literally and variables carry
//   plain text or colour NUMBERS only. The rainbow theme (22) is the one
//   exception: precmd builds its PS1 at assignment time, where the escapes
//   are baked into the PS1 value and are recognised.

package com.termux.app.activities;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.termux.R;
import com.termux.shared.activity.media.AppCompatActivityUtils;
import com.termux.shared.settings.preferences.SharedPreferenceUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;
import com.termux.shared.termux.settings.properties.TermuxPropertyConstants;
import com.termux.shared.theme.NightMode;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PromptStyleActivity extends AppCompatActivity {

    private static final String LOG_TAG = "PromptStyle";

    private static final String PREFS_NAME = "prompt_style";
    private static final String KEY_THEME_ID = "theme_id";
    private static final String KEY_ACCENT = "accent_color"; // 0 = theme default
    private static final int DEFAULT_THEME_ID = 1;

    private static final String ASSET_NAME = "prompt-theme-base.sh";
    private static final String PROMPT_THEME_FILE_NAME = "prompt-theme.sh";

    private static final int THEME_COUNT = 24;

    /** Default accent ANSI colour per theme (index = theme id - 1). */
    private static final int[] THEME_DEFAULT_ACCENTS = {
        32, 36, 34, 32, 32, 33, 32, 32, 35, 36, 33, 32,
        32, 34, 32, 36, 33, 35, 36, 34, 32, 32, 34, 32
    };

    private static final int[] THEME_NAME_RES = {
        R.string.prompt_theme_01_name, R.string.prompt_theme_02_name,
        R.string.prompt_theme_03_name, R.string.prompt_theme_04_name,
        R.string.prompt_theme_05_name, R.string.prompt_theme_06_name,
        R.string.prompt_theme_07_name, R.string.prompt_theme_08_name,
        R.string.prompt_theme_09_name, R.string.prompt_theme_10_name,
        R.string.prompt_theme_11_name, R.string.prompt_theme_12_name,
        R.string.prompt_theme_13_name, R.string.prompt_theme_14_name,
        R.string.prompt_theme_15_name, R.string.prompt_theme_16_name,
        R.string.prompt_theme_17_name, R.string.prompt_theme_18_name,
        R.string.prompt_theme_19_name, R.string.prompt_theme_20_name,
        R.string.prompt_theme_21_name, R.string.prompt_theme_22_name,
        R.string.prompt_theme_23_name, R.string.prompt_theme_24_name
    };

    /** ANSI colour picker palette: code -> Android colour. */
    private static final int[] PALETTE_CODES = {
        30, 31, 32, 33, 34, 35, 36, 37,
        90, 91, 92, 93, 94, 95, 96, 97
    };
    private static final int[] PALETTE_ARGB = {
        0xFF000000, 0xFFCD0000, 0xFF00CD00, 0xFFCDCD00,
        0xFF0000EE, 0xFFCD00CD, 0xFF00CDCD, 0xFFE5E5E5,
        0xFF7F7F7F, 0xFFFF0000, 0xFF00FF00, 0xFFFFFF00,
        0xFF5C5CFF, 0xFFFF00FF, 0xFF00FFFF, 0xFFFFFFFF
    };

    // Terminal display options (termux.properties keys and value ranges are
    // defined in TermuxPropertyConstants; defaults match upstream).
    private static final String[] CURSOR_STYLE_VALUES = {"block", "underline", "bar"};
    private static final int[] CURSOR_STYLE_RES = {
        R.string.prompt_cursor_style_block, R.string.prompt_cursor_style_underline,
        R.string.prompt_cursor_style_bar
    };
    private static final int BLINK_RATE_ON_MS = 500;
    private static final int[] MARGIN_CHOICES = {0, 3, 8, 16};
    private static final int[] TRANSCRIPT_CHOICES = {500, 2000, 10000, 50000};

    private static final String EXTRA_KEYS_DEFAULT =
        "[['ESC',{key: 'DRAWER', popup: 'PASTE'},'SCROLL','HOME','UP','END','PGUP'],"
        + " ['TAB','CTRL','ALT','LEFT','DOWN','RIGHT','PGDN']]";
    private static final String EXTRA_KEYS_PROGRAMMING =
        "[['ESC','TAB','CTRL','ALT','-','/','|','(',')','LEFT','DOWN','UP','RIGHT']]";
    private static final String EXTRA_KEYS_HIDDEN = "[]";
    private static final String[] EXTRA_KEYS_PRESET_VALUES = {
        EXTRA_KEYS_DEFAULT, EXTRA_KEYS_PROGRAMMING, EXTRA_KEYS_HIDDEN
    };
    private static final int[] EXTRA_KEYS_PRESET_RES = {
        R.string.prompt_extra_keys_default, R.string.prompt_extra_keys_programming,
        R.string.prompt_extra_keys_hidden
    };

    private SharedPreferences mPrefs;
    private int mThemeId;
    private int mAccent; // 0 = use the theme default
    private String mAssetText; // prompt-theme-base.sh, read once
    private boolean mDisplayChanged;

    private LinearLayout mThemeRows;
    private GridLayout mColorGrid;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        AppCompatActivityUtils.setNightMode(this, NightMode.getAppNightMode().getName(), true);

        setContentView(R.layout.prompt_style_layout);
        AppCompatActivityUtils.setToolbar(this, com.termux.shared.R.id.toolbar);
        AppCompatActivityUtils.setShowBackButtonInActionBar(this, true);

        mPrefs = SharedPreferenceUtils.getPrivateSharedPreferences(this, PREFS_NAME);
        mThemeId = mPrefs.getInt(KEY_THEME_ID, DEFAULT_THEME_ID);
        if (mThemeId < 1 || mThemeId > THEME_COUNT) mThemeId = DEFAULT_THEME_ID;
        mAccent = mPrefs.getInt(KEY_ACCENT, 0);

        mThemeRows = findViewById(R.id.prompt_themes_container);
        mColorGrid = findViewById(R.id.prompt_colors_grid);

        buildThemeRows();
        buildColorGrid();
        buildDisplayRows();

        Button resetButton = findViewById(R.id.prompt_reset_button);
        resetButton.setOnClickListener(v -> showResetDialog());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Safety net: if display options changed while TermuxActivity was
        // stopped (its broadcast receiver is unregistered then), it resumes
        // before this activity is destroyed, so the broadcast lands now.
        if (mDisplayChanged) sendReloadBroadcast();
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }

    private int effectiveAccent() {
        return mAccent != 0 ? mAccent : THEME_DEFAULT_ACCENTS[mThemeId - 1];
    }

    // ---------- theme list ----------

    private void buildThemeRows() {
        mThemeRows.removeAllViews();
        LayoutInflater inflater = getLayoutInflater();
        for (int id = 1; id <= THEME_COUNT; id++) {
            View row = inflater.inflate(R.layout.prompt_style_theme_row, mThemeRows, false);
            TextView nameView = row.findViewById(R.id.prompt_theme_row_name);
            TextView previewView = row.findViewById(R.id.prompt_theme_row_preview);
            TextView checkView = row.findViewById(R.id.prompt_theme_row_check);
            nameView.setText(THEME_NAME_RES[id - 1]);
            previewView.setText(PromptPreview.render(this, id, effectiveAccent()));
            checkView.setVisibility(id == mThemeId ? View.VISIBLE : View.INVISIBLE);
            final int themeId = id;
            row.setOnClickListener(v -> selectTheme(themeId));
            mThemeRows.addView(row);
        }
    }

    private void selectTheme(int themeId) {
        if (themeId == mThemeId) return;
        mThemeId = themeId;
        mAccent = 0; // a new theme starts with its own default colour
        mPrefs.edit().putInt(KEY_THEME_ID, mThemeId)
            .putInt(KEY_ACCENT, mAccent).apply();
        String content = writePromptThemeFile();
        if (content != null) {
            Toast.makeText(this,
                getString(R.string.prompt_theme_selected, getString(THEME_NAME_RES[themeId - 1])),
                Toast.LENGTH_SHORT).show();
            syncThemeToDistrosAsync(content);
        }
        buildThemeRows();
        buildColorGrid();
    }

    // ---------- colour picker ----------

    private void buildColorGrid() {
        mColorGrid.removeAllViews();
        int accent = effectiveAccent();
        int sizePx = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 40,
            getResources().getDisplayMetrics());
        int marginPx = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4,
            getResources().getDisplayMetrics());
        for (int i = 0; i < PALETTE_CODES.length; i++) {
            final int code = PALETTE_CODES[i];
            TextView swatch = new TextView(this);
            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.width = sizePx;
            params.height = sizePx;
            params.setMargins(marginPx, marginPx, marginPx, marginPx);
            swatch.setLayoutParams(params);
            swatch.setBackgroundColor(PALETTE_ARGB[i]);
            if (code == accent) {
                swatch.setText("✓");
                swatch.setTextSize(20);
                // Light swatches need a dark check mark, dark ones a light one.
                swatch.setTextColor(code == 37 || code == 93 || code == 96 || code == 97
                    ? Color.BLACK : Color.WHITE);
                swatch.setGravity(android.view.Gravity.CENTER);
            }
            swatch.setOnClickListener(v -> selectAccent(code));
            mColorGrid.addView(swatch);
        }
    }

    private void selectAccent(int code) {
        if (code == effectiveAccent()) return;
        mAccent = code;
        mPrefs.edit().putInt(KEY_ACCENT, mAccent).apply();
        String content = writePromptThemeFile();
        if (content != null) {
            Toast.makeText(this, R.string.prompt_applied, Toast.LENGTH_SHORT).show();
            syncThemeToDistrosAsync(content);
        }
        buildThemeRows();
        buildColorGrid();
    }

    /** Sync the new theme to all installed distros on a background thread. */
    private void syncThemeToDistrosAsync(final String content) {
        new Thread(() -> {
            int synced = PromptThemeSync.syncThemeToAllDistros(content);
            if (synced > 0) {
                runOnUiThread(() -> Toast.makeText(PromptStyleActivity.this,
                    getString(R.string.prompt_sync_distros, synced),
                    Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    // ---------- terminal display options ----------

    private static class DisplayOption {
        final int titleRes;
        final String key;
        String valueText;

        DisplayOption(int titleRes, String key) {
            this.titleRes = titleRes;
            this.key = key;
        }
    }

    private final List<DisplayOption> mDisplayOptions = new ArrayList<>();

    private void buildDisplayRows() {
        LinearLayout container = findViewById(R.id.prompt_display_container);
        container.removeAllViews();
        mDisplayOptions.clear();

        TermuxAppSharedProperties props = TermuxAppSharedProperties.getProperties();
        String cursorStyle = "block";
        int blinkRate = 0;
        int marginH = TermuxPropertyConstants.DEFAULT_IVALUE_TERMINAL_MARGIN_HORIZONTAL;
        int marginV = TermuxPropertyConstants.DEFAULT_IVALUE_TERMINAL_MARGIN_VERTICAL;
        int transcriptRows = TermuxPropertyConstants.DEFAULT_IVALUE_TERMINAL_TRANSCRIPT_ROWS;
        String extraKeys = EXTRA_KEYS_DEFAULT;
        if (props != null) {
            String style = TermuxPropertyConstants.MAP_TERMINAL_CURSOR_STYLE.inverse()
                .get(props.getTerminalCursorStyle());
            if (style != null) cursorStyle = style;
            blinkRate = props.getTerminalCursorBlinkRate();
            marginH = props.getTerminalMarginHorizontal();
            marginV = props.getTerminalMarginVertical();
            transcriptRows = props.getTerminalTranscriptRows();
            String raw = props.getPropertyValue(TermuxPropertyConstants.KEY_EXTRA_KEYS,
                EXTRA_KEYS_DEFAULT, true);
            if (raw != null) extraKeys = raw;
        }

        DisplayOption cursorStyleOption = new DisplayOption(
            R.string.prompt_cursor_style, TermuxPropertyConstants.KEY_TERMINAL_CURSOR_STYLE);
        cursorStyleOption.valueText = cursorStyleLabel(cursorStyle);
        mDisplayOptions.add(cursorStyleOption);

        DisplayOption blinkOption = new DisplayOption(
            R.string.prompt_cursor_blink, TermuxPropertyConstants.KEY_TERMINAL_CURSOR_BLINK_RATE);
        blinkOption.valueText = blinkRate == 0
            ? getString(R.string.prompt_cursor_blink_off)
            : getString(R.string.prompt_cursor_blink_on);
        mDisplayOptions.add(blinkOption);

        DisplayOption marginHOption = new DisplayOption(
            R.string.prompt_margin_horizontal, TermuxPropertyConstants.KEY_TERMINAL_MARGIN_HORIZONTAL);
        marginHOption.valueText = getString(R.string.prompt_margin_value, marginH);
        mDisplayOptions.add(marginHOption);

        DisplayOption marginVOption = new DisplayOption(
            R.string.prompt_margin_vertical, TermuxPropertyConstants.KEY_TERMINAL_MARGIN_VERTICAL);
        marginVOption.valueText = getString(R.string.prompt_margin_value, marginV);
        mDisplayOptions.add(marginVOption);

        DisplayOption transcriptOption = new DisplayOption(
            R.string.prompt_transcript_rows, TermuxPropertyConstants.KEY_TERMINAL_TRANSCRIPT_ROWS);
        transcriptOption.valueText = String.valueOf(transcriptRows);
        mDisplayOptions.add(transcriptOption);

        DisplayOption extraKeysOption = new DisplayOption(
            R.string.prompt_extra_keys, TermuxPropertyConstants.KEY_EXTRA_KEYS);
        extraKeysOption.valueText = extraKeysLabel(extraKeys);
        mDisplayOptions.add(extraKeysOption);

        LayoutInflater inflater = getLayoutInflater();
        for (DisplayOption option : mDisplayOptions) {
            View row = inflater.inflate(R.layout.prompt_style_option_row, container, false);
            TextView titleView = row.findViewById(R.id.prompt_option_row_title);
            TextView valueView = row.findViewById(R.id.prompt_option_row_value);
            titleView.setText(option.titleRes);
            valueView.setText(option.valueText);
            row.setOnClickListener(v -> showDisplayOptionDialog(option, valueView));
            container.addView(row);
        }
    }

    private String cursorStyleLabel(String value) {
        for (int i = 0; i < CURSOR_STYLE_VALUES.length; i++) {
            if (CURSOR_STYLE_VALUES[i].equals(value)) return getString(CURSOR_STYLE_RES[i]);
        }
        return value;
    }

    private String extraKeysLabel(String value) {
        for (int i = 0; i < EXTRA_KEYS_PRESET_VALUES.length; i++) {
            if (EXTRA_KEYS_PRESET_VALUES[i].equals(value)) return getString(EXTRA_KEYS_PRESET_RES[i]);
        }
        return getString(R.string.prompt_extra_keys_custom);
    }

    private void showDisplayOptionDialog(DisplayOption option, TextView valueView) {
        String[] labels;
        String[] values;
        int checked = 0;
        if (option.key.equals(TermuxPropertyConstants.KEY_TERMINAL_CURSOR_STYLE)) {
            labels = new String[CURSOR_STYLE_VALUES.length];
            for (int i = 0; i < CURSOR_STYLE_VALUES.length; i++) {
                labels[i] = getString(CURSOR_STYLE_RES[i]);
                if (CURSOR_STYLE_VALUES[i].equals(currentPropertyValue(option.key, "block"))) checked = i;
            }
            values = CURSOR_STYLE_VALUES;
        } else if (option.key.equals(TermuxPropertyConstants.KEY_TERMINAL_CURSOR_BLINK_RATE)) {
            labels = new String[]{
                getString(R.string.prompt_cursor_blink_off),
                getString(R.string.prompt_cursor_blink_on)};
            values = new String[]{"0", String.valueOf(BLINK_RATE_ON_MS)};
            if (!"0".equals(currentPropertyValue(option.key, "0"))) checked = 1;
        } else if (option.key.equals(TermuxPropertyConstants.KEY_TERMINAL_MARGIN_HORIZONTAL)
                || option.key.equals(TermuxPropertyConstants.KEY_TERMINAL_MARGIN_VERTICAL)) {
            labels = new String[MARGIN_CHOICES.length];
            values = new String[MARGIN_CHOICES.length];
            int current = 0;
            try {
                current = Integer.parseInt(currentPropertyValue(option.key, "0"));
            } catch (NumberFormatException ignored) {
            }
            for (int i = 0; i < MARGIN_CHOICES.length; i++) {
                labels[i] = getString(R.string.prompt_margin_value, MARGIN_CHOICES[i]);
                values[i] = String.valueOf(MARGIN_CHOICES[i]);
                if (MARGIN_CHOICES[i] == current) checked = i;
            }
        } else if (option.key.equals(TermuxPropertyConstants.KEY_TERMINAL_TRANSCRIPT_ROWS)) {
            labels = new String[TRANSCRIPT_CHOICES.length];
            values = new String[TRANSCRIPT_CHOICES.length];
            int current = 0;
            try {
                current = Integer.parseInt(currentPropertyValue(option.key, "0"));
            } catch (NumberFormatException ignored) {
            }
            for (int i = 0; i < TRANSCRIPT_CHOICES.length; i++) {
                labels[i] = String.valueOf(TRANSCRIPT_CHOICES[i]);
                values[i] = String.valueOf(TRANSCRIPT_CHOICES[i]);
                if (TRANSCRIPT_CHOICES[i] == current) checked = i;
            }
        } else { // extra keys presets
            labels = new String[EXTRA_KEYS_PRESET_VALUES.length];
            for (int i = 0; i < EXTRA_KEYS_PRESET_VALUES.length; i++) {
                labels[i] = getString(EXTRA_KEYS_PRESET_RES[i]);
                if (EXTRA_KEYS_PRESET_VALUES[i].equals(currentPropertyValue(option.key, ""))) checked = i;
            }
            values = EXTRA_KEYS_PRESET_VALUES;
        }

        new AlertDialog.Builder(this)
            .setTitle(option.titleRes)
            .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                if (writeTermuxProperty(option.key, values[which])) {
                    option.valueText = labels[which];
                    valueView.setText(labels[which]);
                    mDisplayChanged = true;
                    sendReloadBroadcast();
                    Toast.makeText(this, R.string.prompt_applied, Toast.LENGTH_SHORT).show();
                }
                dialog.dismiss();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private String currentPropertyValue(String key, String def) {
        TermuxAppSharedProperties props = TermuxAppSharedProperties.getProperties();
        if (props == null) return def;
        String value = props.getPropertyValue(key, def, true);
        return value != null ? value : def;
    }

    // ---------- reset ----------

    private void showResetDialog() {
        new AlertDialog.Builder(this)
            .setTitle(R.string.prompt_reset)
            .setMessage(R.string.prompt_reset_confirm)
            .setPositiveButton(android.R.string.ok, (dialog, which) -> resetAll())
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void resetAll() {
        mThemeId = DEFAULT_THEME_ID;
        mAccent = 0;
        mPrefs.edit().putInt(KEY_THEME_ID, mThemeId)
            .putInt(KEY_ACCENT, mAccent).apply();
        removePromptTheme();
        // Also remove the theme from every installed distro (background thread).
        new Thread(() -> PromptThemeSync.removeThemeFromAllDistros()).start();
        writeTermuxProperty(TermuxPropertyConstants.KEY_TERMINAL_CURSOR_STYLE, "block");
        writeTermuxProperty(TermuxPropertyConstants.KEY_TERMINAL_CURSOR_BLINK_RATE, "0");
        writeTermuxProperty(TermuxPropertyConstants.KEY_TERMINAL_MARGIN_HORIZONTAL, "3");
        writeTermuxProperty(TermuxPropertyConstants.KEY_TERMINAL_MARGIN_VERTICAL, "0");
        writeTermuxProperty(TermuxPropertyConstants.KEY_TERMINAL_TRANSCRIPT_ROWS, "2000");
        writeTermuxProperty(TermuxPropertyConstants.KEY_EXTRA_KEYS, EXTRA_KEYS_DEFAULT);
        mDisplayChanged = true;
        sendReloadBroadcast();
        buildThemeRows();
        buildColorGrid();
        buildDisplayRows();
        Toast.makeText(this, R.string.prompt_reset_done, Toast.LENGTH_SHORT).show();
    }

    // ---------- file writing ----------

    private String readAssetText() {
        if (mAssetText != null) return mAssetText;
        try (InputStream in = getAssets().open(ASSET_NAME)) {
            StringBuilder builder = new StringBuilder();
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
            mAssetText = builder.toString();
            return mAssetText;
        } catch (IOException e) {
            Log.w(LOG_TAG, "Failed to read asset " + ASSET_NAME, e);
            return null;
        }
    }

    /** The substituted asset text for the current theme + colour, or null when the asset cannot be read. */
    private String buildThemeContent() {
        String asset = readAssetText();
        if (asset == null) return null;
        return asset.replace("__THEME_ID__", String.valueOf(mThemeId))
            .replace("__ACCENT__", String.valueOf(effectiveAccent()));
    }

    /**
     * Write ~/.termux/prompt-theme.sh for the current theme + colour.
     * Returns the written content, or null on failure. Callers sync the
     * returned content to the installed distros.
     */
    private String writePromptThemeFile() {
        String content = buildThemeContent();
        if (content == null) {
            Toast.makeText(this, getString(R.string.prompt_write_failed, ASSET_NAME),
                Toast.LENGTH_LONG).show();
            return null;
        }
        File termuxDir = TermuxConstants.TERMUX_DATA_HOME_DIR;
        if (!(termuxDir.isDirectory() || termuxDir.mkdirs())) {
            Toast.makeText(this, getString(R.string.prompt_write_failed,
                    termuxDir.getAbsolutePath()), Toast.LENGTH_LONG).show();
            return null;
        }
        File themeFile = new File(termuxDir, PROMPT_THEME_FILE_NAME);
        if (!PromptThemeSync.writeFileAtomically(themeFile, content)) {
            Toast.makeText(this, getString(R.string.prompt_write_failed,
                    themeFile.getAbsolutePath()), Toast.LENGTH_LONG).show();
            return null;
        }
        if (!ensureBashrcMarker()) {
            Toast.makeText(this, getString(R.string.prompt_write_failed,
                    new File(TermuxConstants.TERMUX_HOME_DIR, ".bashrc").getAbsolutePath()),
                Toast.LENGTH_LONG).show();
            return null;
        }
        return content;
    }

    /** Append the guarded source block to ~/.bashrc once, if not present. */
    private boolean ensureBashrcMarker() {
        File bashrc = new File(TermuxConstants.TERMUX_HOME_DIR, ".bashrc");
        if (PromptThemeSync.readFileOrEmpty(bashrc).contains(PromptThemeSync.BASHRC_MARKER_BEGIN)) {
            return true;
        }
        return PromptThemeSync.ensureBashrcMarker(bashrc);
    }

    /** Delete prompt-theme.sh and strip the marker block from ~/.bashrc. */
    private void removePromptTheme() {
        File themeFile = new File(TermuxConstants.TERMUX_DATA_HOME_DIR, PROMPT_THEME_FILE_NAME);
        if (themeFile.isFile() && !themeFile.delete()) {
            Log.w(LOG_TAG, "Failed to delete " + themeFile.getAbsolutePath());
        }
        PromptThemeSync.removeBashrcMarker(new File(TermuxConstants.TERMUX_HOME_DIR, ".bashrc"));
    }

    /**
     * Set one key in ~/.termux/termux.properties (primary file): replace the
     * first active (non-comment) line for the key, or append key=value.
     * Every other line is preserved as-is.
     */
    private boolean writeTermuxProperty(String key, String value) {
        File propertiesFile = new File(TermuxConstants.TERMUX_PROPERTIES_PRIMARY_FILE_PATH);
        String content = readFileOrEmpty(propertiesFile);
        String[] lines = content.isEmpty() ? new String[0] : content.split("\n", -1);
        StringBuilder builder = new StringBuilder();
        boolean replaced = false;
        for (String line : lines) {
            String trimmed = line.trim();
            if (!replaced && !trimmed.startsWith("#") && trimmed.startsWith(key)
                    && trimmed.length() > key.length()
                    && Character.isWhitespace(trimmed.charAt(key.length()))) {
                // "key = value" form (spaces around =)
                builder.append(key).append('=').append(value).append('\n');
                replaced = true;
            } else if (!replaced && trimmed.startsWith(key + "=")) {
                builder.append(key).append('=').append(value).append('\n');
                replaced = true;
            } else {
                builder.append(line).append('\n');
            }
        }
        if (!replaced) {
            builder.append(key).append('=').append(value).append('\n');
        }
        File termuxDir = propertiesFile.getParentFile();
        if (termuxDir != null && !(termuxDir.isDirectory() || termuxDir.mkdirs())) {
            Toast.makeText(this, getString(R.string.prompt_write_failed,
                    termuxDir.getAbsolutePath()), Toast.LENGTH_LONG).show();
            return false;
        }
        if (!writeFileAtomically(propertiesFile, builder.toString())) {
            Toast.makeText(this, getString(R.string.prompt_write_failed,
                    propertiesFile.getAbsolutePath()), Toast.LENGTH_LONG).show();
            return false;
        }
        TermuxAppSharedProperties props = TermuxAppSharedProperties.getProperties();
        if (props != null) props.loadTermuxPropertiesFromDisk();
        return true;
    }

    private void sendReloadBroadcast() {
        // Same action TermuxStyleActivity uses; EXTRA_RECREATE_ACTIVITY=false
        // means reloadActivityStyling() only reloads properties, margins and
        // extra keys instead of recreating TermuxActivity. (The
        // EXTRA_RELOAD_STYLE string extra is only read for the "storage"
        // value, so it is not set here.)
        Intent reloadIntent = new Intent(
            TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY.ACTION_RELOAD_STYLE);
        reloadIntent.putExtra(
            TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY.EXTRA_RECREATE_ACTIVITY, false);
        sendBroadcast(reloadIntent);
    }

    // ---------- small file helpers (shared implementation in PromptThemeSync) ----------

    private String readFileOrEmpty(File file) {
        return PromptThemeSync.readFileOrEmpty(file);
    }

    private boolean writeFileAtomically(File file, String content) {
        return PromptThemeSync.writeFileAtomically(file, content);
    }

    // ---------- PS1 preview rendering ----------

    /**
     * Renders a PS1 template (from the asset) as coloured preview text with
     * sample values. The asset is the single source of truth for templates;
     * theme 22 (rainbow) is built by precmd at runtime, so its preview is
     * assembled here from the same per-letter colour cycle.
     */
    static class PromptPreview {

        private static final int[] RAINBOW_CODES = {31, 33, 32, 36, 34, 35};

        static CharSequence render(PromptStyleActivity activity, int themeId, int accent) {
            if (themeId == 22) return rainbowPreview(accent);
            String template = loadTemplate(activity, themeId);
            if (template == null) return "";
            template = template.replace("${_PROMPT_ACCENT}", String.valueOf(accent));
            template = template.replace("${_PROMPT_ACCENT_BG}", String.valueOf(accent + 10));
            template = template.replace("${_PROMPT_USER}", "bai");
            template = template.replace("${_PROMPT_HOST}", "phone");
            template = template.replace("${_PROMPT_STATUS_COLOR}", "32");
            template = template.replace("${_PROMPT_STATUS_TXT}", "✓ ");
            template = template.replace("${_PROMPT_SMILEY_COLOR}", "32");
            template = template.replace("${_PROMPT_SMILEY_TXT}", ":) ");
            template = template.replace("${_PROMPT_GIT_STR}", " (main*)");
            template = template.replace("${_PROMPT_DURATION_TXT}", "[0.3s] ");
            template = template.replace("${_PROMPT_DATE_STR}", "2026-11-21 01:20");
            template = template.replace("${_PROMPT_LAST2}", "workspace/termux-zh");
            template = template.replace("${_PROMPT_USER_COLOR}", "32");
            return interpret(template);
        }

        private static CharSequence rainbowPreview(int accent) {
            SpannableStringBuilder out = new SpannableStringBuilder();
            String name = "bai";
            for (int i = 0; i < name.length(); i++) {
                int start = out.length();
                out.append(name.charAt(i));
                out.setSpan(new ForegroundColorSpan(ansiToArgb(RAINBOW_CODES[i % RAINBOW_CODES.length])),
                    start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            int start = out.length();
            out.append(" ");
            out.append("~/dl");
            out.setSpan(new ForegroundColorSpan(ansiToArgb(34)),
                start + 1, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            out.append(" ");
            start = out.length();
            out.append("$");
            out.setSpan(new ForegroundColorSpan(ansiToArgb(accent)),
                start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            return out;
        }

        private static String loadTemplate(PromptStyleActivity activity, int themeId) {
            String asset = activity.readAssetText();
            if (asset == null) return null;
            Matcher matcher = Pattern.compile(
                    "(?m)^    " + themeId + "\\) _PROMPT_PS1_TEMPLATE='(.*)'; ;;$")
                .matcher(asset);
            return matcher.find() ? matcher.group(1) : null;
        }

        /** Walk a PS1 template: \[...\] regions set colours, \w etc. expand. */
        private static CharSequence interpret(String ps1) {
            SpannableStringBuilder out = new SpannableStringBuilder();
            int fg = -1;
            int bg = -1;
            int runStart = 0;
            int i = 0;
            while (i < ps1.length()) {
                if (ps1.startsWith("\\[", i)) {
                    int end = ps1.indexOf("\\]", i + 2);
                    if (end == -1) break;
                    int[] colors = parseEscape(ps1.substring(i + 2, end));
                    if (colors != null) {
                        flushRun(out, runStart, fg, bg); // old colours first
                        fg = colors[0];
                        bg = colors[1];
                        runStart = out.length();
                    }
                    i = end + 2;
                } else if (ps1.startsWith("\\n", i)) {
                    flushRun(out, runStart, fg, bg);
                    out.append('\n');
                    runStart = out.length();
                    i += 2;
                } else if (ps1.startsWith("\\w", i)) {
                    out.append("~/dl");
                    i += 2;
                } else if (ps1.startsWith("\\W", i)) {
                    out.append("dl");
                    i += 2;
                } else if (ps1.startsWith("\\A", i)) {
                    out.append("01:21");
                    i += 2;
                } else if (ps1.startsWith("\\t", i)) {
                    out.append("01:21:45");
                    i += 2;
                } else if (ps1.startsWith("\\#", i)) {
                    out.append("42");
                    i += 2;
                } else if (ps1.startsWith("\\$", i)) {
                    out.append("$");
                    i += 2;
                } else if (ps1.startsWith("\\e", i)) {
                    i += 2; // stray escape outside \[ \]: ignore
                } else {
                    out.append(ps1.charAt(i));
                    i++;
                }
            }
            flushRun(out, runStart, fg, bg);
            return out;
        }

        /** Apply colours to [runStart, length) when the run is non-empty. */
        private static void flushRun(SpannableStringBuilder out, int runStart, int fg, int bg) {
            if (out.length() > runStart) {
                if (fg != -1) {
                    out.setSpan(new ForegroundColorSpan(ansiToArgb(fg)),
                        runStart, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                if (bg != -1) {
                    out.setSpan(new BackgroundColorSpan(ansiToArgb(bg)),
                        runStart, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
        }

        /**
         * Parse the inside of a \[...\] region. Returns {fg, bg} (-1 = default),
         * or null when the region holds no colour escape.
         */
        private static int[] parseEscape(String inside) {
            Matcher matcher = Pattern.compile("\\\\e\\[([0-9;]*)m").matcher(inside);
            if (!matcher.find()) return null;
            int fg = -1;
            int bg = -1;
            for (String part : matcher.group(1).split(";")) {
                if (part.isEmpty()) continue;
                int code;
                try {
                    code = Integer.parseInt(part);
                } catch (NumberFormatException e) {
                    continue;
                }
                if (code == 0) {
                    fg = -1;
                    bg = -1;
                } else if (code == 7) {
                    fg = 97; // reverse video: bright text on a dark block
                    bg = 40;
                } else if ((code >= 30 && code <= 37) || (code >= 90 && code <= 97)) {
                    fg = code;
                } else if ((code >= 40 && code <= 47) || (code >= 100 && code <= 107)) {
                    bg = code;
                }
            }
            return new int[]{fg, bg};
        }

        /** Approximate Android colour for an ANSI colour code. */
        private static int ansiToArgb(int code) {
            switch (code) {
                case 30: return 0xFF000000;
                case 31: return 0xFFCD0000;
                case 32: return 0xFF00CD00;
                case 33: return 0xFFCDCD00;
                case 34: return 0xFF0000EE;
                case 35: return 0xFFCD00CD;
                case 36: return 0xFF00CDCD;
                case 37: return 0xFFE5E5E5;
                case 40: return 0xFF000000;
                case 41: return 0xFFCD0000;
                case 42: return 0xFF00CD00;
                case 43: return 0xFFCDCD00;
                case 44: return 0xFF0000EE;
                case 45: return 0xFFCD00CD;
                case 46: return 0xFF00CDCD;
                case 47: return 0xFFE5E5E5;
                case 90: return 0xFF7F7F7F;
                case 91: return 0xFFFF0000;
                case 92: return 0xFF00FF00;
                case 93: return 0xFFFFFF00;
                case 94: return 0xFF5C5CFF;
                case 95: return 0xFFFF00FF;
                case 96: return 0xFF00FFFF;
                case 97: return 0xFFFFFFFF;
                case 100: return 0xFF7F7F7F;
                case 101: return 0xFFFF0000;
                case 102: return 0xFF00FF00;
                case 103: return 0xFFFFFF00;
                case 104: return 0xFF5C5CFF;
                case 105: return 0xFFFF00FF;
                case 106: return 0xFF00FFFF;
                case 107: return 0xFFFFFFFF;
                default: return 0xFFFFFFFF;
            }
        }
    }
}
