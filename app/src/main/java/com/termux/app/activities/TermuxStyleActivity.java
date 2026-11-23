// Built-in styling chooser, ported to Java from upstream Termux:Styling
// (termux/termux-styling, TermuxStyleActivity.kt, commit 79338cb).
// The main app has no Kotlin support, so this is a Java port; behaviour is
// unchanged: it lists assets/colors/*.properties and assets/fonts/*.ttf,
// writes the selection to ~/.termux/colors.properties or ~/.termux/font.ttf
// and sends the com.termux.app.reload_style broadcast.
// The dialog theme (TermuxStylingTheme) must stay a dialog theme, otherwise
// the reload broadcast is deferred until TermuxActivity restarts.

package com.termux.app.activities;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.method.LinkMovementMethod;
import android.text.util.Linkify;
import android.util.AtomicFile;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.termux.R;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

public class TermuxStyleActivity extends Activity {

    private static final String DEFAULT_FILENAME = "Default";

    /** Bytes written for the Default colour choice (see copyFile). */
    private static final byte[] DEFAULT_COLORS_BYTES =
            "# Using default color theme.".getBytes(StandardCharsets.UTF_8);

    /**
     * Index of the list entry that is currently in use, per list.
     * -2 = not computed yet, -1 = no entry matches the live file,
     * >= 0 = the matching entry. Content is the truth: the index is found
     * by comparing the live file's bytes against the assets (see
     * findCurrentIndex), never from a recorded choice.
     */
    private int mCurrentColorIndex = -2;
    private int mCurrentFontIndex = -2;

    /** Bumped on every successful copy; a background computation started
     * before the copy must not overwrite the fresh cache (0.09 bug: the
     * onShow refresh read the pre-tap file and clobbered the new index
     * with -1, so a just-picked scheme showed no marker on reopen). */
    private int mCurrentIndexGeneration;

