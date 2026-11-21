// AI assistant quick entry: shared agent definitions, installed detection
// (rootfs scan, no session), launching an installed agent in a new terminal
// session, and the `ai` shell command.
//
// The 6-agent table and the detection / version-probing code used to live in
// AiAgentsActivity; they moved here so the drawer "AI 助手" section, the
// distro options dialog "快速启动" submenu and the installer list all share
// one definition of what "installed" means. AiAgentsActivity now uses this
// class and only keeps its own install / update UI and script building.
//
// How a quick launch runs: `proot-distro login <container> -- <absolute
// binary path>`. The path is absolute on purpose: proot-distro 5.10.3 runs
// a command given after `--` through the container user's shell with `-c`
// (login/__init__.py: `inner = [login_shell, "-c", shlex.join(login_cmd)]`),
// which is NOT a login shell, so profile PATH additions such as
// ~/.local/bin (where codex / agy / aider live) do not apply and the bare
// name `codex` would not resolve. An absolute path works on every distro
// regardless of PATH. The path used is the one detection actually found,
// so npm agents resolve to /usr/bin or /usr/local/bin as installed.
//
// Pure Java, zero new dependencies: only Android framework APIs, org.json
// and existing termux-shared classes are used.

package com.termux.app.activities;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;
import android.util.Log;
import android.widget.Toast;

