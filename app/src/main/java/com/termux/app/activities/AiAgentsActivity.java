// AI assistants installer: selective install / update of 6 coding agents
// inside one installed proot-distro container (never in Termux itself).
//
// Entry: DistroActivity.showDistroDialog -> "Install AI assistants" per
// installed container. The list shows each agent's name, a one-line
// description and its status (not installed / installed + version /
// unsupported on this distro). Uninstalled agents are ticked and installed
// with the bottom button; tapping an installed agent offers an update.
//
// Installed detection scans the container rootfs for known binary paths
// (DistroUtils.containerRootfs), so opening the list never pops up a
// terminal session; detection runs on a background thread and re-runs on
// onResume, so coming back from an install session shows the new status.
//
// Install / update runs in a visible terminal session: the install script
// is written into the container's /tmp and executed with
// "proot-distro login <container> -- bash /tmp/termux-aiagents-<container>.sh",
// the same intent mechanism DistroActivity uses for tools install. Node 24
// is installed first when a selected agent needs it (skipped when the
// container already has Node >= 24.9.0); each agent's commands are
// idempotent, so a failed install can simply be retried.
//
// Pure Java, zero new dependencies: only Android framework APIs,
// org.json and existing termux-shared classes are used.

package com.termux.app.activities;

import android.app.AlertDialog;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.termux.R;
import com.termux.shared.activity.media.AppCompatActivityUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.theme.NightMode;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class AiAgentsActivity extends AppCompatActivity {

    private static final String LOG_TAG = "AiAgentsActivity";

    /** Intent extras (set by DistroActivity.openAiAgents). */
    public static final String EXTRA_CONTAINER_NAME = "com.termux.aiagents.container_name";
    public static final String EXTRA_DISTRO_ID = "com.termux.aiagents.distro_id";
    public static final String EXTRA_DISPLAY_NAME = "com.termux.aiagents.display_name";

    /** Distros whose Node 24 path is verified on-device (v1). Other distros
     * show Node-dependent agents as unsupported until verified; adding a
     * distro here is the whole change needed to enable it. */
    private static final Set<String> NODE_VERIFIED = new HashSet<>(Arrays.asList("ubuntu", "debian"));

    /** Minimum Node version: dsh declares engines node >= 24.9.0
     * (Pi needs >= 22.19.0, OpenCode has no hard requirement). */
    private static final int NODE_MIN_MAJOR = 24;
    private static final int NODE_MIN_MINOR = 9;

    /** How an installed agent's version is found (from files, never by running it). */
    private enum VersionKind {
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
    private static class AgentDef {
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
    // (npm registry, PyPI, official docs); see design.md §1 for the sources.
    private static final List<AgentDef> AGENTS = Arrays.asList(
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

    /** One row of the agent list. */
    private static class AgentRow {
        final AgentDef def;
        boolean installed;
        String version; // "" when unknown
        boolean selected;
        boolean supported;

        AgentRow(AgentDef def) {
            this.def = def;
            this.version = "";
            this.supported = true;
        }

        @Override
        public String toString() {
            return def.displayName;
        }
    }

    private String mContainer;
    private String mDistroId;
    private String mDisplay;

    private ListView mAgentListView;
    private TextView mStatusView;
    private Button mInstallButton;
    private ArrayAdapter<AgentRow> mAdapter;
    private final List<AgentRow> mRows = new ArrayList<>();
    private boolean mDetecting;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        AppCompatActivityUtils.setNightMode(this, NightMode.getAppNightMode().getName(), true);

        setContentView(R.layout.aiagents_layout);
        AppCompatActivityUtils.setToolbar(this, com.termux.shared.R.id.toolbar);
        AppCompatActivityUtils.setShowBackButtonInActionBar(this, true);

        mContainer = getIntent().getStringExtra(EXTRA_CONTAINER_NAME);
        mDistroId = getIntent().getStringExtra(EXTRA_DISTRO_ID);
        mDisplay = getIntent().getStringExtra(EXTRA_DISPLAY_NAME);
        if (mContainer == null) mContainer = "";
        if (mDistroId == null) mDistroId = "";
        if (mDisplay == null) mDisplay = mContainer;

        for (AgentDef def : AGENTS) mRows.add(new AgentRow(def));

        mAgentListView = findViewById(R.id.aiagents_list);
        mStatusView = findViewById(R.id.aiagents_status);
        mInstallButton = findViewById(R.id.aiagents_install_button);
        mAdapter = new ArrayAdapter<AgentRow>(this, R.layout.aiagents_agent_row, mRows) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = convertView;
                if (view == null) {
                    view = getLayoutInflater().inflate(R.layout.aiagents_agent_row, parent, false);
                }
                AgentRow row = getItem(position);
                if (row != null) {
                    CheckBox check = view.findViewById(R.id.aiagents_row_check);
                    TextView title = view.findViewById(R.id.aiagents_row_title);
                    TextView subtitle = view.findViewById(R.id.aiagents_row_subtitle);
                    title.setText(row.def.displayName);
                    subtitle.setText(subtitleFor(row));
                    check.setChecked(row.selected);
                    check.setEnabled(!row.installed && row.supported);
                    check.setVisibility(row.installed || !row.supported ? View.GONE : View.VISIBLE);
                }
                return view;
            }
        };
        mAgentListView.setAdapter(mAdapter);
        mAgentListView.setOnItemClickListener((parent, view, position, id) -> {
            AgentRow row = mRows.get(position);
            if (!row.supported) {
                Toast.makeText(this, R.string.aiagents_unsupported, Toast.LENGTH_SHORT).show();
                return;
            }
            if (row.installed) {
                showUpdateDialog(row.def);
                return;
            }
            row.selected = !row.selected;
            mAdapter.notifyDataSetChanged();
        });
        mInstallButton.setOnClickListener(v -> installSelected());

        detectAgentsInBackground();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Coming back from an install session re-detects, so the new status shows.
        detectAgentsInBackground();
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }

    private String subtitleFor(AgentRow row) {
        String desc = getString(row.def.descriptionRes);
        if (!row.supported) {
            return desc + "\n" + getString(R.string.aiagents_unsupported);
        }
        if (row.installed) {
            if (row.version != null && !row.version.isEmpty()) {
                return desc + "\n" + getString(R.string.aiagents_installed, row.version);
            }
            return desc + "\n" + getString(R.string.aiagents_installed_unknown);
        }
        return desc + "\n" + getString(R.string.aiagents_not_installed);
    }

    private void updateStatus(String status) {
        if (mStatusView != null) mStatusView.setText(status);
    }

    // ------------------------------------------------------------------
    // Installed detection (rootfs scan, no session, background thread)
    // ------------------------------------------------------------------

    private void detectAgentsInBackground() {
        if (mDetecting) return;
        mDetecting = true;
        updateStatus(getString(R.string.aiagents_status_checking));
        new Thread() {
            @Override
            public void run() {
                File rootfs = DistroUtils.containerRootfs(mContainer);
                final List<AgentRow> detected = new ArrayList<>();
                for (AgentDef def : AGENTS) {
                    AgentRow row = new AgentRow(def);
                    row.supported = isSupported(def);
                    if (rootfs != null) {
                        row.installed = isInstalled(rootfs, def);
                        if (row.installed) row.version = probeVersion(rootfs, def);
                    }
                    detected.add(row);
                }
                runOnUiThread(() -> {
                    mDetecting = false;
                    mRows.clear();
                    mRows.addAll(detected);
                    mAdapter.notifyDataSetChanged();
                    int installed = 0;
                    for (AgentRow row : mRows) if (row.installed) installed++;
                    updateStatus(getString(R.string.aiagents_status_summary, installed, mRows.size()));
                });
            }
        }.start();
    }

    /** Node-dependent agents are only offered where the Node 24 path is verified;
     * agy is a glibc binary and Alpine uses musl. */
    private boolean isSupported(AgentDef def) {
        if (def.needsNode && !NODE_VERIFIED.contains(mDistroId)) return false;
        if ("agy".equals(def.id) && "alpine".equals(mDistroId)) return false;
        return true;
    }

    private static boolean isInstalled(File rootfs, AgentDef def) {
        for (String bin : def.binPaths) {
            if (new File(rootfs, bin).isFile()) return true;
        }
        return false;
    }

    private static String probeVersion(File rootfs, AgentDef def) {
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
    // Install / update dialogs
    // ------------------------------------------------------------------

    private void installSelected() {
        List<AgentDef> selected = new ArrayList<>();
        for (AgentRow row : mRows) {
            if (row.selected && !row.installed && row.supported) selected.add(row.def);
        }
        if (selected.isEmpty()) {
            Toast.makeText(this, R.string.aiagents_none_selected, Toast.LENGTH_SHORT).show();
            return;
        }
        startAgentSession(selected, false);
    }

    private void showUpdateDialog(final AgentDef def) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
            .setTitle(getString(R.string.aiagents_update_title, def.displayName))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.aiagents_update, (dialog, which) -> {
                List<AgentDef> single = new ArrayList<>();
                single.add(def);
                startAgentSession(single, true);
            });
        if ("dsh".equals(def.id)) {
            builder.setMessage(getString(R.string.aiagents_update_confirm, def.displayName)
                + "\n" + getString(R.string.aiagents_update_dsh_note));
        } else {
            builder.setMessage(getString(R.string.aiagents_update_confirm, def.displayName));
        }
        builder.show();
    }

    // ------------------------------------------------------------------
    // Sessions: write the install script into the container and run it
    // ------------------------------------------------------------------

    /** Write the install/update script into the container's /tmp and run it
     * in a new visible terminal session. Deliberately does not finish():
     * Back from the session returns here and onResume re-detects. */
    private void startAgentSession(List<AgentDef> agents, boolean update) {
        File rootfs = DistroUtils.containerRootfs(mContainer);
        if (rootfs == null) {
            Toast.makeText(this, R.string.aiagents_no_rootfs, Toast.LENGTH_LONG).show();
            return;
        }
        String scriptName = "termux-aiagents-" + mContainer + (update ? "-update" : "") + ".sh";
        File scriptFile = new File(rootfs, "tmp/" + scriptName);
        try {
            File parent = scriptFile.getParentFile();
            if (parent != null && !(parent.isDirectory() || parent.mkdirs())) {
                throw new IOException("Cannot create " + parent.getAbsolutePath());
            }
            try (FileOutputStream out = new FileOutputStream(scriptFile)) {
                out.write(buildAgentScript(agents, update).getBytes(StandardCharsets.UTF_8));
            }
            scriptFile.setReadable(true, true);
        } catch (Exception e) {
            Log.w(LOG_TAG, "Failed to write agent install script", e);
            Toast.makeText(this, getString(R.string.aiagents_script_failed, e.getMessage()),
                Toast.LENGTH_LONG).show();
            return;
        }
        String prootDistro = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "proot-distro")
            .getAbsolutePath();
        if (!new File(prootDistro).isFile()) {
            Toast.makeText(this, R.string.distro_proot_distro_missing, Toast.LENGTH_LONG).show();
            return;
        }
        String sessionName = getString(R.string.aiagents_session, mDisplay);
        DistroUtils.startSessionWithExecutable(this, prootDistro,
            new String[]{"login", mContainer, "--", "bash", "/tmp/" + scriptName},
            sessionName, sessionName);
        Toast.makeText(this, R.string.aiagents_install_started, Toast.LENGTH_LONG).show();
    }

    /** Full install/update script run inside the container (as root). */
    private String buildAgentScript(List<AgentDef> agents, boolean update) {
        StringBuilder s = new StringBuilder();
        s.append("#!/bin/bash\n");
        s.append("# Generated by Termux \"AI 助手\" (AiAgentsActivity) - do not edit.\n");
        s.append("echo \"").append(update ? "正在更新" : "正在安装")
            .append(" AI 助手（").append(mDisplay).append("）…\"\n");
        s.append("node_ok() {\n");
        s.append("  command -v node >/dev/null 2>&1 || return 1\n");
        s.append("  ver=$(node --version 2>/dev/null | sed 's/^v//')\n");
        s.append("  major=${ver%%.*}; rest=${ver#*.}; minor=${rest%%.*}\n");
        s.append("  [ \"$major\" -eq \"$major\" ] 2>/dev/null || return 1\n");
        s.append("  [ \"$minor\" -eq \"$minor\" ] 2>/dev/null || return 1\n");
        s.append("  [ \"$major\" -gt ").append(NODE_MIN_MAJOR).append(" ] && return 0\n");
        s.append("  [ \"$major\" -eq ").append(NODE_MIN_MAJOR).append(" ] && [ \"$minor\" -ge ")
            .append(NODE_MIN_MINOR).append(" ] && return 0\n");
        s.append("  return 1\n");
        s.append("}\n");
        s.append("node_fallback_links() {\n");
        s.append("  if ! command -v node >/dev/null 2>&1 && command -v node24 >/dev/null 2>&1; then\n");
        s.append("    ln -sf \"$(command -v node24)\" /usr/local/bin/node\n");
        s.append("  fi\n");
        s.append("  if ! command -v npm >/dev/null 2>&1 && command -v npm24 >/dev/null 2>&1; then\n");
        s.append("    ln -sf \"$(command -v npm24)\" /usr/local/bin/npm\n");
        s.append("  fi\n");
        s.append("}\n");
        s.append("check_installed() {\n");
        s.append("  command -v \"$1\" >/dev/null 2>&1 && return 0\n");
        s.append("  [ -n \"$2\" ] && [ -x \"$HOME/$2\" ] && return 0\n");
        s.append("  return 1\n");
        s.append("}\n");
        s.append(ensureCurlCommand(mDistroId)).append("\n");
        boolean needsNode = false;
        for (AgentDef def : agents) if (def.needsNode) needsNode = true;
        if (needsNode) {
            s.append("if ! node_ok; then\n");
            s.append("  echo \"正在安装 Node 24 …\"\n");
            s.append("  ").append(nodeInstallCommand(mDistroId)).append("\n");
            s.append("  node_fallback_links\n");
            s.append("fi\n");
            s.append("if node_ok; then echo \"Node $(node --version) 就绪\"; ");
            s.append("else echo \"Node 24 安装失败，需要 Node 的助手（Pi、dsh、OpenCode）将跳过。\"; fi\n");
        }
        s.append("failed=\"\"\n");
        for (AgentDef def : agents) {
            s.append("echo \"--- ").append(def.displayName).append(" ---\"\n");
            if ("codex".equals(def.id)) {
                // Codex sandboxes with bubblewrap; install it best-effort.
                s.append(installOnePackageCommand(mDistroId, "bubblewrap")).append(" || true\n");
            }
            if (def.needsNode) s.append("if node_ok; then\n");
            s.append("  ").append(update ? def.updateSnippet : def.installSnippet).append("\n");
            if ("codex".equals(def.id)) {
                s.append("  echo \"提示：如果 Codex 报 bwrap 权限错误，在 proot 里改用：codex -s danger-full-access\"\n");
            }
            if ("dsh".equals(def.id) && !update) {
                s.append("  echo \"提示：dsh 是网页界面，装好后在发行版里跑 dsh web，再用手机浏览器打开 http://127.0.0.1:3080\"\n");
            }
            s.append("  if check_installed ").append(def.command).append(" \"")
                .append(def.homeBinPath).append("\"; then\n");
            s.append("    echo \"").append(def.displayName).append(" 已装好\"\n");
            s.append("    ").append(def.command).append(" --version 2>/dev/null || true\n");
            s.append("  else\n");
            s.append("    failed=\"$failed ").append(def.displayName).append("\"\n");
            s.append("  fi\n");
            if (def.needsNode) s.append("else\n  echo \"").append(def.displayName)
                .append(" 已跳过（需要 Node 24）\"\nfi\n");
        }
        s.append("if [ -n \"$failed\" ]; then\n");
        s.append("  echo \"以下助手没有装上：$failed，请检查上面的报错后重试。\"\n");
        s.append("else\n");
        s.append("  echo \"全部完成！回到“AI 助手”列表查看状态。\"\n");
        s.append("fi\n");
        s.append("read -r -p \"按回车关闭此会话\"\n");
        return s.toString();
    }

    // ------------------------------------------------------------------
    // Per-distro package commands (run inside the container, as root)
    // ------------------------------------------------------------------

    /** Install curl + certificates first: every installer below needs curl,
     * and minimal images may not have it. */
    private static String ensureCurlCommand(String distroId) {
        switch (distroId) {
            case "ubuntu":
            case "debian":
                return "apt-get update && apt-get install -y curl ca-certificates gnupg";
            case "arch":
                return "pacman -Sy --noconfirm curl ca-certificates";
            case "fedora":
                return "dnf install -y curl ca-certificates";
            case "alpine":
                return "apk update && apk add curl ca-certificates";
            case "opensuse":
                return "zypper refresh && zypper install -y curl ca-certificates";
            case "void":
                return "xbps-install -Sy curl ca-certificates";
            default:
                return "# unknown distro, curl install skipped";
        }
    }

    /** Install Node 24. All 7 distros can provide Node >= 24.9 (see design.md §2);
     * only ubuntu/debian are offered in v1 (NODE_VERIFIED). */
    private static String nodeInstallCommand(String distroId) {
        switch (distroId) {
            case "ubuntu":
            case "debian":
                return "curl -fsSL https://deb.nodesource.com/setup_24.x -o /tmp/nodesource_setup.sh"
                    + " && bash /tmp/nodesource_setup.sh && apt-get install -y nodejs";
            case "arch":
                return "pacman -Sy --noconfirm nodejs npm";
            case "fedora":
                // Plain "nodejs" is a metapackage for the Node 22 line; name the 24 line.
                return "dnf install -y nodejs24-bin nodejs24-npm-bin";
            case "alpine":
                return "apk add nodejs npm";
            case "opensuse":
                return "zypper install -y nodejs24 npm24";
            case "void":
                return "xbps-install -Sy nodejs";
            default:
                return "# unknown distro, Node install skipped";
        }
    }

    /** One package via the distro's package manager. */
    private static String installOnePackageCommand(String distroId, String pkg) {
        switch (distroId) {
            case "ubuntu":
            case "debian":
                return "apt-get install -y " + pkg;
            case "arch":
                return "pacman -Sy --noconfirm " + pkg;
            case "fedora":
                return "dnf install -y " + pkg;
            case "alpine":
                return "apk add " + pkg;
            case "opensuse":
                return "zypper install -y " + pkg;
            case "void":
                return "xbps-install -Sy " + pkg;
            default:
                return "true";
        }
    }

}
