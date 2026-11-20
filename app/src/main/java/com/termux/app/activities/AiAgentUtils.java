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

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;

import com.termux.R;
import com.termux.shared.termux.TermuxConstants;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class AiAgentUtils {

    private static final String LOG_TAG = "AiAgentUtils";

    /** Maximum rows shown in the drawer; more are reached from DistroActivity. */
    public static final int MAX_DRAWER_AGENT_ROWS = 5;

    private AiAgentUtils() {
        // No instances.
    }

    /** How an installed agent's version is found (from files, never by running it). */
    public enum VersionKind {
        /** No local version file known (agy): show installed without a version. */
        NONE,
        /** <prefix>/lib/node_modules/<npm package>/package.json "version". */
        NPM_PACKAGE_JSON,
        /** ~/.codex/packages/standalone/releases/<version>/ directory names. */
        CODEX_RELEASES,
        /** uv tool metadata: ~/.local/share/uv/tools/aider-chat/.../aider_chat-*.dist-info/METADATA. */
        AIDER_DIST_INFO,
        /** ~/.local/share/claude/versions/<version>/ directory names. */
        CLAUDE_VERSIONS
    }

    /** Which agents can be opened in the phone browser (v1: dsh, OpenCode, Pi only). */
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
        PI
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

        AgentDef(String id, String displayName, int descriptionRes, boolean needsNode,
                 String command, String homeBinPath, String[] binPaths,
                 VersionKind versionKind, String npmPackage,
                 String installSnippet, String updateSnippet,
                 WebKind webKind, int webPort) {
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
    public static final List<AgentDef> AGENTS = Arrays.asList(
        new AgentDef("codex", "Codex", R.string.aiagents_desc_codex, false, "codex",
            ".local/bin/codex",
            new String[]{"root/.local/bin/codex"},
            VersionKind.CODEX_RELEASES, null,
            "curl -fsSL https://chatgpt.com/codex/install.sh | CODEX_NON_INTERACTIVE=1 sh",
            "codex update || curl -fsSL https://chatgpt.com/codex/install.sh | CODEX_NON_INTERACTIVE=1 sh",
            WebKind.NONE, 0),
        new AgentDef("pi", "Pi", R.string.aiagents_desc_pi, true, "pi",
            "",
            new String[]{"usr/bin/pi", "usr/local/bin/pi"},
            VersionKind.NPM_PACKAGE_JSON, "@earendil-works/pi-coding-agent",
            "npm install -g --ignore-scripts @earendil-works/pi-coding-agent",
            "npm install -g --ignore-scripts @earendil-works/pi-coding-agent",
            WebKind.PI, 0),
        new AgentDef("agy", "Antigravity CLI", R.string.aiagents_desc_agy, false, "agy",
            ".local/bin/agy",
            new String[]{"root/.local/bin/agy"},
            VersionKind.NONE, null,
            "curl -fsSL https://antigravity.google/cli/install.sh -o /tmp/agy-install.sh && bash /tmp/agy-install.sh",
            "curl -fsSL https://antigravity.google/cli/install.sh -o /tmp/agy-install.sh && bash /tmp/agy-install.sh",
            WebKind.NONE, 0),
        new AgentDef("dsh", "DeepSeek Harness", R.string.aiagents_desc_dsh, true, "dsh",
            "",
            new String[]{"usr/bin/dsh", "usr/local/bin/dsh"},
            VersionKind.NPM_PACKAGE_JSON, "@deepseek-ai/dsh",
            "npm install -g @deepseek-ai/dsh",
            "old_ver=$(npm list -g @deepseek-ai/dsh --depth=0 2>/dev/null | grep -oE '@deepseek-ai/dsh@[0-9][^ ]*' | cut -d@ -f3); if [ -n \"$old_ver\" ]; then echo \"当前版本：$old_ver；如需回退：npm install -g @deepseek-ai/dsh@$old_ver\"; fi; npm install -g @deepseek-ai/dsh",
            WebKind.DSH, 3080),
        new AgentDef("opencode", "OpenCode", R.string.aiagents_desc_opencode, true, "opencode",
            "",
            new String[]{"usr/bin/opencode", "usr/local/bin/opencode"},
            VersionKind.NPM_PACKAGE_JSON, "@opencode/cli",
            "npm install -g @opencode/cli",
            "npm install -g @opencode/cli",
            WebKind.OPENCODE, 4096),
        new AgentDef("aider", "Aider", R.string.aiagents_desc_aider, false, "aider",
            ".local/bin/aider",
            new String[]{"root/.local/bin/aider"},
            VersionKind.AIDER_DIST_INFO, null,
            "curl -LsSf https://aider.chat/install.sh | sh",
            "aider --upgrade",
            WebKind.NONE, 0),
        new AgentDef("claude", "Claude Code", R.string.aiagents_desc_claude, false, "claude",
            ".local/bin/claude",
            new String[]{"root/.local/bin/claude"},
            VersionKind.CLAUDE_VERSIONS, null,
            "curl -fsSL https://claude.ai/install.sh | bash",
            "claude update",
            WebKind.NONE, 0)
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
                default:
                    return "";
            }
        } catch (Exception e) {
            Log.w(LOG_TAG, "Failed to probe version for " + def.id, e);
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
        DistroUtils.startSessionWithExecutable(context, prootDistro,
            new String[]{"login", agent.container, "--", agent.inContainerPath()},
            sessionName, sessionName);
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
     * it (proot has no systemd, so there is no background daemon). There
     * is deliberately no server-running-state detection in v1: the app
     * cannot probe inside the container without in-container exec, so it
     * never claims a server is up — it only starts one.
     *
     * The three agents differ in what the app can open by itself:
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
        showWebDialog(context, def, agent, webPassword, startSession, onDialogDismissed);
    }

    /**
     * Explanation dialog for a web launch; OpenCode also gets an "Open browser" button.
     * The positive button starts the session (and, for OpenCode, opens the
     * browser); Cancel starts nothing. The dismiss listener still runs
     * onDialogDismissed in every case, including Cancel.
     */
    private static void showWebDialog(final Context context, final AgentDef def,
                                      final InstalledAgent agent, final String webPassword,
                                      final Runnable onStartSession,
                                      final Runnable onDialogDismissed) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.aiagents_web_title, agent.displayName))
            .setOnDismissListener(dialog -> {
                if (onDialogDismissed != null) onDialogDismissed.run();
            });
        switch (def.webKind) {
            case DSH:
                builder.setMessage(R.string.aiagents_web_dsh_msg)
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                        if (onStartSession != null) onStartSession.run();
                    });
                break;
            case PI:
                builder.setMessage(R.string.aiagents_web_pi_msg)
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                        if (onStartSession != null) onStartSession.run();
                    });
                break;
            case OPENCODE:
                builder.setMessage(context.getString(R.string.aiagents_web_opencode_msg, webPassword))
                    .setPositiveButton(R.string.aiagents_web_open_browser, (dialog, which) -> {
                        if (onStartSession != null) onStartSession.run();
                        Intent intent = new Intent(Intent.ACTION_VIEW,
                            Uri.parse("http://127.0.0.1:" + def.webPort));
                        context.startActivity(intent);
                    })
                    .setNegativeButton(android.R.string.cancel, null);
                break;
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
                s.append("dsh web --no-open\n");
                break;
            case OPENCODE:
                s.append("echo \"正在启动 OpenCode 网页版（").append(agent.distroDisplay).append("）…\"\n");
                s.append("echo \"地址：http://127.0.0.1:").append(def.webPort).append("\"\n");
                s.append("echo \"密码：").append(webPassword).append("（也在手机弹出的对话框里）\"\n");
                s.append("command -v opencode >/dev/null 2>&1 || { echo \"没有找到 opencode，请先在“AI 助手”里安装。\"; read -r -p \"按回车关闭此会话\"; exit 1; }\n");
                s.append("export OPENCODE_PASSWORD=\"").append(webPassword).append("\"\n");
                s.append("export OPENCODE_SERVER_PASSWORD=\"").append(webPassword).append("\"\n");
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
        "'\n" +
        "\n" +
        "usage() {\n" +
        "    echo \"用法：ai [助手名] [传给助手的参数…]\"\n" +
        "    echo \"  ai            列出已安装的助手\"\n" +
        "    echo \"  ai list       同上\"\n" +
        "    echo \"  ai codex      在装有 Codex 的发行版里启动 Codex\"\n" +
        "    echo \"  ai dsh web    启动 dsh 网页版（地址在会话里，带 token）\"\n" +
        "    echo \"  ai -h         显示帮助\"\n" +
        "    echo \"助手：codex、pi、agy、dsh、opencode、aider、claude\"\n" +
        "    echo \"网页版：dsh、opencode、pi（ai <助手名> web）\"\n" +
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
        "            dsh|opencode|pi) tag=\" · 网页版\" ;;\n" +
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
        "            echo \"地址：http://127.0.0.1:4096，密码见会话里的 server password 一行。\"\n" +
        "            exec proot-distro login \"$container\" -- opencode serve --hostname 127.0.0.1 --port 4096 \"$@\"\n" +
        "            ;;\n" +
        "        pi)\n" +
        "            echo \"启动 Pi 网页版（$container）…\"\n" +
        "            echo \"地址在会话里（带 token 的链接），复制到浏览器打开；也可以跑 npx pi-webapp status 查看地址。\"\n" +
        "            exec proot-distro login \"$container\" -- bash -c 'PI_WEBAPP_AUTO_OPEN=0 npx -y pi-webapp; echo \"Pi 网页版服务运行中，关闭此会话即停止。\"; read -r -p \"按回车关闭此会话\"'\n" +
        "            ;;\n" +
        "        *)\n" +
        "            echo \"“$display”没有网页版。带网页版的助手：dsh、opencode、pi。\"\n" +
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
