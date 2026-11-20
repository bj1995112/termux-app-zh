// Sync the prompt (PS1) theme chosen in Settings > 命令行样式 into installed
// proot-distro containers, so the same prompt shows inside the distros.
//
// How it works:
// - The theme file content is the same substituted asset text that
//   PromptStyleActivity writes to ~/.termux/prompt-theme.sh. Here it is
//   written to <root-home>/.termux/prompt-theme.sh inside the container
//   rootfs, and the same guarded marker block is appended to
//   <root-home>/.bashrc (created when missing, never clobbering existing
//   content). A container's login shell chain (/etc/profile -> ~/.profile
//   -> ~/.bashrc on Ubuntu/Debian) then sources it on entry, and the
//   theme's precmd re-assigns PS1 every prompt, so it wins over the
//   distro's own PS1.
// - Bash only: root's login shell (read from <rootfs>/etc/passwd; /root +
//   /bin/bash when unreadable) must be bash, and a bash binary must exist
//   in the rootfs. Alpine (root shell /bin/ash) is skipped.
// - This class also owns the .bashrc marker + AtomicFile helpers that
//   PromptStyleActivity uses for Termux itself, so the two sides cannot
//   drift apart (one definition of the marker block, one strip routine).
// - Everything here is static and synchronous. Callers put the
//   all-containers passes on a background thread; the single-container
//   entry sync is cheap (one small read + compare, write only when
//   different) and runs inline in DistroUtils.startLoginSession().

package com.termux.app.activities;

import android.util.AtomicFile;
import android.util.Log;