import com.termux.R;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.settings.preferences.SharedPreferenceUtils;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public final class AiAgentUtils {

    private static final String LOG_TAG = "AiAgentUtils";

    /** SharedPreferences file for AI-agent UI state (one-time hints). */
    private static final String AI_AGENTS_PREFS_NAME = "ai_agents";

    /** Set once the first web-launch battery hint has been shown. */
    private static final String PREF_WEB_BATTERY_HINT_SHOWN = "web_battery_hint_shown";

    /** Timeout for the pre-dialog web-port probe (loopback, fails fast). */
    private static final int WEB_PORT_PROBE_TIMEOUT_MS = 800;

    /** Cache file for latest-version checks (Termux home, same place as the
     * distro list cache). Format: {"checkedAt": <epoch ms>,
     * "latest": {"pi": "1.6.0", ...}}. */
    private static final String UPDATE_CACHE_FILE_NAME = "aiagents-updates.json";

    /** Cached latest versions are used for one day without re-fetching. */
    private static final long UPDATE_CACHE_VALID_MS = 24L * 60L * 60L * 1000L;

    /** Per-request timeouts for latest-version checks; any failure is
     * silent (no badge, no error) — see fetchLatestVersion. */
    private static final int UPDATE_CHECK_CONNECT_TIMEOUT_MS = 10000;
    private static final int UPDATE_CHECK_READ_TIMEOUT_MS = 20000;

    /** Rootfs-relative path of the agy version record file. The agy
     * installer prints the version it installs but writes no version
     * file, so the install/update script records it here right after a
     * successful install (see AiAgentsActivity.record_agy_version). */
    private static final String AGY_VERSION_RECORD_REL_PATH =
        "root/.termux-aiagents/versions/agy";

    /** Maximum rows shown in the drawer; more are reached from DistroActivity. */
    public static final int MAX_DRAWER_AGENT_ROWS = 5;

    private AiAgentUtils() {
        // No instances.
    }

    /** How an installed agent's version is found (from files, never by running it). */
    public enum VersionKind {
        /** No local version file known: show installed without a version. */
        NONE,
        /** <prefix>/lib/node_modules/<npm package>/package.json "version". */
        NPM_PACKAGE_JSON,
        /** ~/.codex/packages/standalone/releases/<version>/ directory names. */
        CODEX_RELEASES,
        /** uv tool metadata: ~/.local/share/uv/tools/aider-chat/.../aider_chat-*.dist-info/METADATA. */
        AIDER_DIST_INFO,
        /** ~/.local/share/claude/versions/<version>/ directory names. */
        CLAUDE_VERSIONS,
        /** ~/.hermes/hermes-agent/install-stamp.json "displayVersion"
         * (the source-install stamp written by install.sh / `hermes update`;
         * "0.23.3", or "0.23.3+12.gabc1234" when the checkout sits ahead of
         * the tag). "unknown" or a missing stamp means no version. */
        HERMES_STAMP,
        /** Version record written by the install/update script:
         * ~/.termux-aiagents/versions/agy (agy only — its installer writes
         * no version file of its own). */
        AGY_RECORDED
    }

    /** Which agents can be opened in the phone browser (v1: dsh, OpenCode, Pi, Hermes). */
    public enum WebKind {
        /** No web UI: terminal only. */
        NONE,
        /** `dsh web`: fixed port 3080, but the startup URL carries a fresh
         * session token, so the app cannot open it by itself (see startWebSession). */
        DSH,
        /** `opencode serve --port 4096`: fixed port + password, so the app
         * can open http://127.0.0.1:4096 directly. */
        OPENCODE,
        /** Official pi-webapp (`npx pi-webapp`): no fixed port, the launcher
         * prints a token-bearing local address, so the app cannot open it
         * by itself (see startWebSession). */
        PI,
        /** `hermes dashboard`: built-in web UI on fixed port 9119, binds
         * 127.0.0.1 only and needs no password on loopback, so the app can
         * open http://127.0.0.1:9119 directly. First run downloads pnpm
         * and builds the web UI's JavaScript dependencies. */
        HERMES
    }

    /** One AI assistant that can be installed into a container. */
    public static class AgentDef {
        final String id;
        final String displayName;
        final int descriptionRes;
        final boolean needsNode;
        final String command;       // binary name, e.g. "codex"
        final String homeBinPath;   // $HOME-relative binary, e.g. ".local/bin/codex", "" if none
        final String[] binPaths;    // rootfs-relative binary paths that mean "installed"
        final VersionKind versionKind;
        final String npmPackage;    // for NPM_PACKAGE_JSON, e.g. "@deepseek-ai/dsh"
        final String installSnippet; // shell lines run inside the container (install)
        final String updateSnippet;  // shell lines run inside the container (update)
        final WebKind webKind;       // NONE when the agent has no web UI
        final int webPort;           // fixed web port (3080 dsh, 4096 opencode), 0 when none
        // false when the agent has no terminal/TUI entry: dsh is web-only
        // (upstream documents only `dsh web`; bare `dsh` is undocumented).
        final boolean terminalEntry;

        AgentDef(String id, String displayName, int descriptionRes, boolean needsNode,
                 String command, String homeBinPath, String[] binPaths,
                 VersionKind versionKind, String npmPackage,
                 String installSnippet, String updateSnippet,
                 WebKind webKind, int webPort, boolean terminalEntry) {
            this.id = id;
            this.displayName = displayName;
            this.descriptionRes = descriptionRes;
            this.needsNode = needsNode;
            this.command = command;
            this.homeBinPath = homeBinPath;
            this.binPaths = binPaths;
            this.versionKind = versionKind;
            this.npmPackage = npmPackage;
            this.installSnippet = installSnippet;
            this.updateSnippet = updateSnippet;
            this.webKind = webKind;
            this.webPort = webPort;
            this.terminalEntry = terminalEntry;
        }
    }

    // All install/update commands verified against primary sources on 2026-11-20
    // (npm registry, PyPI, official docs); see ai-agents/design.md §1.
    // Web launch facts verified 2026-11-21 (see web-agents/design.md §1):
    // dsh web = :3080 with a per-start token in the URL; OpenCode 2 has no
    // `web` command — the web UI is served by `opencode serve --port 4096`
    // (password-protected); pi-webapp has no fixed port. Claude Code facts
    // verified 2026-11-21 against Anthropic docs: the installer is
    // https://claude.ai/install.sh (claude.com/install.sh is a 404), the
    // binary is ~/.local/bin/claude, update is `claude update`, no Node needed.
    // Hermes Agent facts verified 2026-11-21 against the official repo
    // (NousResearch/hermes-agent) and docs (hermes-agent.nousresearch.com):
    // install.sh (2585 lines) clones the repo and bundles Python 3.14 +
    // Node via its own PM, supports aarch64 (uv aarch64 pin, arm64|aarch64
    // arch mapping), needs git + curl; the launcher is published to
    // ~/.local/bin/hermes (hermes_cli/_launchers.py expose_cli) with the
    // real launcher at ~/.hermes/hermes-agent/.hermes/bin/hermes; the
    // version comes from ~/.hermes/hermes-agent/install-stamp.json
    // "displayVersion" (pyproject.toml says "0.0.0", a placeholder);
    // `hermes dashboard` serves the built-in web UI on 127.0.0.1:9119
    // (no password on loopback; first run builds the JS deps via pnpm);
    // update is `hermes update`; the official Termux APT package is
    // currently broken per the official Termux guide, so it is NOT used.
    public static final List<AgentDef> AGENTS = Arrays.asList(
        new AgentDef("codex", "Codex", R.string.aiagents_desc_codex, false, "codex",
            ".local/bin/codex",
            new String[]{"root/.local/bin/codex"},
            VersionKind.CODEX_RELEASES, null,
            "curl -fsSL https://chatgpt.com/codex/install.sh | CODEX_NON_INTERACTIVE=1 sh",
            "codex update || curl -fsSL https://chatgpt.com/codex/install.sh | CODEX_NON_INTERACTIVE=1 sh",
            WebKind.NONE, 0, true),
        new AgentDef("pi", "Pi", R.string.aiagents_desc_pi, true, "pi",
            "",
            new String[]{"usr/bin/pi", "usr/local/bin/pi"},
            VersionKind.NPM_PACKAGE_JSON, "@earendil-works/pi-coding-agent",
            "npm install -g --ignore-scripts @earendil-works/pi-coding-agent",
            "npm install -g --ignore-scripts @earendil-works/pi-coding-agent",
            WebKind.PI, 0, true),
        new AgentDef("agy", "Antigravity CLI", R.string.aiagents_desc_agy, false, "agy",
            ".local/bin/agy",
            new String[]{"root/.local/bin/agy"},
            VersionKind.AGY_RECORDED, null,
            "curl -fsSL https://antigravity.google/cli/install.sh -o /tmp/agy-install.sh && bash /tmp/agy-install.sh",
            "curl -fsSL https://antigravity.google/cli/install.sh -o /tmp/agy-install.sh && bash /tmp/agy-install.sh",
            WebKind.NONE, 0, true),
        new AgentDef("dsh", "DeepSeek Harness", R.string.aiagents_desc_dsh, true, "dsh",
            "",
            new String[]{"usr/bin/dsh", "usr/local/bin/dsh"},
            VersionKind.NPM_PACKAGE_JSON, "@deepseek-ai/dsh",
            "npm install -g @deepseek-ai/dsh",
            "old_ver=$(npm list -g @deepseek-ai/dsh --depth=0 2>/dev/null | grep -oE '@deepseek-ai/dsh@[0-9][^ ]*' | cut -d@ -f3); if [ -n \"$old_ver\" ]; then echo \"当前版本：$old_ver；如需回退：npm install -g @deepseek-ai/dsh@$old_ver\"; fi; npm install -g @deepseek-ai/dsh",
            WebKind.DSH, 3080, false),
        new AgentDef("opencode", "OpenCode", R.string.aiagents_desc_opencode, true, "opencode",
            "",
            new String[]{"usr/bin/opencode", "usr/local/bin/opencode"},
            VersionKind.NPM_PACKAGE_JSON, "@opencode/cli",
            "npm install -g @opencode/cli",
            "npm install -g @opencode/cli",
            WebKind.OPENCODE, 4096, true),
        new AgentDef("aider", "Aider", R.string.aiagents_desc_aider, false, "aider",
            ".local/bin/aider",
            new String[]{"root/.local/bin/aider"},
            VersionKind.AIDER_DIST_INFO, null,
            "curl -LsSf https://aider.chat/install.sh | sh",
            "\"$HOME/.local/bin/aider\" --upgrade",
            WebKind.NONE, 0, true),
        new AgentDef("claude", "Claude Code", R.string.aiagents_desc_claude, false, "claude",
            ".local/bin/claude",
            new String[]{"root/.local/bin/claude"},
            VersionKind.CLAUDE_VERSIONS, null,
            "curl -fsSL https://claude.ai/install.sh | bash",
            "\"$HOME/.local/bin/claude\" update",
            WebKind.NONE, 0, true),
        new AgentDef("hermes", "Hermes Agent", R.string.aiagents_desc_hermes, false, "hermes",
            ".local/bin/hermes",
            new String[]{"root/.local/bin/hermes",
                "root/.hermes/hermes-agent/.hermes/bin/hermes"},
            VersionKind.HERMES_STAMP, null,
            "curl -fsSL https://hermes-agent.nousresearch.com/install.sh -o /tmp/hermes-install.sh"
                + " && bash /tmp/hermes-install.sh --channel stable --non-interactive"
                + " --skip-browser --skip-computer-use",
            // Absolute path: the install script runs in a non-login shell
            // where ~/.local/bin is not on PATH (AiAgentsActivity exports it
            // for the install session, but the update must not depend on
            // that); fall back to the real launcher inside the checkout.
            "\"$HOME/.local/bin/hermes\" update"
                + " || \"$HOME/.hermes/hermes-agent/.hermes/bin/hermes\" update",
            WebKind.HERMES, 9119, true)
    );

    /** AgentDef for an id, or null when unknown. */
    public static AgentDef findDef(String id) {
        if (id == null) return null;
        for (AgentDef def : AGENTS) {
            if (def.id.equals(id)) return def;
        }
        return null;
    }

    /** Whether the agent has a web UI (dsh, OpenCode, Pi). TermuxActivity
     * lives in another package and cannot read AgentDef.webKind directly,
     * so the drawer menu asks here. */
    public static boolean hasWeb(String id) {
        AgentDef def = findDef(id);
        return def != null && def.webKind != WebKind.NONE;
    }

    /** Whether the agent has a terminal entry (false for dsh: upstream
     * documents only `dsh web`, no TUI). TermuxActivity lives in another
     * package and cannot read AgentDef fields directly, so launch paths
     * ask here. Unknown ids return false (safe default: no terminal). */
    public static boolean hasTerminalEntry(String id) {
        AgentDef def = findDef(id);
        return def != null && def.terminalEntry;
    }

    /** One installed agent inside one installed container. */
    public static final class InstalledAgent {
        /** Agent id, e.g. "codex". */
        public final String agentId;
        /** Display name, e.g. "Codex". */
        public final String displayName;
        /** Binary name, e.g. "codex" (what the user types). */
        public final String command;
        /** Rootfs-relative binary path that was found, e.g. "root/.local/bin/codex". */
        public final String binPath;
        /** Container name, e.g. "ubuntu-26.04". */
        public final String container;
        /** Distro id, e.g. "ubuntu"; null when the container matches no known distro. */
        public final String distroId;
        /** Distro display name, e.g. "Ubuntu 26.04". */
        public final String distroDisplay;

        InstalledAgent(String agentId, String displayName, String command, String binPath,
                       String container, String distroId, String distroDisplay) {
            this.agentId = agentId;
            this.displayName = displayName;
            this.command = command;
            this.binPath = binPath;
            this.container = container;
            this.distroId = distroId;
            this.distroDisplay = distroDisplay;
        }

        /** Absolute path of the binary inside the container, e.g. "/root/.local/bin/codex". */
        public String inContainerPath() {
            return "/" + binPath;
        }
    }

    // ------------------------------------------------------------------
    // Installed detection (rootfs scan, no session)
    // ------------------------------------------------------------------

    /**
     * Every installed agent in every installed container, sorted by agent
     * display name, then distro display name. Only checks that the binary
     * files exist (no version probing), so the scan is a few dozen stat()
     * calls and is cheap enough to run when the drawer opens. Never returns
     * null; empty when nothing is installed.
     */
    public static List<InstalledAgent> detectInstalledAgents() {
        List<InstalledAgent> result = new ArrayList<>();
        for (DistroUtils.InstalledDistro distro : DistroUtils.getInstalledDistros()) {
            result.addAll(detectInstalledAgentsForContainer(
                distro.container, distro.distroId, distro.display));
        }
        Collections.sort(result, (a, b) -> {
            int byAgent = a.displayName.compareTo(b.displayName);
            return byAgent != 0 ? byAgent : a.distroDisplay.compareTo(b.distroDisplay);
        });
        return result;
    }

    /** Installed agents inside one container (empty when none are installed). */
    public static List<InstalledAgent> detectInstalledAgentsForContainer(String container) {
        if (container == null || container.isEmpty()) return new ArrayList<>();
        return detectInstalledAgentsForContainer(container,
            DistroUtils.distroIdForContainer(container),
            DistroUtils.displayNameForContainer(container,
                DistroUtils.loadCachedDisplayNames()));
    }

    private static List<InstalledAgent> detectInstalledAgentsForContainer(
            String container, String distroId, String distroDisplay) {
        List<InstalledAgent> result = new ArrayList<>();
        File rootfs = DistroUtils.containerRootfs(container);
        if (rootfs == null) return result;
        for (AgentDef def : AGENTS) {
            String found = findBinPath(rootfs, def);
            if (found != null) {
                result.add(new InstalledAgent(def.id, def.displayName, def.command, found,
                    container, distroId, distroDisplay));
            }
        }
        return result;
    }

    /** First binary path of the agent that exists in the rootfs, or null.
     * Public so AiAgentsActivity can rebuild an InstalledAgent for the web
     * button from a row it already detected. */
    public static String findBinPath(File rootfs, AgentDef def) {
        for (String bin : def.binPaths) {
            if (new File(rootfs, bin).isFile()) return bin;
        }
        return null;
    }

    public static boolean isInstalled(File rootfs, AgentDef def) {
        return findBinPath(rootfs, def) != null;
    }

    public static String probeVersion(File rootfs, AgentDef def) {
        try {
            switch (def.versionKind) {
                case NPM_PACKAGE_JSON:
                    for (String prefix : new String[]{"usr/lib", "usr/local/lib"}) {
                        File pkg = new File(rootfs,
                            prefix + "/node_modules/" + def.npmPackage + "/package.json");
                        if (pkg.isFile()) {
                            String version = new JSONObject(DistroUtils.readFile(pkg))
                                .optString("version", "");
                            if (!version.isEmpty()) return version;
                        }
                    }
                    return "";
                case CODEX_RELEASES:
                    return probeMaxVersionDir(
                        new File(rootfs, "root/.codex/packages/standalone/releases"));
                case CLAUDE_VERSIONS:
                    return probeMaxVersionDir(
                        new File(rootfs, "root/.local/share/claude/versions"));
                case AIDER_DIST_INFO:
                    return probeAiderVersion(rootfs);
                case HERMES_STAMP:
                    return probeHermesVersion(rootfs);
                case AGY_RECORDED:
                    return probeAgyRecordedVersion(rootfs);
                default:
                    return "";
            }
        } catch (Exception e) {
            Log.w(LOG_TAG, "Failed to probe version for " + def.id, e);
            return "";
        }
    }

    /** Installed Hermes version from the source-install stamp written by
     * install.sh / `hermes update` at
     * ~/.hermes/hermes-agent/install-stamp.json ("displayVersion":
     * "0.23.3", or "0.23.3+12.gabc1234" when the checkout sits ahead of
     * the tag; "unknown" or a missing/unreadable stamp means no version,
     * same as agy). pyproject.toml in the checkout is NOT a version
     * source: it says "0.0.0" (placeholder). */
    private static String probeHermesVersion(File rootfs) {
        try {
            File stamp = new File(rootfs, "root/.hermes/hermes-agent/install-stamp.json");
            if (!stamp.isFile()) return "";
            JSONObject json = new JSONObject(DistroUtils.readFile(stamp));
            String version = json.optString("displayVersion", "");
            if (version.isEmpty() || "unknown".equals(version)) {
                version = json.optString("baseVersion", "");
            }
            if (version.isEmpty() || "unknown".equals(version)) return "";
            return version;
        } catch (Exception e) {
            Log.w(LOG_TAG, "Failed to read Hermes install stamp", e);
            return "";
        }
    }

    /** Newest <version> directory name under a versions dir, or "".
     * Shared by Codex (~/.codex/.../releases) and Claude Code
     * (~/.local/share/claude/versions). */
    private static String probeMaxVersionDir(File dir) {
        if (dir == null || !dir.isDirectory()) return "";
        File[] children = dir.listFiles();
        if (children == null) return "";
        List<String> versions = new ArrayList<>();
        for (File child : children) {
            if (child.isDirectory()
                && child.getName().matches("[0-9]+\\.[0-9]+\\.[0-9]+.*")) {
                versions.add(child.getName());
            }
        }
        if (versions.isEmpty()) return "";
        Collections.sort(versions, (a, b) -> compareVersionsDescending(a, b));
        return versions.get(0);
    }

    private static String probeAiderVersion(File rootfs) {
        // uv tool install: ~/.local/share/uv/tools/aider-chat/lib/python3.*/site-packages/...
        String version = readDistInfoVersion(
            new File(rootfs, "root/.local/share/uv/tools/aider-chat/lib"));
        if (!version.isEmpty()) return version;
        // pipx install: ~/.local/share/pipx/venvs/aider-chat/lib/python3.*/site-packages/...
        return readDistInfoVersion(
            new File(rootfs, "root/.local/share/pipx/venvs/aider-chat/lib"));
    }

    private static String readDistInfoVersion(File libDir) {
        if (libDir == null || !libDir.isDirectory()) return "";
        File[] pythons = libDir.listFiles();
        if (pythons == null) return "";
        for (File python : pythons) {
            File sitePackages = new File(python, "site-packages");
            if (!sitePackages.isDirectory()) continue;
            File[] children = sitePackages.listFiles();
            if (children == null) continue;
            for (File child : children) {
                String name = child.getName();
                if (child.isDirectory() && name.startsWith("aider_chat-")
                    && name.endsWith(".dist-info")) {
                    try {
                        String metadata = DistroUtils.readFile(new File(child, "METADATA"));
                        for (String line : metadata.split("\n")) {
                            if (line.startsWith("Version: ")) {
                                return line.substring("Version: ".length()).trim();
                            }
                        }
                    } catch (Exception e) {
                        Log.w(LOG_TAG, "Failed to read aider METADATA", e);
                    }
                }
            }
        }
        return "";
    }

    /** Newest version first. Mirrors DistroActivity.sortVersionStringsDescending. */
    private static int compareVersionsDescending(String a, String b) {
        String[] partsA = a.split("[.-]");
        String[] partsB = b.split("[.-]");
        int length = Math.max(partsA.length, partsB.length);
        for (int i = 0; i < length; i++) {
            int numberA = i < partsA.length ? leadingInt(partsA[i]) : 0;
            int numberB = i < partsB.length ? leadingInt(partsB[i]) : 0;
            if (numberA != numberB) return Integer.compare(numberB, numberA);
        }
        return 0;
    }

    /** Version recorded by the install/update script for agy
     * (~/.termux-aiagents/versions/agy inside the container). "" when the
     * record is missing (installed before the record existed) or does not
     * look like a version — the list then shows no update badge for agy
     * until its next install/update writes the record. */
    private static String probeAgyRecordedVersion(File rootfs) {
        File record = new File(rootfs, AGY_VERSION_RECORD_REL_PATH);
        if (!record.isFile()) return "";
        try {
            String version = DistroUtils.readFile(record).trim();
            return isComparableVersion(version) ? version : "";
        } catch (Exception e) {
            Log.w(LOG_TAG, "Failed to read agy version record", e);
            return "";
        }
    }

    private static int leadingInt(String text) {
        int end = 0;
        while (end < text.length() && Character.isDigit(text.charAt(end))) end++;
        if (end == 0) return 0;
        try {
            return Integer.parseInt(text.substring(0, end));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------
    // Version comparison (update detection)
    // ------------------------------------------------------------------

    /** True when both versions parse (see isComparableVersion) and latest
     * is strictly newer than installed. Anything unparseable on either
     * side means "unknown" — no badge, never an error. */
    public static boolean isNewerVersionAvailable(String installed, String latest) {
        if (installed == null || latest == null) return false;
        String a = stripLeadingV(installed.trim());
        String b = stripLeadingV(latest.trim());
        if (!isComparableVersion(a) || !isComparableVersion(b)) return false;
        return compareVersions(b, a) > 0;
    }

    /** Comparable versions look like 1.6.0, 0.2.2-rc.5 or 2.1.330: numeric
     * core, optional prerelease suffix after '-'. A leading 'v' is
     * stripped by the caller (see isNewerVersionAvailable). */
    public static boolean isComparableVersion(String version) {
        if (version == null || version.isEmpty()) return false;
        return version.matches("[0-9]+(\\.[0-9]+)*(-[0-9A-Za-z][0-9A-Za-z.-]*)?");
    }

    private static String stripLeadingV(String version) {
        if (version != null && version.length() > 1
            && (version.charAt(0) == 'v' || version.charAt(0) == 'V')
            && Character.isDigit(version.charAt(1))) {
            return version.substring(1);
        }
        return version;
    }

    /** Compare two parsed versions: > 0 when a is newer, < 0 when older,
     * 0 when equal. Numeric core segments compare numerically (a missing
     * segment counts as 0, so 1.6 == 1.6.0); when the cores are equal, a
     * release outranks the same number with a prerelease suffix
     * (1.0.0 > 1.0.0-rc.1), and two suffixes compare segment by segment
     * (rc.5 > rc.2, beta > alpha). Callers must check isComparableVersion
     * first; anything else compares as 0 here. */
    public static int compareVersions(String a, String b) {
        a = stripLeadingV(a);
        b = stripLeadingV(b);
        String[] coreA = coreOf(a).split("\\.");
        String[] coreB = coreOf(b).split("\\.");
        int length = Math.max(coreA.length, coreB.length);
        for (int i = 0; i < length; i++) {
            int numberA = i < coreA.length ? leadingInt(coreA[i]) : 0;
            int numberB = i < coreB.length ? leadingInt(coreB[i]) : 0;
            if (numberA != numberB) return Integer.compare(numberA, numberB);
        }
        String preA = prereleaseOf(a);
        String preB = prereleaseOf(b);
        if (preA.isEmpty() && preB.isEmpty()) return 0;
        if (preA.isEmpty()) return 1;
        if (preB.isEmpty()) return -1;
        String[] partsA = preA.split("[.-]");
        String[] partsB = preB.split("[.-]");
        int preLength = Math.max(partsA.length, partsB.length);
        for (int i = 0; i < preLength; i++) {
            String segmentA = i < partsA.length ? partsA[i] : "";
            String segmentB = i < partsB.length ? partsB[i] : "";
            if (segmentA.equals(segmentB)) continue;
            int numberA = leadingInt(segmentA);
            int numberB = leadingInt(segmentB);
            if (numberA != numberB) return Integer.compare(numberA, numberB);
            return segmentA.compareTo(segmentB);
        }
        return 0;
    }

    /** Numeric core: everything before the first '-' ("0.2.2-rc.5" -> "0.2.2"). */
    private static String coreOf(String version) {
        int dash = version.indexOf('-');
        return dash < 0 ? version : version.substring(0, dash);
    }

    /** Prerelease suffix: everything after the first '-', or "" when none. */
    private static String prereleaseOf(String version) {
        int dash = version.indexOf('-');
        return dash < 0 ? "" : version.substring(dash + 1);
    }

    // ------------------------------------------------------------------
    // Latest versions (update detection)
    // ------------------------------------------------------------------

    /**
     * The latest version an agent can be installed at right now, fetched
     * live from the agent's own public source — or "" when the agent has
     * no known source or the fetch failed. No auth, no login, and no
     * version is ever hardcoded: the values below are only URLs and JSON
     * field names. Every failure (timeout, HTTP 403 rate limit, bad
     * body, unparseable version) is silent — the list simply shows no
     * update badge for that agent.
     *
     * Sources, all verified live on 2026-11-21 (see update-check
     * design.md §1):
     * - Pi / dsh / OpenCode: npm registry .../latest, JSON "version".
     * - Aider: PyPI JSON API, info.version.
     * - Codex: GitHub releases/latest, "name" (plain version); fallback
     *   strips the "rust-v" prefix from "tag_name".
     * - Claude Code: plain-text version at downloads.claude.ai.
     * - agy: the release manifest the official installer itself reads
     *   (glibc vs musl manifest by distro; Alpine is musl).
     * - Hermes: GitHub releases/latest, tag_name minus the leading "v".
     *   Dormant until the Hermes AgentDef lands (no such id exists yet);
     *   the badge additionally needs an installed version, which Hermes
     *   has no source for in v1, so nothing shows for it either way.
     */
    public static String fetchLatestVersion(AgentDef def, String distroId) {
        if (def == null) return "";
        try {
            switch (def.id) {
                case "pi":
                case "dsh":
                case "opencode":
                    return fetchNpmLatest(def.npmPackage);
                case "aider":
                    return fetchPyPILatest();
                case "codex":
                    return fetchCodexLatest();
                case "claude":
                    return fetchClaudeLatest();
                case "agy":
                    return fetchAgyLatest(distroId);
                case "hermes":
                    return fetchGithubTagLatest(
                        "https://api.github.com/repos/NousResearch/hermes-agent/releases/latest");
                default:
                    return "";
            }
        } catch (Exception e) {
            Log.w(LOG_TAG, "Failed to fetch latest version for " + def.id, e);
            return "";
        }
    }

    /** npm registry .../latest -> JSON "version" (Pi, dsh, OpenCode). */
    private static String fetchNpmLatest(String npmPackage) {
        if (npmPackage == null || npmPackage.isEmpty()) return "";
        try {
            String body = fetchUrlText("https://registry.npmjs.org/" + npmPackage + "/latest");
            if (body == null) return "";
            String version = new JSONObject(body).optString("version", "").trim();
            return validatedVersion(version);
        } catch (Exception e) {
            Log.w(LOG_TAG, "Failed to fetch npm latest for " + npmPackage, e);
            return "";
        }
    }

    /** PyPI JSON API -> info.version (Aider). */
    private static String fetchPyPILatest() {
        try {
            String body = fetchUrlText("https://pypi.org/pypi/aider-chat/json");
            if (body == null) return "";
            JSONObject info = new JSONObject(body).optJSONObject("info");
            if (info == null) return "";
            return validatedVersion(info.optString("version", "").trim());
        } catch (Exception e) {
            Log.w(LOG_TAG, "Failed to fetch PyPI latest for aider-chat", e);
            return "";
        }
    }

    /** GitHub releases/latest for Codex: prefer "name" (a plain version
     * like "0.171.0"); fall back to "tag_name" minus its "rust-v" prefix. */
    private static String fetchCodexLatest() {
        try {
            String body = fetchUrlText("https://api.github.com/repos/openai/codex/releases/latest");
            if (body == null) return "";
            JSONObject release = new JSONObject(body);
            String name = validatedVersion(release.optString("name", "").trim());
            if (!name.isEmpty()) return name;
            String tag = release.optString("tag_name", "").trim();
            if (tag.startsWith("rust-v")) tag = tag.substring("rust-v".length());
            return validatedVersion(tag);
        } catch (Exception e) {
            Log.w(LOG_TAG, "Failed to fetch GitHub latest for codex", e);
            return "";
        }
    }

    /** GitHub releases/latest -> tag_name minus a leading "v"
     * (Hermes: "v0.23.3" -> "0.23.3"). */
    private static String fetchGithubTagLatest(String url) {
        try {
            String body = fetchUrlText(url);
            if (body == null) return "";
            String tag = new JSONObject(body).optString("tag_name", "").trim();
            return validatedVersion(stripLeadingV(tag));
        } catch (Exception e) {
            Log.w(LOG_TAG, "Failed to fetch GitHub latest from " + url, e);
            return "";
        }
    }

    /** Claude Code: the installer downloads a plain-text version from this
     * URL first (see claude.ai/install.sh), so the app reads the same file. */
    private static String fetchClaudeLatest() {
        try {
            String body = fetchUrlText("https://downloads.claude.ai/claude-code-releases/latest");
            if (body == null) return "";
            return validatedVersion(body.trim());
        } catch (Exception e) {
            Log.w(LOG_TAG, "Failed to fetch Claude Code latest version", e);
            return "";
        }
    }

    /** The release manifest the official agy installer itself reads: its
     * "version" field is what a fresh install would give. Alpine uses the
     * musl manifest, every other distro the glibc one. */
    private static String fetchAgyLatest(String distroId) {
        try {
            String manifest = "linux_arm64.json";
            if ("alpine".equals(distroId)) manifest = "linux_arm64_musl.json";
            String body = fetchUrlText("https://antigravity-cli-auto-updater-974169037036"
                + ".us-central1.run.app/manifests/" + manifest);
            if (body == null) return "";
            String version = new JSONObject(body).optString("version", "").trim();
            return validatedVersion(version);
        } catch (Exception e) {
            Log.w(LOG_TAG, "Failed to fetch agy manifest", e);
            return "";
        }
    }

    /** A fetched version string, or "" when it does not parse (a changed
     * endpoint must never produce a bogus badge). */
    private static String validatedVersion(String version) {
        if (version == null) return "";
        String stripped = stripLeadingV(version.trim());
        return isComparableVersion(stripped) ? stripped : "";
    }

    /** One HTTPS GET; null on any failure (timeout, non-200, empty body).
     * Same HttpURLConnection idiom as DistroActivity's Docker Hub fetch. */
    private static String fetchUrlText(String urlString) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(urlString);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(UPDATE_CHECK_CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(UPDATE_CHECK_READ_TIMEOUT_MS);
            connection.setRequestProperty("User-Agent", "termux-app-zh-aiagents/1.0");
            connection.setRequestProperty("Accept", "application/json, text/plain, */*");
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) return null;
            return readStream(connection.getInputStream());
        } catch (Exception e) {
            Log.w(LOG_TAG, "Fetch failed for " + urlString, e);
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String readStream(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toString(StandardCharsets.UTF_8.name());
    }

    // ------------------------------------------------------------------
    // Latest-version cache (update detection)
    // ------------------------------------------------------------------

    /** One cached check: when it ran, and the latest version per agent id. */
    public static final class UpdateCache {
        public final long checkedAt;
        public final Map<String, String> latest;

        UpdateCache(long checkedAt, Map<String, String> latest) {
            this.checkedAt = checkedAt;
            this.latest = latest;
        }
    }

    /** Cached latest versions, or an empty cache when there is none or it
     * is corrupt (the next check rebuilds it). */
    public static UpdateCache loadUpdateCache() {
        Map<String, String> latest = new HashMap<>();
        File cacheFile = new File(TermuxConstants.TERMUX_DATA_HOME_DIR, UPDATE_CACHE_FILE_NAME);
        if (!cacheFile.isFile()) return new UpdateCache(0, latest);
        try {
            JSONObject root = new JSONObject(DistroUtils.readFile(cacheFile));
            long checkedAt = root.optLong("checkedAt", 0);
            JSONObject versions = root.optJSONObject("latest");
            if (versions != null) {
                Iterator<String> ids = versions.keys();
                while (ids.hasNext()) {
                    String id = ids.next();
                    String version = versions.optString(id, "");
                    if (!version.isEmpty()) latest.put(id, version);
                }
            }
            return new UpdateCache(checkedAt, latest);
        } catch (Exception e) {
            Log.w(LOG_TAG, "Failed to read update cache", e);
            return new UpdateCache(0, latest);
        }
    }

    /** True when the cache was checked within the last day. */
    public static boolean isUpdateCacheValid(long checkedAt) {
        return checkedAt > 0
            && System.currentTimeMillis() - checkedAt < UPDATE_CACHE_VALID_MS;
    }

    /** Save latest versions with "now" as the check time (AtomicFile, same
     * as the distro list cache). Callers pass the merged map: freshly
     * fetched values overwrite the old cache, failed fetches keep theirs. */
    public static void saveUpdateCache(Map<String, String> latest) {
        try {
            JSONObject root = new JSONObject();
            root.put("checkedAt", System.currentTimeMillis());
            JSONObject versions = new JSONObject();
            for (Map.Entry<String, String> entry : latest.entrySet()) {
                if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                    versions.put(entry.getKey(), entry.getValue());
                }
            }
            root.put("latest", versions);
            File cacheFile = new File(TermuxConstants.TERMUX_DATA_HOME_DIR, UPDATE_CACHE_FILE_NAME);
            File parent = cacheFile.getParentFile();
            if (parent != null && !(parent.isDirectory() || parent.mkdirs())) return;
            AtomicFile atomicFile = new AtomicFile(cacheFile);
            FileOutputStream out = atomicFile.startWrite();
            try {
                out.write(root.toString().getBytes(StandardCharsets.UTF_8));
                atomicFile.finishWrite(out);
            } catch (IOException e) {
                atomicFile.failWrite(out);
                throw e;
            }
        } catch (Exception e) {
            Log.w(LOG_TAG, "Failed to save update cache", e);
        }
    }

    // ------------------------------------------------------------------
    // Launch an installed agent in a new visible terminal session
    // ------------------------------------------------------------------

    /**
     * Open a new visible terminal session that runs the agent directly
     * inside its container: `proot-distro login <container> -- <absolute
     * binary path>`. The binary path is absolute (see the class header for
     * why a bare name would not resolve). Does not finish or close
     * anything; the caller decides (TermuxActivity closes the drawer first,
     * DistroActivity finishes itself).
     */
    public static void startAgentSession(Context context, InstalledAgent agent) {
        String prootDistro = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "proot-distro")
            .getAbsolutePath();
        if (!new File(prootDistro).isFile()) {
            Toast.makeText(context, R.string.distro_proot_distro_missing, Toast.LENGTH_LONG).show();
            return;
        }
        String sessionName = context.getString(R.string.aiagents_quick_session,
            agent.displayName, agent.distroDisplay);
        // Codex/agy read their key from the process environment (env_key /
        // GEMINI_API_KEY) and a quick launch is not a login shell, so
        // .bashrc never runs: prefix the launch with `env VAR=value ...`
        // taken from the container's model-env.sh (written by
        // ModelConfigApplier). Passed as argv, never shell-parsed, so any
        // key characters are safe.
        List<String> args = new ArrayList<>();
        args.add("login");
        args.add(agent.container);
        args.add("--");
        List<String> envArgs = ModelConfigApplier.envArgsForAgent(
            DistroUtils.containerRootfs(agent.container), agent.agentId);
        if (!envArgs.isEmpty()) {
            args.add("env");
            args.addAll(envArgs);
        }
        args.add(agent.inContainerPath());
        DistroUtils.startSessionWithExecutable(context, prootDistro,
            args.toArray(new String[0]), sessionName, sessionName);
    }

    // ------------------------------------------------------------------
    // Open an installed agent's web UI in the phone browser
    // ------------------------------------------------------------------

    /**
     * Start a web-capable agent's server inside its container (visible
     * session, same script-file method as the install flow) and explain how
     * to reach it. The explanation dialog is shown FIRST and the session
     * starts only from its positive button: starting the session first
     * would switch to the new session and bring TermuxActivity to the
     * foreground (DistroUtils.startSessionWithExecutable uses
     * VALUE_EXTRA_SESSION_ACTION_SWITCH_TO_NEW_SESSION_AND_OPEN_ACTIVITY),
     * hiding the dialog behind the terminal until the user presses Back.
     * The server runs while the session is open; closing the session stops
     * it (proot has no systemd, so there is no background daemon). For
     * OpenCode the app probes 127.0.0.1:4096 from its own process before
     * showing the dialog (proot shares the phone's network namespace, so
     * no in-container exec is needed): when a server is already running,
     * the dialog also offers "Restart server", which starts a session
     * whose script first kills the old serve and then starts a fresh one
     * with the stable password. There is deliberately NO auto-kill: an
     * accidental tap must never kill a running server, so the restart is
     * opt-in only. The normal launch keeps the guarded script (skip if a
     * server is already listening). A server started before the stable
     * password (Build #83) still holds its old random password, so the
     * restart is how the dialog password and the running server are
     * brought back in sync.
     *
     * The first web launch (any of the four agents) also appends a
     * one-time battery hint to the dialog: the server only stays up while
     * Termux keeps running in the background, and some phones (Vivo and
     * other aggressive vendors) freeze Termux when it is backgrounded,
     * which stalls the web UI until Termux is foregrounded again. The
     * hint is shown at most once (SharedPreferences flag) and never
     * blocks or delays the launch.
     *
     * The four agents differ in what the app can open by itself:
     * - dsh: `dsh web` serves :3080, but the startup URL carries a fresh
     *   per-start session token, so the app cannot build a working URL.
     *   The dialog tells the user to copy the token URL from the session.
     * - OpenCode (v2): there is NO `opencode web` command in v2. The web
     *   UI is served by `opencode serve --port 4096`, password-protected.
     *   The password is stable per container (see getOrCreateWebPassword):
     *   it must not change between launches, because the web UI signs in
     *   with a session cookie and changing the server password signs
     *   every browser session out (dev.opencode.ai/docs/cli/web) — a
     *   fresh password per launch is what made the browser show
     *   "已从此服务器退出登录" right after a correct login. The app
     *   passes it via OPENCODE_PASSWORD (and OPENCODE_SERVER_PASSWORD as
     *   a fallback), shows it in the dialog, and opens
     *   http://127.0.0.1:4096 with an ACTION_VIEW intent; the user types
     *   the password into the web UI once and stays signed in.
     *   (`opencode pair` is NOT a fallback here: it needs the shared
     *   background service, not a standalone `opencode serve`.)
     * - Pi: the official pi-webapp has no fixed port; the launcher prints
     *   a token-bearing local address, so the app cannot build a URL.
     *   The dialog tells the user to copy the address from the session
     *   (or run `npx pi-webapp status` there). PI_WEBAPP_AUTO_OPEN=0
     *   stops the launcher from trying to open a browser inside the
     *   container. The launcher may exit after starting its background
     *   service, so the script keeps the session open with a read prompt.
     * - Hermes: `hermes dashboard` serves the built-in web UI on fixed
     *   port 9119, binds 127.0.0.1 only and needs no password on
     *   loopback, so the app opens http://127.0.0.1:9119 directly, like
     *   OpenCode. The first run downloads pnpm and builds the web UI's
     *   JavaScript dependencies, so the dialog says to give it a moment.
     *   The script resolves the `hermes` binary itself (~/.local/bin
     *   first, the launcher inside the checkout as fallback) because
     *   ~/.local/bin is not on PATH in the script shell. If a dashboard
     *   is already listening on 9119 (e.g. the user tapped web launch
     *   twice), the script prints so and exits instead of starting a
     *   second one. No restart action in v1: there is no password to
     *   resync, and `hermes dashboard --stop` can stop a running
     *   dashboard from a session if ever needed.
     *
     * @param onDialogDismissed run when the explanation dialog is closed
     *        (DistroActivity passes finish() here); null is fine.
     */
    /** Rootfs-relative path of a container's stable OpenCode web password. */
    private static final String OPENCODE_WEB_PASSWORD_REL_PATH =
        "root/.termux-aiagents/opencode-web-password";

    /** 12 lowercase hex chars from a cryptographic RNG. */
    private static String newRandomWebPassword() {
        byte[] bytes = new byte[6];
        new SecureRandom().nextBytes(bytes);
        final char[] hex = "0123456789abcdef".toCharArray();
        StringBuilder s = new StringBuilder(12);
        for (byte b : bytes) {
            s.append(hex[(b >> 4) & 0xF]);
            s.append(hex[b & 0xF]);
        }
        return s.toString();
    }

    /**
     * Returns this container's stable OpenCode web password, creating and
     * storing it on first use. The password must not change between
     * launches: the web UI signs in with a session cookie, and changing
     * the server password signs every browser session out
     * (dev.opencode.ai/docs/cli/web). It is stored in the container
     * rootfs, so it survives as long as the container does. If the
     * rootfs cannot be found, or the stored file is empty, or the write
     * fails, a fresh random password is returned for this call only and
     * the launch still works exactly as before.
     */
    private static String getOrCreateWebPassword(Context context, String container) {
        String fallback = newRandomWebPassword();
        File rootfs = DistroUtils.containerRootfs(container);
        if (rootfs == null) return fallback;
        File passwordFile = new File(rootfs, OPENCODE_WEB_PASSWORD_REL_PATH);
        try {
            if (passwordFile.isFile()) {
                String stored = DistroUtils.readFile(passwordFile).trim();
                if (!stored.isEmpty()) return stored;
            }
        } catch (Exception e) {
            Log.w(LOG_TAG, "Failed to read stored OpenCode web password", e);
        }
        String password = newRandomWebPassword();
        try {
            File parent = passwordFile.getParentFile();
            if (parent != null && !(parent.isDirectory() || parent.mkdirs())) {
                return fallback;
            }
            try (FileOutputStream out = new FileOutputStream(passwordFile)) {
                out.write(password.getBytes(StandardCharsets.UTF_8));
            }
            passwordFile.setReadable(false, false);
            passwordFile.setReadable(true, true);
            passwordFile.setWritable(false, false);
            passwordFile.setWritable(true, true);
            passwordFile.setExecutable(false, false);
            return password;
        } catch (Exception e) {
            Log.w(LOG_TAG, "Failed to store OpenCode web password", e);
            passwordFile.delete();
            return fallback;
        }
    }

    public static void startWebSession(final Context context, final InstalledAgent agent,
                                       final Runnable onDialogDismissed) {
        final AgentDef def = findDef(agent.agentId);
        if (def == null || def.webKind == WebKind.NONE) return;
        final String webPassword;
        if (def.webKind == WebKind.OPENCODE) {
            webPassword = getOrCreateWebPassword(context, agent.container);
        } else {
            webPassword = null;
        }
        File rootfs = DistroUtils.containerRootfs(agent.container);
        if (rootfs == null) {
            Toast.makeText(context, R.string.aiagents_no_rootfs, Toast.LENGTH_LONG).show();
            return;
        }
        String scriptName = "termux-web-" + agent.agentId + "-" + agent.container + ".sh";
        File scriptFile = new File(rootfs, "tmp/" + scriptName);
        try {
            File parent = scriptFile.getParentFile();
            if (parent != null && !(parent.isDirectory() || parent.mkdirs())) {
                throw new IOException("Cannot create " + parent.getAbsolutePath());
            }
            try (FileOutputStream out = new FileOutputStream(scriptFile)) {
                out.write(buildWebScript(def, agent, webPassword)
                    .getBytes(StandardCharsets.UTF_8));
            }
            scriptFile.setReadable(true, true);
        } catch (Exception e) {
            Log.w(LOG_TAG, "Failed to write web launch script", e);
            Toast.makeText(context, context.getString(R.string.aiagents_web_script_failed,
                e.getMessage()), Toast.LENGTH_LONG).show();
            return;
        }
        // Fail fast when proot-distro is missing: no dialog is shown then.
        final String prootDistro = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "proot-distro")
            .getAbsolutePath();
        if (!new File(prootDistro).isFile()) {
            Toast.makeText(context, R.string.distro_proot_distro_missing, Toast.LENGTH_LONG).show();
            return;
        }
        // Dialog FIRST, session second (see the javadoc above): the session
        // start is deferred into a Runnable that only the dialog's positive
        // button runs. Cancel starts nothing.
        final String sessionName = context.getString(R.string.aiagents_web_session,
            agent.displayName, agent.distroDisplay);
        final Runnable startSession = () -> DistroUtils.startSessionWithExecutable(context, prootDistro,
            new String[]{"login", agent.container, "--", "bash", "/tmp/" + scriptName},
            sessionName, sessionName);
        // OpenCode only: the same script with a "restart" argument first
        // kills any old serve, then starts a fresh one with the stable
        // password. It is only started from the dialog's opt-in
        // "Restart server" action, never automatically.
        final Runnable startRestartSession = () -> DistroUtils.startSessionWithExecutable(context, prootDistro,
            new String[]{"login", agent.container, "--", "bash", "/tmp/" + scriptName, "restart"},
            sessionName, sessionName);
        if (def.webKind == WebKind.OPENCODE) {
            // Probe the port off the UI thread so the dialog can offer
            // "Restart server" only when a server may already be running.
            probeAndShowWebDialog(context, def, agent, webPassword,
                startSession, startRestartSession, onDialogDismissed);
        } else {
            showWebDialog(context, def, agent, webPassword, startSession,
                null, false, onDialogDismissed);
        }
    }

    /**
     * Probe whether something is listening on a web port, from the app
     * process (same network namespace as the container — proot shares
     * it), so the dialog can offer "Restart server" only when a server
     * may already be running. TRUE = a server answered, FALSE = the port
     * is definitely closed (connection refused), null = unknown (e.g.
     * timed out): the restart action is shown then too, since it is
     * harmless when nothing is running (it just starts a fresh server).
     */
    private static Boolean probeWebPort(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), WEB_PORT_PROBE_TIMEOUT_MS);
            return Boolean.TRUE;
        } catch (java.net.ConnectException e) {
            return Boolean.FALSE;
        } catch (Exception e) {
            Log.w(LOG_TAG, "Web port probe failed", e);
            return null;
        }
    }

    /** Probe the OpenCode port off the UI thread, then show the web dialog on it. */
    private static void probeAndShowWebDialog(final Context context, final AgentDef def,
                                              final InstalledAgent agent, final String webPassword,
                                              final Runnable onStartSession,
                                              final Runnable onStartRestartSession,
                                              final Runnable onDialogDismissed) {
        new Thread(() -> {
            final Boolean running = probeWebPort(def.webPort);
            new Handler(Looper.getMainLooper()).post(() -> {
                if (context instanceof Activity && ((Activity) context).isFinishing()) return;
                showWebDialog(context, def, agent, webPassword, onStartSession,
                    onStartRestartSession, running != Boolean.FALSE, onDialogDismissed);
            });
        }).start();
    }

    /**
     * The one-time battery hint for web launches, shown as a note inside
     * the web dialog. Returns the hint text the first time any web UI is
     * launched (and marks it shown), null afterwards. The flag lives in a
     * private SharedPreferences file so it survives app updates.
     */
    private static String consumeWebBatteryHint(Context context) {
        SharedPreferences prefs = SharedPreferenceUtils.getPrivateSharedPreferences(
            context, AI_AGENTS_PREFS_NAME);
        if (prefs.getBoolean(PREF_WEB_BATTERY_HINT_SHOWN, false)) return null;
        prefs.edit().putBoolean(PREF_WEB_BATTERY_HINT_SHOWN, true).apply();
        return context.getString(R.string.aiagents_web_battery_hint);
    }

    /**
     * Explanation dialog for a web launch; OpenCode also gets an "Open browser" button.
     * The positive button starts the session (and, for OpenCode, opens the
     * browser); Cancel starts nothing. The dismiss listener still runs
     * onDialogDismissed in every case, including Cancel. OpenCode shows a
     * neutral "Restart server" button only when showRestart is true (a
     * server may already be running, probed before the dialog): it starts
     * the same script with a "restart" argument and opens the browser.
     * The first web launch appends the one-time battery hint to the
     * message (see consumeWebBatteryHint).
     */
    private static void showWebDialog(final Context context, final AgentDef def,
                                      final InstalledAgent agent, final String webPassword,
                                      final Runnable onStartSession,
                                      final Runnable onStartRestartSession,
                                      final boolean showRestart,
                                      final Runnable onDialogDismissed) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.aiagents_web_title, agent.displayName))
            .setOnDismissListener(dialog -> {
                if (onDialogDismissed != null) onDialogDismissed.run();
            });
        final String batteryHint = consumeWebBatteryHint(context);
        switch (def.webKind) {
            case DSH: {
                String message = context.getString(R.string.aiagents_web_dsh_msg);
                if (batteryHint != null) message += "\n\n" + batteryHint;
                builder.setMessage(message)
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                        if (onStartSession != null) onStartSession.run();
                    });
                break;
            }
            case PI: {
                String message = context.getString(R.string.aiagents_web_pi_msg);
                if (batteryHint != null) message += "\n\n" + batteryHint;
                builder.setMessage(message)
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                        if (onStartSession != null) onStartSession.run();
                    });
                break;
            }
            case HERMES: {
                // Fixed address, no password on loopback: open the browser
                // directly, like OpenCode. No restart button in v1 (there
                // is no password to resync).
                String message = context.getString(R.string.aiagents_web_hermes_msg);
                if (batteryHint != null) message += "\n\n" + batteryHint;
                builder.setMessage(message)
                    .setPositiveButton(R.string.aiagents_web_open_browser, (dialog, which) -> {
                        if (onStartSession != null) onStartSession.run();
                        Intent intent = new Intent(Intent.ACTION_VIEW,
                            Uri.parse("http://127.0.0.1:" + def.webPort));
                        context.startActivity(intent);
                    })
                    .setNegativeButton(android.R.string.cancel, null);
                break;
            }
            case OPENCODE: {
                String message = context.getString(R.string.aiagents_web_opencode_msg, webPassword);
                if (showRestart) {
                    message += "\n" + context.getString(R.string.aiagents_web_restart_note);
                }
                if (batteryHint != null) message += "\n\n" + batteryHint;
                builder.setMessage(message)
                    .setPositiveButton(R.string.aiagents_web_open_browser, (dialog, which) -> {
                        if (onStartSession != null) onStartSession.run();
                        Intent intent = new Intent(Intent.ACTION_VIEW,
                            Uri.parse("http://127.0.0.1:" + def.webPort));
                        context.startActivity(intent);
                    })
                    .setNegativeButton(android.R.string.cancel, null);
                if (showRestart) {
                    builder.setNeutralButton(R.string.aiagents_web_restart_server, (dialog, which) -> {
                        if (onStartRestartSession != null) onStartRestartSession.run();
                        Intent intent = new Intent(Intent.ACTION_VIEW,
                            Uri.parse("http://127.0.0.1:" + def.webPort));
                        context.startActivity(intent);
                    });
                }
                break;
            }
            default:
                return;
        }
        builder.show();
    }

    /** The script a web launch runs inside the container (as root). */
    private static String buildWebScript(AgentDef def, InstalledAgent agent, String webPassword) {
        StringBuilder s = new StringBuilder();
        s.append("#!/bin/bash\n");
        s.append("# Generated by Termux \"AI 助手\" web launch - do not edit.\n");
        switch (def.webKind) {
            case DSH:
                s.append("echo \"正在启动 dsh 网页版（").append(agent.distroDisplay).append("）…\"\n");
                s.append("echo \"启动后在下面找到 dsh web: http://127.0.0.1:3080/?token=… 这一行，复制完整地址到手机浏览器打开。\"\n");
                s.append("echo \"注意：直接打开 http://127.0.0.1:3080 进不去，必须带 token 的完整地址。\"\n");
                s.append("command -v dsh >/dev/null 2>&1 || { echo \"没有找到 dsh，请先在“AI 助手”里安装。\"; read -r -p \"按回车关闭此会话\"; exit 1; }\n");
                s.append("cd ~ || true\n");
                // dsh reads its key from the environment (apiKeyEnv in
                // cordis.patch.yml); this script's shell is not a login
                // shell, so source the model env file written by
                // ModelConfigApplier. Missing file = no model config, skip.
                s.append("[ -f \"$HOME/.termux-aiagents/model-env.sh\" ] && . \"$HOME/.termux-aiagents/model-env.sh\"\n");
                s.append("dsh web --no-open\n");
                break;
            case OPENCODE:
                s.append("echo \"正在启动 OpenCode 网页版（").append(agent.distroDisplay).append("）…\"\n");
                s.append("echo \"地址：http://127.0.0.1:").append(def.webPort).append("\"\n");
                s.append("echo \"密码：").append(webPassword).append("（也在手机弹出的对话框里）\"\n");
                s.append("command -v opencode >/dev/null 2>&1 || { echo \"没有找到 opencode，请先在“AI 助手”里安装。\"; read -r -p \"按回车关闭此会话\"; exit 1; }\n");
                s.append("export OPENCODE_PASSWORD=\"").append(webPassword).append("\"\n");
                s.append("export OPENCODE_SERVER_PASSWORD=\"").append(webPassword).append("\"\n");
                // Opt-in restart (only when the script is run with a
                // "restart" argument, i.e. from the dialog's "Restart
                // server" action — never automatically): kill any old
                // serve first, so a server started before the stable
                // password (with a random password) cannot keep holding
                // the port while the dialog shows the new one. The pkill
                // pattern matches only our own serve invocation
                // ("opencode serve --hostname 127.0.0.1 --port 4096");
                // the script's own cmdline is "bash /tmp/termux-web-*.sh"
                // and does not match. If pkill is missing, or the port
                // never frees, the guard below prints the already-running
                // message instead of starting a second server.
                s.append("if [ \"$1\" = \"restart\" ]; then\n");
                s.append("    echo \"正在重启 OpenCode 网页版…\"\n");
                s.append("    if command -v pkill >/dev/null 2>&1; then\n");
                s.append("        pkill -f \"opencode serve --hostname 127.0.0.1 --port ").append(def.webPort).append("\" 2>/dev/null || true\n");
                s.append("        for i in 1 2 3 4 5 6 7 8 9 10; do\n");
                s.append("            (exec 3<>/dev/tcp/127.0.0.1/").append(def.webPort).append(") 2>/dev/null || break\n");
                s.append("            sleep 1\n");
                s.append("        done\n");
                s.append("    else\n");
                s.append("        echo \"没有找到 pkill，无法停掉旧服务，改用已有服务。\"\n");
                s.append("    fi\n");
                s.append("fi\n");
                // If a server is already listening on the port (e.g. the
                // user tapped web launch twice), do not start a second one:
                // it would fail to bind and the session would exit. The
                // password is stable, so the running server already
                // accepts it — just point at the browser instead.
                s.append("if (exec 3<>/dev/tcp/127.0.0.1/").append(def.webPort).append(") 2>/dev/null; then\n");
                s.append("    echo \"OpenCode 网页版已经在运行，密码不变，直接在浏览器里打开上面的地址登录即可。\"\n");
                s.append("    read -r -p \"按回车关闭此会话\"\n");
                s.append("    exit 0\n");
                s.append("fi\n");
                s.append("opencode serve --hostname 127.0.0.1 --port ").append(def.webPort).append("\n");
                break;
            case PI:
                s.append("echo \"正在启动 Pi 网页版（").append(agent.distroDisplay).append("）…\"\n");
                s.append("echo \"第一次启动会先下载官方 pi-webapp（npx），稍等一会。\"\n");
                s.append("echo \"启动后在下面找到带 token 的本地地址，复制到浏览器打开；\"\n");
                s.append("echo \"也可以在会话里跑 npx pi-webapp status 查看地址。\"\n");
                s.append("command -v pi >/dev/null 2>&1 || { echo \"没有找到 pi，请先在“AI 助手”里安装。\"; read -r -p \"按回车关闭此会话\"; exit 1; }\n");
                s.append("command -v npx >/dev/null 2>&1 || { echo \"没有找到 npx（需要 Node），请先在“AI 助手”里安装 Pi（会自动装 Node 24）。\"; read -r -p \"按回车关闭此会话\"; exit 1; }\n");
                s.append("cd ~ || true\n");
                s.append("PI_WEBAPP_AUTO_OPEN=0 npx -y pi-webapp\n");
                s.append("# pi-webapp starts its service in the background and may exit at\n");
                s.append("# once; keep this session open so the service keeps running.\n");
                s.append("echo \"Pi 网页版服务运行中，关闭此会话即停止。\"\n");
                s.append("read -r -p \"按回车关闭此会话\"\n");
                break;
            case HERMES:
                s.append("echo \"正在启动 Hermes Agent 网页版（").append(agent.distroDisplay).append("）…\"\n");
                s.append("echo \"地址：http://127.0.0.1:").append(def.webPort).append("\"\n");
                s.append("echo \"不需要密码（只监听本机）。第一次启动会先下载 pnpm、编译网页界面，稍等一会。\"\n");
                // The `hermes` launcher lives in ~/.local/bin, which is not
                // on PATH in this script's non-login shell, so resolve it
                // explicitly: the convenience in ~/.local/bin first, the
                // real launcher inside the checkout as fallback.
                s.append("HERMES_BIN=\"$HOME/.local/bin/hermes\"\n");
                s.append("[ -x \"$HERMES_BIN\" ] || HERMES_BIN=\"$HOME/.hermes/hermes-agent/.hermes/bin/hermes\"\n");
                s.append("[ -x \"$HERMES_BIN\" ] || { echo \"没有找到 hermes，请先在“AI 助手”里安装。\"; read -r -p \"按回车关闭此会话\"; exit 1; }\n");
                // If a dashboard is already listening on the port (e.g. the
                // user tapped web launch twice), do not start a second one:
                // just point at the browser instead. No restart action in
                // v1 (no password to resync); `hermes dashboard --stop`
                // stops a running dashboard from a session if needed.
                s.append("if (exec 3<>/dev/tcp/127.0.0.1/").append(def.webPort).append(") 2>/dev/null; then\n");
                s.append("    echo \"Hermes 网页版已经在运行，直接在浏览器里打开上面的地址即可。\"\n");
                s.append("    read -r -p \"按回车关闭此会话\"\n");
                s.append("    exit 0\n");
                s.append("fi\n");
                s.append("\"$HERMES_BIN\" dashboard --host 127.0.0.1 --port ").append(def.webPort).append(" --no-open\n");
                s.append("echo \"Hermes 网页版已停止。\"\n");
                s.append("read -r -p \"按回车关闭此会话\"\n");
                break;
            default:
                break;
        }
        return s.toString();
    }

    // ------------------------------------------------------------------
    // `ai` shell command
    // ------------------------------------------------------------------

    /** The `ai` shell command, written to $PREFIX/bin on first use. */
    private static final String AI_COMMAND_FILE_NAME = "ai";
    private static final String AI_COMMAND_CONTENT =
        "#!/data/data/com.termux/files/usr/bin/sh\n" +
        "# `ai` - list and launch AI assistants installed inside proot-distro containers.\n" +
        "# Written by Termux (AiAgentUtils.ensureAiCommand); edits are overwritten.\n" +
        "# Usage:\n" +
        "#   ai            list installed assistants\n" +
        "#   ai list       same as above\n" +
        "#   ai <name>     launch an assistant (extra arguments are passed through)\n" +
        "#   ai dsh        same as `ai dsh web` (dsh has no terminal entry)\n" +
        "#   ai -h|--help  show this help\n" +
        "# Detection scans each container rootfs for the agent binaries. The table\n" +
        "# below must stay in sync with AiAgentUtils.AGENTS (same binary paths).\n" +
        "\n" +
        "PREFIX=/data/data/com.termux/files/usr\n" +
        "CONTAINERS_DIR=\"$PREFIX/var/lib/proot-distro/containers\"\n" +
        "LEGACY_DIR=\"$PREFIX/var/lib/proot-distro/installed-rootfs\"\n" +
        "\n" +
        "# id|display|rootfs-relative binary path (an agent may have two lines,\n" +
        "# e.g. npm installs in /usr/bin or /usr/local/bin).\n" +
        "AGENT_PATHS='\n" +
        "codex|Codex|root/.local/bin/codex\n" +
        "pi|Pi|usr/bin/pi\n" +
        "pi|Pi|usr/local/bin/pi\n" +
        "agy|Antigravity CLI|root/.local/bin/agy\n" +
        "dsh|DeepSeek Harness|usr/bin/dsh\n" +
        "dsh|DeepSeek Harness|usr/local/bin/dsh\n" +
        "opencode|OpenCode|usr/bin/opencode\n" +
        "opencode|OpenCode|usr/local/bin/opencode\n" +
        "aider|Aider|root/.local/bin/aider\n" +
        "claude|Claude Code|root/.local/bin/claude\n" +
        "hermes|Hermes Agent|root/.local/bin/hermes\n" +
        "hermes|Hermes Agent|root/.hermes/hermes-agent/.hermes/bin/hermes\n" +
        "'\n" +
        "\n" +
        "usage() {\n" +
        "    echo \"用法：ai [助手名] [传给助手的参数…]\"\n" +
        "    echo \"  ai            列出已安装的助手\"\n" +
        "    echo \"  ai list       同上\"\n" +
        "    echo \"  ai codex      在装有 Codex 的发行版里启动 Codex\"\n" +
        "    echo \"  ai dsh web    启动 dsh 网页版（地址在会话里，带 token）\"\n" +
        "    echo \"  ai hermes web  打开 Hermes 网页版（http://127.0.0.1:9119）\"\n" +
        "    echo \"  ai dsh      同上（dsh 没有终端入口，只提供网页版）\"\n" +
        "    echo \"  ai -h         显示帮助\"\n" +
        "    echo \"助手：codex、pi、agy、dsh、opencode、aider、claude、hermes\"\n" +
        "    echo \"网页版：dsh、opencode、pi、hermes（ai <助手名> web）\"\n" +
        "}\n" +
        "\n" +
        "# Print one line per installed agent: id|display|container|/absolute/path.\n" +
        "# The list uses raw container names (no display-name lookup in shell).\n" +
        "find_installed() {\n" +
        "    seen=\"\"\n" +
        "    for dir in \"$CONTAINERS_DIR\"/* \"$LEGACY_DIR\"/*; do\n" +
        "        [ -d \"$dir\" ] || continue\n" +
        "        case \"$dir\" in\n" +
        "            \"$CONTAINERS_DIR\"/*) rootfs=\"$dir/rootfs\" ;;\n" +
        "            *) rootfs=\"$dir\" ;;\n" +
        "        esac\n" +
        "        [ -d \"$rootfs\" ] || continue\n" +
        "        container=$(basename \"$dir\")\n" +
        "        printf '%s\\n' \"$AGENT_PATHS\" | while IFS='|' read -r id display rel; do\n" +
        "            [ -n \"$id\" ] || continue\n" +
        "            case \" $seen \" in *\" <$id:$container> \"*) continue ;; esac\n" +
        "            if [ -f \"$rootfs/$rel\" ]; then\n" +
        "                seen=\"$seen <$id:$container> \"\n" +
        "                printf '%s|%s|%s|/%s\\n' \"$id\" \"$display\" \"$container\" \"$rel\"\n" +
        "            fi\n" +
        "        done\n" +
        "    done\n" +
        "}\n" +
        "\n" +
        "if [ $# -ge 1 ]; then\n" +
        "    case \"$1\" in\n" +
        "        -h|--help) usage; exit 0 ;;\n" +
        "    esac\n" +
        "fi\n" +
        "\n" +
        "installed=$(find_installed)\n" +
        "\n" +
        "if [ $# -eq 0 ] || [ \"$1\" = \"list\" ]; then\n" +
        "    if [ -z \"$installed\" ]; then\n" +
        "        echo \"没有已安装的 AI 助手。先在“Linux 发行版”里点一个发行版，再选“安装 AI 助手”。\"\n" +
        "        exit 1\n" +
        "    fi\n" +
        "    printf '%s\\n' \"$installed\" | while IFS='|' read -r id display container path; do\n" +
        "        case \"$id\" in\n" +
        "            dsh|opencode|pi|hermes) tag=\" · 网页版\" ;;\n" +
        "            *) tag=\"\" ;;\n" +
        "        esac\n" +
        "        printf '%s — %s%s\\n' \"$display\" \"$container\" \"$tag\"\n" +
        "    done\n" +
        "    echo \"启动：ai <助手名>，网页版：ai <助手名> web，例如 ai dsh web\"\n" +
        "    exit 0\n" +
        "fi\n" +
        "\n" +
        "# Launch: match the name against the agent id or the lowercase display name.\n" +
        "wanted=\"$1\"\n" +
        "name=$(printf '%s' \"$wanted\" | tr 'A-Z' 'a-z')\n" +
        "shift\n" +
        "matches=$(printf '%s\\n' \"$installed\" | while IFS='|' read -r id display container path; do\n" +
        "    [ -n \"$id\" ] || continue\n" +
        "    lower=$(printf '%s' \"$display\" | tr 'A-Z' 'a-z')\n" +
        "    if [ \"$id\" = \"$name\" ] || [ \"$lower\" = \"$name\" ]; then\n" +
        "        printf '%s|%s|%s|%s\\n' \"$id\" \"$display\" \"$container\" \"$path\"\n" +
        "    fi\n" +
        "done)\n" +
        "count=$(printf '%s\\n' \"$matches\" | grep -c .)\n" +
        "if [ \"$count\" -eq 0 ]; then\n" +
        "    echo \"没有找到助手“$wanted”。\"\n" +
        "    if [ -n \"$installed\" ]; then\n" +
        "        echo \"已安装的助手：\"\n" +
        "        printf '%s\\n' \"$installed\" | while IFS='|' read -r id display container path; do\n" +
        "            printf '  %s — %s\\n' \"$display\" \"$container\"\n" +
        "        done\n" +
        "    else\n" +
        "        echo \"目前没有安装任何 AI 助手，先在“Linux 发行版”里安装。\"\n" +
        "    fi\n" +
        "    exit 1\n" +
        "fi\n" +
        "if [ \"$count\" -gt 1 ]; then\n" +
        "    echo \"“$wanted”装在多个发行版里，选一个：\"\n" +
        "    i=0\n" +
        "    printf '%s\\n' \"$matches\" | while IFS='|' read -r id display container path; do\n" +
        "        i=$((i + 1))\n" +
        "        printf '  %s) %s — %s\\n' \"$i\" \"$display\" \"$container\"\n" +
        "    done\n" +
        "    printf '编号：'\n" +
        "    read -r choice || exit 1\n" +
        "    case \"$choice\" in\n" +
        "        ''|*[!0-9]*) echo \"无效的选择。\"; exit 1 ;;\n" +
        "    esac\n" +
        "    if [ \"$choice\" -lt 1 ] || [ \"$choice\" -gt \"$count\" ]; then\n" +
        "        echo \"无效的选择。\"\n" +
        "        exit 1\n" +
        "    fi\n" +
        "    matches=$(printf '%s\\n' \"$matches\" | sed -n \"${choice}p\")\n" +
        "fi\n" +
        "agent_id=$(printf '%s' \"$matches\" | cut -d'|' -f1)\n" +
        "display=$(printf '%s' \"$matches\" | cut -d'|' -f2)\n" +
        "container=$(printf '%s' \"$matches\" | cut -d'|' -f3)\n" +
        "path=$(printf '%s' \"$matches\" | cut -d'|' -f4)\n" +
        "if ! command -v proot-distro >/dev/null 2>&1; then\n" +
        "    echo \"没有安装 proot-distro，请先执行：pkg install proot-distro\"\n" +
        "    exit 1\n" +
        "fi\n" +
        "# dsh has no terminal entry (upstream documents only `dsh web`), so\n" +
        "# `ai dsh` means `ai dsh web`. Extra arguments are ignored.\n" +
        "if [ \"$agent_id\" = \"dsh\" ] && [ \"$1\" != \"web\" ]; then\n" +
        "    if [ -n \"$1\" ]; then\n" +
        "        echo \"dsh 没有终端入口，只提供网页版；多余的参数已忽略。\"\n" +
        "    fi\n" +
        "    set -- web\n" +
        "fi\n" +
        "# Web UI: ai <name> web starts the agent's web server in this session\n" +
        "# and prints how to reach it. A shell command cannot open the phone\n" +
        "# browser, so open the printed address yourself. The server stops\n" +
        "# when this session closes.\n" +
        "if [ \"$1\" = \"web\" ]; then\n" +
        "    shift\n" +
        "    case \"$agent_id\" in\n" +
        "        dsh)\n" +
        "            echo \"启动 dsh 网页版（$container）…\"\n" +
        "            echo \"地址在会话里：找到 dsh web: http://127.0.0.1:3080/?token=… 这一行，复制完整地址到浏览器打开。\"\n" +
        "            exec proot-distro login \"$container\" -- dsh web --no-open \"$@\"\n" +
        "            ;;\n" +
        "        opencode)\n" +
        "            echo \"启动 OpenCode 网页版（$container）…\"\n" +
        "            PW_FILE=\"$PREFIX/var/lib/proot-distro/containers/$container/rootfs/root/.termux-aiagents/opencode-web-password\"\n" +
        "            [ -f \"$PW_FILE\" ] || PW_FILE=\"$PREFIX/var/lib/proot-distro/installed-rootfs/$container/root/.termux-aiagents/opencode-web-password\"\n" +
        "            PW=$(cat \"$PW_FILE\" 2>/dev/null)\n" +
        "            if [ -n \"$PW\" ]; then echo \"地址：http://127.0.0.1:4096，密码：$PW（固定密码，和弹框里的一样）。\"; else echo \"地址：http://127.0.0.1:4096，密码见会话里的 server password 一行。\"; fi\n" +
        "            exec proot-distro login \"$container\" -- bash -c 'PW=$(cat /root/.termux-aiagents/opencode-web-password 2>/dev/null); [ -n \"$PW\" ] && export OPENCODE_PASSWORD=\"$PW\" OPENCODE_SERVER_PASSWORD=\"$PW\"; exec opencode serve --hostname 127.0.0.1 --port 4096 \"$@\"' _ \"$@\"\n" +
        "            ;;\n" +
        "        pi)\n" +
        "            echo \"启动 Pi 网页版（$container）…\"\n" +
        "            echo \"地址在会话里（带 token 的链接），复制到浏览器打开；也可以跑 npx pi-webapp status 查看地址。\"\n" +
        "            exec proot-distro login \"$container\" -- bash -c 'PI_WEBAPP_AUTO_OPEN=0 npx -y pi-webapp; echo \"Pi 网页版服务运行中，关闭此会话即停止。\"; read -r -p \"按回车关闭此会话\"'\n" +
        "            ;;\n" +
        "        hermes)\n" +
        "            echo \"启动 Hermes Agent 网页版（$container）…\"\n" +
        "            echo \"地址：http://127.0.0.1:9119，第一次启动会先下载 pnpm、编译网页界面，稍等一会。\"\n" +
        "            exec proot-distro login \"$container\" -- \"$path\" dashboard --host 127.0.0.1 --port 9119 --no-open \"$@\"\n" +
        "            ;;\n" +
        "        *)\n" +
        "            echo \"“$display”没有网页版。带网页版的助手：dsh、opencode、pi、hermes。\"\n" +
        "            exit 1\n" +
        "            ;;\n" +
        "    esac\n" +
        "fi\n" +
        "echo \"启动 $display（$container）…\"\n" +
        "exec proot-distro login \"$container\" -- \"$path\" \"$@\"\n";

    /** Write the `ai` shell command to $PREFIX/bin if it is missing or outdated. */
    public static void ensureAiCommand() {
        try {
            File binDir = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH);
            if (!binDir.isDirectory()) return; // bootstrap not ready yet
            File commandFile = new File(binDir, AI_COMMAND_FILE_NAME);
            String existing = null;
            if (commandFile.isFile()) {
                existing = DistroUtils.readFile(commandFile);
            }
            if (AI_COMMAND_CONTENT.equals(existing)) return;
            try (FileOutputStream out = new FileOutputStream(commandFile)) {
                out.write(AI_COMMAND_CONTENT.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            commandFile.setExecutable(true, true);
            commandFile.setReadable(true, true);
            commandFile.setWritable(true, true);
        } catch (Exception e) {
            Log.w(LOG_TAG, "Failed to install `ai` command", e);
        }
    }

}