    // Chinese display names for color schemes and fonts: file names are
    // English, so the chooser shows "中文 (English)" when a mapping exists
    // and plain English otherwise (well-known brand names have no mapping).
    private static final Map<String, String> CHINESE_NAMES = new LinkedHashMap<>();
    static {
        // Colors: Base16 series ("Base16" itself is kept, it is the scheme system name)
        CHINESE_NAMES.put("Base16 3024 Dark", "Base16 3024 深色");
        CHINESE_NAMES.put("Base16 3024 Light", "Base16 3024 浅色");
        CHINESE_NAMES.put("Base16 Apathy Dark", "Base16 冷漠 深色");
        CHINESE_NAMES.put("Base16 Apathy Light", "Base16 冷漠 浅色");
        CHINESE_NAMES.put("Base16 Ashes Dark", "Base16 灰烬 深色");
        CHINESE_NAMES.put("Base16 Ashes Light", "Base16 灰烬 浅色");
        CHINESE_NAMES.put("Base16 Atelierdune Dark", "Base16 画室·沙丘 深色");
        CHINESE_NAMES.put("Base16 Atelierdune Light", "Base16 画室·沙丘 浅色");
        CHINESE_NAMES.put("Base16 Atelierforest Dark", "Base16 画室·森林 深色");
        CHINESE_NAMES.put("Base16 Atelierforest Light", "Base16 画室·森林 浅色");
        CHINESE_NAMES.put("Base16 Atelierheath Dark", "Base16 画室·荒原 深色");
        CHINESE_NAMES.put("Base16 Atelierheath Light", "Base16 画室·荒原 浅色");
        CHINESE_NAMES.put("Base16 Atelierlakeside Dark", "Base16 画室·湖畔 深色");
        CHINESE_NAMES.put("Base16 Atelierlakeside Light", "Base16 画室·湖畔 浅色");
        CHINESE_NAMES.put("Base16 Atelierseaside Dark", "Base16 画室·海滨 深色");
        CHINESE_NAMES.put("Base16 Atelierseaside Light", "Base16 画室·海滨 浅色");
        CHINESE_NAMES.put("Base16 Bright Dark", "Base16 明亮 深色");
        CHINESE_NAMES.put("Base16 Bright Light", "Base16 明亮 浅色");
        CHINESE_NAMES.put("Base16 Chalk Dark", "Base16 粉笔 深色");
        CHINESE_NAMES.put("Base16 Chalk Light", "Base16 粉笔 浅色");
        CHINESE_NAMES.put("Base16 Codeschool Dark", "Base16 代码学校 深色");
        CHINESE_NAMES.put("Base16 Codeschool Light", "Base16 代码学校 浅色");
        CHINESE_NAMES.put("Base16 Colors Dark", "Base16 多彩 深色");
        CHINESE_NAMES.put("Base16 Colors Light", "Base16 多彩 浅色");
        CHINESE_NAMES.put("Base16 Default Dark", "Base16 默认 深色");
        CHINESE_NAMES.put("Base16 Default Light", "Base16 默认 浅色");
        CHINESE_NAMES.put("Base16 Eighties Dark", "Base16 八十年代 深色");
        CHINESE_NAMES.put("Base16 Eighties Light", "Base16 八十年代 浅色");
        CHINESE_NAMES.put("Base16 Embers Dark", "Base16 余烬 深色");
        CHINESE_NAMES.put("Base16 Embers Light", "Base16 余烬 浅色");
        CHINESE_NAMES.put("Base16 Flat Dark", "Base16 扁平 深色");
        CHINESE_NAMES.put("Base16 Flat Light", "Base16 扁平 浅色");
        CHINESE_NAMES.put("Base16 Google Dark", "Base16 谷歌 深色");
        CHINESE_NAMES.put("Base16 Google Light", "Base16 谷歌 浅色");
        CHINESE_NAMES.put("Base16 Grayscale Dark", "Base16 灰度 深色");
        CHINESE_NAMES.put("Base16 Grayscale Light", "Base16 灰度 浅色");
        CHINESE_NAMES.put("Base16 Greenscreen Dark", "Base16 绿屏 深色");
        CHINESE_NAMES.put("Base16 Greenscreen Light", "Base16 绿屏 浅色");
        CHINESE_NAMES.put("Base16 Londontube Dark", "Base16 伦敦地铁 深色");
        CHINESE_NAMES.put("Base16 Londontube Light", "Base16 伦敦地铁 浅色");
        CHINESE_NAMES.put("Base16 Marrakesh Dark", "Base16 马拉喀什 深色");
        CHINESE_NAMES.put("Base16 Marrakesh Light", "Base16 马拉喀什 浅色");
        CHINESE_NAMES.put("Base16 Materia", "Base16 材质");
        CHINESE_NAMES.put("Base16 Mocha Dark", "Base16 摩卡 深色");
        CHINESE_NAMES.put("Base16 Mocha Light", "Base16 摩卡 浅色");
        CHINESE_NAMES.put("Base16 Ocean Dark", "Base16 海洋 深色");
        CHINESE_NAMES.put("Base16 Ocean Light", "Base16 海洋 浅色");
        CHINESE_NAMES.put("Base16 One Dark", "Base16 One 深色");
        CHINESE_NAMES.put("Base16 One Light", "Base16 One 浅色");
        CHINESE_NAMES.put("Base16 Paraiso Dark", "Base16 天堂 深色");
        CHINESE_NAMES.put("Base16 Paraiso Light", "Base16 天堂 浅色");
        CHINESE_NAMES.put("Base16 Summerfruit Dark", "Base16 夏日水果 深色");
        CHINESE_NAMES.put("Base16 Summerfruit Light", "Base16 夏日水果 浅色");
        CHINESE_NAMES.put("Base16 Tomorrow Dark", "Base16 明日 深色");
        CHINESE_NAMES.put("Base16 Tomorrow Light", "Base16 明日 浅色");
        CHINESE_NAMES.put("Base16 Twilight Dark", "Base16 暮光 深色");
        CHINESE_NAMES.put("Base16 Twilight Light", "Base16 暮光 浅色");

        // Colors: standalone schemes
        CHINESE_NAMES.put("Black On White", "黑字白底");
        CHINESE_NAMES.put("Catppuccin Frappe", "卡布奇诺·冰沙");
        CHINESE_NAMES.put("Catppuccin Latte", "卡布奇诺·拿铁");
        CHINESE_NAMES.put("Catppuccin Macchiato", "卡布奇诺·玛奇朵");
        CHINESE_NAMES.put("Catppuccin Mocha", "卡布奇诺·摩卡");
        CHINESE_NAMES.put("Dracula", "德古拉");
        CHINESE_NAMES.put("E Ink", "电子墨水");
        CHINESE_NAMES.put("E Ink Color", "电子墨水·彩色");
        CHINESE_NAMES.put("Gnometerm", "GNOME 终端");
        CHINESE_NAMES.put("Gnometerm New", "GNOME 终端·新版");
        CHINESE_NAMES.put("Gotham", "哥谭");
        CHINESE_NAMES.put("Iceberg", "冰山");
        CHINESE_NAMES.put("Material", "质感");
        CHINESE_NAMES.put("Neon", "霓虹");
        CHINESE_NAMES.put("Rosé Pine", "玫瑰松");
        CHINESE_NAMES.put("Rosé Pine Dawn", "玫瑰松·黎明");
        CHINESE_NAMES.put("Rosé Pine Moon", "玫瑰松·月夜");
        CHINESE_NAMES.put("Tokyonight Dark", "东京之夜 深色");
        CHINESE_NAMES.put("Tokyonight Day", "东京之夜 日间");
        CHINESE_NAMES.put("Tomorrow Night", "明日 夜晚");
        CHINESE_NAMES.put("White On Black", "白字黑底");
        CHINESE_NAMES.put("Wild Cherry", "野樱桃");

        // Fonts (only the 8 bundled ones) and the Default entry
        CHINESE_NAMES.put("Default", "默认");
        CHINESE_NAMES.put("Fira Code", "Fira 代码");
        CHINESE_NAMES.put("JetBrains Mono", "JetBrains 等宽");
        CHINESE_NAMES.put("CascadiaCode", "Cascadia 代码");
        CHINESE_NAMES.put("Ubuntu Mono", "Ubuntu 等宽");
        CHINESE_NAMES.put("DejaVu Sans Mono", "DejaVu 等宽");
        CHINESE_NAMES.put("Go Mono", "Go 等宽");
    }

