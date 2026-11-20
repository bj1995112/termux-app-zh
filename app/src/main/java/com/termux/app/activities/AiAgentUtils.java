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

import android.content.Context;
import android.util.Log;
import android.widget.Toast;

import com.termux.R;
import com.termux.shared.termux.TermuxConstants;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
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
        AIDER_DIST_INFO
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

        AgentDef(String id, String displayName, int descriptionRes, boolean needsNode,
                 String command, String homeBinPath, String[] binPaths,
                 VersionKind versionKind, String npmPackage,
                 String installSnippet, String updateSnippet) {
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
        }
    }

    // All install/update commands verified against primary sources on 2026-11-20
    // (npm registry, PyPI, official docs); see ai-agents/design.md §1.
    public static final List<AgentDef> AGENTS = Arrays.asList(
        new AgentDef("codex", "Codex", R.string.aiagents_desc_codex, false, "codex",
            ".local/bin/codex",
            new String[]{"root/.local/bin/codex"},
            VersionKind.CODEX_RELEASES, null,
            "curl -fsSL https://chatgpt.com/codex/install.sh | CODEX_NON_INTERACTIVE=1 sh",
            "codex update || curl -fsSL https://chatgpt.com/codex/install.sh | CODEX_NON_INTERACTIVE=1 sh"),
        new AgentDef("pi", "Pi", R.string.aiagents_desc_pi, true, "pi",
            "",
            new String[]{"usr/bin/pi", "usr/local/bin/pi"},
            VersionKind.NPM_PACKAGE_JSON, "@earendil-works/pi-coding-agent",
            "npm install -g --ignore-scripts @earendil-works/pi-coding-agent",
            "npm install -g --ignore-scripts @earendil-works/pi-coding-agent"),
        new AgentDef("agy", "Antigravity CLI", R.string.aiagents_desc_agy, false, "agy",
            ".local/bin/agy",
            new String[]{"root/.local/bin/agy"},
            VersionKind.NONE, null,
            "curl -fsSL https://antigravity.google/cli/install.sh -o /tmp/agy-install.sh && bash /tmp/agy-install.sh",
            "curl -fsSL https://antigravity.google/cli/install.sh -o /tmp/agy-install.sh && bash /tmp/agy-install.sh"),
        new AgentDef("dsh", "DeepSeek Harness", R.string.aiagents_desc_dsh, true, "dsh",
            "",
            new String[]{"usr/bin/dsh", "usr/local/bin/dsh"},
            VersionKind.NPM_PACKAGE_JSON, "@deepseek-ai/dsh",
            "npm install -g @deepseek-ai/dsh",
            "old_ver=$(npm list -g @deepseek-ai/dsh --depth=0 2>/dev/null | grep -oE '@deepseek-ai/dsh@[0-9][^ ]*' | cut -d@ -f3); if [ -n \"$old_ver\" ]; then echo \"当前版本：$old_ver；如需回退：npm install -g @deepseek-ai/dsh@$old_ver\"; fi; npm install -g @deepseek-ai/dsh"),
        new AgentDef("opencode", "OpenCode", R.string.aiagents_desc_opencode, true, "opencode",
            "",
            new String[]{"usr/bin/opencode", "usr/local/bin/opencode"},
            VersionKind.NPM_PACKAGE_JSON, "@opencode/cli",
            "npm install -g @opencode/cli",
            "npm install -g @opencode/cli"),
        new AgentDef("aider", "Aider", R.string.aiagents_desc_aider, false, "aider",
            ".local/bin/aider",
            new String[]{"root/.local/bin/aider"},
            VersionKind.AIDER_DIST_INFO, null,
            "curl -LsSf https://aider.chat/install.sh | sh",
            "aider --upgrade")
    );

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

    /** First binary path of the agent that exists in the rootfs, or null. */
    private static String findBinPath(File rootfs, AgentDef def) {
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
                    File releases = new File(rootfs, "root/.codex/packages/standalone/releases");
                    if (!releases.isDirectory()) return "";
                    File[] children = releases.listFiles();
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
        "'\n" +
        "\n" +
        "usage() {\n" +
        "    echo \"用法：ai [助手名] [传给助手的参数…]\"\n" +
        "    echo \"  ai            列出已安装的助手\"\n" +
        "    echo \"  ai list       同上\"\n" +
        "    echo \"  ai codex      在装有 Codex 的发行版里启动 Codex\"\n" +
        "    echo \"  ai -h         显示帮助\"\n" +
        "    echo \"助手：codex、pi、agy、dsh、opencode、aider\"\n" +
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
        "        printf '%s — %s\\n' \"$display\" \"$container\"\n" +
        "    done\n" +
        "    echo \"启动：ai <助手名>，例如 ai codex\"\n" +
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
        "        printf '%s|%s|%s\\n' \"$display\" \"$container\" \"$path\"\n" +
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
        "    printf '%s\\n' \"$matches\" | while IFS='|' read -r display container path; do\n" +
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
        "display=$(printf '%s' \"$matches\" | cut -d'|' -f1)\n" +
        "container=$(printf '%s' \"$matches\" | cut -d'|' -f2)\n" +
        "path=$(printf '%s' \"$matches\" | cut -d'|' -f3)\n" +
        "if ! command -v proot-distro >/dev/null 2>&1; then\n" +
        "    echo \"没有安装 proot-distro，请先执行：pkg install proot-distro\"\n" +
        "    exit 1\n" +
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