import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class PromptThemeSync {

    private static final String LOG_TAG = "PromptThemeSync";

    /** Theme file name, inside <home>/.termux/ — same as in Termux itself. */
    public static final String PROMPT_THEME_FILE_NAME = "prompt-theme.sh";
    /** Theme directory name under the home directory. */
    public static final String THEME_DIR_NAME = ".termux";

    /** Guarded marker block for .bashrc — shared with PromptStyleActivity. */
    public static final String BASHRC_MARKER_BEGIN = "# BEGIN TERMUX_PROMPT_THEME";
    public static final String BASHRC_MARKER_END = "# END TERMUX_PROMPT_THEME";
    /** Comment line written directly above the marker block. */
    public static final String BASHRC_MANAGED_COMMENT =
        "# termux-app-zh prompt theme (managed, do not edit between the markers)";
    /** Source line inside the marker block ($HOME is the login user's home). */
    public static final String BASHRC_SOURCE_LINE =
        "[ -f \"$HOME/.termux/prompt-theme.sh\" ] && . \"$HOME/.termux/prompt-theme.sh\"";

    private PromptThemeSync() {
        // No instances.
    }

    /** Root's home directory and login shell inside a container rootfs. */
    static final class RootLogin {
        final String home;
        final String shell;

        RootLogin(String home, String shell) {
            this.home = home;
            this.shell = shell;
        }
    }

    /**
     * Parse <rootfs>/etc/passwd for the root entry (root:x:0:0:root:/root:/bin/bash).
     * Falls back to /root + /bin/bash when the file is unreadable or has no root entry.
     */
    static RootLogin rootLogin(File rootfs) {
        String text = readFileOrEmpty(new File(rootfs, "etc/passwd"));
        for (String line : text.split("\n")) {
            if (!line.startsWith("root:")) continue;
            String[] fields = line.split(":", -1);
            if (fields.length >= 7) {
                String home = fields[5].isEmpty() ? "/root" : fields[5];
                String shell = fields[6].isEmpty() ? "/bin/bash" : fields[6];
                return new RootLogin(home, shell);
            }
        }
        return new RootLogin("/root", "/bin/bash");
    }

    /** True when the container can take a bash prompt theme. */
    static boolean isBashContainer(File rootfs) {
        RootLogin login = rootLogin(rootfs);
        String shell = login.shell;
        String base = shell.substring(shell.lastIndexOf('/') + 1);
        if (!"bash".equals(base)) return false;
        return new File(rootfs, "usr/bin/bash").isFile()
            || new File(rootfs, "bin/bash").isFile();
    }

    /**
     * "/root" -> "root": new File(parent, child) ignores the parent when the
     * child is absolute on Unix, so the leading slash must come off before
     * resolving a container path under the rootfs. Falls back to "root" for
     * empty or suspicious values (a guest-planted passwd must not redirect
     * writes outside the rootfs).
     */
    static String relativePath(String absolute) {
        if (absolute == null || absolute.isEmpty()) return "root";
        String rel = absolute.replaceFirst("^/+", "");
        if (rel.isEmpty() || rel.contains("..")) return "root";
        return rel;
    }

    /**
     * Write the theme file + marker into one container. Returns true when
     * anything was written or appended (theme file created/changed, or the
     * marker block appended); false when already in sync, not a bash
     * container, or on failure.
     */
    public static boolean syncThemeToContainer(File rootfs, String content) {
        if (rootfs == null || content == null || content.isEmpty()) return false;
        if (!isBashContainer(rootfs)) return false;
        RootLogin login = rootLogin(rootfs);
        File homeDir = new File(rootfs, relativePath(login.home));
        File themeDir = new File(homeDir, THEME_DIR_NAME);
        if (!(themeDir.isDirectory() || themeDir.mkdirs())) {
            Log.w(LOG_TAG, "Cannot create " + themeDir.getAbsolutePath());
            return false;
        }
        boolean updated = false;
        File themeFile = new File(themeDir, PROMPT_THEME_FILE_NAME);
        if (!content.equals(readFileOrEmpty(themeFile))) {
            if (!writeFileAtomically(themeFile, content)) return false;
            updated = true;
        }
        if (ensureBashrcMarker(new File(homeDir, ".bashrc"))) updated = true;
        return updated;
    }

    /** Remove the theme file + marker from one container (no bash gate: cleanup is unconditional). */
    public static void removeThemeFromContainer(File rootfs) {
        if (rootfs == null) return;
        RootLogin login = rootLogin(rootfs);
        File homeDir = new File(rootfs, relativePath(login.home));
        File themeFile = new File(new File(homeDir, THEME_DIR_NAME), PROMPT_THEME_FILE_NAME);
        if (themeFile.isFile() && !themeFile.delete()) {
            Log.w(LOG_TAG, "Failed to delete " + themeFile.getAbsolutePath());
        }
        removeBashrcMarker(new File(homeDir, ".bashrc"));
    }

    /**
     * Sync the given theme content to every installed container.
     * Returns how many containers were updated. Call on a background thread.
     */
    public static int syncThemeToAllDistros(String content) {
        if (content == null || content.isEmpty()) return 0;
        int synced = 0;
        for (String container : DistroUtils.scanInstalledContainers()) {
            File rootfs = DistroUtils.containerRootfs(container);
            if (rootfs != null && syncThemeToContainer(rootfs, content)) synced++;
        }
        return synced;
    }

    /** Remove the theme from every installed container. Call on a background thread. */
    public static void removeThemeFromAllDistros() {
        for (String container : DistroUtils.scanInstalledContainers()) {
            File rootfs = DistroUtils.containerRootfs(container);
            if (rootfs != null) removeThemeFromContainer(rootfs);
        }
    }

    /**
     * Entry-point helper, called from DistroUtils.startLoginSession() before
     * the session starts: copy the current Termux theme into this container
     * when it is missing or outdated. No-op when no theme was ever picked in
     * Termux (the Termux prompt-theme.sh file does not exist), when the
     * container is not bash, or when it is already in sync. Cheap: one
     * small read + compare, write only when different.
     */
    public static void syncCurrentThemeToContainer(String container) {
        File termuxTheme = new File(TermuxConstants.TERMUX_DATA_HOME_DIR, PROMPT_THEME_FILE_NAME);
        String content = readFileOrEmpty(termuxTheme);
        if (content.isEmpty()) return;
        File rootfs = DistroUtils.containerRootfs(container);
        if (rootfs == null) return;
        syncThemeToContainer(rootfs, content);
    }

    // ---------- .bashrc marker ----------

    /**
     * Append the guarded source block to a .bashrc once, if not present.
     * Returns true when the block was appended, false when it was already
     * there or the write failed. Never clobbers existing content.
     */
    public static boolean ensureBashrcMarker(File bashrc) {
        String content = readFileOrEmpty(bashrc);
        if (content.contains(BASHRC_MARKER_BEGIN)) return false;
        StringBuilder builder = new StringBuilder(content);
        if (builder.length() > 0 && builder.charAt(builder.length() - 1) != '\n') {
            builder.append('\n');
        }
        builder.append(BASHRC_MANAGED_COMMENT).append('\n');
        builder.append(BASHRC_MARKER_BEGIN).append('\n');
        builder.append(BASHRC_SOURCE_LINE).append('\n');
        builder.append(BASHRC_MARKER_END).append('\n');
        if (!writeFileAtomically(bashrc, builder.toString())) {
            Log.w(LOG_TAG, "Failed to write " + bashrc.getAbsolutePath());
            return false;
        }
        return true;
    }

    /** Strip the marker block + the managed comment line from a .bashrc. */
    public static void removeBashrcMarker(File bashrc) {
        String content = readFileOrEmpty(bashrc);
        if (!content.contains(BASHRC_MARKER_BEGIN)) return;
        String[] lines = content.split("\n", -1);
        StringBuilder builder = new StringBuilder();
        boolean inBlock = false;
        for (String line : lines) {
            if (line.equals(BASHRC_MARKER_BEGIN)) {
                inBlock = true;
                continue;
            }
            if (inBlock) {
                if (line.equals(BASHRC_MARKER_END)) inBlock = false;
                continue;
            }
            // Also drop the managed comment line directly above the block.
            if (line.equals(BASHRC_MANAGED_COMMENT)) continue;
            builder.append(line).append('\n');
        }
        writeFileAtomically(bashrc, builder.toString());
    }

    // ---------- small file helpers (same AtomicFile pattern as TermuxStyleActivity) ----------

    public static String readFileOrEmpty(File file) {
        if (!file.isFile()) return "";
        try {
            byte[] bytes = new byte[(int) file.length()];
            try (FileInputStream in = new FileInputStream(file)) {
                int read = 0;
                while (read < bytes.length) {
                    int n = in.read(bytes, read, bytes.length - read);
                    if (n == -1) break;
                    read += n;
                }
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.w(LOG_TAG, "Failed to read " + file.getAbsolutePath(), e);
            return "";
        }
    }

    public static boolean writeFileAtomically(File file, String content) {
        try {
            AtomicFile atomicFile = new AtomicFile(file);
            FileOutputStream out = atomicFile.startWrite();
            out.write(content.getBytes(StandardCharsets.UTF_8));
            atomicFile.finishWrite(out);
            return true;
        } catch (IOException e) {
            Log.w(LOG_TAG, "Failed to write " + file.getAbsolutePath(), e);
            return false;
        }
    }
}
