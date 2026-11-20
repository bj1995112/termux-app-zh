// AI assistants installer: selective install / update of 8 coding agents
// inside one installed proot-distro container (never in Termux itself).
// Installed web-capable agents (dsh, OpenCode, Pi, Hermes Agent) also get a "网页版"
// button in their row, which starts the web server in a visible session
// and explains how to reach it (AiAgentUtils.startWebSession).
//
// Entry: DistroActivity.showDistroDialog -> "Install AI assistants" per
// installed container. The list shows each agent's name, a one-line
// description and its status (not installed / installed + version /
// unsupported on this distro). Uninstalled agents are ticked and installed
// with the bottom button; tapping an installed agent offers an update.
//
// Installed detection scans the container rootfs for known binary paths
// (AiAgentUtils, via DistroUtils.containerRootfs), so opening the list never
// pops up a terminal session; detection runs on a background thread and
// re-runs on onResume, so coming back from an install session shows the new
// status.
//
// Install / update runs in a visible terminal session: the install script
// is written into the container's /tmp and executed with
// "proot-distro login <container> -- bash /tmp/termux-aiagents-<container>.sh",
// the same intent mechanism DistroActivity uses for tools install. Node 24
// is installed first when a selected agent needs it (skipped when the
// container already has Node >= 24.9.0); each agent's commands are
// idempotent, so a failed install can simply be retried.
//
// Pure Java, zero new dependencies: only Android framework APIs and
// existing termux-shared classes are used.

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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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

    // Agent definitions, detection and version probing live in AiAgentUtils
    // (shared with the drawer quick-entry section and the `ai` command).

    /** One row of the agent list. */
    private static class AgentRow {
        final AiAgentUtils.AgentDef def;
        boolean installed;
        String version; // "" when unknown
        String latestVersion; // "" when unknown / not checked yet
        String binPath; // rootfs-relative binary path found, "" when not installed
        boolean selected;
        boolean supported;

        AgentRow(AiAgentUtils.AgentDef def) {
            this.def = def;
            this.version = "";
            this.latestVersion = "";
            this.binPath = "";
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
    private Button mCheckUpdatesButton;
    private ArrayAdapter<AgentRow> mAdapter;
    private final List<AgentRow> mRows = new ArrayList<>();
    private boolean mDetecting;
    private boolean mCheckingUpdates;

    /** Latest versions from the last check (agent id -> version), loaded
     * from the cache on start and refreshed by checkUpdatesInBackground. */
    private final Map<String, String> mLatestVersions = new HashMap<>();

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

        for (AiAgentUtils.AgentDef def : AiAgentUtils.AGENTS) mRows.add(new AgentRow(def));

        mAgentListView = findViewById(R.id.aiagents_list);
        mStatusView = findViewById(R.id.aiagents_status);
        mInstallButton = findViewById(R.id.aiagents_install_button);
        mCheckUpdatesButton = findViewById(R.id.aiagents_check_updates_button);
        mCheckUpdatesButton.setOnClickListener(v -> checkUpdatesInBackground());
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
                    Button webButton = view.findViewById(R.id.aiagents_row_web);
                    title.setText(row.def.displayName);
                    subtitle.setText(subtitleFor(row));
                    check.setChecked(row.selected);
                    check.setEnabled(!row.installed && row.supported);
                    check.setVisibility(row.installed || !row.supported ? View.GONE : View.VISIBLE);
                    // Web UI button: only for installed web-capable agents.
                    if (row.installed && row.def.webKind != AiAgentUtils.WebKind.NONE) {
                        webButton.setVisibility(View.VISIBLE);
                        webButton.setOnClickListener(v -> {
                            AiAgentUtils.InstalledAgent agent =
                                new AiAgentUtils.InstalledAgent(row.def.id, row.def.displayName,
                                    row.def.command, row.binPath,
                                    mContainer, mDistroId, mDisplay);
                            AiAgentUtils.startWebSession(AiAgentsActivity.this, agent, null);
                        });
                    } else {
                        webButton.setVisibility(View.GONE);
                        webButton.setOnClickListener(null);
                    }
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
                showUpdateDialog(row);
                return;
            }
            row.selected = !row.selected;
            mAdapter.notifyDataSetChanged();
        });
        mInstallButton.setOnClickListener(v -> installSelected());

        // Cached latest versions (checked within the last day) apply to
        // the rows as soon as detection finishes, without re-fetching.
        AiAgentUtils.UpdateCache cache = AiAgentUtils.loadUpdateCache();
        if (AiAgentUtils.isUpdateCacheValid(cache.checkedAt)) {
            mLatestVersions.putAll(cache.latest);
        }

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
                if (AiAgentUtils.isNewerVersionAvailable(row.version, row.latestVersion)) {
                    return desc + "\n" + getString(R.string.aiagents_installed_with_update,
                        row.version, row.latestVersion);
                }
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
                for (AiAgentUtils.AgentDef def : AiAgentUtils.AGENTS) {
                    AgentRow row = new AgentRow(def);
                    row.supported = isSupported(def);
                    if (rootfs != null) {
                        row.installed = AiAgentUtils.isInstalled(rootfs, def);
                        if (row.installed) {
                            row.version = AiAgentUtils.probeVersion(rootfs, def);
                            String found = AiAgentUtils.findBinPath(rootfs, def);
                            if (found != null) row.binPath = found;
                        }
                    }
                    detected.add(row);
                }
                runOnUiThread(() -> {
                    mDetecting = false;
                    mRows.clear();
                    mRows.addAll(detected);
                    applyLatestVersionsToRows();
                    mAdapter.notifyDataSetChanged();
                    int installed = 0;
                    for (AgentRow row : mRows) if (row.installed) installed++;
                    mCheckUpdatesButton.setEnabled(installed > 0 && !mCheckingUpdates);
                    updateStatus(getString(R.string.aiagents_status_summary, installed, mRows.size()));
                });
            }
        }.start();
    }

    /** Node-dependent agents are only offered where the Node 24 path is verified;
     * agy is a glibc binary and Alpine uses musl. */
    private boolean isSupported(AiAgentUtils.AgentDef def) {
        if (def.needsNode && !NODE_VERIFIED.contains(mDistroId)) return false;
        if ("agy".equals(def.id) && "alpine".equals(mDistroId)) return false;
        return true;
    }

    // ------------------------------------------------------------------
    // Update detection (latest versions, cached one day)
    // ------------------------------------------------------------------

    /** Copy the known latest versions (cache + last check) into the
     * current rows, matched by agent id. Runs on the UI thread; called
     * after detection replaces mRows and after a check finishes. */
    private void applyLatestVersionsToRows() {
        for (AgentRow row : mRows) {
            String latest = mLatestVersions.get(row.def.id);
            row.latestVersion = latest == null ? "" : latest;
        }
    }

    /** Check for updates of the agents installed in this container.
     * Only runs on an explicit tap (never automatically): the button is
     * disabled while a check is running and when nothing is installed.
     * Fetches run on a background thread; rows are matched by agent id
     * on the UI thread, so a detection running in parallel cannot leave
     * stale row references behind. Failures are silent — a failed agent
     * keeps its cached value and shows no badge. */
    private void checkUpdatesInBackground() {
        if (mCheckingUpdates) return;
        List<AiAgentUtils.AgentDef> installed = new ArrayList<>();
        for (AgentRow row : mRows) {
            if (row.installed) installed.add(row.def);
        }
        if (installed.isEmpty()) return;
        mCheckingUpdates = true;
        mCheckUpdatesButton.setEnabled(false);
        updateStatus(getString(R.string.aiagents_checking_updates));
        new Thread() {
            @Override
            public void run() {
                Map<String, String> fetched = new HashMap<>();
                for (AiAgentUtils.AgentDef def : installed) {
                    String latest = AiAgentUtils.fetchLatestVersion(def, mDistroId);
                    if (!latest.isEmpty()) fetched.put(def.id, latest);
                }
                boolean anyFetched = !fetched.isEmpty();
                final Map<String, String> merged;
                if (anyFetched) {
                    merged = new HashMap<>();
                    AiAgentUtils.UpdateCache cache = AiAgentUtils.loadUpdateCache();
                    merged.putAll(cache.latest);
                    merged.putAll(fetched);
                    AiAgentUtils.saveUpdateCache(merged);
                } else {
                    merged = null;
                }
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    mCheckingUpdates = false;
                    if (merged != null) {
                        mLatestVersions.clear();
                        mLatestVersions.putAll(merged);
                    }
                    applyLatestVersionsToRows();
                    mAdapter.notifyDataSetChanged();
                    int installedCount = 0;
                    boolean anyUpdate = false;
                    for (AgentRow row : mRows) {
                        if (!row.installed) continue;
                        installedCount++;
                        if (AiAgentUtils.isNewerVersionAvailable(row.version, row.latestVersion)) {
                            anyUpdate = true;
                        }
                    }
                    mCheckUpdatesButton.setEnabled(installedCount > 0);
                    updateStatus(getString(R.string.aiagents_status_summary,
                        installedCount, mRows.size()));
                    if (anyFetched && !anyUpdate) {
                        Toast.makeText(AiAgentsActivity.this,
                            R.string.aiagents_no_updates, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }.start();
    }

    // ------------------------------------------------------------------
    // Install / update dialogs
    // ------------------------------------------------------------------

    private void installSelected() {
        List<AiAgentUtils.AgentDef> selected = new ArrayList<>();
        for (AgentRow row : mRows) {
            if (row.selected && !row.installed && row.supported) selected.add(row.def);
        }
        if (selected.isEmpty()) {
            Toast.makeText(this, R.string.aiagents_none_selected, Toast.LENGTH_SHORT).show();
            return;
        }
        startAgentSession(selected, false);
    }

    private void showUpdateDialog(final AgentRow row) {
        final AiAgentUtils.AgentDef def = row.def;
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
            .setTitle(getString(R.string.aiagents_update_title, def.displayName))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.aiagents_update, (dialog, which) -> {
                List<AiAgentUtils.AgentDef> single = new ArrayList<>();
                single.add(def);
                startAgentSession(single, true);
            });
        String message = getString(R.string.aiagents_update_confirm, def.displayName);
        if (AiAgentUtils.isNewerVersionAvailable(row.version, row.latestVersion)) {
            message += "\n" + getString(R.string.aiagents_update_new_version,
                row.latestVersion, row.version);
        }
        if ("dsh".equals(def.id)) {
            message += "\n" + getString(R.string.aiagents_update_dsh_note);
        }
        builder.setMessage(message);
        builder.show();
    }

    // ------------------------------------------------------------------
    // Sessions: write the install script into the container and run it
    // ------------------------------------------------------------------

    /** Write the install/update script into the container's /tmp and run it
     * in a new visible terminal session. Deliberately does not finish():
     * Back from the session returns here and onResume re-detects. */
    private void startAgentSession(List<AiAgentUtils.AgentDef> agents, boolean update) {
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
    private String buildAgentScript(List<AiAgentUtils.AgentDef> agents, boolean update) {
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
        s.append("record_agy_version() {\n");
        s.append("  # The agy installer prints the version it installs but writes no\n");
        s.append("  # version file, so the \"AI 助手\" list cannot tell an installed agy\n");
        s.append("  # from its latest version. Record what the release server reports\n");
        s.append("  # right after a good install, in the same .termux-aiagents dir as\n");
        s.append("  # the OpenCode web password. Only called from the installed branch\n");
        s.append("  # below: a failed install must not claim the new version.\n");
        s.append("  manifest_url=\"https://antigravity-cli-auto-updater-974169037036.us-central1.run.app/manifests/linux_arm64.json\"\n");
        s.append("  if [ -f /lib/libc.musl-aarch64.so.1 ] || ldd /bin/ls 2>/dev/null | grep -q musl; then\n");
        s.append("    manifest_url=\"https://antigravity-cli-auto-updater-974169037036.us-central1.run.app/manifests/linux_arm64_musl.json\"\n");
        s.append("  fi\n");
        s.append("  ver=$(curl -fsSL --max-time 15 \"$manifest_url\" 2>/dev/null | grep -oE '\"version\"[[:space:]]*:[[:space:]]*\"[^\"]*\"' | head -1 | cut -d'\"' -f4)\n");
        s.append("  case \"$ver\" in\n");
        s.append("    [0-9]*.[0-9]*)\n");
        s.append("      mkdir -p \"$HOME/.termux-aiagents/versions\" 2>/dev/null\n");
        s.append("      printf '%s' \"$ver\" > \"$HOME/.termux-aiagents/versions/agy\" 2>/dev/null || true\n");
        s.append("      ;;\n");
        s.append("  esac\n");
        s.append("}\n");
        s.append(ensureCurlCommand(mDistroId)).append("\n");
        boolean needsNode = false;
        for (AiAgentUtils.AgentDef def : agents) if (def.needsNode) needsNode = true;
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
        for (AiAgentUtils.AgentDef def : agents) {
            s.append("echo \"--- ").append(def.displayName).append(" ---\"\n");
            if ("codex".equals(def.id)) {
                // Codex sandboxes with bubblewrap; install it best-effort.
                s.append(installOnePackageCommand(mDistroId, "bubblewrap")).append(" || true\n");
            }
            if ("hermes".equals(def.id)) {
                // The installer clones the repo, so git must exist; on
                // Alpine (musl) its bundled Node also needs the system
                // libstdc++, or the installer fails outright. The launcher
                // lands in ~/.local/bin, which is not on PATH in this
                // script's non-login shell, so export it here for the
                // checks and the version print below.
                s.append("  ").append(installOnePackageCommand(mDistroId, "git")).append("\n");
                if ("alpine".equals(mDistroId)) {
                    s.append("  apk add libstdc++\n");
                }
                s.append("  export PATH=\"$HOME/.local/bin:$PATH\"\n");
            }
            if (def.needsNode) s.append("if node_ok; then\n");
            s.append("  ").append(update ? def.updateSnippet : def.installSnippet).append("\n");
            if ("codex".equals(def.id)) {
                s.append("  echo \"提示：如果 Codex 报 bwrap 权限错误，在 proot 里改用：codex -s danger-full-access\"\n");
            }
            if ("dsh".equals(def.id) && !update) {
                s.append("  echo \"提示：dsh 是网页界面，装好后在发行版里跑 dsh web，再用手机浏览器打开 http://127.0.0.1:3080\"\n");
            }
            if ("hermes".equals(def.id) && !update) {
                s.append("  echo \"提示：首次使用先运行 hermes setup 选择模型（需要模型 key 或 Nous Portal 账号）\"\n");
            }
            s.append("  if check_installed ").append(def.command).append(" \"")
                .append(def.homeBinPath).append("\"; then\n");
            s.append("    echo \"").append(def.displayName).append(" 已装好\"\n");
            s.append("    ").append(def.command).append(" --version 2>/dev/null || true\n");
            if ("agy".equals(def.id)) {
                s.append("    record_agy_version\n");
            }
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
