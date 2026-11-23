// Shared Linux-distro helpers for DistroActivity and TermuxActivity:
// scan installed proot-distro containers, resolve their display names from
// the version-list cache, and start a login session for a container.
//
// Everything here is static and dependency-free (only java.io, org.json and
// existing termux-shared classes). The display-name cache is
// ~/.termux/distro-list.json, written by DistroActivity; when a container
// has no cached name (e.g. installed from the command line), its display
// name is derived from the container name itself.

package com.termux.app.activities;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;

import com.termux.R;
import com.termux.app.TermuxService;
import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_SERVICE;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class DistroUtils {

    private static final String LOG_TAG = "DistroUtils";

    /** Version list cache file, under ~/.termux/. Written by DistroActivity. */
    public static final String CACHE_FILE_NAME = "distro-list.json";

    /** Maximum rows shown in the drawer; more are reached from DistroActivity. */
    public static final int MAX_DRAWER_DISTRO_ROWS = 5;

    private DistroUtils() {
        // No instances.
    }

    /** One installed container, as shown in the drawer / distro list. */
    public static final class InstalledDistro {
        /** Container name, e.g. "ubuntu-26.04". */
        public final String container;
        /** Display name, e.g. "Ubuntu 26.04" (or the container name as a fallback). */
        public final String display;
        /** Distro id, e.g. "ubuntu"; null when the container name matches no known distro. */
        public final String distroId;

        InstalledDistro(String container, String display, String distroId) {
            this.container = container;
            this.display = display;
            this.distroId = distroId;
        }
    }

    // Distro id -> display name. Used to build display names from the cache
    // (cache entries only carry the version part, e.g. "26.10" / "Leap 16.1").
    private static final Map<String, String> DISTRO_DISPLAY_NAMES = new HashMap<>();
    static {
        DISTRO_DISPLAY_NAMES.put("ubuntu", "Ubuntu");
        DISTRO_DISPLAY_NAMES.put("debian", "Debian");
        DISTRO_DISPLAY_NAMES.put("arch", "Arch Linux");
        DISTRO_DISPLAY_NAMES.put("alpine", "Alpine Linux");
        DISTRO_DISPLAY_NAMES.put("fedora", "Fedora");
        DISTRO_DISPLAY_NAMES.put("opensuse", "openSUSE");
        DISTRO_DISPLAY_NAMES.put("void", "Void Linux");
    }

    /** Container-name prefix -> distro id. Longest prefix wins, so the
     * openSUSE entries must come before the plain "opensuse" entry. */
    private static final String[][] CONTAINER_PREFIXES = {
        {"opensuse-leap", "opensuse"},
        {"opensuse-tumbleweed", "opensuse"},
        {"opensuse", "opensuse"},
        {"ubuntu", "ubuntu"},
        {"debian", "debian"},
        {"archlinux", "arch"},
        {"alpine", "alpine"},
        {"fedora", "fedora"},
        {"voidlinux", "void"},
    };

    /** Container-name prefix -> display-name prefix, for names derived without a cache. */
    private static final String[][] CONTAINER_DISPLAY_PREFIXES = {
        {"opensuse-leap", "openSUSE Leap"},
        {"opensuse-tumbleweed", "openSUSE Tumbleweed"},
        {"opensuse", "openSUSE"},
        {"ubuntu", "Ubuntu"},
        {"debian", "Debian"},
        {"archlinux", "Arch Linux"},
        {"alpine", "Alpine Linux"},
        {"fedora", "Fedora"},
        {"voidlinux", "Void Linux"},
    };

    /**
     * All installed containers, sorted by display name: scan the containers
     * directories, resolve each display name from the cache (or derive it),
     * and sort. Never returns null; empty when nothing is installed.
     */
    public static List<InstalledDistro> getInstalledDistros() {
        Set<String> containers = scanInstalledContainers();
        Map<String, String> cachedNames = loadCachedDisplayNames();
        List<InstalledDistro> result = new ArrayList<>();
        for (String container : containers) {
            String distroId = distroIdForContainer(container);
            String display = displayNameForContainer(container, cachedNames);
            result.add(new InstalledDistro(container, display, distroId));
        }
        Collections.sort(result, (a, b) -> a.display.compareTo(b.display));
        return result;
    }

    /** Installed containers, from the proot-distro v5 containers dir (plus the legacy dir). */
    public static Set<String> scanInstalledContainers() {
        Set<String> installed = new HashSet<>();
        // v5: $PREFIX/var/lib/proot-distro/containers/<name>/rootfs/
        collectContainers(
            new File(TermuxConstants.TERMUX_VAR_PREFIX_DIR_PATH, "lib/proot-distro/containers"),
            installed, true);
        // Legacy layout (pre-v5, migrated on first login): .../installed-rootfs/<name>/
        // is the rootfs itself, so any directory counts.
        collectContainers(
            new File(TermuxConstants.TERMUX_VAR_PREFIX_DIR_PATH, "lib/proot-distro/installed-rootfs"),
            installed, false);
        return installed;
    }

    /**
     * Rootfs directory of one installed container, or null when it cannot be
     * found (container removed, or an unfinished install). v5 layout first:
     * $PREFIX/var/lib/proot-distro/containers/<name>/rootfs/; legacy layout
     * (.../installed-rootfs/<name>) is the rootfs itself. Used by
     * AiAgentsActivity to detect installed agents by scanning binary paths,
     * and to write the install script into the container's /tmp.
     */
    public static File containerRootfs(String container) {
        if (container == null || container.isEmpty()) return null;
        File v5 = new File(TermuxConstants.TERMUX_VAR_PREFIX_DIR_PATH,
            "lib/proot-distro/containers/" + container + "/rootfs");
        if (v5.isDirectory()) return v5;
        File legacy = new File(TermuxConstants.TERMUX_VAR_PREFIX_DIR_PATH,
            "lib/proot-distro/installed-rootfs/" + container);
        if (legacy.isDirectory()) return legacy;
        return null;
    }

    private static void collectContainers(File parentDir, Set<String> out, boolean rootfsIsChild) {
        if (parentDir == null || !parentDir.isDirectory()) return;
        File[] children = parentDir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (!child.isDirectory()) continue;
            // A container only counts as installed once its rootfs exists, so a
            // half-finished install does not show up as installed.
            if (!rootfsIsChild || new File(child, "rootfs").isDirectory()) {
                out.add(child.getName());
            }
        }
    }

    /**
     * Container name -> display name from the version-list cache
     * (~/.termux/distro-list.json). A corrupt or missing cache returns an
     * empty map; callers fall back to deriving the name.
     */
    public static Map<String, String> loadCachedDisplayNames() {
        Map<String, String> names = new HashMap<>();
        File cacheFile = new File(TermuxConstants.TERMUX_DATA_HOME_DIR, CACHE_FILE_NAME);
        if (!cacheFile.isFile()) return names;
        try {
            JSONObject root = new JSONObject(readFile(cacheFile));
            JSONObject versions = root.optJSONObject("versions");
            if (versions == null) return names;
            for (String distroId : DISTRO_DISPLAY_NAMES.keySet()) {
                String displayName = DISTRO_DISPLAY_NAMES.get(distroId);
                JSONArray array = versions.optJSONArray(distroId);
                if (array == null) continue;
                for (int i = 0; i < array.length(); i++) {
                    JSONObject option = array.optJSONObject(i);
                    if (option == null) continue;
                    String display = option.optString("d", null);
                    String imageRef = option.optString("r", null);
                    if (display == null || imageRef == null) continue;
                    names.put(containerNameForImageRef(imageRef), displayName + " " + display);
                }
            }
        } catch (Exception e) {
            // Corrupt cache: ignore it, the caller falls back to derived names.
            Log.w(LOG_TAG, "Failed to read distro list cache", e);
        }
        return names;
    }

    /** Distro id for a container name ("ubuntu-26.04" -> "ubuntu"), or null if unknown. */
    public static String distroIdForContainer(String container) {
        if (container == null) return null;
        for (String[] prefix : CONTAINER_PREFIXES) {
            if (container.equals(prefix[0]) || container.startsWith(prefix[0] + "-")) {
                return prefix[1];
            }
        }
        return null;
    }

    /**
     * Display name for a container: the cached name when available, otherwise
     * derived from the container name ("ubuntu-26.04" -> "Ubuntu 26.04",
     * "archlinux" -> "Arch Linux"), otherwise the container name itself.
     */
    public static String displayNameForContainer(String container, Map<String, String> cachedNames) {
        if (cachedNames != null) {
            String cached = cachedNames.get(container);
            if (cached != null) return cached;
        }
        for (String[] prefix : CONTAINER_DISPLAY_PREFIXES) {
            if (container.equals(prefix[0])) {
                return prefix[1];
            }
            if (container.startsWith(prefix[0] + "-")) {
                String version = container.substring(prefix[0].length() + 1);
                return prefix[1] + " " + version;
            }
        }
        return container;
    }

    /**
     * Log in to a container in a new visible terminal session, via the same
     * internal TERMUX_SERVICE.ACTION_SERVICE_EXECUTE intent that
     * RunCommandService uses. TermuxService creates the session, switches to
     * it and brings TermuxActivity forward. This method does not finish or
     * close anything; the caller decides (DistroActivity finishes itself,
     * TermuxActivity closes the drawer first).
     */
    public static void startLoginSession(Context context, String container, String display) {
        String prootDistro = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "proot-distro")
            .getAbsolutePath();
        if (!new File(prootDistro).isFile()) {
            Toast.makeText(context, R.string.distro_proot_distro_missing, Toast.LENGTH_LONG).show();
            return;
        }
        // Bring the container's prompt up to date with the theme chosen in
        // Settings > 命令行样式 (no-op when no theme was ever picked, the
        // container is not bash, or it is already in sync). This also covers
        // newly installed distros: they get the current theme on first entry.
        PromptThemeSync.syncCurrentThemeToContainer(container);
        startSessionWithExecutable(context, prootDistro, new String[]{"login", container},
            display, display);
    }

    /**
     * Run a command in a new visible terminal session, via the same internal
     * TERMUX_SERVICE.ACTION_SERVICE_EXECUTE intent that RunCommandService uses
     * (RunCommandService.java, "Start TERMUX_SERVICE and pass it execution intent").
     * TermuxService creates the session, switches to it and brings
     * TermuxActivity forward (default session action).
     */
    public static void startSessionWithExecutable(Context context, String executablePath,
                                                  String[] arguments,
                                                  String sessionName, String commandLabel) {
        Uri executableUri = new Uri.Builder()
            .scheme(TERMUX_SERVICE.URI_SCHEME_SERVICE_EXECUTE)
            .path(executablePath)
            .build();
        Intent execIntent = new Intent(TERMUX_SERVICE.ACTION_SERVICE_EXECUTE, executableUri);
        execIntent.setClass(context, TermuxService.class);
        if (arguments != null) execIntent.putExtra(TERMUX_SERVICE.EXTRA_ARGUMENTS, arguments);
        execIntent.putExtra(TERMUX_SERVICE.EXTRA_RUNNER,
            ExecutionCommand.Runner.TERMINAL_SESSION.getName());
        execIntent.putExtra(TERMUX_SERVICE.EXTRA_SESSION_ACTION,
            String.valueOf(TERMUX_SERVICE.VALUE_EXTRA_SESSION_ACTION_SWITCH_TO_NEW_SESSION_AND_OPEN_ACTIVITY));
        execIntent.putExtra(TERMUX_SERVICE.EXTRA_SHELL_NAME, sessionName);
        execIntent.putExtra(TERMUX_SERVICE.EXTRA_COMMAND_LABEL, commandLabel);
        execIntent.putExtra(TERMUX_SERVICE.EXTRA_WORKDIR, TermuxConstants.TERMUX_HOME_DIR_PATH);
        context.startService(execIntent);
    }

    /** "opensuse/leap:16.1" -> "opensuse-leap-16.1", "archlinux:latest" -> "archlinux". */
    public static String containerNameForImageRef(String imageRef) {
        String name = imageRef.replace('/', '-').replace(':', '-');
        if (name.endsWith("-latest")) name = name.substring(0, name.length() - "-latest".length());
        return name;
    }

    public static String readFile(File file) throws IOException {
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[(int) file.length()];
            int read = 0;
            while (read < buffer.length) {
                int n = in.read(buffer, read, buffer.length - read);
                if (n == -1) break;
                read += n;
            }
            return new String(buffer, 0, read, StandardCharsets.UTF_8);
        }
    }

    /** Primary CA bundle path inside a container's rootfs, relative to
     *  the rootfs: the file the distro's TLS clients read by default.
     *  Fedora/openSUSE/Arch point at a symlink the bootstrap resolves
     *  before writing; the others are regular files. Unknown distros
     *  fall back to the Debian-style path. */
    public static String caBundleRelPath(String distroId) {
        if (distroId == null) return "etc/ssl/certs/ca-certificates.crt";
        switch (distroId) {
            case "fedora":
                return "etc/pki/tls/certs/ca-bundle.crt";
            case "opensuse":
                return "etc/ssl/ca-bundle.pem";
            case "ubuntu":
            case "debian":
            case "arch":
            case "alpine":
            case "void":
            default:
                return "etc/ssl/certs/ca-certificates.crt";
        }
    }
}
