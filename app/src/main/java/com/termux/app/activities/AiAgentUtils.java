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
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;
import android.util.Log;
import android.widget.Button;
import android.widget.Toast;

import com.termux.R;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.settings.preferences.SharedPreferenceUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;

public final class AiAgentUtils {

    private static final String LOG_TAG = "AiAgentUtils";

    /** SharedPreferences file for AI-agent UI state (one-time hints). */
    private static final String AI_AGENTS_PREFS_NAME = "ai_agents";

    /** Set once the first web-launch battery hint has been shown. */
    private static final String PREF_WEB_BATTERY_HINT_SHOWN = "web_battery_hint_shown";

    /** How long the app waits for the OpenCode pairing-link file to appear
     * after starting the pair script (polls every 500 ms). */
    private static final int OPENCODE_PAIR_WAIT_TIMEOUT_MS = 30000;

    /** Interval between pairing-link file polls. */
    private static final int OPENCODE_PAIR_POLL_INTERVAL_MS = 500;

    /** How long the app waits for the Pi web address to appear after
     * starting pi-webapp (polls every 500 ms). The first launch
     * downloads pi-webapp via npx, so this is much longer than the
     * OpenCode/dsh wait. */
    private static final int PI_WEB_WAIT_TIMEOUT_MS = 120000;

    /** How long the app waits for the dsh web address to appear after
     * starting `dsh web` (polls every 500 ms). 0 means wait
     * indefinitely: only a token-bearing link counts, so there is no
     * timeout to hit — the wait ends when the token link appears, when
     * `dsh web` exits (the script writes ERROR), or when the user
     * cancels the progress dialog. */
    private static final int DSH_WEB_WAIT_TIMEOUT_MS = 0;

    /** How long the app waits for the Hermes dashboard port to answer
     * after starting `hermes dashboard` (probes every 1000 ms). The
     * first launch downloads pnpm and builds the web UI, so this is
     * the longest wait of the four web agents. */
    private static final int HERMES_PORT_WAIT_TIMEOUT_MS = 300000;

    /** Interval between Hermes port probes. */
    private static final int HERMES_PORT_POLL_INTERVAL_MS = 1000;

    /** Web-browser choice for web launches, stored in the ai_agents
     * SharedPreferences: the package name of the browser to open web
     * links with, or "" for the system default. */
    public static final String PREF_WEB_BROWSER_PACKAGE = "web_browser_package";

    /** Whether tapping a web button opens the browser automatically
     * (default true). When false the app only shows the link. */
    public static final String PREF_WEB_AUTO_OPEN = "web_auto_open";

    /** Cache file for latest-version checks (Termux home, same place as the
     * distro list cache). Format: {"checkedAt": <epoch ms>,
     * "latest": {"pi": "1.6.0", ...}, "historyCheckedAt": <epoch ms>,
     * "history": {"pi": ["1.6.0", "1.5.2", "1.5.1"], ...}}. */
    private static final String UPDATE_CACHE_FILE_NAME = "aiagents-updates.json";

    /** Cached latest versions are used for one hour without re-fetching. */
    private static final long UPDATE_CACHE_VALID_MS = 1L * 60L * 60L * 1000L;

    /** Cached version histories are used for 24 hours without re-fetching
     * (a history fetch is the expensive one: the full npm metadata for
     * OpenCode is several MB, so it must not re-download on every dialog
     * open). The history timestamp lives in the same cache file, under
     * "historyCheckedAt" — see UpdateCache. */
    private static final long HISTORY_CACHE_VALID_MS = 24L * 60L * 60L * 1000L;

    /** At most this many past versions are kept per agent (latest + 2). */
    private static final int MAX_HISTORY_VERSIONS = 3;

