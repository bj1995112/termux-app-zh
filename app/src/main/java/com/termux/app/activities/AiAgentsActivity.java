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
// with the bottom button; tapping a row header expands it inline to show
// the last 3 published versions (with dates) and an Install button —
// install/update now happens only there or from the bottom button.
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
import android.widget.LinearLayout;
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
    /** Agent id to update right after detection (set by the drawer
     * "可更新" label, which already confirmed with the user). */
    public static final String EXTRA_AUTO_UPDATE_AGENT_ID =
        "com.termux.aiagents.auto_update_agent_id";

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
        /** Inline expansion state (v2): at most one row is expanded (see
         * toggleExpand); the expanded row keeps its history, the version
         * the user tapped ("" = latest, the first entry), a fetch error
         * code ("" = none), and whether the shown history is the bundled
         * offline fallback. */
        boolean expanded;
        String selectedVersion;
        List<AiAgentUtils.VersionEntry> history;
        String historyError;
        boolean historyOffline;
        boolean historyLoading;

        AgentRow(AiAgentUtils.AgentDef def) {
            this.def = def;
            this.version = "";
            this.latestVersion = "";
            this.binPath = "";
            this.supported = true;
            this.selectedVersion = "";
            this.historyError = "";
        }

        @Override
        public String toString() {
            return def.displayName;
        }
    }

    private String mContainer;
    private String mDistroId;
    private String mDisplay;
    /** Agent id from EXTRA_AUTO_UPDATE_AGENT_ID; consumed exactly once
     * after the first detection, then cleared so a later re-detection
     * (e.g. coming back from the update session) does not re-run it. */
    private String mAutoUpdateAgentId;

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

    /** Version histories from the last fetch (agent id -> newest-first
     * entries with dates), loaded from the cache on start when fresh (24h)
     * and refreshed by checkUpdatesInBackground and by expanding a row. */
    private final Map<String, List<AiAgentUtils.VersionEntry>> mVersionHistories =
        new HashMap<>();
    private long mHistoryCheckedAt;
    /** The one expanded row (null when none), its agent id (survives the
     * row rebuild on re-detection), and the version each agent's expanded
     * row last had selected. */
    private AgentRow mExpandedRow;
    private String mExpandedAgentId = "";
    private final Map<String, String> mSelectedVersionByAgent = new HashMap<>();
    /** Guard for the all-agents latest fetch on open (one at a time). */
    private boolean mFetchingLatest;

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
        mAutoUpdateAgentId = getIntent().getStringExtra(EXTRA_AUTO_UPDATE_AGENT_ID);
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
                    TextView arrow = view.findViewById(R.id.aiagents_row_arrow);
                    LinearLayout textColumn = view.findViewById(R.id.aiagents_row_text);
                    LinearLayout expanded = view.findViewById(R.id.aiagents_row_expanded);
                    Button webButton = view.findViewById(R.id.aiagents_row_web);
                    title.setText(row.def.displayName);
                    subtitle.setText(subtitleFor(row));
                    arrow.setText(row.expanded ? "\u25BE" : "\u25B8");
                    // Header tap (title, subtitle, arrow) expands or
                    // collapses this row's version list — and does nothing
                    // else: it never opens the update dialog and never
                    // toggles the checkbox. Unsupported rows expand too
                    // (history viewable, no Install button). Only one row
                    // is expanded at a time (see toggleExpand).
                    View.OnClickListener toggle = v -> toggleExpand(row);
                    textColumn.setOnClickListener(toggle);
                    arrow.setOnClickListener(toggle);
                    check.setChecked(row.selected);
                    check.setEnabled(!row.installed && row.supported);
                    check.setVisibility(row.installed || !row.supported ? View.GONE : View.VISIBLE);
                    // The checkbox toggles batch selection only; it is
                    // never toggled by a header tap.
                    check.setOnClickListener(v -> row.selected = check.isChecked());
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
                    buildExpandedArea(row, expanded,
                        title.getCurrentTextColor(), subtitle.getCurrentTextColor());
                }
                return view;
            }
        };
        mAgentListView.setAdapter(mAdapter);
        // No setOnItemClickListener: the old row-tap behaviour (installed ->
        // update dialog, uninstalled -> toggle checkbox) is gone. Header
        // taps expand, checkbox taps select, the web button opens the web UI.
        mInstallButton.setOnClickListener(v -> installSelected());

        // Cached latest versions (checked within the last hour) apply to
        // the rows as soon as detection finishes, without re-fetching.
        AiAgentUtils.UpdateCache cache = AiAgentUtils.loadUpdateCache();
        if (AiAgentUtils.isUpdateCacheValid(cache.checkedAt)) {
            mLatestVersions.putAll(cache.latest);
        }
        if (AiAgentUtils.isHistoryCacheValid(cache.historyCheckedAt)) {
            mVersionHistories.putAll(cache.history);
            mHistoryCheckedAt = cache.historyCheckedAt;
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
        return desc + "\n" + statusLineFor(row);
    }

    /** Second line of a row: installed version + latest version, e.g.
     * "已安装 1.6.0 · 最新 1.6.0" or "未安装 · 最新 1.6.0". An installed
     * agent whose version cannot be
     * read (an old agy install without a record file, Hermes, Claude
     * with an unreadable versions dir) shows "已安装 · 版本未知". */
    private String statusLineFor(AgentRow row) {
        if (row.installed) {
            if (row.version != null && !row.version.isEmpty()) {
                if (AiAgentUtils.isNewerVersionAvailable(row.version, row.latestVersion)) {
                    return getString(R.string.aiagents_installed_with_update,
                        row.version, row.latestVersion);
                }
                if (row.latestVersion != null && !row.latestVersion.isEmpty()) {
                    return getString(R.string.aiagents_installed_with_latest,
                        row.version, row.latestVersion);
                }
                return getString(R.string.aiagents_installed, row.version);
            }
            return getString(R.string.aiagents_installed_unknown) + " · "
                + getString(R.string.aiagents_version_unknown);
        }
        if (row.latestVersion != null && !row.latestVersion.isEmpty()) {
            return getString(R.string.aiagents_not_installed_with_latest, row.latestVersion);
        }
        return getString(R.string.aiagents_not_installed);
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
                    restoreExpandedRow();
                    mAdapter.notifyDataSetChanged();
                    int installed = 0;
                    for (AgentRow row : mRows) if (row.installed) installed++;
                    mCheckUpdatesButton.setEnabled(!mCheckingUpdates);
                    updateStatus(getString(R.string.aiagents_status_summary, installed, mRows.size()));
                    runPendingAutoUpdate();
                    refreshLatestForAllInBackground();
                });
            }
        }.start();
    }

    /** Run the drawer-requested update once, right after detection: the
     * user already confirmed in the drawer, so the update session starts
     * directly (same path as the row's update dialog). The flag is
     * cleared first, so coming back from the session cannot re-run it. */
    private void runPendingAutoUpdate() {
        if (mAutoUpdateAgentId == null) return;
        String target = mAutoUpdateAgentId;
        mAutoUpdateAgentId = null;
        for (AgentRow row : mRows) {
            if (row.def.id.equals(target) && row.installed) {
                List<AiAgentUtils.AgentDef> single = new ArrayList<>();
                single.add(row.def);
                startAgentSession(single, true);
                return;
            }
        }
    }

    /** Node-dependent agents are only offered where the Node 24 path is verified;
     * agy is a glibc binary and Alpine uses musl. */
    private boolean isSupported(AiAgentUtils.AgentDef def) {
        if (def.needsNode && !NODE_VERIFIED.contains(mDistroId)) return false;
        if ("agy".equals(def.id) && "alpine".equals(mDistroId)) return false;
        return true;
    }

    // ------------------------------------------------------------------
    // Update detection (latest versions, cached one hour)
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

    /** Check for updates. The button is enabled even when nothing is
     * installed: with no installs it refreshes latest versions AND
     * histories for ALL agents; with installs it refreshes latest versions
     * for the installed ones and histories for the installed ones only
     * (uninstalled histories stay on expand-fetch, to save traffic — the
     * all-agents latest fetch on open already covers their "最新 x.y.z"
     * line). Fetches run in parallel on a background thread; rows are
     * matched by agent id on the UI thread, so a detection running in
     * parallel cannot leave stale row references behind. Failures are
     * silent — a failed agent keeps its cached value and shows no badge. */
    private void checkUpdatesInBackground() {
        if (mCheckingUpdates) return;
        List<AiAgentUtils.AgentDef> installed = new ArrayList<>();
        for (AgentRow row : mRows) {
            if (row.installed) installed.add(row.def);
        }
        List<AiAgentUtils.AgentDef> targets = installed.isEmpty()
            ? new ArrayList<>(AiAgentUtils.AGENTS) : installed;
        mCheckingUpdates = true;
        mCheckUpdatesButton.setEnabled(false);
        updateStatus(getString(R.string.aiagents_checking_updates));
        new Thread() {
            @Override
            public void run() {
                Map<String, String> distroByAgent = new HashMap<>();
                for (AiAgentUtils.AgentDef def : targets) {
                    distroByAgent.put(def.id, mDistroId);
                }
                Map<String, String> fetched =
                    AiAgentUtils.fetchLatestVersions(targets, distroByAgent);
                // "检查更新" refreshes the histories too (same tap, same
                // target set); a failed history fetch keeps the cached
                // one and never blocks the latest-version result.
                Map<String, AiAgentUtils.HistoryResult> historyResults =
                    AiAgentUtils.fetchVersionHistories(targets, distroByAgent);
                Map<String, List<AiAgentUtils.VersionEntry>> histories = new HashMap<>();
                for (Map.Entry<String, AiAgentUtils.HistoryResult> e
                        : historyResults.entrySet()) {
                    if (e.getValue() != null && !e.getValue().entries.isEmpty()) {
                        histories.put(e.getKey(), e.getValue().entries);
                    }
                }
                boolean anyFetched = !fetched.isEmpty();
                boolean anyHistory = !histories.isEmpty();
                final Map<String, String> merged;
                final Map<String, List<AiAgentUtils.VersionEntry>> mergedHistory;
                if (anyFetched || anyHistory) {
                    merged = new HashMap<>();
                    AiAgentUtils.UpdateCache cache = AiAgentUtils.loadUpdateCache();
                    merged.putAll(cache.latest);
                    merged.putAll(fetched);
                    mergedHistory = new HashMap<>(cache.history);
                    mergedHistory.putAll(histories);
                    AiAgentUtils.saveUpdateCache(merged, mergedHistory);
                } else {
                    merged = null;
                    mergedHistory = null;
                }
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    mCheckingUpdates = false;
                    if (merged != null) {
                        mLatestVersions.clear();
                        mLatestVersions.putAll(merged);
                    }
                    if (mergedHistory != null) {
                        mVersionHistories.clear();
                        mVersionHistories.putAll(mergedHistory);
                        AiAgentUtils.UpdateCache cache = AiAgentUtils.loadUpdateCache();
                        mHistoryCheckedAt = cache.historyCheckedAt;
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
                    mCheckUpdatesButton.setEnabled(true);
                    updateStatus(getString(R.string.aiagents_status_summary,
                        installedCount, mRows.size()));
                    if (anyFetched && installedCount > 0 && !anyUpdate) {
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


    // ------------------------------------------------------------------
    // Inline expandable rows (v2, replaces the version picker dialog)
    // ------------------------------------------------------------------

    /** Expand one row's inline version list, collapsing whichever row was
     * expanded before — at most one row is expanded at a time. Expanding a
     * row with no history yet starts a fetch (cached history first, live
     * fetch otherwise); collapsing never fetches. The expanded agent id is
     * kept so re-detection (e.g. coming back from an install session)
     * re-opens the same row. */
    private void toggleExpand(AgentRow row) {
        if (mExpandedRow == row) {
            row.expanded = false;
            mExpandedRow = null;
            mExpandedAgentId = "";
        } else {
            if (mExpandedRow != null) mExpandedRow.expanded = false;
            row.expanded = true;
            mExpandedRow = row;
            mExpandedAgentId = row.def.id;
            if (row.history == null && !row.historyLoading) {
                fetchHistoryForRow(row, false);
            }
        }
        mAdapter.notifyDataSetChanged();
    }

    /** Re-open the row that was expanded before a re-detection rebuilt
     * mRows (same agent id, same tapped version, cached history when it is
     * still fresh). Called from the detection callback, on the UI thread. */
    private void restoreExpandedRow() {
        mExpandedRow = null;
        if (mExpandedAgentId == null || mExpandedAgentId.isEmpty()) return;
        for (AgentRow row : mRows) {
            if (row.def.id.equals(mExpandedAgentId)) {
                row.expanded = true;
                mExpandedRow = row;
                String selected = mSelectedVersionByAgent.get(row.def.id);
                if (selected != null) row.selectedVersion = selected;
                List<AiAgentUtils.VersionEntry> cached =
                    mVersionHistories.get(row.def.id);
                if (cached != null && !cached.isEmpty()
                    && AiAgentUtils.isHistoryCacheValid(mHistoryCheckedAt)) {
                    row.history = cached;
                }
                break;
            }
        }
    }

    /** Fetch one row's history for its expanded area. A fresh (24h) cache
     * entry is used as-is unless forceRefresh (the retry button) asks for a
     * live re-fetch. On a live failure with no cache, the bundled offline
     * history is shown instead, tagged as offline data — bundled entries
     * are never written into the cache. On a failure with neither, the row
     * keeps the failure reason (row.historyError) and shows it with a
     * retry button. */
    private void fetchHistoryForRow(final AgentRow row, boolean forceRefresh) {
        if (!forceRefresh) {
            List<AiAgentUtils.VersionEntry> cached = mVersionHistories.get(row.def.id);
            if (cached != null && !cached.isEmpty()
                && AiAgentUtils.isHistoryCacheValid(mHistoryCheckedAt)) {
                row.history = cached;
                row.historyError = "";
                row.historyOffline = false;
                row.historyLoading = false;
                mAdapter.notifyDataSetChanged();
                return;
            }
        }
        row.historyLoading = true;
        row.historyError = "";
        mAdapter.notifyDataSetChanged();
        new Thread() {
            @Override
            public void run() {
                AiAgentUtils.HistoryResult result =
                    AiAgentUtils.fetchVersionHistory(row.def, mDistroId);
                List<AiAgentUtils.VersionEntry> entries = result.entries;
                boolean offline = false;
                String error = result.error;
                if (entries == null || entries.isEmpty()) {
                    Map<String, List<AiAgentUtils.VersionEntry>> bundled =
                        AiAgentUtils.loadBundledHistory(AiAgentsActivity.this);
                    List<AiAgentUtils.VersionEntry> fallback = bundled.get(row.def.id);
                    if (fallback != null && !fallback.isEmpty()) {
                        entries = fallback;
                        offline = true;
                        error = "";
                    }
                }
                final List<AiAgentUtils.VersionEntry> finalEntries = entries;
                final boolean finalOffline = offline;
                final String finalError = error;
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    row.historyLoading = false;
                    if (finalEntries != null && !finalEntries.isEmpty()) {
                        row.history = finalEntries;
                        row.historyError = "";
                        row.historyOffline = finalOffline;
                        if (!finalOffline) {
                            Map<String, List<AiAgentUtils.VersionEntry>> one = new HashMap<>();
                            one.put(row.def.id, finalEntries);
                            AiAgentUtils.saveVersionHistory(one);
                            mVersionHistories.put(row.def.id, finalEntries);
                            AiAgentUtils.UpdateCache cache =
                                AiAgentUtils.loadUpdateCache();
                            mHistoryCheckedAt = cache.historyCheckedAt;
                        }
                    } else {
                        row.history = null;
                        row.historyError = finalError;
                        row.historyOffline = false;
                        Log.w(LOG_TAG, "History fetch failed for " + row.def.id
                            + ": " + finalError);
                    }
                    mAdapter.notifyDataSetChanged();
                });
            }
        }.start();
    }

    /** Fill (or hide) one row's expanded area. The area is built in code —
     * explicit sizes, and colours taken from the row's own title/subtitle
     * so it follows the theme — never an AlertDialog list. Each history
     * entry is one line, "version · date · tag" with a radio dot on the
     * right; tapping a version row selects it (default: latest). The
     * Install button installs the selected version through the same
     * onVersionInstallChosen path as before. Rows that cannot install a
     * pinned version — Hermes (view-only) or an unsupported distro — show
     * the history with no Install button, and a tap explains why. */
    private void buildExpandedArea(AgentRow row, LinearLayout container,
            int primaryColor, int secondaryColor) {
        container.removeAllViews();
        if (!row.expanded) {
            container.setVisibility(View.GONE);
            return;
        }
        container.setVisibility(View.VISIBLE);
        if (!row.supported) {
            addExpandedLine(container,
                getString(R.string.aiagents_unsupported), 13, secondaryColor);
        }
        if (row.historyLoading) {
            addExpandedLine(container,
                getString(R.string.aiagents_version_loading), 13, secondaryColor);
            return;
        }
        if (row.history == null || row.history.isEmpty()) {
            addExpandedLine(container, historyErrorMessage(row.historyError),
                13, secondaryColor);
            Button retry = new Button(this);
            retry.setText(R.string.aiagents_retry);
            retry.setTextSize(13);
            retry.setOnClickListener(v -> fetchHistoryForRow(row, true));
            container.addView(retry);
            return;
        }
        if (row.historyOffline) {
            addExpandedLine(container,
                getString(R.string.aiagents_offline_data), 12, secondaryColor);
        }
        boolean canInstall = row.supported
            && AiAgentUtils.supportsVersionInstall(row.def);
        String selected = selectedVersionFor(row);
        for (int i = 0; i < row.history.size(); i++) {
            AiAgentUtils.VersionEntry entry = row.history.get(i);
            LinearLayout versionRow = new LinearLayout(this);
            versionRow.setOrientation(LinearLayout.HORIZONTAL);
            versionRow.setPadding(0, 8, 0, 8);
            TextView label = new TextView(this);
            label.setText(versionLabelFor(row, entry, i));
            label.setTextSize(14);
            label.setTextColor(primaryColor);
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            versionRow.addView(label, labelParams);
            TextView radio = new TextView(this);
            radio.setText(entry.version.equals(selected) ? "◉" : "○");
            radio.setTextSize(14);
            radio.setTextColor(secondaryColor);
            versionRow.addView(radio);
            final String version = entry.version;
            versionRow.setOnClickListener(v -> {
                if (!canInstall) {
                    if (!row.supported) {
                        Toast.makeText(AiAgentsActivity.this,
                            R.string.aiagents_unsupported, Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(AiAgentsActivity.this,
                            getString(R.string.aiagents_version_not_supported,
                                row.def.displayName),
                            Toast.LENGTH_SHORT).show();
                    }
                    return;
                }
                row.selectedVersion = version;
                mSelectedVersionByAgent.put(row.def.id, version);
                mAdapter.notifyDataSetChanged();
            });
            container.addView(versionRow);
        }
        if (canInstall) {
            Button install = new Button(this);
            install.setText(R.string.aiagents_version_install);
            install.setTextSize(14);
            install.setOnClickListener(v -> {
                String version = selectedVersionFor(row);
                onVersionInstallChosen(row, version,
                    version.equals(row.history.get(0).version));
            });
            container.addView(install);
        }
    }

    private void addExpandedLine(LinearLayout container, String text,
            float sizeSp, int color) {
        TextView line = new TextView(this);
        line.setText(text);
        line.setTextSize(sizeSp);
        line.setTextColor(color);
        line.setPadding(0, 4, 0, 4);
        container.addView(line);
    }

    /** One version line: "1.6.0 · 2026-11-20 · 最新" / "1.5.2 · 2026-11-10" /
     * "1.5.1 · 2026-11-05 · 当前". The date is omitted when unknown (never
     * invented); the first entry is always the latest. */
    private String versionLabelFor(AgentRow row, AiAgentUtils.VersionEntry entry, int index) {
        String label = entry.version;
        if (entry.date != null && !entry.date.isEmpty()) {
            label += " · " + entry.date;
        }
        if (index == 0) {
            label += " · " + getString(R.string.aiagents_version_latest);
        }
        if (row.installed && entry.version.equals(row.version)) {
            label += " · " + getString(R.string.aiagents_version_current);
        }
        return label;
    }

    /** The version the expanded Install button installs: the tapped
     * version, or the latest (first entry) when nothing was tapped yet. */
    private String selectedVersionFor(AgentRow row) {
        if (row.selectedVersion != null && !row.selectedVersion.isEmpty()) {
            return row.selectedVersion;
        }
        if (row.history != null && !row.history.isEmpty()) {
            return row.history.get(0).version;
        }
        return "";
    }

    /** User string for a history failure code (see HistoryResult): the
     * "empty" code — nothing usable came back — reuses the existing
     * "查不到历史" string. */
    private String historyErrorMessage(String error) {
        if ("timeout".equals(error)) {
            return getString(R.string.aiagents_history_error_timeout);
        }
        if ("http-403".equals(error)) {
            return getString(R.string.aiagents_history_error_http_403);
        }
        if ("no-network".equals(error)) {
            return getString(R.string.aiagents_history_error_no_network);
        }
        if ("parse-error".equals(error)) {
            return getString(R.string.aiagents_history_error_parse);
        }
        return getString(R.string.aiagents_version_history_unavailable);
    }

    /** Fetch the latest version for ALL 8 agents (not only installed ones)
     * once, on open, when the 1h cache is stale — so uninstalled rows can
     * show "未安装 · 最新 x.y.z" without the user tapping anything. Runs
     * on a background thread; a cached value is applied to the rows by
     * detection already (see onCreate). Never re-fetched while a fetch is
     * running or the cache is fresh. */
    private void refreshLatestForAllInBackground() {
        if (mFetchingLatest) return;
        AiAgentUtils.UpdateCache cache = AiAgentUtils.loadUpdateCache();
        if (AiAgentUtils.isUpdateCacheValid(cache.checkedAt)) return;
        mFetchingLatest = true;
        new Thread() {
            @Override
            public void run() {
                Map<String, String> distroByAgent = new HashMap<>();
                for (AiAgentUtils.AgentDef def : AiAgentUtils.AGENTS) {
                    distroByAgent.put(def.id, mDistroId);
                }
                Map<String, String> fetched =
                    AiAgentUtils.fetchLatestVersions(AiAgentUtils.AGENTS, distroByAgent);
                if (!fetched.isEmpty()) {
                    Map<String, String> merged =
                        new HashMap<>(AiAgentUtils.loadUpdateCache().latest);
                    merged.putAll(fetched);
                    AiAgentUtils.saveUpdateCache(merged);
                }
                runOnUiThread(() -> {
                    mFetchingLatest = false;
                    if (isFinishing()) return;
                    if (!fetched.isEmpty()) {
                        mLatestVersions.putAll(fetched);
                        applyLatestVersionsToRows();
                        mAdapter.notifyDataSetChanged();
                    }
                });
            }
        }.start();
    }

    /** Install the picked version: the latest goes straight in; an older
     * version asks first — it may have known issues, and a downgrade
     * replaces the current install. agy gets one extra line: its binary
     * self-updates in the background, so an older agy may lift itself
     * back to the latest on its own. */
    private void onVersionInstallChosen(final AgentRow row, final String version,
            boolean isLatest) {
        if (isLatest) {
            installVersion(row, version);
            return;
        }
        String message = getString(R.string.aiagents_install_old_version, version);
        if (row.installed) {
            message += "\n" + getString(R.string.aiagents_downgrade_note);
        }
        if ("agy".equals(row.def.id)) {
            message += "\n" + getString(R.string.aiagents_downgrade_agy_note);
        }
        new AlertDialog.Builder(this)
            .setTitle(getString(R.string.aiagents_choose_version))
            .setMessage(message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.aiagents_version_install,
                (dialog, which) -> installVersion(row, version))
            .show();
    }

    /** Start the install session for one agent at a pinned version
     * (update=true when it is already installed, so the script header
     * reads "正在更新"). The version rides in the pinned map; see
     * buildAgentScript and versionedInstallSnippet. */
    private void installVersion(AgentRow row, String version) {
        List<AiAgentUtils.AgentDef> single = new ArrayList<>();
        single.add(row.def);
        Map<String, String> pinned = new HashMap<>();
        pinned.put(row.def.id, version);
        startAgentSession(single, row.installed, pinned);
    }

    // ------------------------------------------------------------------
    // Sessions: write the install script into the container and run it
    // ------------------------------------------------------------------

    /** Write the install/update script into the container's /tmp and run it
     * in a new visible terminal session. Deliberately does not finish():
     * Back from the session returns here and onResume re-detects. */
    private void startAgentSession(List<AiAgentUtils.AgentDef> agents, boolean update) {
        startAgentSession(agents, update, null);
    }

    /** As above, with pinnedVersions mapping agent id -> version to install
     * (from the expanded row); null/empty means "latest", the old
     * behaviour. A pinned map always holds a single agent. */
    private void startAgentSession(List<AiAgentUtils.AgentDef> agents, boolean update,
            Map<String, String> pinnedVersions) {
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
                out.write(buildAgentScript(agents, update, pinnedVersions)
                    .getBytes(StandardCharsets.UTF_8));
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

    /** Full install/update script run inside the container (as root).
     * pinnedVersions maps agent id -> version (expanded row); a pinned
     * agent is installed by versionedInstallSnippet instead of its
     * install/update snippet. */
    private String buildAgentScript(List<AiAgentUtils.AgentDef> agents, boolean update,
            Map<String, String> pinnedVersions) {
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
        s.append("  for _nb in /usr/local/lib/nodejs/*/bin/\"$1\"; do [ -x \"$_nb\" ] && return 0; done\n");
        s.append("  [ -n \"$1\" ] && [ -x \"$HOME/.npm-global/bin/$1\" ] && return 0\n");
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
        // Download mirrors: npm / PyPI / uv / Hermes downloads inside the
        // distro use the selected preset (for the Official preset this just
        // pins the official registries). Must come before any npm/uv use.
        MirrorSettings.MirrorPreset preset = MirrorSettings.loadPreset(this);
        s.append(MirrorSettings.mirrorScriptExports(preset));
        boolean needsNode = false;
        for (AiAgentUtils.AgentDef def : agents) if (def.needsNode) needsNode = true;
        if (needsNode) {
            s.append("if ! node_ok; then\n");
            s.append("  echo \"正在安装 Node 24 …\"\n");
            s.append("  ").append(nodeInstallCommand(mDistroId, preset)).append("\n");
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
            String pinned = pinnedVersions == null ? null : pinnedVersions.get(def.id);
            if (pinned != null && !pinned.isEmpty()) {
                s.append("  ").append(versionedInstallSnippet(def, pinned)).append("\n");
            } else {
                s.append("  ").append(update ? def.updateSnippet : def.installSnippet).append("\n");
            }
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
                if (pinned != null && !pinned.isEmpty()) {
                    // A pinned agy install must record the version that
                    // was actually installed, not the manifest's latest:
                    // record_agy_version() reads the manifest and would
                    // claim the newest version for an old install.
                    s.append("    mkdir -p \"$HOME/.termux-aiagents/versions\" 2>/dev/null\n");
                    s.append("    printf '%s' \"").append(pinned)
                        .append("\" > \"$HOME/.termux-aiagents/versions/agy\" 2>/dev/null || true\n");
                } else {
                    s.append("    record_agy_version\n");
                }
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

    /** Shell lines that install one agent at a pinned version (expanded
     * row). The version comes only from fetchVersionHistory, which
     * validates it, so it is safe to interpolate. Hermes has no branch
     * here on purpose: supportsVersionInstall keeps it view-only.
     *
     * - Pi / dsh / OpenCode / Claude: npm install -g &lt;pkg&gt;@&lt;version&gt;
     *   (Pi keeps --ignore-scripts; Claude must NOT use it — its
     *   postinstall downloads the platform-native binary, and
     *   bin/claude.exe is only a wrapper without it. OpenCode and
     *   Claude both pass --allow-scripts=&lt;pkg&gt; because npm 11
     *   skips postinstall scripts that are not explicitly allowed;
     *   dsh has no postinstall and stays unchanged.)
     * - Aider: uv tool install --force replaces the installed version
     *   in place (no separate uninstall step). --upgrade only goes to
     *   latest, so a pinned install must go through uv directly; uv
     *   itself is installed first when missing.
     * - agy: the official installer only installs latest, so a pinned
     *   version downloads the release tarball for that tag instead —
     *   same layout the installer produces (single `antigravity`
     *   binary copied to ~/.local/bin/agy). glibc uses
     *   agy_cli_linux_arm64.tar.gz, Alpine the _musl file.
     * - Codex: same idea with the standalone musl tarball. The binary
     *   is placed in the official releases layout
     *   ~/.codex/packages/standalone/releases/&lt;version&gt;-&lt;target&gt;/bin/codex
     *   with `current` re-pointed at it and ~/.local/bin/codex re-linked,
     *   so detection (which reads the releases dir) reports the new
     *   version. Older release dirs are removed first — otherwise a
     *   downgrade would leave the newer dir behind and detection would
     *   keep reporting the newer version. The package layout's extras
     *   (codex-package.json, rg, bwrap) are not recreated: a later
     *   `codex update` restores the full package. */
    private String versionedInstallSnippet(AiAgentUtils.AgentDef def, String version) {
        switch (def.id) {
            case "pi":
                return "npm install -g --ignore-scripts @earendil-works/pi-coding-agent@" + version;
            case "dsh":
                return "npm install -g @deepseek-ai/dsh@" + version;
            case "opencode":
                return "npm install -g --allow-scripts=@opencode/cli @opencode/cli@" + version;
            case "claude":
                return "npm install -g --allow-scripts=@anthropic-ai/claude-code @anthropic-ai/claude-code@" + version;
            case "aider":
                return "command -v uv >/dev/null 2>&1 || { curl -LsSf https://astral.sh/uv/install.sh | sh; export PATH=\"$HOME/.local/bin:$PATH\"; }\n"
                    + "  uv tool install --force aider-chat@" + version;
            case "agy": {
                String file = "alpine".equals(mDistroId)
                    ? "agy_cli_linux_arm64_musl.tar.gz" : "agy_cli_linux_arm64.tar.gz";
                String tmpDir = "/tmp/agy-" + version;
                return "curl -fsSL \"https://github.com/google-antigravity/antigravity-cli/releases/download/"
                    + version + "/" + file + "\" -o \"" + tmpDir + ".tar.gz\""
                    + " && rm -rf \"" + tmpDir + "\" && mkdir -p \"" + tmpDir + "\""
                    + " && tar -xzf \"" + tmpDir + ".tar.gz\" -C \"" + tmpDir + "\""
                    + " && mkdir -p \"$HOME/.local/bin\""
                    + " && cp \"" + tmpDir + "/antigravity\" \"$HOME/.local/bin/agy\""
                    + " && chmod +x \"$HOME/.local/bin/agy\""
                    + " && rm -rf \"" + tmpDir + "\" \"" + tmpDir + ".tar.gz\"";
            }
            case "codex": {
                String releaseDir = version + "-aarch64-unknown-linux-musl";
                String tmpDir = "/tmp/codex-" + version;
                String releases = "$HOME/.codex/packages/standalone/releases";
                return "curl -fsSL \"https://github.com/openai/codex/releases/download/rust-v"
                    + version + "/codex-aarch64-unknown-linux-musl.tar.gz\" -o \"" + tmpDir + ".tar.gz\""
                    + " && rm -rf \"" + tmpDir + "\" && mkdir -p \"" + tmpDir + "\""
                    + " && tar -xzf \"" + tmpDir + ".tar.gz\" -C \"" + tmpDir + "\""
                    + " && [ -f \"" + tmpDir + "/codex-aarch64-unknown-linux-musl\" ]"
                    + " && mkdir -p \"" + releases + "\""
                    + " && (rm -rf \"" + releases + "\"/* 2>/dev/null || true)"
                    + " && mkdir -p \"" + releases + "/" + releaseDir + "/bin\""
                    + " && mv \"" + tmpDir + "/codex-aarch64-unknown-linux-musl\" \"" + releases + "/" + releaseDir + "/bin/codex\""
                    + " && chmod +x \"" + releases + "/" + releaseDir + "/bin/codex\""
                    + " && ln -sf \"bin/codex\" \"" + releases + "/" + releaseDir + "/codex\""
                    + " && ln -sfn \"" + releases + "/" + releaseDir + "\" \"$HOME/.codex/packages/standalone/current\""
                    + " && mkdir -p \"$HOME/.local/bin\""
                    + " && ln -sf \"$HOME/.codex/packages/standalone/current/bin/codex\" \"$HOME/.local/bin/codex\""
                    + " && rm -rf \"" + tmpDir + "\" \"" + tmpDir + ".tar.gz\"";
            }
            default:
                return "";
        }
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
     * only ubuntu/debian are offered in v1 (NODE_VERIFIED). On Ubuntu/Debian
     * with a non-official mirror preset, NodeSource has no Chinese mirror,
     * so install Node 24 from the npmmirror binary tarball instead. */
    private static String nodeInstallCommand(String distroId, MirrorSettings.MirrorPreset preset) {
        if (("ubuntu".equals(distroId) || "debian".equals(distroId))
                && preset != null && !MirrorSettings.isOfficial(preset)) {
            return MirrorSettings.nodeTarballInstallCommand();
        }
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
