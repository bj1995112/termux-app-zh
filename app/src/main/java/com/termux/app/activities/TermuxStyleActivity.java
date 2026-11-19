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

import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

public class TermuxStyleActivity extends Activity {

    private static final String DEFAULT_FILENAME = "Default";

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
            this.displayName = capitalize(name);
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
                new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item);

        colorSpinner.setOnClickListener(v -> {
            AlertDialog dialog = new AlertDialog.Builder(TermuxStyleActivity.this)
                    .setAdapter(colorAdapter, (d, which) -> copyFile(colorAdapter.getItem(which), true))
                    .create();
            dialog.setOnShowListener(d -> {
                dialog.getListView().setOnItemLongClickListener((parent, view, position, id) -> {
                    showLicense(colorAdapter.getItem(position), true);
                    return true;
                });
            });
            dialog.show();
        });

        final ArrayAdapter<Selectable> fontAdapter =
                new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item);

        fontSpinner.setOnClickListener(v -> {
            AlertDialog dialog = new AlertDialog.Builder(TermuxStyleActivity.this)
                    .setAdapter(fontAdapter, (d, which) -> copyFile(fontAdapter.getItem(which), false))
                    .create();
            dialog.setOnShowListener(d -> {
                dialog.getListView().setOnItemLongClickListener((parent, view, position, id) -> {
                    showLicense(fontAdapter.getItem(position), false);
                    return true;
                });
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

    private void copyFile(Selectable currentSelectable, boolean colors) {
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
            java.io.OutputStream out = atomicFile.startWrite();
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
        } catch (Exception e) {
            Log.w("termux", "Failed to write " + outputFile, e);
            String message = getResources().getString(R.string.writing_failed) + e.getMessage();
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        }
    }
}