    /** Per-request timeouts for latest-version checks; any failure is
     * silent (no badge, no error) — see fetchLatestVersion. */
    private static final int UPDATE_CHECK_CONNECT_TIMEOUT_MS = 20000;
    private static final int UPDATE_CHECK_READ_TIMEOUT_MS = 45000;

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
        /** <prefix>/lib/node_modules/<npm package>/package.json "version"
         *  (see probeNpmPackageJson for the prefixes searched). */
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
        /** A fresh pairing link per tap (`opencode pair`): the app opens
         * the first /auth/connect/ link it prints (see startWebSession). */
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
        final int webPort;           // fixed web port (3080 dsh), 0 when none
                                     // (OpenCode: the port comes from the pair link)
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
    // `web` command — the web UI is opened with a fresh pairing link from
    // `opencode pair` (see the startWebSession javadoc); pi-webapp has no
    // fixed port. Claude Code facts
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
            new String[]{"root/.local/bin/codex", "usr/local/bin/codex",
                "usr/bin/codex",
                "root/.codex/packages/standalone/current/bin/codex"},
            VersionKind.CODEX_RELEASES, null,
            "curl -fsSL https://chatgpt.com/codex/install.sh | CODEX_NON_INTERACTIVE=1 sh",
            "codex update || curl -fsSL https://chatgpt.com/codex/install.sh | CODEX_NON_INTERACTIVE=1 sh",
            WebKind.NONE, 0, true),
        new AgentDef("pi", "Pi", R.string.aiagents_desc_pi, true, "pi",
            "",
            new String[]{"usr/bin/pi", "usr/local/bin/pi", "root/.npm-global/bin/pi"},
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
            new String[]{"usr/bin/dsh", "usr/local/bin/dsh", "root/.npm-global/bin/dsh"},
            VersionKind.NPM_PACKAGE_JSON, "@deepseek-ai/dsh",
            "npm install -g @deepseek-ai/dsh",
            "old_ver=$(npm list -g @deepseek-ai/dsh --depth=0 2>/dev/null | grep -oE '@deepseek-ai/dsh@[0-9][^ ]*' | cut -d@ -f3); if [ -n \"$old_ver\" ]; then echo \"当前版本：$old_ver；如需回退：npm install -g @deepseek-ai/dsh@$old_ver\"; fi; npm install -g @deepseek-ai/dsh",
            WebKind.DSH, 3080, false),
        new AgentDef("opencode", "OpenCode", R.string.aiagents_desc_opencode, true, "opencode",
            "",
            new String[]{"usr/bin/opencode", "usr/local/bin/opencode", "root/.npm-global/bin/opencode"},
            VersionKind.NPM_PACKAGE_JSON, "@opencode/cli",
            "npm install -g --allow-scripts=@opencode/cli @opencode/cli",
            "npm install -g --allow-scripts=@opencode/cli @opencode/cli",
            WebKind.OPENCODE, 0, true),  // no fixed port: pair prints its own link
        new AgentDef("aider", "Aider", R.string.aiagents_desc_aider, false, "aider",
            ".local/bin/aider",
            new String[]{"root/.local/bin/aider"},
            VersionKind.AIDER_DIST_INFO, null,
            "curl -LsSf https://aider.chat/install.sh | sh",
            "\"$HOME/.local/bin/aider\" --upgrade",
            WebKind.NONE, 0, true),
        new AgentDef("claude", "Claude Code", R.string.aiagents_desc_claude, false, "claude",
            ".local/bin/claude",
            // npm installs (-g @anthropic-ai/claude-code[@<version>], the
            // pinned-version path) land in /usr/bin or /usr/local/bin and
            // write no versions/ dir; the official installer lands in
            // ~/.local/bin, so that path stays first. probeVersion falls
            // back to the npm package.json when the versions/ dir is empty.
            new String[]{"root/.local/bin/claude", "usr/bin/claude",
                "usr/local/bin/claude", "root/.npm-global/bin/claude"},
            VersionKind.CLAUDE_VERSIONS, "@anthropic-ai/claude-code",
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
            File file = new File(rootfs, bin);
            // A symlink itself counts: an agent binary may be an ABSOLUTE
            // symlink into the container (Codex ~/.local/bin/codex ->
            // /root/.codex/packages/standalone/current/bin/codex), whose
            // target only resolves inside proot — from the host side it
            // points at the phone's own /root and isFile() says false,
            // so the agent would never be detected. Launching runs inside
            // the container, where the link resolves, so the link
            // existing is enough. (npm's links are relative, which is
            // why Pi was detected and Codex was not.)
            if (Files.isSymbolicLink(file.toPath()) || file.isFile()) return bin;
        }
        // npm agents installed under a Node tarball's own npm prefix
        // (see probeNpmPackageJson): the binary is linked into
        // <tarball>/bin, which is neither on PATH nor in binPaths, so
        // the static list above cannot see it. Any node-v* dir counts;
        // newest first so a re-installed newer Node wins.
        if (def.npmPackage != null) {
            String tarballBin = findTarballBinPath(rootfs, def.command);
            if (tarballBin != null) return tarballBin;
        }
        return null;
    }

    /** Rootfs-relative path of <command> under a Node tarball's own bin
     * dir (usr/local/lib/nodejs/node-v<version>/bin/<command>), or null. The
     * link itself counts (same absolute-symlink rule as findBinPath:
     * npm's links are relative, but a link must never be followed out
     * of the container from the host side). */
    private static String findTarballBinPath(File rootfs, String command) {
        File nodejsDir = new File(rootfs, "usr/local/lib/nodejs");
        if (!nodejsDir.isDirectory()) return null;
        File[] children = nodejsDir.listFiles();
        if (children == null) return null;
        List<String> tarballs = new ArrayList<>();
        for (File child : children) {
            if (child.isDirectory() && child.getName().startsWith("node-v")) {
                tarballs.add(child.getName());
            }
        }
        if (tarballs.isEmpty()) return null;
        Collections.sort(tarballs, (a, b) -> {
            String versionA = nodeDirVersion(a);
            String versionB = nodeDirVersion(b);
            if (versionA.isEmpty() && versionB.isEmpty()) return 0;
            if (versionA.isEmpty()) return 1;
            if (versionB.isEmpty()) return -1;
            return compareVersions(versionB, versionA);
        });
        for (String dir : tarballs) {
            String rel = "usr/local/lib/nodejs/" + dir + "/bin/" + command;
            File file = new File(rootfs, rel);
            if (Files.isSymbolicLink(file.toPath()) || file.isFile()) return rel;
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
                    return probeNpmPackageJson(rootfs, def.npmPackage);
                case CODEX_RELEASES:
                    return probeMaxVersionDir(
                        new File(rootfs, "root/.codex/packages/standalone/releases"));
                case CLAUDE_VERSIONS:
                    return probeClaudeVersion(rootfs);
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

    /** Version of an npm package from its package.json, searching every
     * npm global prefix the app can produce, in order:
     * <ul>
     * <li>distro-packaged Node and NodeSource (prefix /usr):
     * usr/lib/node_modules/&lt;pkg&gt;/package.json — npm's prefix
     * defaults to where node is installed, so every distro package
     * (Arch pacman, Alpine apk, Fedora dnf, openSUSE zypper, Void
     * xbps) lands here;</li>
     * <li>tarball-redirected prefix (prefix /usr/local):
     * usr/local/lib/node_modules/&lt;pkg&gt;/package.json — what
     * nodeTarballInstallCommand sets for new installs;</li>
     * <li>user-level prefix:
     * root/.npm-global/lib/node_modules/&lt;pkg&gt;/package.json;</li>
     * <li>a Node tarball's own prefix (before the /usr/local
     * redirect existed): usr/local/lib/nodejs/node-v<version>/lib/
     * node_modules/&lt;pkg&gt;/package.json, newest node-v* first.</li>
     * </ul>
     * "" when no prefix has the package. Shared by probeVersion
     * (NPM_PACKAGE_JSON) and probeClaudeVersion, so detection, the
     * version read and the install gate can never drift apart. */
    private static String probeNpmPackageJson(File rootfs, String npmPackage) {
        for (String prefix : new String[]{"usr", "usr/local"}) {
            String version = readNpmPackageVersion(new File(rootfs,
                prefix + "/lib/node_modules/" + npmPackage + "/package.json"));
            if (!version.isEmpty()) return version;
        }
        String version = readNpmPackageVersion(new File(rootfs,
            "root/.npm-global/lib/node_modules/" + npmPackage + "/package.json"));
        if (!version.isEmpty()) return version;
        return probeTarballNpmPackageJson(rootfs, npmPackage);
    }

    /** Read "version" from one npm package.json, "" when missing or unreadable. */
    private static String readNpmPackageVersion(File packageJson) {
        if (!packageJson.isFile()) return "";
        try {
            return new JSONObject(DistroUtils.readFile(packageJson))
                .optString("version", "");
        } catch (Exception e) {
            Log.w(LOG_TAG, "Failed to read npm package.json " + packageJson, e);
            return "";
        }
    }

    /** Version of an npm package installed under a Node tarball's own
     * global prefix: usr/local/lib/nodejs/node-v<version>/lib/node_modules/
     * &lt;pkg&gt;/package.json. Several node-v* dirs may coexist (Node
     * was re-installed at a newer version); the newest dir wins. ""
     * when none has the package. */
    private static String probeTarballNpmPackageJson(File rootfs, String npmPackage) {
        File nodejsDir = new File(rootfs, "usr/local/lib/nodejs");
        if (!nodejsDir.isDirectory()) return "";
        File[] children = nodejsDir.listFiles();
        if (children == null) return "";
        List<String> tarballs = new ArrayList<>();
        for (File child : children) {
            if (child.isDirectory() && child.getName().startsWith("node-v")) {
                tarballs.add(child.getName());
            }
        }
        if (tarballs.isEmpty()) return "";
        Collections.sort(tarballs, (a, b) -> {
            String versionA = nodeDirVersion(a);
            String versionB = nodeDirVersion(b);
            if (versionA.isEmpty() && versionB.isEmpty()) return 0;
            if (versionA.isEmpty()) return 1;
            if (versionB.isEmpty()) return -1;
            return compareVersions(versionB, versionA);
        });
        for (String dir : tarballs) {
            String version = readNpmPackageVersion(new File(nodejsDir,
                dir + "/lib/node_modules/" + npmPackage + "/package.json"));
            if (!version.isEmpty()) return version;
        }
        return "";
    }

    /** "node-v24.22.3" -> "24.22.3"; "" for anything else (sorts last). */
    private static String nodeDirVersion(String dirName) {
        if (dirName != null && dirName.startsWith("node-v")) {
            String version = dirName.substring("node-v".length());
            return isComparableVersion(version) ? version : "";
        }
        return "";
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
                versions.add(stripArchTargetSuffix(child.getName()));
            }
        }
        if (versions.isEmpty()) return "";
        Collections.sort(versions, (a, b) -> compareVersionsDescending(a, b));
        return versions.get(0);
    }

    /** Strip a Codex-style target suffix ("-aarch64-unknown-linux-musl",
     * "-x86_64-unknown-linux-gnu") from a release dir name, leaving the
     * clean version ("0.171.0"). Only fires on a known arch suffix, so
     * Claude's versions/ dirs — already clean versions — pass through
     * unchanged, as do prerelease names like "0.2.2-rc.5" (no arch
     * segment). Without the strip, the suffixed name compared newer
     * than the same clean version (a release outranks the same number
     * with a suffix) and every Codex install showed a false update. */
    private static String stripArchTargetSuffix(String dirName) {
        if (dirName == null) return "";
        return dirName.replaceFirst("-(aarch64|x86_64)-unknown-linux-(musl|gnu)$", "");
    }

    /** Installed Claude Code version: the official installer writes
     * ~/.local/share/claude/versions/<version>/, but an npm install
     * (including a pinned older version from the version dialog) lands
     * in /usr/lib or /usr/local/lib/node_modules and writes no versions/
     * dir, so fall back to that package.json (via probeNpmPackageJson,
     * which also covers /usr/local, ~/.npm-global and Node tarball
     * dirs). The versions/ dir wins when both exist (the installer
     * path is the one `claude update` maintains). */
    private static String probeClaudeVersion(File rootfs) {
        String dirVersion = probeMaxVersionDir(
            new File(rootfs, "root/.local/share/claude/versions"));
        if (!dirVersion.isEmpty()) return dirVersion;
        return probeNpmPackageJson(rootfs, "@anthropic-ai/claude-code");
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

    // ------------------------------------------------------------------
    // Version history (inline expandable rows)
    // ------------------------------------------------------------------

    /** One published version with its publish date (YYYY-MM-DD, or ""
     * when the source gives no date — a date is never invented). */
    public static class VersionEntry {
        public final String version;
        public final String date;

        public VersionEntry(String version, String date) {
            this.version = version == null ? "" : version;
            this.date = date == null ? "" : date;
        }
    }

    /** A history fetch: the entries (newest first, at most
     * MAX_HISTORY_VERSIONS), plus a short English failure code when the
     * list is empty: "" (ok), "timeout", "http-403", "no-network",
     * "parse-error", "empty". The code is mapped to a user string at
     * display time (AiAgentsActivity.historyErrorMessage); the reason is
     * also logged with Log.w. */
    public static class HistoryResult {
        public final List<VersionEntry> entries;
        public final String error;

        public HistoryResult(List<VersionEntry> entries, String error) {
            this.entries = entries == null ? new ArrayList<>() : entries;
            this.error = error == null ? "" : error;
        }

        static HistoryResult failure(String error) {
            return new HistoryResult(new ArrayList<>(), error);
        }
    }

    /** Result of one HTTP GET: the body (null on failure) and a short
     * English error code ("" on success): "timeout", "http-403",
     * "no-network", "empty". Any other HTTP status is reported as
     * "empty" (nothing usable came back); the real status is logged. */
    private static final class FetchResult {
        final String body;
        final String error;

        FetchResult(String body, String error) {
            this.body = body;
            this.error = error == null ? "" : error;
        }
    }

    /** YYYY-MM-DD from an ISO 8601 timestamp ("2026-11-20T..."), or ""
     * when the value has no valid date prefix. */
    private static String dateFromIso(String iso) {
        if (iso == null || iso.length() < 10) return "";
        return validDate(iso.substring(0, 10));
    }

    /** The date itself, or "" when it is not a YYYY-MM-DD calendar shape
     * (month 01-12, day 01-31; no day-of-month validation beyond that —
     * a bad date is dropped, never shown). */
    private static String validDate(String date) {
        if (date == null || date.length() != 10) return "";
        if (date.charAt(4) != '-' || date.charAt(7) != '-') return "";
        for (int i = 0; i < 10; i++) {
            if (i == 4 || i == 7) continue;
            if (!Character.isDigit(date.charAt(i))) return "";
        }
        int month = Integer.parseInt(date.substring(5, 7));
        int day = Integer.parseInt(date.substring(8, 10));
        if (month < 1 || month > 12 || day < 1 || day > 31) return "";
        return date;
    }

    /** "owner/repo" from a GitHub API releases URL, or "" when the URL
     * does not match (the atom fallback is then skipped). */
    private static String githubOwnerRepo(String apiUrl) {
        if (apiUrl == null) return "";
        int start = apiUrl.indexOf("repos/");
        if (start < 0) return "";
        int end = apiUrl.indexOf("/releases", start);
        if (end < 0) return "";
        return apiUrl.substring(start + "repos/".length(), end);
    }

    /**
     * The newest few published versions of an agent, newest first, at most
     * MAX_HISTORY_VERSIONS (latest + 2), each with its publish date. The
     * first entry is the same version fetchLatestVersion returns. When the
     * primary source yields nothing, one fallback is tried (see the
     * per-source methods); when both fail, the result carries the failure
     * reason instead of a bare empty list. No version is ever hardcoded.
     *
     * Sources (all verified live 2026-11-22, see agent-version-history-v2
     * design.md §1):
     * - Pi / dsh / OpenCode / Claude: the full npm metadata's "time"
     *   object, newest first; OpenCode skips 0.0.0-dev-* builds. Primary
     *   registry.npmjs.org, fallback registry.npmmirror.com (same JSON
     *   shape, verified: identical top 3 for all four packages).
     * - Aider: PyPI "releases", each version's newest upload_time, newest
     *   first — never sorted as strings. Primary pypi.org, fallback
     *   mirrors.tuna.tsinghua.edu.cn/pypi (same JSON shape, verified).
     *   npmmirror / USTC / Aliyun PyPI JSON endpoints all 404, so they
     *   are NOT used.
     * - Codex / Hermes / agy: the GitHub releases list (?per_page=20),
     *   newest first; drafts and prereleases are skipped, and for Hermes
     *   any tag containing "canary" is skipped too. Fallback is the
     *   releases.atom feed (no auth, no rate-limit counter): tag from the
     *   entry link href, date from <updated>, prerelease-looking tags
     *   skipped by substring (the feed has no prerelease flag — see
     *   ATOM_SKIP_SUBSTRINGS). The feed only holds
     *   10 entries, so the fallback can be partial — Codex and Hermes
     *   yield 2 usable versions there today (alpha/canary flood), agy
     *   yields 3; a partial list is still shown.
     */
    public static HistoryResult fetchVersionHistory(AgentDef def, String distroId) {
        if (def == null) return HistoryResult.failure("empty");
        try {
            switch (def.id) {
                case "pi":
                case "dsh":
                case "opencode":
                    return fetchNpmHistory(def.npmPackage,
                        "opencode".equals(def.id));
                case "claude":
                    return fetchNpmHistory(def.npmPackage, false);
                case "aider":
                    return fetchPyPIHistory();
                case "codex":
                    return fetchGithubReleaseHistory(
                        "https://api.github.com/repos/openai/codex/releases?per_page=20",
                        true, false);
                case "agy":
                    return fetchGithubReleaseHistory(
                        "https://api.github.com/repos/google-antigravity/antigravity-cli/releases?per_page=20",
                        false, false);
                case "hermes":
                    return fetchGithubReleaseHistory(
                        "https://api.github.com/repos/NousResearch/hermes-agent/releases?per_page=20",
                        false, true);
                default:
                    return HistoryResult.failure("empty");
            }
        } catch (Exception e) {
            Log.w(LOG_TAG, "Failed to fetch version history for " + def.id, e);
            return HistoryResult.failure("parse-error");
        }
    }

    /** npm full metadata: "time" maps version -> first-publish time
     * (ISO 8601, so a plain string sort is chronological). Newest first,
     * at most MAX_HISTORY_VERSIONS, skipping dev builds when asked and
     * anything that does not parse as a version. Primary registry first,
     * npmmirror once on failure (same shape). */
    private static HistoryResult fetchNpmHistory(String npmPackage, boolean skipDevVersions) {
        if (npmPackage == null || npmPackage.isEmpty()) return HistoryResult.failure("empty");
        HistoryResult primary = fetchNpmHistoryFrom(
            "https://registry.npmjs.org/" + npmPackage, skipDevVersions);
        if (!primary.entries.isEmpty()) return primary;
        HistoryResult mirror = fetchNpmHistoryFrom(
            "https://registry.npmmirror.com/" + npmPackage, skipDevVersions);
        if (!mirror.entries.isEmpty()) return mirror;
        return primary.error.isEmpty() ? mirror : primary;
    }

    private static HistoryResult fetchNpmHistoryFrom(String url, boolean skipDevVersions) {
        FetchResult fetched = fetchUrlResult(url);
        if (fetched.body == null) return HistoryResult.failure(fetched.error);
        try {
            JSONObject time = new JSONObject(fetched.body).optJSONObject("time");
            if (time == null) return HistoryResult.failure("parse-error");
            List<String[]> dated = new ArrayList<>();
            Iterator<String> versions = time.keys();
            while (versions.hasNext()) {
                String version = versions.next();
                if ("created".equals(version) || "modified".equals(version)) continue;
                if (skipDevVersions && version.startsWith("0.0.0-dev")) continue;
                String when = time.optString(version, "");
                if (when.isEmpty() || validatedVersion(version).isEmpty()) continue;
                dated.add(new String[]{version, when});
            }
            Collections.sort(dated, (a, b) -> b[1].compareTo(a[1]));
            List<VersionEntry> result = new ArrayList<>();
            for (int i = 0; i < dated.size() && result.size() < MAX_HISTORY_VERSIONS; i++) {
                result.add(new VersionEntry(validatedVersion(dated.get(i)[0]),
                    dateFromIso(dated.get(i)[1])));
            }
            if (result.isEmpty()) return HistoryResult.failure("empty");
            return new HistoryResult(result, "");
        } catch (Exception e) {
            Log.w(LOG_TAG, "Failed to parse npm history from " + url, e);
            return HistoryResult.failure("parse-error");
        }
    }

    /** PyPI: "releases" maps version -> files; a version's date is its
     * newest file's upload_time_iso_8601. Newest first, at most
     * MAX_HISTORY_VERSIONS. Sorting by upload time (not by version
     * string) is what keeps the 2023-era 0.9.0 out of the top 3.
     * Primary pypi.org first, TUNA mirror once on failure (same shape). */
    private static HistoryResult fetchPyPIHistory() {
        HistoryResult primary = fetchPyPIHistoryFrom("https://pypi.org/pypi/aider-chat/json");
        if (!primary.entries.isEmpty()) return primary;
        HistoryResult mirror = fetchPyPIHistoryFrom(
            "https://mirrors.tuna.tsinghua.edu.cn/pypi/aider-chat/json");
        if (!mirror.entries.isEmpty()) return mirror;
        return primary.error.isEmpty() ? mirror : primary;
    }

    private static HistoryResult fetchPyPIHistoryFrom(String url) {
        FetchResult fetched = fetchUrlResult(url);
        if (fetched.body == null) return HistoryResult.failure(fetched.error);
        try {
            JSONObject releases = new JSONObject(fetched.body).optJSONObject("releases");
            if (releases == null) return HistoryResult.failure("parse-error");
            List<String[]> dated = new ArrayList<>();
            Iterator<String> versions = releases.keys();
            while (versions.hasNext()) {
                String version = versions.next();
                if (validatedVersion(version).isEmpty()) continue;
                JSONArray files = releases.optJSONArray(version);
                String newest = "";
                if (files != null) {
                    for (int i = 0; i < files.length(); i++) {
                        JSONObject file = files.optJSONObject(i);
                        String when = file == null
                            ? "" : file.optString("upload_time_iso_8601", "");
                        if (when.compareTo(newest) > 0) newest = when;
                    }
                }
                if (!newest.isEmpty()) dated.add(new String[]{version, newest});
            }
            Collections.sort(dated, (a, b) -> b[1].compareTo(a[1]));
            List<VersionEntry> result = new ArrayList<>();
            for (int i = 0; i < dated.size() && result.size() < MAX_HISTORY_VERSIONS; i++) {
                result.add(new VersionEntry(validatedVersion(dated.get(i)[0]),
                    dateFromIso(dated.get(i)[1])));
            }
            if (result.isEmpty()) return HistoryResult.failure("empty");
            return new HistoryResult(result, "");
        } catch (Exception e) {
            Log.w(LOG_TAG, "Failed to parse PyPI history from " + url, e);
            return HistoryResult.failure("parse-error");
        }
    }

    /** GitHub releases list (?per_page=20), newest first. Drafts and
     * prereleases are always skipped; canary tags are skipped too when
     * asked (Hermes). Codex prefers the release "name" (a plain version)
     * and falls back to tag_name minus "rust-v"; the others use tag_name
     * minus a leading "v". Newest first by published_at, at most
     * MAX_HISTORY_VERSIONS. When the API yields nothing, the releases.atom
     * feed is tried once (see fetchGithubAtomHistory). */
    private static HistoryResult fetchGithubReleaseHistory(String url,
            boolean preferName, boolean skipCanary) {
        FetchResult fetched = fetchUrlResult(url);
        HistoryResult primary;
        if (fetched.body == null) {
            Log.w(LOG_TAG, "GitHub history fetch failed (" + fetched.error + "): " + url);
            primary = HistoryResult.failure(fetched.error);
        } else {
            primary = parseGithubReleaseHistory(fetched.body, preferName, skipCanary);
        }
        if (!primary.entries.isEmpty()) return primary;
        HistoryResult atom = fetchGithubAtomHistory(url);
        if (!atom.entries.isEmpty()) return atom;
        return primary.error.isEmpty() ? atom : primary;
    }

    private static HistoryResult parseGithubReleaseHistory(String body,
            boolean preferName, boolean skipCanary) {
        try {
            JSONArray releases = new JSONArray(body);
            List<String[]> dated = new ArrayList<>();
            for (int i = 0; i < releases.length(); i++) {
                JSONObject release = releases.optJSONObject(i);
                if (release == null) continue;
                if (release.optBoolean("draft", false)
                    || release.optBoolean("prerelease", false)) continue;
                String tag = release.optString("tag_name", "").trim();
                if (skipCanary && tag.toLowerCase(Locale.ROOT).contains("canary")) continue;
                String version = "";
                if (preferName) {
                    version = validatedVersion(release.optString("name", "").trim());
                }
                if (version.isEmpty()) {
                    if (tag.startsWith("rust-v")) tag = tag.substring("rust-v".length());
                    version = validatedVersion(tag);
                }
                if (version.isEmpty()) continue;
                dated.add(new String[]{version,
                    release.optString("published_at", "")});
            }
            Collections.sort(dated, (a, b) -> b[1].compareTo(a[1]));
            List<VersionEntry> result = new ArrayList<>();
            for (int i = 0; i < dated.size() && result.size() < MAX_HISTORY_VERSIONS; i++) {
                result.add(new VersionEntry(dated.get(i)[0], dateFromIso(dated.get(i)[1])));
            }
            if (result.isEmpty()) return HistoryResult.failure("empty");
            return new HistoryResult(result, "");
        } catch (Exception e) {
            Log.w(LOG_TAG, "Failed to parse GitHub history", e);
            return HistoryResult.failure("parse-error");
        }
    }

    /** Tag substrings that mark an atom entry as not-a-stable-release.
     * The feed has no prerelease flag, so this is the whole filter (plus
     * the version check below). "alpha" is essential for Codex (its feed
     * is flooded with rust-v0.17x.0-alpha.N); "canary"/"rc" for Hermes
     * (live feed shows canary.2026…-v0.23.3, v0.23.3+canary.… and
     * rc.1-v0.23.3 forms). A stable tag for these three repos never
     * contains any of these words, so the list is safe to share. */
    private static final String[] ATOM_SKIP_SUBSTRINGS = {"alpha", "beta", "rc",
        "dev", "canary", "preview", "nightly"};

    /** Atom fallback for GitHub histories: the releases.atom feed needs no
     * auth and does not count against the REST rate limit, which is what
     * makes it a fallback at all (the agy API call 403s from this VM's
     * shared egress IP while its atom feed answers 200). Parsing is plain
     * string scanning, not an XML parser: each <entry> gives its tag from
     * the link href (".../releases/tag/<tag>", %2B-decoded) and its date
     * from <updated>. There is no prerelease flag in the feed, so
     * ATOM_SKIP_SUBSTRINGS is matched against the tag, and a tag that does
     * not parse as a version is skipped too (e.g. Hermes "rc.1-v0.23.3"
     * would fail validation even without the "rc" skip). The feed holds 10
     * entries, so the result can be partial (Codex/Hermes: 2 usable today)
     * — still shown. */
    private static HistoryResult fetchGithubAtomHistory(String apiUrl) {
        String ownerRepo = githubOwnerRepo(apiUrl);
        if (ownerRepo.isEmpty()) return HistoryResult.failure("empty");
        FetchResult fetched = fetchUrlResult(
            "https://github.com/" + ownerRepo + "/releases.atom");
        if (fetched.body == null) return HistoryResult.failure(fetched.error);
        try {
            List<String[]> dated = new ArrayList<>();
            String body = fetched.body;
            int pos = 0;
            while (true) {
                int entryStart = body.indexOf("<entry>", pos);
                if (entryStart < 0) break;
                int entryEnd = body.indexOf("</entry>", entryStart);
                if (entryEnd < 0) break;
                String entry = body.substring(entryStart, entryEnd);
                pos = entryEnd + "</entry>".length();
                int linkStart = entry.indexOf("<link");
                if (linkStart < 0) continue;
                int hrefStart = entry.indexOf("href=\"", linkStart);
                if (hrefStart < 0) continue;
                hrefStart += "href=\"".length();
                int hrefEnd = entry.indexOf("\"", hrefStart);
                if (hrefEnd < 0) continue;
                String href = entry.substring(hrefStart, hrefEnd);
                int tagStart = href.indexOf("/releases/tag/");
                if (tagStart < 0) continue;
                String tag = href.substring(tagStart + "/releases/tag/".length());
                tag = tag.replace("%2B", "+").replace("%2b", "+");
                String lowerTag = tag.toLowerCase(Locale.ROOT);
                boolean skipped = false;
                for (String marker : ATOM_SKIP_SUBSTRINGS) {
                    if (lowerTag.contains(marker)) {
                        skipped = true;
                        break;
                    }
                }
                if (skipped) continue;
                if (tag.startsWith("rust-v")) tag = tag.substring("rust-v".length());
                String version = validatedVersion(tag);
                if (version.isEmpty()) continue;
                String when = "";
                int updatedStart = entry.indexOf("<updated>");
                if (updatedStart >= 0) {
                    int updatedEnd = entry.indexOf("</updated>", updatedStart);
                    if (updatedEnd > updatedStart) {
                        when = entry.substring(updatedStart + "<updated>".length(), updatedEnd);
                    }
                }
                dated.add(new String[]{version, when});
            }
            Collections.sort(dated, (a, b) -> b[1].compareTo(a[1]));
            List<VersionEntry> result = new ArrayList<>();
            for (int i = 0; i < dated.size() && result.size() < MAX_HISTORY_VERSIONS; i++) {
                result.add(new VersionEntry(dated.get(i)[0], dateFromIso(dated.get(i)[1])));
            }
            if (result.isEmpty()) return HistoryResult.failure("empty");
            return new HistoryResult(result, "");
        } catch (Exception e) {
            Log.w(LOG_TAG, "Failed to parse GitHub atom feed for " + apiUrl, e);
            return HistoryResult.failure("parse-error");
        }
    }

    /**
     * Version histories for several agents in parallel (same 4-thread /
     * 70s shape as fetchLatestVersions). Every attempted agent contributes
     * its HistoryResult; callers use the entries when non-empty and the
     * error code otherwise. Runs synchronously on the caller's thread;
     * callers must call it off the UI thread.
     */
    public static Map<String, HistoryResult> fetchVersionHistories(
            List<AgentDef> defs, Map<String, String> distroIdByAgentId) {
        Map<String, HistoryResult> fetched = Collections.synchronizedMap(new HashMap<>());
        if (defs == null || defs.isEmpty()) return fetched;
        ExecutorService pool = Executors.newFixedThreadPool(4);
        for (AgentDef def : defs) {
            final AgentDef current = def;
            pool.submit(() -> {
                String distroId = distroIdByAgentId == null
                    ? "" : distroIdByAgentId.get(current.id);
                HistoryResult history = fetchVersionHistory(current,
                    distroId == null ? "" : distroId);
                fetched.put(current.id, history);
            });
        }
        pool.shutdown();
        try {
            pool.awaitTermination(70, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return fetched;
    }

    /** True when the app can install a pinned (older) version of this
     * agent. Hermes is view-only: its releases carry no installable
     * package for a pinned version and its installer only knows the
     * stable channel, so the dialog shows its history but no Install
     * button. Every other agent has a pinned install path (see
     * AiAgentsActivity.versionedInstallSnippet). */
    public static boolean supportsVersionInstall(AgentDef def) {
        return def != null && !"hermes".equals(def.id);
    }

    /** A fetched version string, or "" when it does not parse (a changed
     * endpoint must never produce a bogus badge). */
    private static String validatedVersion(String version) {
        if (version == null) return "";
        String stripped = stripLeadingV(version.trim());
        return isComparableVersion(stripped) ? stripped : "";
    }

    /** One HTTPS GET; null on any failure (timeout, non-200, empty body).
     * Same HttpURLConnection idiom as DistroActivity's Docker Hub fetch.
     * Asks for gzip: the full npm metadata behind a history fetch is
     * several MB uncompressed (OpenCode ~4.3 MB) but ~0.6 MB gzipped, so
     * the wire cost stays small; servers that ignore the header just
     * answer plain and the Content-Encoding check below skips the wrap.
     * Callers that need the failure reason use fetchUrlResult directly. */
    private static String fetchUrlText(String urlString) {
        return fetchUrlResult(urlString).body;
    }

    /** One HTTPS GET with its failure reason. The taxonomy is small on
     * purpose: SocketTimeoutException -> "timeout", UnknownHostException
     * or any other IOException (refused/reset mid-read) -> "no-network",
     * HTTP 403 -> "http-403", any other non-200 status or an empty body
     * -> "empty" (the real status is logged). JSON problems are NOT
     * reported here — a 200 with a bad body is the caller's "parse-error". */
    private static FetchResult fetchUrlResult(String urlString) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(urlString);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(UPDATE_CHECK_CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(UPDATE_CHECK_READ_TIMEOUT_MS);
            connection.setRequestProperty("User-Agent", "termux-app-zh-aiagents/1.0");
            connection.setRequestProperty("Accept", "application/json, text/plain, */*");
            connection.setRequestProperty("Accept-Encoding", "gzip");
            int status = connection.getResponseCode();
            if (status == HttpURLConnection.HTTP_FORBIDDEN) {
                Log.w(LOG_TAG, "Fetch forbidden (403) for " + urlString);
                return new FetchResult(null, "http-403");
            }
            if (status != HttpURLConnection.HTTP_OK) {
                Log.w(LOG_TAG, "Fetch HTTP " + status + " for " + urlString);
                return new FetchResult(null, "empty");
            }
            InputStream in = connection.getInputStream();
            if ("gzip".equalsIgnoreCase(connection.getContentEncoding())) {
                in = new GZIPInputStream(in);
            }
            String body = readStream(in);
            if (body == null || body.isEmpty()) return new FetchResult(null, "empty");
            return new FetchResult(body, "");
        } catch (SocketTimeoutException e) {
            Log.w(LOG_TAG, "Fetch timed out for " + urlString, e);
            return new FetchResult(null, "timeout");
        } catch (UnknownHostException e) {
            Log.w(LOG_TAG, "Fetch failed (no network / unknown host) for " + urlString, e);
            return new FetchResult(null, "no-network");
        } catch (IOException e) {
            Log.w(LOG_TAG, "Fetch failed (network) for " + urlString, e);
            return new FetchResult(null, "no-network");
        } catch (Exception e) {
            Log.w(LOG_TAG, "Fetch failed for " + urlString, e);
            return new FetchResult(null, "empty");
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

    /** One cached check: when it ran, the latest version per agent id,
     * and — since the version-history round — when the histories were
     * last fetched and the history per agent id (newest first, at most
     * MAX_HISTORY_VERSIONS, each entry a {"version", "date"} object).
     * Latest and history have separate clocks: latest is re-checked
     * hourly, history daily (see HISTORY_CACHE_VALID_MS). Old caches
     * without the history fields load as "no history"; v1 caches whose
     * history entries are plain strings (no dates) are dropped outright
     * — a string entry is never parsed as a date — so the format stays
     * backward compatible and loadUpdateCache never crashes on them. */
    public static final class UpdateCache {
        public final long checkedAt;
        public final Map<String, String> latest;
        public final long historyCheckedAt;
        public final Map<String, List<VersionEntry>> history;

        UpdateCache(long checkedAt, Map<String, String> latest) {
            this(checkedAt, latest, 0, new HashMap<String, List<VersionEntry>>());
        }

        UpdateCache(long checkedAt, Map<String, String> latest,
                    long historyCheckedAt, Map<String, List<VersionEntry>> history) {
            this.checkedAt = checkedAt;
            this.latest = latest;
            this.historyCheckedAt = historyCheckedAt;
            this.history = history;
        }
    }

    /** Cached latest versions, or an empty cache when there is none or it
     * is corrupt (the next check rebuilds it). */
    public static UpdateCache loadUpdateCache() {
        Map<String, String> latest = new HashMap<>();
        Map<String, List<VersionEntry>> history = new HashMap<>();
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
            long historyCheckedAt = root.optLong("historyCheckedAt", 0);
            JSONObject histories = root.optJSONObject("history");
            if (histories != null) {
                Iterator<String> ids = histories.keys();
                while (ids.hasNext()) {
                    String id = ids.next();
                    JSONArray versionsArray = histories.optJSONArray(id);
                    if (versionsArray == null) continue;
                    List<VersionEntry> versionsList = new ArrayList<>();
                    // A v1 cache stores plain strings here ("1.6.0") with
                    // no date at all: drop the whole agent entry rather
                    // than guessing a date for it. A v2 entry is a
                    // {"version", "date"} object; anything else is skipped.
                    boolean oldFormat = false;
                    for (int i = 0; i < versionsArray.length()
                        && versionsList.size() < MAX_HISTORY_VERSIONS; i++) {
                        Object item = versionsArray.opt(i);
                        if (item instanceof String) {
                            oldFormat = true;
                            break;
                        }
                        if (!(item instanceof JSONObject)) continue;
                        JSONObject entry = (JSONObject) item;
                        String version = validatedVersion(
                            entry.optString("version", "").trim());
                        String date = validDate(
                            entry.optString("date", "").trim());
                        if (!version.isEmpty()) {
                            versionsList.add(new VersionEntry(version, date));
                        }
                    }
                    if (!oldFormat && !versionsList.isEmpty()) {
                        history.put(id, versionsList);
                    }
                }
            }
            return new UpdateCache(checkedAt, latest, historyCheckedAt, history);
        } catch (Exception e) {
            Log.w(LOG_TAG, "Failed to read update cache", e);
            return new UpdateCache(0, latest);
        }
    }

    /** True when the cache was checked within the last hour. */
    public static boolean isUpdateCacheValid(long checkedAt) {
        return checkedAt > 0
            && System.currentTimeMillis() - checkedAt < UPDATE_CACHE_VALID_MS;
    }

    /** True when the version histories were fetched within the last 24 hours. */
    public static boolean isHistoryCacheValid(long historyCheckedAt) {
        return historyCheckedAt > 0
            && System.currentTimeMillis() - historyCheckedAt < HISTORY_CACHE_VALID_MS;
    }

    /** Save latest versions with "now" as the check time (AtomicFile, same
     * as the distro list cache). Callers pass the merged map: freshly
     * fetched values overwrite the old cache, failed fetches keep theirs.
     * The cached histories and their check time are preserved untouched. */
    public static void saveUpdateCache(Map<String, String> latest) {
        UpdateCache current = loadUpdateCache();
        Map<String, String> merged = new HashMap<>(current.latest);
        if (latest != null) merged.putAll(latest);
        saveUpdateCacheInternal(merged, current.history,
            System.currentTimeMillis(), current.historyCheckedAt);
    }

    /** Save latest versions together with freshly fetched histories
     * (the "检查更新" path: one tap refreshes both). Either map may be
     * empty — an empty map keeps the cached side and does not advance
     * its check time, so a failed history fetch never marks "checked". */
    public static void saveUpdateCache(Map<String, String> latest,
            Map<String, List<VersionEntry>> history) {
        UpdateCache current = loadUpdateCache();
        Map<String, String> mergedLatest = new HashMap<>(current.latest);
        if (latest != null) mergedLatest.putAll(latest);
        Map<String, List<VersionEntry>> mergedHistory = new HashMap<>(current.history);
        if (history != null) mergedHistory.putAll(history);
        saveUpdateCacheInternal(mergedLatest, mergedHistory,
            latest != null && !latest.isEmpty()
                ? System.currentTimeMillis() : current.checkedAt,
            history != null && !history.isEmpty()
                ? System.currentTimeMillis() : current.historyCheckedAt);
    }

    /** Save freshly fetched histories only (the expanded-row path):
     * the latest map and its check time are preserved untouched. */
    public static void saveVersionHistory(Map<String, List<VersionEntry>> history) {
        UpdateCache current = loadUpdateCache();
        Map<String, List<VersionEntry>> mergedHistory = new HashMap<>(current.history);
        if (history != null) mergedHistory.putAll(history);
        saveUpdateCacheInternal(current.latest, mergedHistory, current.checkedAt,
            history != null && !history.isEmpty()
                ? System.currentTimeMillis() : current.historyCheckedAt);
    }

    /** Write the cache file (AtomicFile, same as the distro list cache):
     * {"checkedAt": ..., "latest": {...}, "historyCheckedAt": ...,
     * "history": {"pi": [{"version": "1.6.0", "date": "2026-11-20"}, ...], ...}}. */
    private static void saveUpdateCacheInternal(Map<String, String> latest,
            Map<String, List<VersionEntry>> history, long checkedAt, long historyCheckedAt) {
        try {
            JSONObject root = new JSONObject();
            root.put("checkedAt", checkedAt);
            JSONObject versions = new JSONObject();
            for (Map.Entry<String, String> entry : latest.entrySet()) {
                if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                    versions.put(entry.getKey(), entry.getValue());
                }
            }
            root.put("latest", versions);
            root.put("historyCheckedAt", historyCheckedAt);
            JSONObject histories = new JSONObject();
            for (Map.Entry<String, List<VersionEntry>> entry : history.entrySet()) {
                if (entry.getValue() == null || entry.getValue().isEmpty()) continue;
                JSONArray versionsArray = new JSONArray();
                for (VersionEntry versionEntry : entry.getValue()) {
                    if (versionEntry == null || versionEntry.version == null
                        || versionEntry.version.isEmpty()) continue;
                    JSONObject versionObject = new JSONObject();
                    versionObject.put("version", versionEntry.version);
                    versionObject.put("date",
                        versionEntry.date == null ? "" : versionEntry.date);
                    versionsArray.put(versionObject);
                }
                if (versionsArray.length() > 0) histories.put(entry.getKey(), versionsArray);
            }
            root.put("history", histories);
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
    // Bundled offline fallback (assets/aiagents-versions.json)
    // ------------------------------------------------------------------

    /** The bundled version history shipped in the APK (assets file
     * aiagents-versions.json, top 3 versions per agent with dates, snapshot
     * taken when the APK was built). Read with no network, never crashes:
     * any failure (missing asset, bad JSON, bad version) just yields an
     * empty map. The expanded row uses it only when a live history fetch
     * fails AND there is no cache — live results always win when available,
     * and bundled entries are never written into the cache. */
    public static Map<String, List<VersionEntry>> loadBundledHistory(Context context) {
        Map<String, List<VersionEntry>> result = new HashMap<>();
        if (context == null) return result;
        InputStream in = null;
        try {
            in = context.getAssets().open("aiagents-versions.json");
            String body = readStream(in);
            JSONObject root = new JSONObject(body);
            JSONObject history = root.optJSONObject("history");
            if (history == null) return result;
            Iterator<String> ids = history.keys();
            while (ids.hasNext()) {
                String id = ids.next();
                JSONArray entries = history.optJSONArray(id);
                if (entries == null) continue;
                List<VersionEntry> list = new ArrayList<>();
                for (int i = 0; i < entries.length() && list.size() < MAX_HISTORY_VERSIONS; i++) {
                    JSONObject entry = entries.optJSONObject(i);
                    if (entry == null) continue;
                    String version = validatedVersion(entry.optString("version", "").trim());
                    String date = validDate(entry.optString("date", "").trim());
                    if (!version.isEmpty()) list.add(new VersionEntry(version, date));
                }
                if (!list.isEmpty()) result.put(id, list);
            }
        } catch (Exception e) {
            Log.w(LOG_TAG, "Failed to load bundled version history", e);
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // Best effort; the stream is going away either way.
                }
            }
        }
        return result;
    }

    // ------------------------------------------------------------------
    // Latest-version refresh (parallel + refresh-if-stale)
    // ------------------------------------------------------------------

    /** Guard for refreshLatestVersionsIfStale: launch, resume and
     * drawer-open all trigger it, but only one refresh may run at once. */
    private static final Object UPDATE_REFRESH_LOCK = new Object();
    private static volatile boolean sRefreshingUpdates = false;

    /**
     * Fetch the latest version for each given agent definition in
     * parallel (4 threads; each fetch has its own 20s connect / 45s
     * read timeout, so the whole refresh is bounded by ~70s worst case
     * instead of 65s per agent). distroIdByAgentId picks the per-agent
     * source variant (agy's glibc vs musl manifest); a missing entry
     * means "any distro". Failures are silent — a failed agent simply
     * contributes nothing. Returns only freshly fetched values (never
     * null). Runs synchronously on the caller's thread; callers must
     * call it off the UI thread.
     */
    public static Map<String, String> fetchLatestVersions(
            List<AgentDef> defs, Map<String, String> distroIdByAgentId) {
        Map<String, String> fetched = Collections.synchronizedMap(new HashMap<>());
        if (defs == null || defs.isEmpty()) return fetched;
        ExecutorService pool = Executors.newFixedThreadPool(4);
        for (AgentDef def : defs) {
            final AgentDef current = def;
            pool.submit(() -> {
                String distroId = distroIdByAgentId == null
                    ? "" : distroIdByAgentId.get(current.id);
                String latest = fetchLatestVersion(current,
                    distroId == null ? "" : distroId);
                if (!latest.isEmpty()) fetched.put(current.id, latest);
            });
        }
        pool.shutdown();
        try {
            // Each fetch caps at its own timeouts (20s + 45s); 70s covers
            // a full round even if every request stalls.
            pool.awaitTermination(70, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return fetched;
    }

    /**
     * Refresh the latest-version cache in the background when it is stale
     * (older than UPDATE_CACHE_VALID_MS, one hour). Safe to call from
     * onCreate, onResume and drawer-open: it no-ops when the cache is
     * fresh or a refresh is already running, and never touches the UI
     * thread itself. onDone, when given, runs on the UI thread after a
     * refresh that fetched at least one fresh value (so the caller can
     * repaint a badge); it does not run when the cache was fresh or the
     * refresh fetched nothing. The latest version is fetched once per
     * agent id, using the first container it was found in for the
     * distro variant (agy in two distros with different libc families
     * compares against the first one's manifest).
     */
    public static void refreshLatestVersionsIfStale(final Runnable onDone) {
        new Thread(() -> {
            if (isUpdateRefreshRunning()) return;
            UpdateCache cache = loadUpdateCache();
            if (isUpdateCacheValid(cache.checkedAt)) return;
            synchronized (UPDATE_REFRESH_LOCK) {
                if (sRefreshingUpdates) return;
                sRefreshingUpdates = true;
            }
            boolean anyFetched = false;
            try {
                List<InstalledAgent> installed = detectInstalledAgents();
                Map<String, String> distroByAgent = new LinkedHashMap<>();
                for (InstalledAgent agent : installed) {
                    distroByAgent.putIfAbsent(agent.agentId, agent.distroId);
                }
                if (distroByAgent.isEmpty()) return;
                List<AgentDef> defs = new ArrayList<>();
                for (String id : distroByAgent.keySet()) {
                    AgentDef def = findDef(id);
                    if (def != null) defs.add(def);
                }
                Map<String, String> fetched = fetchLatestVersions(defs, distroByAgent);
                if (!fetched.isEmpty()) {
                    Map<String, String> merged = new HashMap<>(loadUpdateCache().latest);
                    merged.putAll(fetched);
                    saveUpdateCache(merged);
                    anyFetched = true;
                }
            } finally {
                synchronized (UPDATE_REFRESH_LOCK) {
                    sRefreshingUpdates = false;
                }
            }
            if (anyFetched && onDone != null) {
                new Handler(Looper.getMainLooper()).post(onDone);
            }
        }).start();
    }

    private static boolean isUpdateRefreshRunning() {
        synchronized (UPDATE_REFRESH_LOCK) {
            return sRefreshingUpdates;
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
     * session, same script-file method as the install flow) and open
     * its web UI in the phone browser. A progress dialog is shown
     * first, the session is started from it, and the app then waits
     * for the server to be reachable: for OpenCode / Pi / dsh it
     * polls the container rootfs for the link file the script writes
     * (/tmp/termux-web-&lt;agent&gt;-&lt;container&gt;.url); for Hermes
     * it probes 127.0.0.1:9119 until the dashboard answers. When the
     * link (or port) appears the browser opens it — except dsh, whose
     * link is shown for manual copy instead (see below); Settings &gt;
     * AI 助手 chooses which browser, and whether to open at all. On
     * failure a dialog shows the reason plus the last lines of the
     * launch log and offers Retry. The first web launch (any of the
     * four agents) also appends a one-time battery hint to the
     * success dialog: the server only stays up while Termux keeps
     * running in the background, and some phones freeze Termux when
     * it is backgrounded, which stalls the web UI until Termux is
     * foregrounded again. The hint is shown at most once
     * (SharedPreferences flag) and never blocks or delays the launch.
     * Every success dialog has a one-tap "Copy link" button that
     * writes the link to the clipboard and leaves the dialog open.
     *
     * The four agents differ in how their address is found:
     * - dsh: `dsh web` serves :3080, but the startup URL carries a
     *   fresh per-start session token that cannot be disabled, so
     *   the app cannot build a working URL. Only a loopback URL
     *   carrying `?token=` counts: on-device 2026-11-23 a bare
     *   http://127.0.0.1:3080 address was printed before the token
     *   URL, and opening the bare address cannot sign the browser
     *   in, so a bare address is never opened. The script keeps
     *   watching its log until the token URL appears; if `dsh web`
     *   exits without printing one, the script writes ERROR and the
     *   app shows the failure plus the log tail. The token URL is
     *   written to the URL file as soon as it appears — the app
     *   does NOT open it in the browser: dsh refuses a navigation
     *   that an app opens for it (the browser marks it
     *   sec-fetch-site: cross-site and dsh answers 403), while the
     *   same link pasted into the address bar is sec-fetch-site:
     *   none and works. Confirmed on-device 2026-11-23 with curl
     *   probes against the live server (cross-site -&gt; 403, none -&gt;
     *   401). So the app shows the link in a dialog with a
     *   one-tap copy button instead, whatever the auto-open switch
     *   says. (0.07 tried waiting for the service to answer 401
     *   before opening; that premise was wrong — the 403 is a
     *   per-request origin check, not a readiness window — and the
     *   probe was removed in 0.08.) There is no
     *   timeout: cancelling the progress dialog stops the wait and
     *   nothing opens afterwards, even if a token link appears later.
     * - OpenCode (v2.3.2): there is NO `opencode web` command in v2, and
     *   the browser password form is NOT a documented sign-in path at
     *   all (dev.opencode.ai/docs/cli/web): the password is documented
     *   only for Basic-auth clients (curl, `opencode --server`, TUI
     *   attach). On-device 2026-11-23 the form failed with the correct
     *   password (curl to /api/info returned 200 with the same
     *   password) while the official pairing link worked. So every tap
     *   just runs `opencode pair` and opens the first /auth/connect/
     *   link it prints (one-time use, expires in 5 minutes; the
     *   browser signs in with a 30-day session cookie). No password
     *   is shown or typed for the web UI, no port is pinned — the
     *   link's own host and port are used as-is — and no service
     *   setup is done in the app: `pair` starts the background
     *   service itself when it needs one. The old per-container
     *   password file (/root/.termux-aiagents/opencode-web-password)
     *   is retired: it was found empty on-device and OpenCode never
     *   reads it.
     * - Pi: the official pi-webapp has no fixed port; the launcher prints
     *   a token-bearing local address, so the app cannot build a URL.
     *   The script captures the first http URL from its full log, and
     *   as a second source polls `npx pi-webapp status`. PI_WEBAPP_AUTO_OPEN=0
     *   stops the launcher from trying to open a browser inside the
     *   container. The launcher may exit after starting its background
     *   service, so the script keeps the session open with a read prompt.
     * - Hermes: `hermes dashboard` serves the built-in web UI on fixed
     *   port 9119, binds 127.0.0.1 only and needs no password on
     *   loopback. The first run downloads pnpm and builds the web UI's
     *   JavaScript dependencies, so the app waits up to 5 minutes for
     *   the port to answer before opening the browser. The script
     *   resolves the `hermes` binary itself (~/.local/bin
     *   first, the launcher inside the checkout as fallback) because
     *   ~/.local/bin is not on PATH in the script shell. If a dashboard
     *   is already listening on 9119 (e.g. the user tapped web launch
     *   twice), the script prints so and exits instead of starting a
     *   second one.
     *
     * @param onDialogDismissed run when the web dialog is closed
     *        (DistroActivity passes finish() here); null is fine.
     */
    /** Rootfs-relative file the OpenCode pair script writes the fresh
     * pairing link into (one line:
     * http://<host>:<port>/auth/connect/...). The app polls this file
     * from the rootfs; the script deletes any stale copy first, so a
     * spent (single-use) link can never be re-opened. */
    private static String openCodePairUrlFileName(String container) {
        return "termux-web-opencode-" + container + ".url";
    }


    public static void startWebSession(final Context context, final InstalledAgent agent,
                                       final Runnable onDialogDismissed) {
        final AgentDef def = findDef(agent.agentId);
        if (def == null || def.webKind == WebKind.NONE) return;
        if (def.webKind == WebKind.OPENCODE) {
            // Pairing link only: see the javadoc above and
            // startOpenCodePairSession. No password is involved.
            startOpenCodePairSession(context, agent, onDialogDismissed);
            return;
        }
        switch (def.webKind) {
            case PI:
                startWebLinkSession(context, agent, PI_WEB_WAIT_TIMEOUT_MS,
                    R.string.aiagents_web_pi_progress, R.string.aiagents_web_pi_timeout,
                    onDialogDismissed);
                return;
            case DSH:
                startWebLinkSession(context, agent, DSH_WEB_WAIT_TIMEOUT_MS,
                    R.string.aiagents_web_dsh_progress, R.string.aiagents_web_dsh_failed,
                    onDialogDismissed);
                return;
            case HERMES:
                startHermesWebSession(context, agent, onDialogDismissed);
                return;
            default:
                return;
        }
    }

    /** Rootfs-relative file a web launch script writes its fresh link
     * into (one line: an http(s) URL). The app polls this file from
     * the rootfs; the script deletes any stale copy first, so a spent
     * (single-use) link can never be re-opened. */
    private static String webUrlFileName(String agentId, String container) {
        return "termux-web-" + agentId + "-" + container + ".url";
    }

    /** Rootfs-relative file a web launch script writes its full
     * stdout/stderr into, so a failure dialog can show the last lines.
     * On-device only; never uploaded anywhere. */
    private static String webLogFileName(String agentId, String container) {
        return "termux-web-" + agentId + "-" + container + ".log";
    }

    /** Generic web launch for Pi and dsh (and the shape OpenCode
     * already uses): write the launch script, delete any stale link
     * and log files, show a progress dialog, start the visible
     * session, and poll the rootfs for the link file the script
     * writes. The script, not the app, knows how its agent prints
     * its address; the app only ever looks for a generic http(s)
     * URL in the file. */
    private static void startWebLinkSession(final Context context, final InstalledAgent agent,
                                            final int timeoutMs, final int progressRes,
                                            final int timeoutRes,
                                            final Runnable onDialogDismissed) {
        final AgentDef def = findDef(agent.agentId);
        if (def == null) return;
        File rootfs = DistroUtils.containerRootfs(agent.container);
        if (rootfs == null) {
            Toast.makeText(context, R.string.aiagents_no_rootfs, Toast.LENGTH_LONG).show();
            return;
        }
        String scriptName = "termux-web-" + agent.agentId + "-" + agent.container + ".sh";
        File scriptFile = new File(rootfs, "tmp/" + scriptName);
        File urlFile = new File(rootfs, "tmp/" + webUrlFileName(agent.agentId, agent.container));
        File logFile = new File(rootfs, "tmp/" + webLogFileName(agent.agentId, agent.container));
        try {
            File parent = scriptFile.getParentFile();
            if (parent != null && !(parent.isDirectory() || parent.mkdirs())) {
                throw new IOException("Cannot create " + parent.getAbsolutePath());
            }
            try (FileOutputStream out = new FileOutputStream(scriptFile)) {
                out.write(buildWebScript(def, agent)
                    .getBytes(StandardCharsets.UTF_8));
            }
            scriptFile.setReadable(true, true);
            // Delete any stale link and log first: a link is single-use,
            // so last launch's file must never be re-opened.
            urlFile.delete();
            logFile.delete();
        } catch (Exception e) {
            Log.w(LOG_TAG, "Failed to write web launch script", e);
            Toast.makeText(context, context.getString(R.string.aiagents_web_script_failed,
                e.getMessage()), Toast.LENGTH_LONG).show();
            return;
        }
        final String prootDistro = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "proot-distro")
            .getAbsolutePath();
        if (!new File(prootDistro).isFile()) {
            Toast.makeText(context, R.string.distro_proot_distro_missing, Toast.LENGTH_LONG).show();
            return;
        }
        final String sessionName = context.getString(R.string.aiagents_web_session,
            agent.displayName, agent.distroDisplay);
        final Runnable startSession = () -> DistroUtils.startSessionWithExecutable(context, prootDistro,
            new String[]{"login", agent.container, "--", "bash", "/tmp/" + scriptName},
            sessionName, sessionName);
        // Cancelling the progress dialog must really stop the wait:
        // without this flag the poll thread would keep running and
        // open the browser the moment a link appeared, after the
        // user had already cancelled. The flag is checked on every
        // poll and again before anything is shown or opened.
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final AlertDialog progress = new AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.aiagents_web_title, agent.displayName))
            .setMessage(progressRes)
            .setNegativeButton(android.R.string.cancel, (dialog, which) -> {
                cancelled.set(true);
                if (onDialogDismissed != null) onDialogDismissed.run();
            })
            .show();
        startSession.run();
        // dsh never auto-opens (see the startWebSession javadoc):
        // its link is shown for manual copy instead.
        final boolean copyOnly = def.webKind == WebKind.DSH;
        final Runnable retry = () -> startWebLinkSession(context, agent, timeoutMs,
            progressRes, timeoutRes, onDialogDismissed);
        pollWebLinkFile(context, agent, progress, timeoutMs, timeoutRes, onDialogDismissed, retry,
            cancelled, copyOnly);
    }

    /** Poll the container rootfs for the web-link file, up to
     * timeoutMs (0 means wait indefinitely — used only by dsh, which
     * has no timeout: only a token link counts, and a bare address is
     * never opened). The file holds one line: the link, or
     * "ERROR: <reason>". Accepts any http(s) URL (the script already
     * prefers a token / pairing link when several URLs are printed);
     * the app never depends on the exact wording around it. If the
     * user cancels, the poll stops and nothing is opened afterwards.
     * @param cancelled set by the progress dialog's Cancel button;
     *        the poll thread checks it on every round and before
     *        showing or opening anything.
     * @param copyOnly when true (dsh) the link is shown for manual
     *        copy instead of being opened in the browser. */
    private static void pollWebLinkFile(final Context context, final InstalledAgent agent,
                                        final AlertDialog progress, final int timeoutMs,
                                        final int timeoutRes,
                                        final Runnable onDialogDismissed,
                                        final Runnable onRetry,
                                        final AtomicBoolean cancelled,
                                        final boolean copyOnly) {
        final File rootfs = DistroUtils.containerRootfs(agent.container);
        new Thread(() -> {
            String link = null;
            String error = null;
            int attempts = timeoutMs / OPENCODE_PAIR_POLL_INTERVAL_MS;
            for (int i = 0; timeoutMs <= 0 || i < attempts; i++) {
                if (cancelled.get()) return;
                try {
                    Thread.sleep(OPENCODE_PAIR_POLL_INTERVAL_MS);
                } catch (InterruptedException e) {
                    break;
                }
                if (cancelled.get()) return;
                if (rootfs == null) break;
                File urlFile = new File(rootfs, "tmp/" + webUrlFileName(agent.agentId, agent.container));
                String content = null;
                try {
                    if (urlFile.isFile()) content = DistroUtils.readFile(urlFile).trim();
                } catch (Exception e) {
                    Log.w(LOG_TAG, "Failed to read web-link file", e);
                }
                if (content == null || content.isEmpty()) continue;
                if (content.startsWith("ERROR:")) {
                    error = content.substring("ERROR:".length()).trim();
                    break;
                }
                if (content.startsWith("http://") || content.startsWith("https://")) {
                    link = content.split("\\s+")[0];
                    break;
                }
            }
            if (cancelled.get()) return;
            if (error == null && link == null) {
                error = context.getString(timeoutRes);
            }
            final String resultLink = link;
            final String resultError = error;
            new Handler(Looper.getMainLooper()).post(() -> {
                if (cancelled.get()) return;
                if (progress != null && progress.isShowing()) progress.dismiss();
                if (context instanceof Activity && ((Activity) context).isFinishing()) return;
                if (resultLink != null) {
                    if (copyOnly) {
                        showWebLinkCopyOnly(context, agent, resultLink, onDialogDismissed);
                    } else {
                        showWebLinkSuccess(context, agent, resultLink, onDialogDismissed);
                    }
                } else {
                    showWebLinkFailure(context, agent, resultError, onDialogDismissed, onRetry);
                }
            });
        }).start();
    }

    /** Open the web link in the browser (unless auto-open is off) and
     * show it with a one-tap copy button, with the one-time battery
     * hint on the first web launch. The copy button leaves the dialog
     * open so the user can still see the link. */
    private static void showWebLinkSuccess(final Context context, final InstalledAgent agent,
                                           final String link,
                                           final Runnable onDialogDismissed) {
        openWebUrl(context, link);
        String message = context.getString(R.string.aiagents_web_link_msg, link);
        String batteryHint = consumeWebBatteryHint(context);
        if (batteryHint != null) message += "\n\n" + batteryHint;
        AlertDialog dialog = new AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.aiagents_web_title, agent.displayName))
            .setMessage(message)
            .setNeutralButton(R.string.aiagents_web_copy_link,
                (d, which) -> copyLinkToClipboard(context, link))
            .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                if (onDialogDismissed != null) onDialogDismissed.run();
            })
            .show();
        keepCopyButtonOpen(dialog, context, link);
    }

    /** dsh only: show the link for manual copy instead of opening it.
     * dsh refuses a navigation that an app opens for it (the browser
     * marks it sec-fetch-site: cross-site and dsh answers 403), while
     * the same link pasted into the address bar is sec-fetch-site:
     * none and works — confirmed on-device 2026-11-23 with curl
     * probes. So the app never calls openWebUrl for dsh, whatever
     * the auto-open switch says; the dialog explains this and offers
     * one-tap copy, and stays open after copying so the user can
     * still see the link. */
    private static void showWebLinkCopyOnly(final Context context, final InstalledAgent agent,
                                            final String link,
                                            final Runnable onDialogDismissed) {
        String message = context.getString(R.string.aiagents_web_dsh_copy_msg, link);
        String batteryHint = consumeWebBatteryHint(context);
        if (batteryHint != null) message += "\n\n" + batteryHint;
        AlertDialog dialog = new AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.aiagents_web_title, agent.displayName))
            .setMessage(message)
            .setNeutralButton(R.string.aiagents_web_copy_link,
                (d, which) -> copyLinkToClipboard(context, link))
            .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                if (onDialogDismissed != null) onDialogDismissed.run();
            })
            .show();
        keepCopyButtonOpen(dialog, context, link);
    }

    /** Copy a web link to the clipboard and toast. Used by the
     * "Copy link" button of every web link dialog. */
    private static void copyLinkToClipboard(final Context context, final String link) {
        try {
            ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) return;
            cm.setPrimaryClip(ClipData.newPlainText("web link", link));
            Toast.makeText(context, R.string.msg_select_url_copied_to_clipboard,
                Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.w(LOG_TAG, "Failed to copy link to clipboard", e);
        }
    }

    /** A stock AlertDialog button dismisses the dialog when tapped.
     * Replace the "Copy link" neutral button's listener after show()
     * so tapping it copies the link and leaves the dialog open. */
    private static void keepCopyButtonOpen(final AlertDialog dialog, final Context context,
                                           final String link) {
        if (dialog == null) return;
        Button copyButton = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        if (copyButton != null) {
            copyButton.setOnClickListener(v -> copyLinkToClipboard(context, link));
        }
    }

    /** Web launch failed: show the reason plus the last lines of the
     * launch log (on-device only) and offer Retry. */
    private static void showWebLinkFailure(final Context context, final InstalledAgent agent,
                                           final String reason,
                                           final Runnable onDialogDismissed,
                                           final Runnable onRetry) {
        String detail = reason;
        String logTail = readLogTail(context, agent);
        if (logTail != null && !logTail.isEmpty()) detail += "\n\n" + logTail;
        new AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.aiagents_web_title, agent.displayName))
            .setMessage(context.getString(R.string.aiagents_web_link_failed, detail))
            .setPositiveButton(R.string.aiagents_retry, (dialog, which) -> {
                if (onRetry != null) onRetry.run();
            })
            .setNegativeButton(android.R.string.cancel, (dialog, which) -> {
                if (onDialogDismissed != null) onDialogDismissed.run();
            })
            .show();
    }

    /** Last 8 lines of the web launch log for this agent, or null when
     * there is no log file. Shown in the failure dialog only. */
    private static String readLogTail(final Context context, final InstalledAgent agent) {
        try {
            File rootfs = DistroUtils.containerRootfs(agent.container);
            if (rootfs == null) return null;
            File logFile = new File(rootfs, "tmp/" + webLogFileName(agent.agentId, agent.container));
            if (!logFile.isFile()) return null;
            String content = DistroUtils.readFile(logFile);
            if (content == null || content.isEmpty()) return null;
            String[] lines = content.split("\n");
            int from = Math.max(0, lines.length - 8);
            StringBuilder out = new StringBuilder();
            for (int i = from; i < lines.length; i++) {
                if (out.length() > 0) out.append("\n");
                out.append(lines[i]);
            }
            return out.toString();
        } catch (Exception e) {
            Log.w(LOG_TAG, "Failed to read web launch log", e);
            return null;
        }
    }

    /** Hermes web launch: fixed port 9119, no link to mint. Start the
     * dashboard session, then probe 127.0.0.1:9119 from the app (proot
     * shares Termux's network namespace, so the container's loopback
     * is this phone's loopback) until it answers, and only then open
     * the browser. The first launch downloads pnpm and builds the web
     * UI, so the wait is up to 5 minutes; a dashboard that is already
     * running answers the first probe and opens at once. */
    private static void startHermesWebSession(final Context context, final InstalledAgent agent,
                                              final Runnable onDialogDismissed) {
        final AgentDef def = findDef(agent.agentId);
        if (def == null) return;
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
                out.write(buildWebScript(def, agent)
                    .getBytes(StandardCharsets.UTF_8));
            }
            scriptFile.setReadable(true, true);
        } catch (Exception e) {
            Log.w(LOG_TAG, "Failed to write web launch script", e);
            Toast.makeText(context, context.getString(R.string.aiagents_web_script_failed,
                e.getMessage()), Toast.LENGTH_LONG).show();
            return;
        }
        final String prootDistro = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "proot-distro")
            .getAbsolutePath();
        if (!new File(prootDistro).isFile()) {
            Toast.makeText(context, R.string.distro_proot_distro_missing, Toast.LENGTH_LONG).show();
            return;
        }
        final String sessionName = context.getString(R.string.aiagents_web_session,
            agent.displayName, agent.distroDisplay);
        final Runnable startSession = () -> DistroUtils.startSessionWithExecutable(context, prootDistro,
            new String[]{"login", agent.container, "--", "bash", "/tmp/" + scriptName},
            sessionName, sessionName);
        final AlertDialog progress = new AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.aiagents_web_title, agent.displayName))
            .setMessage(R.string.aiagents_web_hermes_progress)
            .setNegativeButton(android.R.string.cancel, (dialog, which) -> {
                if (onDialogDismissed != null) onDialogDismissed.run();
            })
            .show();
        startSession.run();
        final String hermesUrl = "http://127.0.0.1:" + def.webPort;
        final Runnable retry = () -> startHermesWebSession(context, agent, onDialogDismissed);
        new Thread(() -> {
            boolean up = false;
            int attempts = HERMES_PORT_WAIT_TIMEOUT_MS / HERMES_PORT_POLL_INTERVAL_MS;
            for (int i = 0; i < attempts; i++) {
                try {
                    Thread.sleep(HERMES_PORT_POLL_INTERVAL_MS);
                } catch (InterruptedException e) {
                    break;
                }
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress("127.0.0.1", def.webPort), 1000);
                    up = true;
                    break;
                } catch (Exception ignored) {
                    // Not up yet; keep waiting.
                }
            }
            final boolean resultUp = up;
            new Handler(Looper.getMainLooper()).post(() -> {
                if (progress != null && progress.isShowing()) progress.dismiss();
                if (context instanceof Activity && ((Activity) context).isFinishing()) return;
                if (resultUp) {
                    showWebLinkSuccess(context, agent, hermesUrl, onDialogDismissed);
                } else {
                    showWebLinkFailure(context, agent,
                        context.getString(R.string.aiagents_web_hermes_timeout),
                        onDialogDismissed, retry);
                }
            });
        }).start();
    }

    /**
     * OpenCode web launch, pairing link only (see the
     * startWebSession javadoc). Writes the pair script, deletes any
     * stale pairing-link file, shows a progress dialog, starts the
     * visible session, and polls the rootfs for the link file the
     * script writes. No password is shown or typed anywhere here.
     */
    private static void startOpenCodePairSession(final Context context, final InstalledAgent agent,
                                                 final Runnable onDialogDismissed) {
        final AgentDef def = findDef(agent.agentId);
        if (def == null) return;
        File rootfs = DistroUtils.containerRootfs(agent.container);
        if (rootfs == null) {
            Toast.makeText(context, R.string.aiagents_no_rootfs, Toast.LENGTH_LONG).show();
            return;
        }
        String scriptName = "termux-web-" + agent.agentId + "-" + agent.container + ".sh";
        File scriptFile = new File(rootfs, "tmp/" + scriptName);
        File urlFile = new File(rootfs, "tmp/" + openCodePairUrlFileName(agent.container));
        try {
            File parent = scriptFile.getParentFile();
            if (parent != null && !(parent.isDirectory() || parent.mkdirs())) {
                throw new IOException("Cannot create " + parent.getAbsolutePath());
            }
            try (FileOutputStream out = new FileOutputStream(scriptFile)) {
                out.write(buildWebScript(def, agent)
                    .getBytes(StandardCharsets.UTF_8));
            }
            scriptFile.setReadable(true, true);
            // Delete any stale pairing link first: a link is single-use,
            // so last launch's file must never be re-opened.
            urlFile.delete();
        } catch (Exception e) {
            Log.w(LOG_TAG, "Failed to write web launch script", e);
            Toast.makeText(context, context.getString(R.string.aiagents_web_script_failed,
                e.getMessage()), Toast.LENGTH_LONG).show();
            return;
        }
        final String prootDistro = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "proot-distro")
            .getAbsolutePath();
        if (!new File(prootDistro).isFile()) {
            Toast.makeText(context, R.string.distro_proot_distro_missing, Toast.LENGTH_LONG).show();
            return;
        }
        final String sessionName = context.getString(R.string.aiagents_web_session,
            agent.displayName, agent.distroDisplay);
        final Runnable startSession = () -> DistroUtils.startSessionWithExecutable(context, prootDistro,
            new String[]{"login", agent.container, "--", "bash", "/tmp/" + scriptName},
            sessionName, sessionName);
        final AlertDialog progress = new AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.aiagents_web_title, agent.displayName))
            .setMessage(R.string.aiagents_web_opencode_pair_progress)
            .setNegativeButton(android.R.string.cancel, (dialog, which) -> {
                if (onDialogDismissed != null) onDialogDismissed.run();
            })
            .show();
        startSession.run();
        pollOpenCodePairLink(context, agent, progress, onDialogDismissed);
    }

    /**
     * Poll the container rootfs for the pairing-link file, up to 30 s.
     * The file holds one line: the pairing link, or "ERROR: <reason>".
     * Posts the result to the UI thread; the progress dialog is
     * dismissed there and the browser opened (or the failure dialog
     * shown). Guards against a finishing activity, like the old probe.
     */
    private static void pollOpenCodePairLink(final Context context, final InstalledAgent agent,
                                             final AlertDialog progress,
                                             final Runnable onDialogDismissed) {
        final File rootfs = DistroUtils.containerRootfs(agent.container);
        new Thread(() -> {
            String link = null;
            String error = null;
            int attempts = OPENCODE_PAIR_WAIT_TIMEOUT_MS / OPENCODE_PAIR_POLL_INTERVAL_MS;
            for (int i = 0; i < attempts; i++) {
                try {
                    Thread.sleep(OPENCODE_PAIR_POLL_INTERVAL_MS);
                } catch (InterruptedException e) {
                    break;
                }
                if (rootfs == null) break;
                File urlFile = new File(rootfs, "tmp/" + openCodePairUrlFileName(agent.container));
                String content = null;
                try {
                    if (urlFile.isFile()) content = DistroUtils.readFile(urlFile).trim();
                } catch (Exception e) {
                    Log.w(LOG_TAG, "Failed to read pairing-link file", e);
                }
                if (content == null || content.isEmpty()) continue;
                if (content.startsWith("ERROR:")) {
                    error = content.substring("ERROR:".length()).trim();
                    break;
                }
                if (content.startsWith("http://") && content.contains("/auth/connect/")) {
                    link = content.split("\\s+")[0];
                    break;
                }
                // Any other content is ignored: the script only ever
                // writes a link or an ERROR line.
            }
            if (error == null && link == null) {
                error = context.getString(R.string.aiagents_web_opencode_pair_timeout);
            }
            final String resultLink = link;
            final String resultError = error;
            new Handler(Looper.getMainLooper()).post(() -> {
                if (progress != null && progress.isShowing()) progress.dismiss();
                if (context instanceof Activity && ((Activity) context).isFinishing()) return;
                if (resultLink != null) {
                    showOpenCodePairSuccess(context, agent, resultLink, onDialogDismissed);
                } else {
                    showOpenCodePairFailure(context, agent, resultError, onDialogDismissed,
                        () -> startOpenCodePairSession(context, agent, onDialogDismissed));
                }
            });
        }).start();
    }

    /** Open the pairing link in the browser and show it with a
     * one-tap copy button. The copy button leaves the dialog open
     * so the user can still see the link. */
    private static void showOpenCodePairSuccess(final Context context, final InstalledAgent agent,
                                                final String pairLink,
                                                final Runnable onDialogDismissed) {
        openWebUrl(context, pairLink);
        AlertDialog dialog = new AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.aiagents_web_title, agent.displayName))
            .setMessage(context.getString(R.string.aiagents_web_opencode_pair_msg, pairLink))
            .setNeutralButton(R.string.aiagents_web_copy_link,
                (d, which) -> copyLinkToClipboard(context, pairLink))
            .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                if (onDialogDismissed != null) onDialogDismissed.run();
            })
            .show();
        keepCopyButtonOpen(dialog, context, pairLink);
    }

    /** Pairing failed: show the reason and offer Retry (mint a fresh link). */
    private static void showOpenCodePairFailure(final Context context, final InstalledAgent agent,
                                                final String reason,
                                                final Runnable onDialogDismissed,
                                                final Runnable onRetry) {
        new AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.aiagents_web_title, agent.displayName))
            .setMessage(context.getString(R.string.aiagents_web_opencode_pair_failed, reason))
            .setPositiveButton(R.string.aiagents_retry, (dialog, which) -> {
                if (onRetry != null) onRetry.run();
            })
            .setNegativeButton(android.R.string.cancel, (dialog, which) -> {
                if (onDialogDismissed != null) onDialogDismissed.run();
            })
            .show();
    }

    // ------------------------------------------------------------------
    // Web-browser choice for web launches (Settings > AI 助手)
    // ------------------------------------------------------------------

    /** One browser the user can pick for web launches. */
    public static final class BrowserChoice {
        public final String packageName; // "" = system default
        public final String label;

        public BrowserChoice(String packageName, String label) {
            this.packageName = packageName;
            this.label = label;
        }
    }

    /** The intent Android itself uses to detect browser apps
     * (Chromium's BROWSER_INTENT, copied from Android's
     * BrowserRoleBehavior): ACTION_VIEW + BROWSABLE + a bare http
     * scheme, no host. A scheme-only data part matches every http
     * link handler. */
    private static Intent newBrowserProbeIntent() {
        return new Intent(Intent.ACTION_VIEW)
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .setData(Uri.fromParts("http", "", null));
    }

    /** Package of the browser Android would open for an http link
     * when no browser is chosen in the app ("" when none is set:
     * then the system shows a chooser). Used to keep the
     * system-default browser out of listWebBrowsers, so it only
     * appears once, as the "system default" row in Settings. When
     * no default is set Android answers the system resolver
     * (chooser) activity instead of a browser; that is "" too. */
    public static String getSystemDefaultBrowserPackage(Context context) {
        if (context == null) return "";
        try {
            PackageManager pm = context.getPackageManager();
            // resolveActivity returns the single best match (the
            // default); MATCH_DEFAULT_ONLY is right here — it is
            // only the *listing* query that must use MATCH_ALL.
            ResolveInfo resolved = pm.resolveActivity(newBrowserProbeIntent(),
                PackageManager.MATCH_DEFAULT_ONLY);
            if (resolved == null || resolved.activityInfo == null) return "";
            String pkg = resolved.activityInfo.packageName;
            if (pkg == null || pkg.equals(context.getPackageName())) return "";
            if ("android".equals(pkg)) return "";
            String cls = resolved.activityInfo.name;
            if (cls != null && (cls.endsWith("ResolverActivity")
                    || cls.endsWith("ChooserActivity"))) return "";
            return pkg;
        } catch (Exception e) {
            Log.w(LOG_TAG, "Failed to resolve default browser", e);
            return "";
        }
    }

    /** Well-known browser packages, used only as a supplementary
     * safety net in listWebBrowsers (see there). The generic query
     * is the primary source and must list every browser, including
     * ones not named here. */
    private static final String[] KNOWN_BROWSER_PACKAGES = {
        "com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary",
        "com.microsoft.emmx", "org.mozilla.firefox", "org.mozilla.firefox_beta",
        "org.mozilla.focus", "com.brave.browser", "com.opera.browser",
        "com.opera.mini.native", "com.sec.android.app.sbrowser", "mark.via",
        "com.kiwibrowser.browser", "com.vivaldi.browser", "com.duckduckgo.mobile.android",
    };

    /** Browsers installed on this phone that can open an http link,
     * sorted by label, de-duplicated by package. Termux itself is
     * excluded, and so is the system-default browser: it is already
     * the "system default" row in Settings, so listing it again by
     * name would show the same browser twice. The list is built
     * live: a browser installed after the last settings visit shows
     * up, an uninstalled one does not. The primary source is the
     * generic query below; the well-known packages are only a
     * safety net, so a browser not named there still shows up.
     * Returns an empty list when nothing can open http links. */
    public static List<BrowserChoice> listWebBrowsers(Context context) {
        List<BrowserChoice> out = new ArrayList<>();
        if (context == null) return out;
        PackageManager pm = context.getPackageManager();
        Map<String, String> byPackage = new LinkedHashMap<>();
        String defaultPkg = getSystemDefaultBrowserPackage(context);
        // Primary source: the generic query. It MUST use MATCH_ALL.
        // With MATCH_DEFAULT_ONLY the system returns only the
        // default browser once one is set — on-device 0.06/0.07
        // showed exactly that (only the default ever appeared), and
        // the 0.07 <queries> declaration changed nothing, because
        // the app targets API 28: package-visibility filtering only
        // applies to apps targeting API 30+, so <queries> was never
        // the operative factor here at all. The flag set below is
        // Chromium's queryAllWebBrowsersInfo, copied from Android's
        // own BrowserRoleBehavior (Chromium crbug 738506 fixed the
        // same "only the default browser" symptom by switching from
        // MATCH_DEFAULT_ONLY to MATCH_ALL). Do NOT go back to
        // MATCH_DEFAULT_ONLY alone here.
        try {
            int flags = PackageManager.MATCH_ALL | PackageManager.MATCH_DIRECT_BOOT_AWARE
                | PackageManager.MATCH_DIRECT_BOOT_UNAWARE | PackageManager.MATCH_DEFAULT_ONLY;
            List<ResolveInfo> infos = pm.queryIntentActivities(newBrowserProbeIntent(), flags);
            if (infos != null) {
                for (ResolveInfo info : infos) {
                    if (info == null || info.activityInfo == null) continue;
                    String pkg = info.activityInfo.packageName;
                    if (pkg == null || pkg.equals(context.getPackageName())) continue;
                    if (pkg.equals(defaultPkg)) continue;
                    if (!byPackage.containsKey(pkg)) {
                        CharSequence label = info.loadLabel(pm);
                        byPackage.put(pkg, label == null ? pkg : label.toString());
                    }
                }
            }
        } catch (Exception e) {
            Log.w(LOG_TAG, "Failed to list browsers", e);
        }
        // Supplementary safety net only: well-known browsers,
        // checked one by one against the <package> entries in the
        // manifest. A browser installed later has to show up
        // through the generic query above, not through this list.
        for (String pkg : KNOWN_BROWSER_PACKAGES) {
            if (byPackage.containsKey(pkg) || pkg.equals(defaultPkg)
                    || pkg.equals(context.getPackageName())) continue;
            try {
                ApplicationInfo appInfo = pm.getApplicationInfo(pkg, 0);
                Intent probe = newBrowserProbeIntent();
                probe.setPackage(pkg);
                List<ResolveInfo> infos = pm.queryIntentActivities(probe, 0);
                if (infos == null || infos.isEmpty()) continue;
                CharSequence label = pm.getApplicationLabel(appInfo);
                byPackage.put(pkg, label == null ? pkg : label.toString());
            } catch (PackageManager.NameNotFoundException e) {
                // Not installed; skip silently.
            } catch (Exception e) {
                Log.w(LOG_TAG, "Failed to check browser " + pkg, e);
            }
        }
        List<Map.Entry<String, String>> entries = new ArrayList<>(byPackage.entrySet());
        Collections.sort(entries, (a, b) -> a.getValue().compareToIgnoreCase(b.getValue()));
        for (Map.Entry<String, String> entry : entries) {
            out.add(new BrowserChoice(entry.getKey(), entry.getValue()));
        }
        return out;
    }

    /** Saved browser package for web launches ("" = system default). */
    public static String getWebBrowserPackage(Context context) {
        SharedPreferences prefs = SharedPreferenceUtils.getPrivateSharedPreferences(
            context, AI_AGENTS_PREFS_NAME);
        return prefs.getString(PREF_WEB_BROWSER_PACKAGE, "");
    }

    public static void setWebBrowserPackage(Context context, String packageName) {
        SharedPreferences prefs = SharedPreferenceUtils.getPrivateSharedPreferences(
            context, AI_AGENTS_PREFS_NAME);
        prefs.edit().putString(PREF_WEB_BROWSER_PACKAGE,
            packageName == null ? "" : packageName).apply();
    }

    /** Whether tapping a web button opens the browser automatically. */
    public static boolean isWebAutoOpen(Context context) {
        SharedPreferences prefs = SharedPreferenceUtils.getPrivateSharedPreferences(
            context, AI_AGENTS_PREFS_NAME);
        return prefs.getBoolean(PREF_WEB_AUTO_OPEN, true);
    }

    public static void setWebAutoOpen(Context context, boolean autoOpen) {
        SharedPreferences prefs = SharedPreferenceUtils.getPrivateSharedPreferences(
            context, AI_AGENTS_PREFS_NAME);
        prefs.edit().putBoolean(PREF_WEB_AUTO_OPEN, autoOpen).apply();
    }

    /** Open a web link in the chosen browser. Returns false without
     * opening anything when auto-open is off (the caller then only
     * shows the link). A saved browser that is gone or cannot open
     * the link falls back to the system default once, and the saved
     * choice is cleared so the next tap does not fail the same way. */
    public static boolean openWebUrl(Context context, String url) {
        if (context == null || url == null || url.isEmpty()) return false;
        if (!isWebAutoOpen(context)) return false;
        String pkg = getWebBrowserPackage(context);
        if (pkg != null && !pkg.isEmpty()) {
            Intent chosen = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            if (!(context instanceof Activity)) {
                chosen.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            chosen.setPackage(pkg);
            try {
                context.startActivity(chosen);
                return true;
            } catch (ActivityNotFoundException e) {
                Log.w(LOG_TAG, "Saved browser " + pkg + " cannot open web links; falling back", e);
                setWebBrowserPackage(context, "");
            }
        }
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        if (!(context instanceof Activity)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        try {
            context.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException e) {
            Log.w(LOG_TAG, "No browser can open " + url, e);
            return false;
        }
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

    /** The script a web launch runs inside the container (as root). */
    private static String buildWebScript(AgentDef def, InstalledAgent agent) {
        StringBuilder s = new StringBuilder();
        s.append("#!/bin/bash\n");
        s.append("# Generated by Termux \"AI 助手\" web launch - do not edit.\n");
        switch (def.webKind) {
            case DSH:
                // `dsh web` prints a token-bearing URL:
                // dsh web: http://127.0.0.1:3080/?token=<fresh token>
                // The token is minted fresh on every start and cannot be
                // disabled. Only a loopback URL carrying token= counts:
                // on-device 2026-11-23 a bare http://127.0.0.1:3080
                // address was printed before the token URL, and opening
                // the bare address cannot sign the browser in, so a
                // bare address is never written to the URL file. The
                // token URL is written to the URL file as soon as it
                // appears; the app never opens it in the browser (the
                // dialog offers copy instead — see showWebLinkCopyOnly):
                // dsh refuses an app-opened navigation (the browser
                // marks it sec-fetch-site: cross-site and dsh answers
                // 403), while the same link pasted into the address
                // bar is sec-fetch-site: none and works. Confirmed
                // on-device 2026-11-23 with curl probes (cross-site
                // -> 403, none -> 401). 0.07 added a readiness probe
                // (curl/wget GET of the root until 401) before writing
                // the URL file; that premise was wrong — the 403 is a
                // per-request origin check, not a readiness window —
                // so the probe was removed in 0.08 and the script is
                // back to token-then-write. There is no time limit;
                // if `dsh web` exits first, the script writes ERROR so
                // the app shows the failure plus the log tail. The
                // app-side wait is cancelled only by the user
                // (progress dialog Cancel), which stops the poll
                // thread; the script itself keeps the session alive.
                s.append("echo \"正在启动 dsh 网页版（").append(agent.distroDisplay).append("）…\"\n");
                s.append("URL_FILE=\"/tmp/").append(webUrlFileName(agent.agentId, agent.container)).append("\"\n");
                s.append("LOG_FILE=\"/tmp/").append(webLogFileName(agent.agentId, agent.container)).append("\"\n");
                s.append("rm -f \"$URL_FILE\" \"$LOG_FILE\"\n");
                s.append("command -v dsh >/dev/null 2>&1 || { echo \"没有找到 dsh，请先在“AI 助手”里安装。\"; echo \"ERROR: dsh not installed\" > \"$URL_FILE\"; read -r -p \"按回车关闭此会话\"; exit 1; }\n");
                s.append("cd ~ || true\n");
                s.append("dsh web --no-open >\"$LOG_FILE\" 2>&1 &\n");
                s.append("DSH_PID=$!\n");
                s.append("LINK=\"\"\n");
                s.append("while true; do LINK=\"$(grep -o 'http[s]*://\\(127\\.0\\.0\\.1\\|localhost\\)[^ ]*' \"$LOG_FILE\" 2>/dev/null | grep -m 1 'token=')\"; [ -n \"$LINK\" ] && break; kill -0 \"$DSH_PID\" 2>/dev/null || break; sleep 1; done\n");
                s.append("if [ -z \"$LINK\" ]; then echo \"dsh 网页版地址没找到（服务已退出）。\"; ERR=\"$(tail -n 1 \"$LOG_FILE\" 2>/dev/null)\"; [ -n \"$ERR\" ] || ERR=\"dsh web exited without printing a token link\"; echo \"ERROR: $ERR\" > \"$URL_FILE\"; wait \"$DSH_PID\" 2>/dev/null; read -r -p \"按回车关闭此会话\"; exit 1; fi\n");
                s.append("printf '%s\\n' \"$LINK\" > \"$URL_FILE\"\n");
                s.append("echo \"dsh 网页版地址：$LINK\"\n");
                s.append("wait \"$DSH_PID\"\n");
                s.append("echo \"dsh 网页版已停止。\"\n");
                s.append("read -r -p \"按回车关闭此会话\" || true\n");
                break;
            case OPENCODE:
                // Pairing link only (see the startWebSession javadoc):
                // the official way to open the web UI. `opencode pair`
                // starts the background service itself when needed, so
                // the app does no service setup at all: no fixed port,
                // no password, no standalone serve. The script writes
                // the first /auth/connect/ link (one line) to the
                // pairing-link file the app polls, or an
                // "ERROR: <reason>" line on failure.
                s.append("echo \"正在生成 OpenCode 配对链接（").append(agent.distroDisplay).append("）…\"\n");
                s.append("URL_FILE=\"/tmp/").append(openCodePairUrlFileName(agent.container)).append("\"\n");
                s.append("LOG_FILE=\"/tmp/").append(webLogFileName(agent.agentId, agent.container)).append("\"\n");
                s.append("rm -f \"$URL_FILE\" \"$LOG_FILE\"\n");
                s.append("command -v opencode >/dev/null 2>&1 || { echo \"没有找到 opencode，请先在“AI 助手”里安装。\"; echo \"ERROR: opencode not installed\" > \"$URL_FILE\"; read -r -p \"按回车关闭此会话\"; exit 1; }\n");
                s.append("PAIR_OUT=\"$(opencode pair 2>&1)\"\n");
                s.append("printf '%s\\n' \"$PAIR_OUT\" > \"$LOG_FILE\"\n");
                s.append("LINK=\"$(printf '%s\\n' \"$PAIR_OUT\" | grep -m 1 -o 'http[s]*://[^ ]*/auth/connect/[^ ]*')\"\n");
                s.append("[ -z \"$LINK\" ] && LINK=\"$(printf '%s\\n' \"$PAIR_OUT\" | grep -m 1 -o 'http[s]*://[^ ]*')\"\n");
                s.append("if [ -z \"$LINK\" ]; then printf '%s\\n' \"$PAIR_OUT\"; echo \"配对链接生成失败。\"; ERR=\"$(printf '%s\\n' \"$PAIR_OUT\" | head -n 1)\"; [ -n \"$ERR\" ] || ERR=\"could not mint a pairing link\"; echo \"ERROR: $ERR\" > \"$URL_FILE\"; read -r -p \"按回车关闭此会话\"; exit 1; fi\n");
                s.append("printf '%s\\n' \"$LINK\" > \"$URL_FILE\"\n");
                s.append("echo \"配对链接：$LINK（一次有效，5 分钟内打开）\"\n");
                s.append("read -r -p \"按回车关闭此会话\" || true\n");
                break;
            case PI:
                // pi-webapp starts a background service and prints a
                // token-bearing local address; there is no fixed port.
                // The script captures the first http URL from the full
                // log (preferring one with a token), and as a second
                // source polls `npx pi-webapp status` every 10 s, which
                // lists the running service's address. Either source
                // works even if the launcher's wording changes.
                s.append("echo \"正在启动 Pi 网页版（").append(agent.distroDisplay).append("）…\"\n");
                s.append("echo \"第一次启动会先下载官方 pi-webapp（npx），稍等一会。\"\n");
                s.append("URL_FILE=\"/tmp/").append(webUrlFileName(agent.agentId, agent.container)).append("\"\n");
                s.append("LOG_FILE=\"/tmp/").append(webLogFileName(agent.agentId, agent.container)).append("\"\n");
                s.append("rm -f \"$URL_FILE\" \"$LOG_FILE\"\n");
                s.append("command -v pi >/dev/null 2>&1 || { echo \"没有找到 pi，请先在“AI 助手”里安装。\"; echo \"ERROR: pi not installed\" > \"$URL_FILE\"; read -r -p \"按回车关闭此会话\"; exit 1; }\n");
                s.append("command -v npx >/dev/null 2>&1 || { echo \"没有找到 npx（需要 Node），请先在“AI 助手”里安装 Pi（会自动装 Node 24）。\"; echo \"ERROR: npx not installed\" > \"$URL_FILE\"; read -r -p \"按回车关闭此会话\"; exit 1; }\n");
                s.append("cd ~ || true\n");
                s.append("PI_WEBAPP_AUTO_OPEN=0 npx -y pi-webapp >\"$LOG_FILE\" 2>&1 &\n");
                s.append("PI_PID=$!\n");
                s.append("LINK=\"\"\n");
                s.append("for i in $(seq 1 60); do LINK=\"$(grep -o 'http[s]*://[^ ]*' \"$LOG_FILE\" 2>/dev/null | grep -m 1 -E 'token=|/auth/connect/')\"; [ -z \"$LINK\" ] && LINK=\"$(grep -m 1 -o 'http[s]*://\\(127\\.0\\.0\\.1\\|localhost\\)[^ ]*' \"$LOG_FILE\" 2>/dev/null)\"; [ -n \"$LINK\" ] && break; if [ $((i % 5)) -eq 0 ]; then STATUS_OUT=\"$(npx -y pi-webapp status 2>/dev/null)\"; LINK=\"$(printf '%s\\n' \"$STATUS_OUT\" | grep -m 1 -o 'http[s]*://\\(127\\.0\\.0\\.1\\|localhost\\)[^ ]*')\"; [ -n \"$LINK\" ] && break; fi; sleep 2; done\n");
                s.append("if [ -z \"$LINK\" ]; then echo \"Pi 网页版地址没找到。\"; ERR=\"$(tail -n 1 \"$LOG_FILE\" 2>/dev/null)\"; [ -n \"$ERR\" ] || ERR=\"could not find the Pi web address\"; echo \"ERROR: $ERR\" > \"$URL_FILE\"; read -r -p \"按回车关闭此会话\"; exit 1; fi\n");
                s.append("printf '%s\\n' \"$LINK\" > \"$URL_FILE\"\n");
                s.append("echo \"Pi 网页版地址：$LINK\"\n");
                s.append("echo \"Pi 网页版服务运行中，关闭此会话即停止。\"\n");
                s.append("wait \"$PI_PID\" 2>/dev/null\n");
                s.append("read -r -p \"按回车关闭此会话\" || true\n");
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
        "codex|Codex|usr/local/bin/codex\n" +
        "codex|Codex|usr/bin/codex\n" +
        "codex|Codex|root/.codex/packages/standalone/current/bin/codex\n" +
        "pi|Pi|usr/bin/pi\n" +
        "pi|Pi|usr/local/bin/pi\n" +
        "agy|Antigravity CLI|root/.local/bin/agy\n" +
        "dsh|DeepSeek Harness|usr/bin/dsh\n" +
        "dsh|DeepSeek Harness|usr/local/bin/dsh\n" +
        "opencode|OpenCode|usr/bin/opencode\n" +
        "opencode|OpenCode|usr/local/bin/opencode\n" +
        "aider|Aider|root/.local/bin/aider\n" +
        "claude|Claude Code|root/.local/bin/claude\n" +
        "claude|Claude Code|usr/bin/claude\n" +
        "claude|Claude Code|usr/local/bin/claude\n" +
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
        "            # -L first: a binary may be an absolute symlink into the container\n" +
        "            # (Codex ~/.local/bin/codex -> /root/.codex/...), whose target only\n" +
        "            # resolves inside proot; -f alone would miss it from the host side.\n" +
        "            if [ -L \"$rootfs/$rel\" ] || [ -f \"$rootfs/$rel\" ]; then\n" +
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
        "            echo \"生成 OpenCode 配对链接（$container）…\"\n" +
        "            exec proot-distro login \"$container\" -- bash -c 'PAIR_OUT=\"$(opencode pair 2>&1)\"; LINK=\"$(printf \"%s\\n\" \"$PAIR_OUT\" | grep -m 1 -o \"http[s]*://[^ ]*/auth/connect/[^ ]*\")\"; if [ -z \"$LINK\" ]; then printf \"%s\\n\" \"$PAIR_OUT\"; echo \"配对链接生成失败。\"; else echo \"配对链接：$LINK（一次有效，5 分钟内打开）\"; fi; read -r -p \"按回车关闭此会话\" || true'\n" +
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