    private static String capitalize(String str) {
        boolean lastWhitespace = true;
        char[] chars = str.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            if (Character.isLetter(chars[i])) {
                if (lastWhitespace) chars[i] = Character.toUpperCase(chars[i]);
                lastWhitespace = false;
            } else {
                lastWhitespace = Character.isWhitespace(chars[i]);
            }
        }
        return new String(chars);
    }

    /** One selectable asset file; displayName is derived from the file name. */
    static class Selectable {
        final String fileName;
        final String displayName;

        Selectable(String fileName) {
            this.fileName = fileName;
            String name = fileName.replace('-', ' ');
            int dotIndex = name.lastIndexOf('.');
            if (dotIndex != -1) name = name.substring(0, dotIndex);
            String displayName = capitalize(name);
            String chineseName = CHINESE_NAMES.get(displayName);
            this.displayName = chineseName != null
                    ? chineseName + " (" + displayName + ")"
                    : displayName;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Avoid dim behind:
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        setContentView(R.layout.termux_style_layout);

        final Button colorSpinner = findViewById(R.id.color_spinner);
        final Button fontSpinner = findViewById(R.id.font_spinner);

        final ArrayAdapter<Selectable> colorAdapter =
                new ArrayAdapter<>(this, android.R.layout.select_dialog_singlechoice);

        colorSpinner.setOnClickListener(v -> {
            int checked = mCurrentColorIndex >= 0 ? mCurrentColorIndex : -1;
            AlertDialog dialog = new AlertDialog.Builder(TermuxStyleActivity.this)
                    .setSingleChoiceItems(colorAdapter, checked, (d, which) -> {
                        if (copyFile(colorAdapter.getItem(which), true)) {
                            mCurrentColorIndex = which;
                            mCurrentIndexGeneration++;
                        }
                        d.dismiss();
                    })
                    .create();
            dialog.setOnShowListener(d -> {
                dialog.getListView().setOnItemLongClickListener((parent, view, position, id) -> {
                    showLicense(colorAdapter.getItem(position), true);
                    return true;
                });
                refreshCheckedIndexAsync(dialog, colorAdapter, true);
            });
            dialog.show();
        });

        final ArrayAdapter<Selectable> fontAdapter =
                new ArrayAdapter<>(this, android.R.layout.select_dialog_singlechoice);

        fontSpinner.setOnClickListener(v -> {
            int checked = mCurrentFontIndex >= 0 ? mCurrentFontIndex : -1;
            AlertDialog dialog = new AlertDialog.Builder(TermuxStyleActivity.this)
                    .setSingleChoiceItems(fontAdapter, checked, (d, which) -> {
                        if (copyFile(fontAdapter.getItem(which), false)) {
                            mCurrentFontIndex = which;
                            mCurrentIndexGeneration++;
                        }
                        d.dismiss();
                    })
                    .create();
            dialog.setOnShowListener(d -> {
                dialog.getListView().setOnItemLongClickListener((parent, view, position, id) -> {
                    showLicense(fontAdapter.getItem(position), false);
                    return true;
                });
                refreshCheckedIndexAsync(dialog, fontAdapter, false);
            });
            dialog.show();
        });

        ArrayList<Selectable> colorList = new ArrayList<>();
        ArrayList<Selectable> fontList = new ArrayList<>();
        for (String assetType : new String[]{"colors", "fonts"}) {
            boolean isColors = assetType.equals("colors");
            String assetsFileExtension = isColors ? ".properties" : ".ttf";
            ArrayList<Selectable> currentList = isColors ? colorList : fontList;

            currentList.add(new Selectable(DEFAULT_FILENAME));

            try {
                String[] assets = getAssets().list(assetType);
                if (assets != null) {
                    for (String asset : assets) {
                        if (asset.endsWith(assetsFileExtension)) {
                            currentList.add(new Selectable(asset));
                        }
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        colorAdapter.addAll(colorList);
        fontAdapter.addAll(fontList);

        // Precompute which scheme/font is current, off the UI thread, so the
        // dialogs can show the marker immediately when first opened.
        new Thread(() -> {
            int gen = mCurrentIndexGeneration;
            int color = computeCurrentIndex(colorAdapter, true);
            int font = computeCurrentIndex(fontAdapter, false);
            if (gen == mCurrentIndexGeneration) {
                mCurrentColorIndex = color;
                mCurrentFontIndex = font;
            }
        }).start();
    }

    /** Reads one asset's bytes by file name (colours or fonts folder). */
    interface AssetBytesReader {
        byte[] read(String fileName);
    }

    /** Read an asset's bytes, or null on failure. */
    private byte[] readAssetBytes(String assetPath) {
        try (java.io.InputStream in = getAssets().open(assetPath)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    /** Bytes of the live file, or null when it is missing / unreadable. */
    private static byte[] readLiveFileBytes(File file) {
        if (file == null || !file.isFile()) return null;
        try (java.io.InputStream in = new java.io.FileInputStream(file)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Index of the list entry whose content matches the live file, or -1
     * when nothing matches. Items are in list order (Default first, index
     * 0); the first byte-identical asset wins. Default matches when the
     * live file is missing (the built-in default is in use) or holds
     * exactly the Default marker: the comment for colours, an empty file
     * for fonts. A hand-edited live file matches nothing, so no marker is
     * shown rather than a wrong one.
     */
    static int findCurrentIndex(byte[] liveBytes, ArrayList<Selectable> items,
            boolean colors, AssetBytesReader reader) {
        if (liveBytes == null) return 0;
        if (colors && Arrays.equals(liveBytes, DEFAULT_COLORS_BYTES)) return 0;
        if (!colors && liveBytes.length == 0) return 0;
        for (int i = 1; i < items.size(); i++) {
            byte[] assetBytes = reader.read(items.get(i).fileName);
            if (assetBytes != null && Arrays.equals(liveBytes, assetBytes)) return i;
        }
        return -1;
    }

    /** Synchronous current-index computation (call off the UI thread). */
    private int computeCurrentIndex(ArrayAdapter<Selectable> adapter, boolean colors) {
        ArrayList<Selectable> items = new ArrayList<>();
        for (int i = 0; i < adapter.getCount(); i++) items.add(adapter.getItem(i));
        File liveFile = colors ? TermuxConstants.TERMUX_COLOR_PROPERTIES_FILE
                : TermuxConstants.TERMUX_FONT_FILE;
        byte[] liveBytes = readLiveFileBytes(liveFile);
        String folder = colors ? "colors" : "fonts";
        return findCurrentIndex(liveBytes, items, colors,
                fileName -> readAssetBytes(folder + "/" + fileName));
    }

    /** Compute the current index off the UI thread and check it in the
     * dialog's list when done (if the dialog is still showing). */
    private void refreshCheckedIndexAsync(final AlertDialog dialog,
            final ArrayAdapter<Selectable> adapter, final boolean colors) {
        final int gen = mCurrentIndexGeneration;
        new Thread(() -> {
            int index = computeCurrentIndex(adapter, colors);
            if (gen == mCurrentIndexGeneration) {
                if (colors) mCurrentColorIndex = index; else mCurrentFontIndex = index;
            }
            runOnUiThread(() -> {
                if (dialog.isShowing() && index >= 0) {
                    dialog.getListView().setItemChecked(index, true);
                }
            });
        }).start();
    }

    private void showLicense(Selectable currentSelectable, boolean colors) {
        try {
            String assetsFolder = colors ? "colors" : "fonts";
            String fileName = currentSelectable.fileName;
            int dotIndex = fileName.lastIndexOf('.');
            if (dotIndex != -1) fileName = fileName.substring(0, dotIndex);
            fileName += ".txt";

            try (java.io.InputStream in = getAssets().open(assetsFolder + "/" + fileName)) {
                byte[] buffer = new byte[in.available()];
                //noinspection ResultOfMethodCallIgnored
                in.read(buffer);
                SpannableString license = new SpannableString(new String(buffer));
                Linkify.addLinks(license, Linkify.ALL);
                AlertDialog dialog = new AlertDialog.Builder(this)
                        .setTitle(currentSelectable.displayName)
                        .setMessage(license)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
                ((TextView) dialog.findViewById(android.R.id.message))
                        .setMovementMethod(LinkMovementMethod.getInstance());
            }
        } catch (IOException e) {
            // Ignore: no license file bundled for this asset.
        }
    }

    /** Copy the chosen asset to the live file; returns true on success. */
    private boolean copyFile(Selectable currentSelectable, boolean colors) {
        String outputFile = colors ? "colors.properties" : "font.ttf";
        try {
            // Same destination files the external plugin wrote to, now referenced
            // through the main app's own constants (no createPackageContext needed).
            File destinationFile = colors
                    ? TermuxConstants.TERMUX_COLOR_PROPERTIES_FILE
                    : TermuxConstants.TERMUX_FONT_FILE;
            destinationFile = destinationFile.getCanonicalFile();

            // Fix for if the user has messed up with chmod:
            File termuxDir = destinationFile.getParentFile();
            if (termuxDir != null && !(termuxDir.isDirectory() || termuxDir.mkdirs())) {
                throw new RuntimeException("Cannot create termux dir=" + termuxDir.getAbsolutePath());
            }
            destinationFile.setWritable(true);
            if (termuxDir != null) {
                termuxDir.setWritable(true);
                termuxDir.setExecutable(true);
            }

            boolean defaultChoice = currentSelectable.fileName.equals(DEFAULT_FILENAME);
            AtomicFile atomicFile = new AtomicFile(destinationFile);
            java.io.FileOutputStream out = atomicFile.startWrite();
            if (defaultChoice) {
                if (colors) {
                    byte[] comment = "# Using default color theme.".getBytes(StandardCharsets.UTF_8);
                    out.write(comment);
                } else {
                    // Just leave an empty font file as a marker.
                }
            } else {
                String assetsFolder = colors ? "colors" : "fonts";
                try (java.io.InputStream in = getAssets().open(assetsFolder + "/" + currentSelectable.fileName)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                    }
                }
            }
            atomicFile.finishWrite(out);

            // Note: Must match constant in TermuxActivity (ACTION_RELOAD_STYLE).
            // The extra key is the action string itself, value "colors" or "font".
            Intent reloadIntent = new Intent(TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY.ACTION_RELOAD_STYLE);
            reloadIntent.putExtra(TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY.EXTRA_RELOAD_STYLE,
                    colors ? "colors" : "font");
            sendBroadcast(reloadIntent);
            return true;
        } catch (Exception e) {
            Log.w("termux", "Failed to write " + outputFile, e);
            String message = getResources().getString(R.string.writing_failed) + e.getMessage();
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            return false;
        }
    }
}
