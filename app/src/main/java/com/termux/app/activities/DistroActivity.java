// Linux distro manager: list, one-click install and login for proot-distro
// containers.
//
// The distro list is fetched at runtime: the 7 supported distros and their
// Docker Hub image names are the only fixed data. Version numbers come from
// the Docker Hub tags API, with the last fetched list cached in
// ~/.termux/distro-list.json as an offline fallback and a bundled last-known
// list as a first-run fallback, so version numbers are always visible.
// proot-distro v5 has no alias system, so installs use image references
// such as "ubuntu:26.04".
//
// Install / login / tools install run in a visible terminal session via the
// same internal TERMUX_SERVICE.ACTION_SERVICE_EXECUTE intent that
// RunCommandService uses, so TermuxService creates the session, switches to
// it and brings TermuxActivity forward by itself. This activity finishes
// right after sending the intent.
//
// Pure Java, zero new dependencies: only Android framework APIs
// (HttpURLConnection, org.json, AtomicFile) and existing termux-shared
// classes are used.

package com.termux.app.activities;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.format.Formatter;
import android.util.AtomicFile;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.termux.R;
import com.termux.shared.activity.media.AppCompatActivityUtils;
import com.termux.shared.settings.preferences.SharedPreferenceUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.theme.NightMode;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class DistroActivity extends AppCompatActivity {

    private static final String LOG_TAG = "DistroActivity";

    /** Intent extra: container name whose options dialog opens automatically
     * (drawer long-press). */
    public static final String EXTRA_CONTAINER_NAME = "com.termux.distro.container_name";

    /** SharedPreferences file holding containers whose uninstall session
     * was launched and whose host metadata cleanup is still pending. */
    private static final String UNINSTALL_PREFS_NAME = "distro_uninstall";
    private static final String KEY_PENDING_UNINSTALLS = "pending_uninstalls";
    /** Entries "<container>|<absolute backup dir path>" for uninstalls
     * whose backup was packed internally but still has to be copied to
     * external storage (see copyPendingExternalBackup). Kept in the
     * same "distro_uninstall" file as the pending uninstalls. */
    private static final String KEY_PENDING_EXTERNAL_COPIES = "pending_external_copies";

    /** SharedPreferences file for the restore auto-prompt (design.md §4):
     * which containers this screen has already seen, and which it has
     * already offered a restore for. */
    private static final String RESTORE_PREFS_NAME = "distro_restore";
    private static final String KEY_RESTORE_KNOWN_CONTAINERS = "known_containers";
    private static final String KEY_RESTORE_PROMPTED_CONTAINERS = "prompted_containers";

    /** SharedPreferences file for backup settings (design.md §6), shared
     * with ExternalBackupStore: the default backup location and the
     * external folder Uri. App convention: one named file per feature. */
    private static final String BACKUP_PREFS_NAME = "distro_backup";

    /** SharedPreferences key for the default backup location
     * ("internal" / "external", design.md §6). Default is internal. */
    private static final String PREFS_KEY_DEFAULT_BACKUP_LOCATION = "backup_default_location";
    private static final String BACKUP_LOCATION_INTERNAL = "internal";
    private static final String BACKUP_LOCATION_EXTERNAL = "external";

    /** Programmatic options-menu id for the backup manager (the
     * distro_menu.xml is not in this checkout, so the item is added in
     * code in onCreateOptionsMenu instead of editing the menu XML). */
    private static final int MENU_BACKUP_MANAGER = Menu.FIRST + 1;

    private static final int DOCKER_HUB_CONNECT_TIMEOUT_MS = 15000;
    private static final int DOCKER_HUB_READ_TIMEOUT_MS = 30000;
    /** One retry per image: large tag pages can stall on slow mobile networks. */
    private static final int DOCKER_HUB_FETCH_ATTEMPTS = 2;
    /** Tags not updated in this many years are treated as stale (e.g. Leap 42.3). */
    private static final int STALE_TAG_YEARS = 3;
    private static final int DOCKER_HUB_TAGS_PAGE_SIZE = 100;

    /** The `distro` shell command, written to $PREFIX/bin on first use. */
    private static final String DISTRO_COMMAND_FILE_NAME = "distro";
    private static final String DISTRO_COMMAND_CONTENT =
        "#!/data/data/com.termux/files/usr/bin/sh\n" +
        "# `distro` - open the Linux distro manager, or pass arguments to proot-distro.\n" +
        "# Written by Termux (DistroActivity.ensureDistroCommand); edits are overwritten.\n" +
        "if [ $# -eq 0 ]; then\n" +
        "    am start -n com.termux/.app.activities.DistroActivity >/dev/null 2>&1 \\\n" +
        "        || /system/bin/am start -n com.termux/.app.activities.DistroActivity >/dev/null 2>&1 \\\n" +
        "        || echo \"Cannot open the Linux distro screen, please use the drawer entry.\"\n" +
        "else\n" +
        "    exec proot-distro \"$@\"\n" +
        "fi\n";

    /** How version tags of a Docker Hub image are picked. */
    private enum TagFilter {
        /** Tags like 26.04 / 3.23, newest first. */
        DOTTED_VERSION,
        /** Tags like 46 / 13, newest first. */
        INTEGER_VERSION,
        /** Debian codenames, in preference order. */
        DEBIAN_CODENAME,
        /** Only the "latest" tag (rolling releases). */
        ROLLING
    }

    /** One Docker Hub image that provides versions for a distro. */
    private static class TagSource {
        final String image;          // e.g. "ubuntu" or "opensuse/leap"
        final TagFilter filter;
        final String displayPrefix;  // e.g. "" or "Leap "

        TagSource(String image, TagFilter filter, String displayPrefix) {
            this.image = image;
            this.filter = filter;
            this.displayPrefix = displayPrefix;
        }
    }

    /** One distro shown in the list. Only the image names are fixed; versions are fetched. */
    private static class DistroDef {
        final String id;             // e.g. "ubuntu", used for package manager mapping
        final String displayName;    // e.g. "Ubuntu"
        final String note;           // short note shown in the row subtitle when nothing is installed
        final String containerPrefix; // installed container names start with this, e.g. "ubuntu"
        final List<TagSource> sources;

        DistroDef(String id, String displayName, String note, String containerPrefix, TagSource... sources) {
            this.id = id;
            this.displayName = displayName;
            this.note = note;
            this.containerPrefix = containerPrefix;
            this.sources = Arrays.asList(sources);
        }
    }

    /** One installable version: what the user picks in the dialog. */
    private static class VersionOption {
        final String display;        // e.g. "26.10" or "Leap 16.1"
        final String imageRef;       // e.g. "ubuntu:26.10" or "opensuse/leap:16.1"
        final String container;      // e.g. "ubuntu-26.10"
        final String updated;        // yyyy-MM-dd when the image was last updated, "" if unknown

        VersionOption(String display, String imageRef) {
            this(display, imageRef, "");
        }

        VersionOption(String display, String imageRef, String updated) {
            this.display = display;
            this.imageRef = imageRef;
            this.container = DistroUtils.containerNameForImageRef(imageRef);
            this.updated = updated != null ? updated : "";
        }
    }

    /** One row of the distro list. */
    private static class DistroRow {
        final String title;
        final String subtitle;

        DistroRow(String title, String subtitle) {
            this.title = title;
            this.subtitle = subtitle;
        }

        @Override
        public String toString() {
            return title;
        }
    }

    // Debian codenames in preference order, with their version numbers.
    // "sid" has no version number.
    private static final String[] DEBIAN_CODENAMES = {"trixie", "bookworm", "bullseye", "sid"};
    private static final Map<String, String> DEBIAN_CODENAME_VERSIONS = new HashMap<>();
    static {
        DEBIAN_CODENAME_VERSIONS.put("trixie", "13");
        DEBIAN_CODENAME_VERSIONS.put("bookworm", "12");
        DEBIAN_CODENAME_VERSIONS.put("bullseye", "11");
    }

    // The 6 supported distros. Only the Docker Hub image names are fixed here;
    // version numbers are fetched from the Docker Hub tags API at runtime.
    private static final List<DistroDef> DISTROS = Arrays.asList(
        new DistroDef("ubuntu", "Ubuntu", "可选最新版或长期支持版", "ubuntu",
            new TagSource("ubuntu", TagFilter.DOTTED_VERSION, "")),
        new DistroDef("debian", "Debian", "可选代号版本", "debian",
            new TagSource("debian", TagFilter.DEBIAN_CODENAME, "")),
        new DistroDef("arch", "Arch Linux", "滚动更新", "menci-archlinuxarm",
            new TagSource("menci/archlinuxarm", TagFilter.ROLLING, "")),
        new DistroDef("alpine", "Alpine Linux", "轻量小巧", "alpine",
            new TagSource("alpine", TagFilter.DOTTED_VERSION, "")),
        new DistroDef("fedora", "Fedora", "可选数字版本", "fedora",
            new TagSource("fedora", TagFilter.INTEGER_VERSION, "")),
        new DistroDef("opensuse", "openSUSE", "Leap 稳定版 / Tumbleweed 滚动版", "opensuse",
            new TagSource("opensuse/leap", TagFilter.DOTTED_VERSION, "Leap "),
            new TagSource("opensuse/tumbleweed", TagFilter.ROLLING, "Tumbleweed "))
    );

    /** Common tools the user can tick after installing a distro. */
    private static final String[] TOOL_IDS =
        {"git", "curl", "wget", "vim", "nano", "htop", "python3", "unzip", "openssh"};
    private static final String[] TOOL_DISPLAY_NAMES =
        {"Git", "cURL", "Wget", "Vim", "Nano", "htop", "Python 3", "unzip", "OpenSSH 客户端"};

    private ListView mDistroListView;
    private TextView mStatusView;
    private ArrayAdapter<DistroRow> mAdapter;

    /** distro id -> fetched version options (empty until fetched). */
    private final Map<String, List<VersionOption>> mVersions = new HashMap<>();
    /** container name -> display name, e.g. "ubuntu-26.04" -> "Ubuntu 26.04". */
    private final Map<String, String> mContainerDisplayNames = new HashMap<>();
    /** Installed container names, from scanning the containers directories. */
    private Set<String> mInstalledContainers = new HashSet<>();
    /** Containers already offered the model-config re-apply prompt in this
     * activity lifetime, so a dismissed prompt does not nag on every resume. */
    private final Set<String> mReapplyPromptedContainers = new HashSet<>();
    private boolean mListLoading = true;
    /** "latest" label, resolved on the UI thread once and reused in background threads. */
    private String mLatestLabel;
    /** When the cached list was fetched, 0 if there is no cache. */
    private long mCacheUpdatedAt;
    /** Pending-uninstall containers already reported as "still there" in
     * this activity lifetime, so a long-running backup does not nag on
     * every resume (see checkPendingUninstalls). */
    private final Set<String> mUninstallStillPresentNoted = new HashSet<>();
    /** External-copy failures already toasted in this activity lifetime
     * (the pending copy is retried on a later resume; see
     * copyPendingExternalBackup). */
    private final Set<String> mExternalCopyFailedNoted = new HashSet<>();
    /** Containers whose backup is being copied to external storage
     * right now. copyPendingExternalBackup adds the container before
     * copying and removes it afterwards, so the watcher and the
     * resume-time check can never copy the same container twice at
     * once; a second call while one is running simply skips. */
    private final Set<String> mExternalCopyInProgress =
        Collections.synchronizedSet(new HashSet<String>());
    /** Watcher threads started by startBackupSession /
     * startUninstallSession (see startExternalCopyWatcher), stopped
     * in onDestroy. */
    private final Set<Thread> mExternalCopyWatchers =
        Collections.synchronizedSet(new HashSet<Thread>());
    /** How often the external-copy watcher re-checks whether the
     * backup dir is complete. */
    private static final long EXTERNAL_COPY_WATCH_INTERVAL_MS = 5000;
    /** The watcher gives up after this long; the pending record and
     * the resume-time checkPendingExternalCopies remain the fallback. */
    private static final long EXTERNAL_COPY_WATCH_TIMEOUT_MS = 2 * 60 * 60 * 1000;
    /** Guards checkRestoreAutoPrompt so overlapping onCreate/onResume
     * scans do not prompt twice for the same container. */
    private final java.util.concurrent.atomic.AtomicBoolean mRestoreAutoPromptRunning =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    /** The backup-manager dialog, while it is open (for refresh after a
     * folder pick or a delete). */
    private AlertDialog mBackupManagerDialog;
    /** The backup detail dialog, if open: dismissed when a per-file
     * delete completes so it never shows a stale item list. */
    private AlertDialog mBackupDetailDialog;
    /** The backup picker dialog currently open (uninstall confirm or
     * standalone backup), while it is open — its destination widgets
     * are refreshed from the SAF folder-picker result in
     * onActivityResult, and its totals drive the dialog positive
     * button (see BackupPicker). */
    private BackupPicker mActiveBackupPicker;
    /** True while a folder pick launched from a backup picker dialog
     * is outstanding; a cancel then falls back to internal storage. */
    private boolean mBackupFolderPickPending;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        AppCompatActivityUtils.setNightMode(this, NightMode.getAppNightMode().getName(), true);

        setContentView(R.layout.distro_layout);
        AppCompatActivityUtils.setToolbar(this, com.termux.shared.R.id.toolbar);
        AppCompatActivityUtils.setShowBackButtonInActionBar(this, true);

        ensureDistroCommand();

        AiAgentUtils.ensureAiCommand();

        mLatestLabel = getString(R.string.distro_version_latest);

        mDistroListView = findViewById(R.id.distro_list);
        mStatusView = findViewById(R.id.distro_status);
        mAdapter = new ArrayAdapter<DistroRow>(this, android.R.layout.simple_list_item_2,
            android.R.id.text1, new ArrayList<DistroRow>()) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                DistroRow row = getItem(position);
                if (row != null) {
                    ((TextView) view.findViewById(android.R.id.text1)).setText(row.title);
                    ((TextView) view.findViewById(android.R.id.text2)).setText(row.subtitle);
                }
                return view;
            }
        };
        mDistroListView.setAdapter(mAdapter);
        mDistroListView.setOnItemClickListener((parent, view, position, id) -> {
            DistroDef distro = DISTROS.get(position);
            if (installedContainersFor(distro).isEmpty()) {
                showVersionDialog(distro);
            } else {
                showDistroDialog(distro);
            }
        });

        loadCachedVersions();
        refreshInstalledContainers();
        // A distro installed just now (its install ran in a terminal session
        // with no callback) gets the current download mirror applied here.
        new Thread(() -> MirrorSettings.syncPendingInstalls(DistroActivity.this)).start();
        // Same pattern for uninstalls: a container removed by its terminal
        // session gets its host metadata cleaned here (see
        // checkPendingUninstalls).
        new Thread(this::checkPendingUninstalls).start();
        // A container installed just now gets offered a restore from any
        // backup on this screen (see checkRestoreAutoPrompt).
        new Thread(this::checkRestoreAutoPrompt).start();
        String showContainer = getIntent().getStringExtra(EXTRA_CONTAINER_NAME);
        if (showContainer != null) showDistroDialogForContainer(showContainer);
        if (mCacheUpdatedAt > 0) {
            updateListRows(getString(R.string.distro_status_updated, formatTime(mCacheUpdatedAt)));
        } else {
            updateListRows(getString(R.string.distro_status_loading));
        }
        refreshVersionsInBackground();
    }

    @Override
    protected void onResume() {
        super.onResume();
        new Thread(() -> MirrorSettings.syncPendingInstalls(DistroActivity.this)).start();
        new Thread(this::checkPendingUninstalls).start();
        new Thread(this::checkRestoreAutoPrompt).start();
        new Thread(this::checkModelConfigReapply).start();
    }

    @Override
    protected void onDestroy() {
        // Stop any external-copy watcher: its activity is gone, and the
        // pending record + checkPendingExternalCopies on the next resume
        // remain the fallback for the copy.
        stopExternalCopyWatchers();
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        // The SAF folder pick (request code 1401) is handled first: it
        // persists the grant and saves the folder Uri, and returns true
        // when the pick was handled.
        boolean folderPicked = ExternalBackupStore.onFolderPicked(
            this, requestCode, resultCode, data);
        if (requestCode != ExternalBackupStore.REQUEST_PICK_EXTERNAL_FOLDER) return;
        if (folderPicked) {
            mBackupFolderPickPending = false;
            updateBackupDestinationLabel();
            if (mBackupManagerDialog != null && mBackupManagerDialog.isShowing()) {
                refreshBackupManager();
            }
        } else if (mBackupFolderPickPending) {
            // The user cancelled the picker from a backup dialog:
            // fall back to internal storage for this backup.
            mBackupFolderPickPending = false;
            if (mActiveBackupPicker != null) {
                mActiveBackupPicker.internalRadio.setChecked(true);
            }
            updateBackupDestinationLabel();
            Toast.makeText(this, R.string.backup_external_folder_not_set,
                Toast.LENGTH_LONG).show();
        } else if (mBackupManagerDialog != null && mBackupManagerDialog.isShowing()) {
            refreshBackupManager();
        }
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.distro_menu, menu);
        // Backup manager (design.md §6). Added in code: distro_menu.xml
        // is not in this checkout, and the manager needs no new menu XML.
        menu.add(Menu.NONE, MENU_BACKUP_MANAGER, Menu.NONE, R.string.backup_manager_title)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_refresh_distro_list) {
            refreshVersionsInBackground();
            return true;
        }
        if (item.getItemId() == MENU_BACKUP_MANAGER) {
            showBackupManager();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ------------------------------------------------------------------
    // `distro` shell command
    // ------------------------------------------------------------------

    /** Write the `distro` shell command to $PREFIX/bin if it is missing or outdated. */
    public static void ensureDistroCommand() {
        try {
            File binDir = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH);
            if (!binDir.isDirectory()) return; // bootstrap not ready yet
            File commandFile = new File(binDir, DISTRO_COMMAND_FILE_NAME);
            String existing = null;
            if (commandFile.isFile()) {
                existing = DistroUtils.readFile(commandFile);
            }
            if (DISTRO_COMMAND_CONTENT.equals(existing)) return;
            try (FileOutputStream out = new FileOutputStream(commandFile)) {
                out.write(DISTRO_COMMAND_CONTENT.getBytes(StandardCharsets.UTF_8));
            }
            commandFile.setExecutable(true, true);
            commandFile.setReadable(true, true);
            commandFile.setWritable(true, true);
        } catch (Exception e) {
            Log.w(LOG_TAG, "Failed to install `distro` command", e);
        }
    }

    // ------------------------------------------------------------------
    // Installed containers
    // ------------------------------------------------------------------

    /** Installed containers, from the proot-distro v5 containers dir (plus the legacy dir). */
    private void refreshInstalledContainers() {
        mInstalledContainers = DistroUtils.scanInstalledContainers();
    }

    private List<String> installedContainersFor(DistroDef distro) {
        List<String> result = new ArrayList<>();
        for (String container : mInstalledContainers) {
            if (container.equals(distro.containerPrefix)
                || container.startsWith(distro.containerPrefix + "-")) {
                result.add(container);
            }
        }
        Collections.sort(result);
        return result;
    }

    private String displayNameForContainer(String container) {
        String display = mContainerDisplayNames.get(container);
        return display != null ? display : container;
    }

    // ------------------------------------------------------------------
    // Model-config re-apply prompt after a distro reinstall
    // ------------------------------------------------------------------

    /** After a reinstall the apply records survive (keyed by container
     * name) but the container's files are gone, so the model-config list
     * would only show "未应用". Prompt once per container per activity
     * lifetime to re-apply; never auto-apply (design §11). Installs run
     * in a terminal session with no callback, so like the mirror sync
     * this is detected on resume with a fresh container scan. */
    private void checkModelConfigReapply() {
        Set<String> installed = DistroUtils.scanInstalledContainers();
        if (installed.isEmpty()) return;
        final Map<String, List<ModelConfigStore.Entry>> toReapply = new LinkedHashMap<>();
        final List<String> names = new ArrayList<>();
        for (String container : installed) {
            if (mReapplyPromptedContainers.contains(container)) continue;
            File rootfs = DistroUtils.containerRootfs(container);
            List<ModelConfigStore.Entry> entries =
                ModelConfigApplier.entriesNeedingReapply(this, rootfs, container);
            if (!entries.isEmpty()) {
                toReapply.put(container, entries);
                names.add(displayNameForContainer(container));
            }
        }
        if (toReapply.isEmpty()) return;
        mReapplyPromptedContainers.addAll(toReapply.keySet());
        final String joined = joinWithComma(names);
        runOnUiThread(() -> new AlertDialog.Builder(DistroActivity.this)
            .setTitle(R.string.modelconfig_reapply_title)
            .setMessage(getString(R.string.modelconfig_reapply_message, joined))
            .setPositiveButton(R.string.modelconfig_apply, (dialog, which) ->
                new Thread(() -> reapplyModelConfigs(toReapply)).start())
            .setNegativeButton(android.R.string.cancel, null)
            .show());
    }

    /** Re-apply every recorded entry to its container, on a background
     * thread. Entries whose key can no longer be decrypted are skipped
     * (the user re-enters the key in the model-config screen). */
    private void reapplyModelConfigs(Map<String, List<ModelConfigStore.Entry>> toReapply) {
        String error = null;
        for (Map.Entry<String, List<ModelConfigStore.Entry>> e : toReapply.entrySet()) {
            for (ModelConfigStore.Entry entry : e.getValue()) {
                String apiKey = entry.getApiKey();
                if (apiKey == null || apiKey.isEmpty()) {
                    error = getString(R.string.modelconfig_key_invalid);
                    continue;
                }
                ModelConfigApplier.ApplyResult result = ModelConfigApplier.applyEntry(
                    DistroActivity.this, entry, apiKey,
                    Collections.singletonList(e.getKey()));
                if (!result.errors.isEmpty()) error = result.errors.get(0);
            }
        }
        final String failure = error;
        final int distros = toReapply.size();
        runOnUiThread(() -> {
            if (failure == null) {
                Toast.makeText(DistroActivity.this,
                    getString(R.string.modelconfig_applied_done, distros),
                    Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(DistroActivity.this,
                    getString(R.string.modelconfig_apply_failed, failure),
                    Toast.LENGTH_LONG).show();
            }
        });
    }

    // ------------------------------------------------------------------
    // Version list: cache + Docker Hub
    // ------------------------------------------------------------------

    private void loadCachedVersions() {
        File cacheFile = new File(TermuxConstants.TERMUX_DATA_HOME_DIR, DistroUtils.CACHE_FILE_NAME);
        if (!cacheFile.isFile()) return;
        try {
            JSONObject root = new JSONObject(DistroUtils.readFile(cacheFile));
            mCacheUpdatedAt = root.optLong("updatedAt", 0);
            JSONObject versions = root.optJSONObject("versions");
            if (versions == null) return;
            for (DistroDef distro : DISTROS) {
                JSONArray array = versions.optJSONArray(distro.id);
                if (array == null) continue;
                List<VersionOption> options = new ArrayList<>();
                for (int i = 0; i < array.length(); i++) {
                    JSONObject option = array.optJSONObject(i);
                    if (option == null) continue;
                    String display = option.optString("d", null);
                    String imageRef = option.optString("r", null);
                    if (display == null || imageRef == null) continue;
                    options.add(new VersionOption(display, imageRef, option.optString("u", "")));
                }
                if (!options.isEmpty()) {
                    mVersions.put(distro.id, options);
                }
            }
            mContainerDisplayNames.putAll(DistroUtils.loadCachedDisplayNames());
        } catch (Exception e) {
            // Corrupt cache: ignore it, the background refresh rebuilds it.
            Log.w(LOG_TAG, "Failed to read distro list cache", e);
            mCacheUpdatedAt = 0;
        }
    }

    private void saveCachedVersions() {
        try {
            JSONObject root = new JSONObject();
            root.put("updatedAt", System.currentTimeMillis());
            JSONObject versions = new JSONObject();
            for (DistroDef distro : DISTROS) {
                List<VersionOption> options = mVersions.get(distro.id);
                if (options == null || options.isEmpty()) continue;
                JSONArray array = new JSONArray();
                for (VersionOption option : options) {
                    JSONObject object = new JSONObject();
                    object.put("d", option.display);
                    object.put("r", option.imageRef);
                    if (option.updated != null && !option.updated.isEmpty()) {
                        object.put("u", option.updated);
                    }
                    array.put(object);
                }
                versions.put(distro.id, array);
            }
            root.put("versions", versions);
            File cacheFile = new File(TermuxConstants.TERMUX_DATA_HOME_DIR, DistroUtils.CACHE_FILE_NAME);
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
            Log.w(LOG_TAG, "Failed to save distro list cache", e);
        }
    }

    private void refreshVersionsInBackground() {
        mListLoading = true;
        updateStatus(getString(R.string.distro_status_loading));
        new Thread() {
            @Override
            public void run() {
                boolean anyUpdated = false;
                for (final DistroDef distro : DISTROS) {
                    final List<VersionOption> options = fetchVersionsForDistro(distro);
                    if (options == null || options.isEmpty()) continue;
                    anyUpdated = true;
                    runOnUiThread(() -> {
                        mVersions.put(distro.id, options);
                        for (VersionOption option : options) {
                            mContainerDisplayNames.put(option.container,
                                distro.displayName + " " + option.display);
                        }
                        // Show each distro's versions as soon as they arrive,
                        // instead of waiting for the slowest fetch.
                        updateListRows(getString(R.string.distro_status_loading));
                    });
                }
                final boolean updated = anyUpdated;
                runOnUiThread(() -> {
                    if (updated) saveCachedVersions();
                    mListLoading = false;
                    refreshInstalledContainers();
                    if (updated) {
                        updateListRows(getString(R.string.distro_status_updated,
                            formatTime(System.currentTimeMillis())));
                    } else {
                        updateListRows(getString(R.string.distro_status_offline));
                    }
                });
            }
        }.start();
    }

    /** One Docker Hub tag: its name and when its image was last updated (ISO 8601, may be ""). */
    private static class TagInfo {
        final String name;
        final String lastUpdated;

        TagInfo(String name, String lastUpdated) {
            this.name = name;
            this.lastUpdated = lastUpdated;
        }
    }

    private List<VersionOption> fetchVersionsForDistro(DistroDef distro) {
        List<VersionOption> options = new ArrayList<>();
        for (TagSource source : distro.sources) {
            List<TagInfo> tags = null;
            for (int attempt = 1; attempt <= DOCKER_HUB_FETCH_ATTEMPTS; attempt++) {
                try {
                    tags = fetchDockerHubTags(source.image);
                    break;
                } catch (Exception e) {
                    Log.w(LOG_TAG, "Failed to fetch tags for " + source.image
                        + " (attempt " + attempt + ")", e);
                }
            }
            if (tags != null) options.addAll(pickVersions(source, tags));
        }
        return options;
    }

    /** One page of tags from the Docker Hub API (most recently updated first). */
    private List<TagInfo> fetchDockerHubTags(String image) throws IOException, JSONException {
        URL url = new URL("https://hub.docker.com/v2/repositories/" + image
            + "/tags?page_size=" + DOCKER_HUB_TAGS_PAGE_SIZE);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(DOCKER_HUB_CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(DOCKER_HUB_READ_TIMEOUT_MS);
        connection.setRequestProperty("User-Agent", "termux-app-zh-distro/1.0");
        try {
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("Docker Hub returned HTTP " + status + " for " + image);
            }
            String body = readStream(connection.getInputStream());
            JSONObject root = new JSONObject(body);
            JSONArray results = root.getJSONArray("results");
            List<TagInfo> tags = new ArrayList<>();
            for (int i = 0; i < results.length(); i++) {
                JSONObject tag = results.getJSONObject(i);
                tags.add(new TagInfo(tag.getString("name"), tag.optString("last_updated", "")));
            }
            return tags;
        } finally {
            connection.disconnect();
        }
    }

    private List<VersionOption> pickVersions(TagSource source, List<TagInfo> tags) {
        List<VersionOption> options = new ArrayList<>();
        List<String> names = new ArrayList<>();
        Map<String, String> updatedDates = new HashMap<>();
        for (TagInfo tag : tags) {
            names.add(tag.name);
            if (tag.lastUpdated.length() >= 10) {
                updatedDates.put(tag.name, tag.lastUpdated.substring(0, 10));
            }
        }
        switch (source.filter) {
            case ROLLING:
                if (names.contains("latest")) {
                    options.add(new VersionOption(source.displayPrefix + mLatestLabel,
                        source.image + ":latest", updatedDates.get("latest")));
                }
                break;
            case DOTTED_VERSION: {
                List<String> versions = freshVersions(tags, "\\d+\\.\\d+");
                for (int i = 0; i < Math.min(3, versions.size()); i++) {
                    String version = versions.get(i);
                    options.add(new VersionOption(source.displayPrefix + version,
                        source.image + ":" + version, updatedDates.get(version)));
                }
                break;
            }
            case INTEGER_VERSION: {
                List<String> versions = freshVersions(tags, "\\d+");
                for (int i = 0; i < Math.min(3, versions.size()); i++) {
                    String version = versions.get(i);
                    options.add(new VersionOption(source.displayPrefix + version,
                        source.image + ":" + version, updatedDates.get(version)));
                }
                break;
            }
            case DEBIAN_CODENAME: {
                Set<String> coveredVersions = new HashSet<>();
                for (String codename : DEBIAN_CODENAMES) {
                    if (!names.contains(codename)) continue;
                    String version = DEBIAN_CODENAME_VERSIONS.get(codename);
                    String display = version != null ? codename + " (" + version + ")" : codename;
                    options.add(new VersionOption(source.displayPrefix + display,
                        source.image + ":" + codename, updatedDates.get(codename)));
                    if (version != null) coveredVersions.add(version);
                }
                // Numeric tags not already covered by a codename, newest first.
                List<Integer> numerics = new ArrayList<>();
                for (String tag : names) {
                    if (tag.matches("\\d+")) numerics.add(Integer.parseInt(tag));
                }
                Collections.sort(numerics, Collections.reverseOrder());
                for (int numeric : numerics) {
                    if (options.size() >= 4) break;
                    if (coveredVersions.contains(String.valueOf(numeric))) continue;
                    String version = String.valueOf(numeric);
                    options.add(new VersionOption(source.displayPrefix + version,
                        source.image + ":" + version, updatedDates.get(version)));
                }
                break;
            }
        }
        return options;
    }

    /**
     * Tag names matching the pattern, dropping images not updated in the last
     * STALE_TAG_YEARS years (e.g. openSUSE Leap 42.3, untouched since 2019),
     * newest version first. If the freshness filter leaves nothing, the
     * unfiltered list is used so a stale mirror never hides every version.
     */
    private List<String> freshVersions(List<TagInfo> tags, String pattern) {
        String cutoff = freshnessCutoffDate();
        List<String> fresh = new ArrayList<>();
        List<String> all = new ArrayList<>();
        for (TagInfo tag : tags) {
            if (!tag.name.matches(pattern)) continue;
            all.add(tag.name);
            if (tag.lastUpdated.length() < 10
                || tag.lastUpdated.substring(0, 10).compareTo(cutoff) >= 0) {
                fresh.add(tag.name);
            }
        }
        List<String> versions = fresh.isEmpty() ? all : fresh;
        sortVersionStringsDescending(versions);
        return versions;
    }

    /** Today minus STALE_TAG_YEARS, as yyyy-MM-dd (lexicographic compare works for ISO dates). */
    private static String freshnessCutoffDate() {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.YEAR, -STALE_TAG_YEARS);
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.getTime());
    }

    /**
     * Last-known versions, bundled in the app so version numbers are always
     * shown even when the network fetch fails and there is no cache yet.
     * These are real image refs (e.g. ubuntu:26.04) that proot-distro can
     * install; the live fetch replaces them whenever it succeeds.
     * Last verified 2026-11-20.
     */
    private List<VersionOption> fallbackOptions(DistroDef distro) {
        List<VersionOption> options = new ArrayList<>();
        switch (distro.id) {
            case "ubuntu":
                options.add(new VersionOption("26.10", "ubuntu:26.10", "2026-11-20"));
                options.add(new VersionOption("26.04", "ubuntu:26.04", "2026-11-16"));
                options.add(new VersionOption("25.10", "ubuntu:25.10", "2026-06-19"));
                break;
            case "debian":
                options.add(new VersionOption("trixie (13)", "debian:trixie", "2026-11-05"));
                options.add(new VersionOption("bookworm (12)", "debian:bookworm", "2026-11-05"));
                break;
            case "arch":
                options.add(new VersionOption(mLatestLabel, "menci/archlinuxarm:latest", "2026-11-23"));
                break;
            case "alpine":
                options.add(new VersionOption("3.24", "alpine:3.24", "2026-11-08"));
                options.add(new VersionOption("3.23", "alpine:3.23", "2026-11-08"));
                options.add(new VersionOption("3.22", "alpine:3.22", "2026-11-08"));
                break;
            case "fedora":
                options.add(new VersionOption("46", "fedora:46", "2026-11-02"));
                options.add(new VersionOption("45", "fedora:45", "2026-11-02"));
                options.add(new VersionOption("44", "fedora:44", "2026-11-02"));
                break;
            case "opensuse":
                options.add(new VersionOption("Leap 16.1", "opensuse/leap:16.1", "2026-11-19"));
                options.add(new VersionOption("Leap 16.0", "opensuse/leap:16.0", "2026-11-06"));
                options.add(new VersionOption("Leap 15.6", "opensuse/leap:15.6", "2026-04-27"));
                options.add(new VersionOption("Tumbleweed " + mLatestLabel,
                    "opensuse/tumbleweed:latest", "2026-11-20"));
                break;
            default:
                for (TagSource source : distro.sources) {
                    options.add(new VersionOption(source.displayPrefix + mLatestLabel,
                        source.image + ":latest"));
                }
                break;
        }
        return options;
    }

    /** Fetched versions when available, otherwise the bundled last-known versions. */
    private List<VersionOption> optionsFor(DistroDef distro) {
        List<VersionOption> options = mVersions.get(distro.id);
        if (options == null || options.isEmpty()) options = fallbackOptions(distro);
        for (VersionOption option : options) {
            if (!mContainerDisplayNames.containsKey(option.container)) {
                mContainerDisplayNames.put(option.container,
                    distro.displayName + " " + option.display);
            }
        }
        return options;
    }

    private static void sortVersionStringsDescending(List<String> versions) {
        Collections.sort(versions, (a, b) -> {
            String[] partsA = a.split("\\.");
            String[] partsB = b.split("\\.");
            int length = Math.max(partsA.length, partsB.length);
            for (int i = 0; i < length; i++) {
                int numberA = i < partsA.length ? parseIntOrZero(partsA[i]) : 0;
                int numberB = i < partsB.length ? parseIntOrZero(partsB[i]) : 0;
                if (numberA != numberB) return Integer.compare(numberB, numberA);
            }
            return 0;
        });
    }

    private static int parseIntOrZero(String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String readStream(InputStream inputStream) throws IOException {
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                out.append(line);
            }
        }
        return out.toString();
    }

    // ------------------------------------------------------------------
    // List rows
    // ------------------------------------------------------------------

    private void updateListRows(String status) {
        List<DistroRow> rows = new ArrayList<>();
        for (DistroDef distro : DISTROS) {
            rows.add(new DistroRow(distro.displayName, subtitleFor(distro)));
        }
        mAdapter.clear();
        mAdapter.addAll(rows);
        updateStatus(status);
    }

    private String subtitleFor(DistroDef distro) {
        List<String> installed = installedContainersFor(distro);
        if (!installed.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (String container : installed) names.add(displayNameForContainer(container));
            return getString(R.string.distro_installed, joinWithComma(names));
        }
        List<VersionOption> options = optionsFor(distro);
        if (!options.isEmpty()) {
            List<String> versions = new ArrayList<>();
            for (VersionOption option : options) versions.add(option.display);
            String subtitle = getString(R.string.distro_available_versions, joinWithComma(versions));
            String updated = options.get(0).updated;
            if (updated != null && !updated.isEmpty()) {
                subtitle += " · " + getString(R.string.distro_latest_updated, updated);
            }
            return subtitle;
        }
        if (mListLoading) return getString(R.string.distro_status_loading);
        return distro.note;
    }

    private void updateStatus(String status) {
        if (mStatusView != null) mStatusView.setText(status);
    }

    private static String joinWithComma(List<String> items) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) out.append(", ");
            out.append(items.get(i));
        }
        return out.toString();
    }

    private static String joinWithSpace(List<String> items) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) out.append(' ');
            out.append(items.get(i));
        }
        return out.toString();
    }

    private static String formatTime(long timeMs) {
        return new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(new Date(timeMs));
    }

    // ------------------------------------------------------------------
    // Dialogs
    // ------------------------------------------------------------------

    /** Distro with at least one installed container: enter / install tools / install more. */
    private void showDistroDialog(final DistroDef distro) {
        List<String> items = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();
        for (final String container : installedContainersFor(distro)) {
            final String display = displayNameForContainer(container);
            items.add(getString(R.string.distro_enter, display));
            actions.add(() -> startLoginSession(container, display));
            items.add(getString(R.string.distro_install_tools_for, display));
            actions.add(() -> showToolsDialog(distro, container, display));
            items.add(getString(R.string.distro_install_ai_agents_for, display));
            actions.add(() -> openAiAgents(distro, container, display));
            List<AiAgentUtils.InstalledAgent> quickAgents =
                AiAgentUtils.detectInstalledAgentsForContainer(container);
            if (!quickAgents.isEmpty()) {
                items.add(getString(R.string.distro_quick_start_for, display));
                actions.add(() -> showQuickStartDialog(distro, container, display, quickAgents));
            }
            items.add(getString(R.string.distro_uninstall_for, display));
            actions.add(() -> startUninstallFlow(container, display));
            items.add(getString(R.string.distro_backup_for, display));
            actions.add(() -> startBackupFlow(container, display));
            items.add(getString(R.string.restore_from_backup_for, display));
            actions.add(() -> startRestoreFlow(container, display));
        }
        items.add(getString(R.string.distro_install_new_version));
        actions.add(() -> showVersionDialog(distro));
        new AlertDialog.Builder(this)
            .setTitle(distro.displayName)
            .setItems(items.toArray(new String[0]), (dialog, which) -> actions.get(which).run())
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /** Open the options dialog for one container (from the drawer long-press). */
    private void showDistroDialogForContainer(String container) {
        if (!mInstalledContainers.contains(container)) return;
        String distroId = DistroUtils.distroIdForContainer(container);
        if (distroId == null) return;
        for (DistroDef distro : DISTROS) {
            if (distro.id.equals(distroId)) {
                showDistroDialog(distro);
                return;
            }
        }
    }

    // ------------------------------------------------------------------
    // Distro uninstall (design.md §2-3): backup (optional, default off)
    // + `proot-distro remove` in a visible terminal session, then host
    // metadata cleanup on the next resume (sessions have no callback).
    // ------------------------------------------------------------------

    /** "卸载 <display>" tapped: compute the backup sizes off the UI
     * thread (a du over a whole rootfs can take seconds), then show the
     * confirm dialog. */
    private void startUninstallFlow(final String container, final String display) {
        Toast.makeText(this, R.string.distro_uninstall_computing_sizes, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            final BackupSizes sizes = computeBackupSizes(container);
            runOnUiThread(() -> showUninstallConfirmDialog(container, display, sizes));
        }).start();
    }

    /** Sizes for the backup picker, computed off the UI thread (a du
     * over a whole rootfs can take seconds). Shared by the uninstall
     * and standalone backup flows. */
    private static final class BackupSizes {
        final File rootfs;
        final List<AiAgentUtils.InstalledAgent> agents;
        final long systemSize;
        final long homeSize;
        final Map<String, Long> agentSizes;

        BackupSizes(File rootfs, List<AiAgentUtils.InstalledAgent> agents,
                long systemSize, long homeSize, Map<String, Long> agentSizes) {
            this.rootfs = rootfs;
            this.agents = agents;
            this.systemSize = systemSize;
            this.homeSize = homeSize;
            this.agentSizes = agentSizes;
        }
    }

    private static BackupSizes computeBackupSizes(String container) {
        File rootfs = DistroUtils.containerRootfs(container);
        List<AiAgentUtils.InstalledAgent> agents =
            AiAgentUtils.detectInstalledAgentsForContainer(container);
        long systemSize = rootfs == null ? 0 : DistroUtils.systemBackupSize(rootfs);
        long homeSize = rootfs == null ? 0 : DistroUtils.homeBackupSize(rootfs);
        Map<String, Long> agentSizes = new LinkedHashMap<>();
        for (AiAgentUtils.InstalledAgent agent : agents) {
            AiAgentUtils.AgentDef def = AiAgentUtils.findDef(agent.agentId);
            agentSizes.put(agent.agentId,
                (rootfs == null || def == null) ? 0 : DistroUtils.agentPathsSize(rootfs, def));
        }
        return new BackupSizes(rootfs, agents, systemSize, homeSize, agentSizes);
    }

    /** The backup picker shared by the uninstall confirm dialog and
     * the standalone backup dialog, so the two cannot drift apart:
     * destination radios (defaulting to the "默认备份位置" setting),
     * the external folder line with a "更改" button, and the per-item
     * checklist — system first, then user files, then each installed
     * agent, default ALL CHECKED, with sizes — plus a live total-size
     * line, a free-space line and an over-space warning. The owning
     * dialog sets onTotalsChanged to re-evaluate its positive button
     * whenever the totals change. */
    private final class BackupPicker {
        final LinearLayout view;
        final RadioButton internalRadio;
        final RadioButton externalRadio;
        final TextView folderStatus;
        final android.widget.Button changeFolderButton;
        final CheckBox systemCheck;
        final CheckBox homeCheck;
        final Map<String, CheckBox> agentChecks;
        final TextView totalView;
        final TextView freeSpaceView;
        final TextView spaceWarningView;
        final long systemSize;
        final long homeSize;
        final Map<String, Long> agentSizes;
        Runnable onTotalsChanged;
        private boolean overSpace;

        BackupPicker(long systemSize, long homeSize,
                List<AiAgentUtils.InstalledAgent> agents,
                Map<String, Long> agentSizes) {
            this.systemSize = systemSize;
            this.homeSize = homeSize;
            this.agentSizes = agentSizes;
            view = new LinearLayout(DistroActivity.this);
            view.setOrientation(LinearLayout.VERTICAL);
            TextView itemsTitle = new TextView(DistroActivity.this);
            itemsTitle.setText(R.string.distro_uninstall_backup_items_title);
            view.addView(itemsTitle);
            // Backup destination (design.md §6): Termux internal
            // storage (default) or the external SAF folder. The choice
            // defaults to the "默认备份位置" setting from the backup
            // manager. Picking external with no folder set yet opens
            // the system folder picker first (see onActivityResult);
            // the backup itself is still packed internally by the
            // terminal-session script and copied to external storage
            // afterwards (see copyPendingExternalBackup).
            TextView destinationTitle = new TextView(DistroActivity.this);
            destinationTitle.setText(R.string.backup_external_destination_title);
            view.addView(destinationTitle);
            RadioGroup destinationGroup = new RadioGroup(DistroActivity.this);
            destinationGroup.setOrientation(RadioGroup.VERTICAL);
            internalRadio = new RadioButton(DistroActivity.this);
            internalRadio.setId(View.generateViewId());
            internalRadio.setText(R.string.backup_external_location_internal);
            externalRadio = new RadioButton(DistroActivity.this);
            externalRadio.setId(View.generateViewId());
            externalRadio.setText(R.string.backup_external_location_external);
            destinationGroup.addView(internalRadio);
            destinationGroup.addView(externalRadio);
            if (isExternalBackupDefault()) {
                externalRadio.setChecked(true);
            } else {
                internalRadio.setChecked(true);
            }
            view.addView(destinationGroup);
            // External folder line + "更改" button, on one row. The
            // button only shows when external storage is selected and
            // a folder is already set (see refreshDestinationLabel).
            LinearLayout folderRow = new LinearLayout(DistroActivity.this);
            folderRow.setOrientation(LinearLayout.HORIZONTAL);
            folderStatus = new TextView(DistroActivity.this);
            folderStatus.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            folderRow.addView(folderStatus);
            changeFolderButton = new android.widget.Button(DistroActivity.this);
            changeFolderButton.setText(R.string.backup_external_change_folder);
            changeFolderButton.setOnClickListener(v -> {
                mBackupFolderPickPending = true;
                ExternalBackupStore.pickExternalFolder(DistroActivity.this);
            });
            folderRow.addView(changeFolderButton);
            view.addView(folderRow);
            destinationGroup.setOnCheckedChangeListener((group, checkedId) -> {
                if (checkedId == externalRadio.getId()
                    && !ExternalBackupStore.hasExternalFolder(DistroActivity.this)) {
                    mBackupFolderPickPending = true;
                    ExternalBackupStore.pickExternalFolder(DistroActivity.this);
                }
                refreshDestinationLabel();
            });
            systemCheck = new CheckBox(DistroActivity.this);
            systemCheck.setText(getString(R.string.distro_backup_system)
                + " (" + Formatter.formatShortFileSize(DistroActivity.this, systemSize) + ")");
            systemCheck.setChecked(true);
            systemCheck.setOnCheckedChangeListener((button, checked) -> updateTotals());
            view.addView(systemCheck);
            homeCheck = new CheckBox(DistroActivity.this);
            homeCheck.setText(getString(R.string.distro_uninstall_backup_home)
                + " (" + Formatter.formatShortFileSize(DistroActivity.this, homeSize) + ")");
            homeCheck.setChecked(true);
            homeCheck.setOnCheckedChangeListener((button, checked) -> updateTotals());
            view.addView(homeCheck);
            agentChecks = new LinkedHashMap<>();
            for (AiAgentUtils.InstalledAgent agent : agents) {
                CheckBox checkBox = new CheckBox(DistroActivity.this);
                Long size = agentSizes.get(agent.agentId);
                checkBox.setText(agent.displayName + " ("
                    + Formatter.formatShortFileSize(DistroActivity.this,
                        size == null ? 0 : size) + ")");
                checkBox.setChecked(true);
                checkBox.setOnCheckedChangeListener((button, checked) -> updateTotals());
                view.addView(checkBox);
                agentChecks.put(agent.agentId, checkBox);
            }
            totalView = new TextView(DistroActivity.this);
            view.addView(totalView);
            freeSpaceView = new TextView(DistroActivity.this);
            view.addView(freeSpaceView);
            spaceWarningView = new TextView(DistroActivity.this);
            spaceWarningView.setVisibility(View.GONE);
            view.addView(spaceWarningView);
            refreshDestinationLabel();
        }

        /** Item ids currently checked, in checklist order: system,
         * home, then agents. */
        List<String> selectedItemIds() {
            List<String> selected = new ArrayList<>();
            if (systemCheck.isChecked()) selected.add(DistroBackup.SYSTEM_ITEM_ID);
            if (homeCheck.isChecked()) selected.add(DistroBackup.HOME_ITEM_ID);
            for (Map.Entry<String, CheckBox> e : agentChecks.entrySet()) {
                if (e.getValue().isChecked()) {
                    selected.add(DistroBackup.agentItemId(e.getKey()));
                }
            }
            return selected;
        }

        boolean isExternal() {
            return externalRadio.isChecked();
        }

        boolean isOverSpace() {
            return overSpace;
        }

        /** Folder line + "更改" button for the current destination,
         * then the totals (the destination changes the free space). */
        void refreshDestinationLabel() {
            boolean external = externalRadio.isChecked();
            if (!external) {
                folderStatus.setText(
                    R.string.distro_uninstall_backup_destination_internal);
            } else if (ExternalBackupStore.hasExternalFolder(DistroActivity.this)) {
                String path = ExternalBackupStore.displayFolderPath(DistroActivity.this);
                if (path == null) {
                    Uri uri = ExternalBackupStore.getExternalFolderUri(DistroActivity.this);
                    path = uri == null ? "" : uri.toString();
                }
                folderStatus.setText(getString(
                    R.string.backup_external_folder_chosen, path));
            } else {
                folderStatus.setText(R.string.backup_external_folder_not_set);
            }
            changeFolderButton.setVisibility(external
                && ExternalBackupStore.hasExternalFolder(DistroActivity.this)
                ? View.VISIBLE : View.GONE);
            updateTotals();
        }

        /** Recompute the total of the checked items and the free space
         * for the chosen destination; show the over-space warning and
         * remember it for the dialog positive button. External backups
         * are packed internally first, so they temporarily need about
         * twice the space in Termux internal storage. */
        void updateTotals() {
            long total = 0;
            if (systemCheck.isChecked()) total += systemSize;
            if (homeCheck.isChecked()) total += homeSize;
            for (Map.Entry<String, CheckBox> e : agentChecks.entrySet()) {
                if (e.getValue().isChecked()) {
                    Long size = agentSizes.get(e.getKey());
                    total += size == null ? 0 : size;
                }
            }
            File internalRoot = DistroBackup.internalBackupRoot(DistroActivity.this);
            long internalFree = DistroUtils.usableSpace(internalRoot);
            boolean external = externalRadio.isChecked();
            long externalFree = external
                ? DistroUtils.externalStorageUsableSpace() : -1;
            totalView.setText(getString(R.string.distro_backup_total_size,
                Formatter.formatShortFileSize(DistroActivity.this, total)));
            long shownFree = external ? externalFree : internalFree;
            if (shownFree >= 0) {
                freeSpaceView.setText(getString(R.string.distro_backup_free_space,
                    Formatter.formatShortFileSize(DistroActivity.this, shownFree)));
                freeSpaceView.setVisibility(View.VISIBLE);
            } else {
                freeSpaceView.setVisibility(View.GONE);
            }
            overSpace = false;
            String warning = null;
            if (external) {
                if (internalFree >= 0 && total * 2 > internalFree) {
                    overSpace = true;
                    warning = getString(R.string.distro_backup_external_double_space,
                        Formatter.formatShortFileSize(DistroActivity.this, total * 2));
                } else if (externalFree >= 0 && total > externalFree) {
                    overSpace = true;
                    warning = getString(R.string.distro_backup_not_enough_space);
                }
            } else if (internalFree >= 0 && total > internalFree) {
                overSpace = true;
                warning = getString(R.string.distro_backup_not_enough_space);
            }
            if (warning != null) {
                spaceWarningView.setText(warning);
                spaceWarningView.setVisibility(View.VISIBLE);
            } else {
                spaceWarningView.setVisibility(View.GONE);
            }
            if (onTotalsChanged != null) onTotalsChanged.run();
        }
    }

    /** Refresh the open backup picker dialog's destination line (and
     * its totals: the destination changes the free space). No-op when
     * no backup picker dialog is open. */
    private void updateBackupDestinationLabel() {
        if (mActiveBackupPicker != null) mActiveBackupPicker.refreshDestinationLabel();
    }

    /** Uninstall confirm dialog: whole-environment warning, the agents
     * that will be deleted too, a "先备份数据" switch (default OFF) and,
     * when it is on, a per-item checklist (user files + each installed
     * agent, default ALL CHECKED, with sizes). */
    private void showUninstallConfirmDialog(final String container,
            final String display, final BackupSizes sizes) {
        if (isFinishing() || isDestroyed()) return;
        if (sizes.rootfs == null) {
            // The container is already gone (e.g. removed from a terminal):
            // just refresh the list.
            Toast.makeText(this, R.string.distro_uninstall_rootfs_missing, Toast.LENGTH_LONG).show();
            refreshInstalledContainers();
            updateListRows(getString(R.string.distro_status_updated,
                formatTime(System.currentTimeMillis())));
            return;
        }

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad / 2, pad, pad / 2);

        TextView warning = new TextView(this);
        warning.setText(R.string.distro_uninstall_warning);
        layout.addView(warning);

        TextView agentsLine = new TextView(this);
        if (sizes.agents.isEmpty()) {
            agentsLine.setText(R.string.distro_uninstall_no_agents);
        } else {
            List<String> names = new ArrayList<>();
            for (AiAgentUtils.InstalledAgent agent : sizes.agents) names.add(agent.displayName);
            agentsLine.setText(getString(R.string.distro_uninstall_agents_will_delete,
                joinWithComma(names)));
        }
        layout.addView(agentsLine);

        final Switch backupSwitch = new Switch(this);
        backupSwitch.setText(R.string.distro_uninstall_backup_switch);
        backupSwitch.setChecked(false);
        layout.addView(backupSwitch);

        final LinearLayout checklist = new LinearLayout(this);
        checklist.setOrientation(LinearLayout.VERTICAL);
        checklist.setVisibility(View.GONE);
        // Shared backup picker (see BackupPicker): destination, folder
        // line, per-item checklist, live total + free space.
        final BackupPicker picker = new BackupPicker(sizes.systemSize, sizes.homeSize,
            sizes.agents, sizes.agentSizes);
        checklist.addView(picker.view);
        layout.addView(checklist);
        backupSwitch.setOnCheckedChangeListener((button, checked) -> {
            checklist.setVisibility(checked ? View.VISIBLE : View.GONE);
            picker.updateTotals();
        });

        ScrollView scroll = new ScrollView(this);
        scroll.addView(layout);
        final AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(getString(R.string.distro_uninstall_for, display))
            .setView(scroll)
            // Positive button is overridden below so that confirming with
            // backup on but nothing checked shows an error instead of
            // dismissing the dialog.
            .setPositiveButton(R.string.distro_uninstall_confirm, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create();
        mActiveBackupPicker = picker;
        // Not enough free space disables the confirm button, but only
        // while backup is on (with backup off there is nothing to pack).
        picker.onTotalsChanged = () -> {
            android.widget.Button positive =
                dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (positive != null) {
                positive.setEnabled(!backupSwitch.isChecked() || !picker.isOverSpace());
            }
        };
        dialog.setOnShowListener(d -> {
            picker.updateTotals();
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            .setOnClickListener(v -> {
                boolean backup = backupSwitch.isChecked();
                List<String> selected = new ArrayList<>();
                if (backup) {
                    selected.addAll(picker.selectedItemIds());
                    if (selected.isEmpty()) {
                        Toast.makeText(DistroActivity.this,
                            R.string.distro_uninstall_backup_none_selected,
                            Toast.LENGTH_SHORT).show();
                        return;
                    }
                }
                boolean backupToExternal = backup && picker.isExternal();
                if (backupToExternal
                    && !ExternalBackupStore.hasExternalFolder(DistroActivity.this)) {
                    // External chosen but no folder (e.g. the picker was
                    // cancelled): stay in the dialog, do not start.
                    Toast.makeText(DistroActivity.this,
                        R.string.backup_external_folder_not_set,
                        Toast.LENGTH_LONG).show();
                    return;
                }
                dialog.dismiss();
                startUninstallSession(container, display, sizes.rootfs, backup, selected,
                    backupToExternal);
            });
        });
        dialog.setOnDismissListener(d -> {
            if (mActiveBackupPicker == picker) mActiveBackupPicker = null;
            mBackupFolderPickPending = false;
        });
        dialog.show();
    }

    // ------------------------------------------------------------------
    // Standalone backup (no uninstall): same picker, backup-only script.
    // ------------------------------------------------------------------

    /** "备份 <display>" tapped: same size computation as the uninstall
     * flow, then the shared picker in standalone mode (no warning, no
     * switch — backing up is the point). */
    private void startBackupFlow(final String container, final String display) {
        Toast.makeText(this, R.string.distro_uninstall_computing_sizes, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            final BackupSizes sizes = computeBackupSizes(container);
            runOnUiThread(() -> showBackupConfirmDialog(container, display, sizes));
        }).start();
    }

    /** Standalone backup dialog: the shared picker directly, with
     * title "备份 <display>". */
    private void showBackupConfirmDialog(final String container,
            final String display, final BackupSizes sizes) {
        if (isFinishing() || isDestroyed()) return;
        if (sizes.rootfs == null) {
            Toast.makeText(this, R.string.distro_uninstall_rootfs_missing, Toast.LENGTH_LONG).show();
            refreshInstalledContainers();
            updateListRows(getString(R.string.distro_status_updated,
                formatTime(System.currentTimeMillis())));
            return;
        }
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad / 2, pad, pad / 2);
        final BackupPicker picker = new BackupPicker(sizes.systemSize, sizes.homeSize,
            sizes.agents, sizes.agentSizes);
        layout.addView(picker.view);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(layout);
        final AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(getString(R.string.distro_backup_title, display))
            .setView(scroll)
            // Positive button is overridden below so that confirming
            // with nothing checked shows an error instead of dismissing
            // the dialog.
            .setPositiveButton(R.string.distro_backup_confirm, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create();
        mActiveBackupPicker = picker;
        picker.onTotalsChanged = () -> {
            android.widget.Button positive =
                dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (positive != null) positive.setEnabled(!picker.isOverSpace());
        };
        dialog.setOnShowListener(d -> {
            picker.updateTotals();
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    List<String> selected = picker.selectedItemIds();
                    if (selected.isEmpty()) {
                        Toast.makeText(DistroActivity.this,
                            R.string.distro_backup_none_selected,
                            Toast.LENGTH_SHORT).show();
                        return;
                    }
                    boolean backupToExternal = picker.isExternal();
                    if (backupToExternal
                        && !ExternalBackupStore.hasExternalFolder(DistroActivity.this)) {
                        // External chosen but no folder (e.g. the picker
                        // was cancelled): stay in the dialog, do not start.
                        Toast.makeText(DistroActivity.this,
                            R.string.backup_external_folder_not_set,
                            Toast.LENGTH_LONG).show();
                        return;
                    }
                    dialog.dismiss();
                    startBackupSession(container, display, sizes.rootfs,
                        selected, backupToExternal);
                });
        });
        dialog.setOnDismissListener(d -> {
            if (mActiveBackupPicker == picker) mActiveBackupPicker = null;
            mBackupFolderPickPending = false;
        });
        dialog.show();
    }

    /** Write the backup-only script and run it in a new visible
     * terminal session (same pattern as the uninstall script, but no
     * `proot-distro remove`). Unlike uninstall, this does NOT finish
     * the activity and does NOT mark a pending uninstall — the
     * container stays. When backupToExternal is true the script still
     * packs internally first; the copy to external storage is recorded
     * here, the script's final message names the external folder, and
     * a watcher thread (see startExternalCopyWatcher) copies it as
     * soon as the backup dir is complete (see isBackupDirComplete) —
     * the resume-time checkPendingExternalCopies remains the fallback.
     * The backup options the caller picked (selectedItemIds, the
     * internal/external destination) are passed through unchanged. */
    private void startBackupSession(String container, String display,
            File rootfs, List<String> selectedItemIds, boolean backupToExternal) {
        if (!DistroBackup.isValidContainerName(container)) {
            Log.w(LOG_TAG, "Refusing to back up bad container name: " + container);
            return;
        }
        File backupDir;
        try {
            backupDir = DistroBackup.newBackupDir(
                DistroBackup.internalBackupRoot(this), container);
        } catch (IllegalStateException e) {
            Log.w(LOG_TAG, "Failed to create backup dir", e);
            Toast.makeText(this,
                getString(R.string.distro_backup_script_failed, e.getMessage()),
                Toast.LENGTH_LONG).show();
            return;
        }
        if (backupToExternal) markPendingExternalCopy(container, backupDir);
        String distroId = DistroUtils.distroIdForContainer(container);
        String externalFolderDisplay = backupToExternal
            ? ExternalBackupStore.displayFolderPath(this) : null;
        String script = DistroBackup.buildBackupOnlyScript(rootfs, container,
            distroId == null ? "" : distroId, display, backupDir, selectedItemIds,
            AiAgentUtils.AGENTS, externalFolderDisplay);
        File scriptFile = new File(TermuxConstants.TERMUX_DATA_HOME_DIR,
            "distro-backup-" + container + ".sh");
        try {
            File parent = scriptFile.getParentFile();
            if (parent != null && !(parent.isDirectory() || parent.mkdirs())) {
                throw new IOException("Cannot create " + parent.getAbsolutePath());
            }
            try (FileOutputStream out = new FileOutputStream(scriptFile)) {
                out.write(script.getBytes(StandardCharsets.UTF_8));
            }
            scriptFile.setExecutable(true, true);
        } catch (Exception e) {
            Log.w(LOG_TAG, "Failed to write backup script", e);
            if (backupToExternal) removePendingExternalCopy(container);
            Toast.makeText(this,
                getString(R.string.distro_backup_script_failed, e.getMessage()),
                Toast.LENGTH_LONG).show();
            return;
        }
        String sessionName = getString(R.string.distro_backup_session, display);
        DistroUtils.startSessionWithExecutable(this, scriptFile.getAbsolutePath(), null,
            sessionName, sessionName);
        Toast.makeText(this, R.string.distro_backup_started, Toast.LENGTH_LONG).show();
        if (backupToExternal) startExternalCopyWatcher(container, backupDir);
    }

    /** Write the uninstall script and run it in a new visible terminal
     * session (same pattern as the install script), then finish: the
     * session has no callback, so cleanup + verification happen in
     * checkPendingUninstalls on the next resume. When backupToExternal
     * is true the script still packs internally first (the script only
     * knows local paths); the copy to external storage is recorded here,
     * the script's final message names the external folder, and a
     * watcher thread (see startExternalCopyWatcher) copies it as soon
     * as the backup dir is complete — checkPendingExternalCopies on
     * the next resume remains the fallback. The backup options the
     * caller picked (selectedItemIds, the internal/external
     * destination) are passed through unchanged. */
    private void startUninstallSession(String container, String display,
            File rootfs, boolean backup, List<String> selectedItemIds,
            boolean backupToExternal) {
        if (!DistroBackup.isValidContainerName(container)) {
            Log.w(LOG_TAG, "Refusing to uninstall bad container name: " + container);
            return;
        }
        String script;
        File backupDir = null;
        if (backup) {
            try {
                backupDir = DistroBackup.newBackupDir(
                    DistroBackup.internalBackupRoot(this), container);
            } catch (IllegalStateException e) {
                Log.w(LOG_TAG, "Failed to create backup dir", e);
                Toast.makeText(this,
                    getString(R.string.distro_uninstall_script_failed, e.getMessage()),
                    Toast.LENGTH_LONG).show();
                return;
            }
            if (backupToExternal) markPendingExternalCopy(container, backupDir);
            String distroId = DistroUtils.distroIdForContainer(container);
            String externalFolderDisplay = backupToExternal
                ? ExternalBackupStore.displayFolderPath(this) : null;
            script = DistroBackup.buildBackupAndRemoveScript(rootfs, container,
                distroId == null ? "" : distroId, display, backupDir, selectedItemIds,
                AiAgentUtils.AGENTS, externalFolderDisplay);
        } else {
            script = buildRemoveOnlyScript(container, display);
        }
        File scriptFile = new File(TermuxConstants.TERMUX_DATA_HOME_DIR,
            "distro-uninstall-" + container + ".sh");
        try {
            File parent = scriptFile.getParentFile();
            if (parent != null && !(parent.isDirectory() || parent.mkdirs())) {
                throw new IOException("Cannot create " + parent.getAbsolutePath());
            }
            try (FileOutputStream out = new FileOutputStream(scriptFile)) {
                out.write(script.getBytes(StandardCharsets.UTF_8));
            }
            scriptFile.setExecutable(true, true);
        } catch (Exception e) {
            Log.w(LOG_TAG, "Failed to write uninstall script", e);
            if (backupToExternal) removePendingExternalCopy(container);
            Toast.makeText(this,
                getString(R.string.distro_uninstall_script_failed, e.getMessage()),
                Toast.LENGTH_LONG).show();
            return;
        }
        markPendingUninstall(container);
        String sessionName = getString(R.string.distro_uninstall_session, display);
        DistroUtils.startSessionWithExecutable(this, scriptFile.getAbsolutePath(), null,
            sessionName, sessionName);
        Toast.makeText(this, R.string.distro_uninstall_started, Toast.LENGTH_LONG).show();
        if (backupToExternal && backupDir != null) {
            startExternalCopyWatcher(container, backupDir);
        }
        finish();
    }

    /** Remove-only script (backup switch off): just `proot-distro remove`.
     * No confirmation prompt: proot-distro remove does not ask (upstream
     * README: "Be careful as it does not ask for confirmation"), so the
     * app's own confirm dialog is the only prompt. */
    private static String buildRemoveOnlyScript(String container, String display) {
        StringBuilder script = new StringBuilder();
        script.append("#!/data/data/com.termux/files/usr/bin/bash\n");
        script.append("# Generated by Termux \"Linux 发行版\" (DistroActivity) - do not edit.\n");
        script.append("echo \"正在卸载 ").append(DistroBackup.shellQuote(display)).append(" ...\"\n");
        script.append("proot-distro remove ").append(container).append("\n");
        script.append("status=$?\n");
        script.append("if [ \"$status\" -eq 0 ]; then\n");
        script.append("  echo \"卸载完成。\"\n");
        script.append("else\n");
        script.append("  echo \"卸载失败（退出码 $status）。\"\n");
        script.append("fi\n");
        script.append("read -r -p \"按回车关闭此会话\" || true\n");
        return script.toString();
    }

    /** Remember a container whose uninstall session was launched, so its
     * host metadata can be cleaned once the container is gone (installs
     * use the same pattern: MirrorSettings.markPendingInstall). */
    private void markPendingUninstall(String container) {
        SharedPreferences prefs =
            SharedPreferenceUtils.getPrivateSharedPreferences(this, UNINSTALL_PREFS_NAME);
        Set<String> pending = new LinkedHashSet<>(
            prefs.getStringSet(KEY_PENDING_UNINSTALLS, new LinkedHashSet<String>()));
        pending.add(container);
        prefs.edit().putStringSet(KEY_PENDING_UNINSTALLS, pending).apply();
    }

    /** Follow-up for uninstalls launched in a terminal session (which has
     * no completion callback): when this screen is next opened/resumed, a
     * pending container that is gone gets its host metadata cleaned and
     * the list refreshed; one that is still there means the session is
     * still running (a big backup takes a while) or the remove failed,
     * so the pending entry is kept for a later retry and the user is
     * told once per activity lifetime. A stale pending entry is harmless
     * meanwhile: the drawer list is built from the container scan, not
     * the cache, and the mirror/model-config follow-ups only act on
     * containers that still exist. Call on a background thread, e.g.
     * from onCreate/onResume (same as MirrorSettings.syncPendingInstalls). */
    private void checkPendingUninstalls() {
        // A standalone backup never removes its container, so the
        // external copy for it cannot wait for "container gone" (see
        // checkPendingExternalCopies); run that check here too, on this
        // same background thread.
        checkPendingExternalCopies();
        SharedPreferences prefs =
            SharedPreferenceUtils.getPrivateSharedPreferences(this, UNINSTALL_PREFS_NAME);
        Set<String> pending = new LinkedHashSet<>(
            prefs.getStringSet(KEY_PENDING_UNINSTALLS, new LinkedHashSet<String>()));
        if (pending.isEmpty()) return;
        Set<String> installed = DistroUtils.scanInstalledContainers();
        Map<String, String> cachedNames = DistroUtils.loadCachedDisplayNames();
        Set<String> remaining = new LinkedHashSet<>();
        List<String> cleaned = new ArrayList<>();
        List<String> stillThere = new ArrayList<>();
        List<String> stillThereContainers = new ArrayList<>();
        for (String container : pending) {
            String display = DistroUtils.displayNameForContainer(container, cachedNames);
            if (installed.contains(container)) {
                remaining.add(container);
                stillThere.add(display);
                stillThereContainers.add(container);
            } else {
                cleanupUninstalledContainer(container);
                cleaned.add(display);
                // The container is gone, so the backup the session packed
                // internally can now be copied to external storage when
                // that was the chosen destination (see
                // copyPendingExternalBackup for the exact sequencing).
                copyPendingExternalBackup(container);
            }
        }
        if (remaining.size() != pending.size()) {
            prefs.edit().putStringSet(KEY_PENDING_UNINSTALLS, remaining).apply();
        }
        if (cleaned.isEmpty() && stillThere.isEmpty()) return;
        final String done = joinWithComma(cleaned);
        final List<String> stuck = new ArrayList<>(stillThere);
        final List<String> stuckContainers = new ArrayList<>(stillThereContainers);
        runOnUiThread(() -> {
            if (!done.isEmpty()) {
                Toast.makeText(DistroActivity.this,
                    getString(R.string.distro_uninstall_done, done), Toast.LENGTH_LONG).show();
            }
            for (int i = 0; i < stuck.size(); i++) {
                if (mUninstallStillPresentNoted.add(stuckContainers.get(i))) {
                    Toast.makeText(DistroActivity.this,
                        getString(R.string.distro_uninstall_still_present, stuck.get(i)),
                        Toast.LENGTH_LONG).show();
                }
            }
            refreshInstalledContainers();
            updateListRows(getString(R.string.distro_status_updated,
                formatTime(System.currentTimeMillis())));
        });
    }

    /** Resume-time external copy for backups whose container is NOT
     * gone — i.e. standalone backups (a standalone backup never runs
     * `proot-distro remove`, so checkPendingUninstalls would never
     * trigger the copy). For each pending external copy whose backup
     * dir is COMPLETE (see isBackupDirComplete), run the existing
     * copyPendingExternalBackup logic; incomplete ones are left pending
     * for a later resume (the terminal session may still be packing).
     * Call on a background thread (the copy can move gigabytes over
     * SAF). Safe to run alongside the uninstall flow: a complete pack
     * means the script has finished packing (it writes manifest.json
     * last), so copying cannot race the pack step. */
    private void checkPendingExternalCopies() {
        SharedPreferences prefs = SharedPreferenceUtils.getPrivateSharedPreferences(
            this, UNINSTALL_PREFS_NAME);
        Set<String> pending = new LinkedHashSet<>(prefs.getStringSet(
            KEY_PENDING_EXTERNAL_COPIES, new LinkedHashSet<String>()));
        if (pending.isEmpty()) return;
        for (String entry : pending) {
            String[] parts = entry.split("\\|", 2);
            if (parts.length != 2) continue;
            File backupDir = new File(parts[1]);
            if (!isBackupDirComplete(backupDir)) continue;
            copyPendingExternalBackup(parts[0]);
        }
    }

    /** True when a backup dir holds everything the session was meant
     * to pack: a readable manifest listing at least one item, and
     * every listed archive exists and is non-empty. The backup script
     * writes manifest.json only after all archives are packed (and
     * verifies them), so a complete dir means packing is done — an
     * in-progress session never looks complete. */
    private static boolean isBackupDirComplete(File backupDir) {
        JSONObject manifest = DistroBackup.readManifest(backupDir);
        if (manifest == null) return false;
        JSONArray items = manifest.optJSONArray("items");
        if (items == null || items.length() == 0) return false;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) return false;
            String archive = item.optString("archive", "");
            if (archive.isEmpty() || archive.contains("/")
                || archive.contains("\\") || archive.contains("..")) return false;
            File archiveFile = new File(backupDir, archive);
            if (!archiveFile.isFile() || archiveFile.length() == 0) return false;
        }
        return true;
    }

    /** Host metadata for a container whose rootfs is gone: the
     * distro-list.json cache entry, the install/uninstall scripts, the
     * pending-install entry and the model-config apply records. The
     * container dir itself is already gone (the terminal session ran
     * `proot-distro remove`); the drawer list re-scans, so nothing else
     * needs updating here. */
    private void cleanupUninstalledContainer(String container) {
        DistroUtils.removeContainerFromCache(container);
        deleteIfExists(new File(TermuxConstants.TERMUX_DATA_HOME_DIR,
            "distro-install-" + container + ".sh"));
        deleteIfExists(new File(TermuxConstants.TERMUX_DATA_HOME_DIR,
            "distro-uninstall-" + container + ".sh"));
        MirrorSettings.clearPendingInstall(this, container);
        ModelConfigStore.clearContainerRecords(container);
    }

    private static void deleteIfExists(File file) {
        if (file != null && file.isFile()) {
            file.delete();
        }
    }

    private void showVersionDialog(final DistroDef distro) {
        List<VersionOption> options = optionsFor(distro);
        final List<VersionOption> finalOptions = options;
        String[] displays = new String[options.size()];
        for (int i = 0; i < options.size(); i++) {
            VersionOption option = options.get(i);
            displays[i] = option.display;
            if (option.updated != null && !option.updated.isEmpty()) {
                displays[i] += "（" + getString(R.string.distro_updated_on, option.updated) + "）";
            }
        }
        new AlertDialog.Builder(this)
            .setTitle(getString(R.string.distro_choose_version, distro.displayName))
            .setItems(displays, (dialog, which) -> showInstallConfirmDialog(distro, finalOptions.get(which)))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void showInstallConfirmDialog(final DistroDef distro, final VersionOption version) {
        // Show the image ref that will actually be pulled (through the
        // registry mirror when one is selected).
        String effectiveRef = MirrorSettings.imageRefFor(version.imageRef,
                MirrorSettings.loadPreset(this));
        new AlertDialog.Builder(this)
            .setTitle(getString(R.string.distro_install_title, distro.displayName + " " + version.display))
            .setMessage(getString(R.string.distro_install_confirm, effectiveRef))
            .setPositiveButton(R.string.distro_install, (dialog, which) -> startInstallSession(distro, version))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void showToolsDialog(final DistroDef distro, final String container, final String display) {
        final boolean[] checked = new boolean[TOOL_IDS.length];
        Arrays.fill(checked, true);
        new AlertDialog.Builder(this)
            .setTitle(getString(R.string.distro_tools_title, display))
            .setMultiChoiceItems(TOOL_DISPLAY_NAMES, checked,
                (dialog, which, isChecked) -> checked[which] = isChecked)
            .setPositiveButton(R.string.distro_install, (dialog, which) -> {
                List<String> packages = new ArrayList<>();
                for (int i = 0; i < TOOL_IDS.length; i++) {
                    if (checked[i]) packages.add(packageNameFor(distro.id, TOOL_IDS[i]));
                }
                if (packages.isEmpty()) {
                    Toast.makeText(DistroActivity.this,
                        R.string.distro_tools_none_selected, Toast.LENGTH_SHORT).show();
                    return;
                }
                startToolsSession(distro, container, display, packages);
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    // ------------------------------------------------------------------
    // Package manager mapping
    // ------------------------------------------------------------------

    /** Package name of a tool on a distro. Only python3 and openssh differ. */
    private static String packageNameFor(String distroId, String toolId) {
        if ("python3".equals(toolId) && "arch".equals(distroId)) return "python";
        if ("openssh".equals(toolId)) {
            if ("ubuntu".equals(distroId) || "debian".equals(distroId) || "alpine".equals(distroId)) {
                return "openssh-client";
            }
            if ("fedora".equals(distroId)) return "openssh-clients";
            return "openssh"; // arch, opensuse, void
        }
        return toolId;
    }

    /** Full install command run inside the container (as root). */
    private static String buildToolsInstallCommand(String distroId, List<String> packageNames) {
        String packages = joinWithSpace(packageNames);
        switch (distroId) {
            case "ubuntu":
            case "debian":
                return "apt-get update && apt-get install -y " + packages;
            case "alpine":
                return "apk update && apk add " + packages;
            case "arch":
                return "pacman -Sy --noconfirm " + packages;
            case "fedora":
                return "dnf install -y " + packages;
            case "opensuse":
                return "zypper refresh && zypper install -y " + packages;
            default:
                return null;
        }
    }

    // ------------------------------------------------------------------
    // Sessions: install / login / tools install
    // ------------------------------------------------------------------

    /** Write the install script and run it in a new visible terminal session. */
    private void startInstallSession(DistroDef distro, VersionOption version) {
        File scriptFile = new File(TermuxConstants.TERMUX_DATA_HOME_DIR,
            "distro-install-" + version.container + ".sh");
        MirrorSettings.MirrorPreset preset = MirrorSettings.loadPreset(this);
        try {
            File parent = scriptFile.getParentFile();
            if (parent != null && !(parent.isDirectory() || parent.mkdirs())) {
                throw new IOException("Cannot create " + parent.getAbsolutePath());
            }
            try (FileOutputStream out = new FileOutputStream(scriptFile)) {
                out.write(buildInstallScript(distro, version, preset).getBytes(StandardCharsets.UTF_8));
            }
            scriptFile.setExecutable(true, true);
        } catch (Exception e) {
            Log.w(LOG_TAG, "Failed to write install script", e);
            Toast.makeText(this, getString(R.string.distro_install_script_failed, e.getMessage()),
                Toast.LENGTH_LONG).show();
            return;
        }
        // Remember the install: the mirror is applied to the container once
        // it appears (see syncPendingInstalls, called from onCreate/onResume).
        MirrorSettings.markPendingInstall(this, version.container);
        String sessionName = getString(R.string.distro_session_install,
            distro.displayName + " " + version.display);
        DistroUtils.startSessionWithExecutable(this, scriptFile.getAbsolutePath(), null, sessionName, sessionName);
        Toast.makeText(this, R.string.distro_install_started, Toast.LENGTH_LONG).show();
        finish();
    }

    private static String buildInstallScript(DistroDef distro, VersionOption version,
                                             MirrorSettings.MirrorPreset preset) {
        String imageRef = MirrorSettings.imageRefFor(version.imageRef, preset);
        StringBuilder script = new StringBuilder();
        script.append("#!/data/data/com.termux/files/usr/bin/bash\n");
        script.append("# Generated by Termux \"Linux 发行版\" (DistroActivity) - do not edit.\n");
        script.append("echo \"正在安装 ").append(distro.displayName).append(" ").append(version.display)
            .append(" (").append(imageRef).append(") ...\"\n");
        script.append("if ! command -v proot-distro >/dev/null 2>&1; then\n");
        script.append("  echo \"正在安装 proot-distro ...\"\n");
        script.append("  pkg install -y proot-distro\n");
        script.append("  if ! command -v proot-distro >/dev/null 2>&1; then\n");
        script.append("    echo \"安装 proot-distro 失败，请检查网络后重试。\"\n");
        script.append("    read -r -p \"按回车关闭此会话\"\n");
        script.append("    exit 1\n");
        script.append("  fi\n");
        script.append("fi\n");
        script.append("proot-distro install ").append(imageRef)
            .append(" --name ").append(version.container).append("\n");
        script.append("status=$?\n");
        script.append("if [ \"$status\" -eq 0 ]; then\n");
        script.append("  echo \"安装成功！回到“Linux 发行版”界面，点“进入”即可使用。\"\n");
        script.append("else\n");
        if (preset != null && !MirrorSettings.isOfficial(preset)) {
            script.append("  echo \"安装失败（退出码 $status）。常见原因：网络中断或镜像站故障，可在设置 → 下载源切回官方后重试。\"\n");
        } else {
            script.append("  echo \"安装失败（退出码 $status）。常见原因：网络中断或 Docker Hub 限流，稍后重试。\"\n");
        }
        script.append("fi\n");
        script.append("read -r -p \"按回车关闭此会话\"\n");
        return script.toString();
    }

    /** Open the AI assistant installer for one installed container. */
    private void openAiAgents(DistroDef distro, String container, String display) {
        Intent intent = new Intent(this, AiAgentsActivity.class);
        intent.putExtra(AiAgentsActivity.EXTRA_CONTAINER_NAME, container);
        intent.putExtra(AiAgentsActivity.EXTRA_DISTRO_ID, distro.id);
        intent.putExtra(AiAgentsActivity.EXTRA_DISPLAY_NAME, display);
        startActivity(intent);
    }

    /** Quick start: list this container's installed agents; tapping one
     * opens a terminal session that runs it directly, then closes this
     * screen (same as entering the distro). Web-capable agents
     * (dsh, OpenCode, Pi, Hermes Agent) get an extra "打开 X 网页版" item, which starts
     * the web server in a visible session and shows how to reach it; this
     * screen closes only after that dialog is dismissed. dsh has no
     * terminal entry (upstream documents only `dsh web`), so it lists only
     * its web item, no terminal item. */
    private void showQuickStartDialog(final DistroDef distro, final String container,
                                      final String display,
                                      final List<AiAgentUtils.InstalledAgent> agents) {
        List<String> names = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        for (final AiAgentUtils.InstalledAgent agent : agents) {
            if (AiAgentUtils.hasTerminalEntry(agent.agentId)) {
                names.add(agent.displayName);
                actions.add(() -> {
                    AiAgentUtils.startAgentSession(DistroActivity.this, agent);
                    finish();
                });
            }
            AiAgentUtils.AgentDef def = AiAgentUtils.findDef(agent.agentId);
            if (def != null && def.webKind != AiAgentUtils.WebKind.NONE) {
                names.add(getString(R.string.aiagents_web_quick_item, agent.displayName));
                actions.add(() -> AiAgentUtils.startWebSession(DistroActivity.this, agent,
                    () -> finish()));
            }
        }
        new AlertDialog.Builder(this)
            .setTitle(getString(R.string.aiagents_quick_start_title, display))
            .setItems(names.toArray(new String[0]), (dialog, which) -> actions.get(which).run())
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void startLoginSession(String container, String display) {
        DistroUtils.startLoginSession(this, container, display);
        finish();
    }

    private void startToolsSession(DistroDef distro, String container, String display,
                                   List<String> packages) {
        String command = buildToolsInstallCommand(distro.id, packages);
        if (command == null) return;
        String prootDistro = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "proot-distro")
            .getAbsolutePath();
        if (!new File(prootDistro).isFile()) {
            Toast.makeText(this, R.string.distro_proot_distro_missing, Toast.LENGTH_LONG).show();
            return;
        }
        String sessionName = getString(R.string.distro_session_tools, display);
        DistroUtils.startSessionWithExecutable(this, prootDistro,
            new String[]{"login", container, "--", "bash", "-c", command},
            sessionName, sessionName);
        Toast.makeText(this, R.string.distro_tools_started, Toast.LENGTH_LONG).show();
        finish();
    }

    // ------------------------------------------------------------------
    // Selective restore (design.md §4) + backup manager (§6) + external
    // storage wiring (§6). Restore extraction itself lives in
    // DistroBackup.restoreItems / verifyRestoredItems; this section is
    // the UI around it: pick a backup, pick its items, run the restore,
    // verify and report per item. External backups are staged locally
    // first (copyBackupFromExternal) and the staging dir is deleted
    // afterwards; nothing is ever read straight out of the SAF tree.
    // ------------------------------------------------------------------

    /** One backup from either location, in the shape the restore picker
     * and the backup manager both need. */
    private static final class UnifiedBackup {
        final boolean external;
        /** True when the backup dir has no readable manifest: shown as
         * "损坏", never selectable for restore (it can still be deleted
         * from the manager). Only internal damaged backups are listed:
         * ExternalBackupStore.listExternalBackups skips unreadable
         * manifests and exposes no raw dir listing, so a damaged
         * external backup is invisible here. */
        final boolean damaged;
        final File dir; // internal only
        final Uri externalUri; // external only
        final String container;
        final String distroId;
        final String distroDisplay;
        final String createdAt;
        final long totalSize;
        final List<BackupItemRow> items;

        static final class BackupItemRow {
            final String id;
            final String label;
            final String archive;
            final long size;
            /** False for external backups: ExternalBackupInfo carries
             * only the total size, not per-item sizes. */
            final boolean sizeKnown;

            BackupItemRow(String id, String label, String archive, long size, boolean sizeKnown) {
                this.id = id;
                this.label = label;
                this.archive = archive;
                this.size = size;
                this.sizeKnown = sizeKnown;
            }
        }

        private UnifiedBackup(boolean external, boolean damaged, File dir, Uri externalUri,
                String container, String distroId, String distroDisplay, String createdAt,
                long totalSize, List<BackupItemRow> items) {
            this.external = external;
            this.damaged = damaged;
            this.dir = dir;
            this.externalUri = externalUri;
            this.container = container == null ? "" : container;
            this.distroId = distroId == null ? "" : distroId;
            this.distroDisplay = distroDisplay == null ? "" : distroDisplay;
            this.createdAt = createdAt == null ? "" : createdAt;
            this.totalSize = totalSize;
            this.items = items;
        }

        static UnifiedBackup fromInternal(DistroBackup.BackupInfo info) {
            List<BackupItemRow> rows = new ArrayList<>();
            for (DistroBackup.BackupInfo.Item item : info.items) {
                rows.add(new BackupItemRow(item.id, item.label, item.archive, item.size, true));
            }
            return new UnifiedBackup(false, false, info.dir, null, info.container,
                info.distroId, info.distroDisplay, info.createdAt, info.totalSize, rows);
        }

        static UnifiedBackup fromExternal(ExternalBackupStore.ExternalBackupInfo info) {
            List<BackupItemRow> rows = new ArrayList<>();
            for (int i = 0; i < info.itemIds.size(); i++) {
                String label = i < info.itemLabels.size()
                    ? info.itemLabels.get(i) : info.itemIds.get(i);
                // Archive names come from ExternalBackupInfo.itemArchives
                // (parallel to itemIds/itemLabels); "" when absent so the
                // detail row just omits the archive name.
                String archive = info.itemArchives != null
                    && i < info.itemArchives.size()
                    && info.itemArchives.get(i) != null
                    ? info.itemArchives.get(i) : "";
                rows.add(new BackupItemRow(info.itemIds.get(i), label, archive, 0, false));
            }
            return new UnifiedBackup(true, false, null, info.uri, info.container,
                info.distroId, info.distroDisplay, info.createdAt, info.totalSize, rows);
        }

        static UnifiedBackup damaged(File dir) {
            return new UnifiedBackup(false, true, dir, null, dir.getName(),
                "", "", "", 0, new ArrayList<BackupItemRow>());
        }
    }

    /** All backups from both locations, newest first (damaged internal
     * backups, which have no createdAt, sort last). Call on a background
     * thread: the external listing queries the SAF provider. */
    private List<UnifiedBackup> collectUnifiedBackups() {
        List<UnifiedBackup> result = new ArrayList<>();
        File internalRoot = DistroBackup.internalBackupRoot(this);
        Set<String> listedDirs = new HashSet<>();
        for (DistroBackup.BackupInfo info : DistroBackup.listBackups(internalRoot)) {
            listedDirs.add(info.dir.getName());
            result.add(UnifiedBackup.fromInternal(info));
        }
        // Damaged internal backups: listBackups skips dirs with no
        // readable manifest; they are still listed (as damaged) so the
        // user can see and delete them from the manager.
        if (internalRoot != null && internalRoot.isDirectory()) {
            File[] children = internalRoot.listFiles();
            if (children != null) {
                for (File dir : children) {
                    if (!dir.isDirectory() || listedDirs.contains(dir.getName())) continue;
                    if (DistroBackup.readManifest(dir) == null) {
                        result.add(UnifiedBackup.damaged(dir));
                    }
                }
            }
        }
        for (ExternalBackupStore.ExternalBackupInfo info
                : ExternalBackupStore.listExternalBackups(this)) {
            result.add(UnifiedBackup.fromExternal(info));
        }
        Collections.sort(result, (a, b) -> b.createdAt.compareTo(a.createdAt));
        return result;
    }

    private static String formatCreatedAt(String createdAt) {
        if (createdAt == null) return "";
        return createdAt.length() >= 16
            ? createdAt.substring(0, 16).replace('T', ' ') : createdAt;
    }

    /** First line of a backup row: source distro + date. */
    private String backupTitleLine(UnifiedBackup backup) {
        if (backup.damaged) return backup.dir == null ? "" : backup.dir.getName();
        String source = !backup.distroDisplay.isEmpty()
            ? backup.distroDisplay : backup.container;
        String date = formatCreatedAt(backup.createdAt);
        return date.isEmpty() ? source : source + " · " + date;
    }

    /** Second line of a backup row: size + location + item labels. */
    private String backupSubtitleLine(UnifiedBackup backup) {
        if (backup.damaged) return getString(R.string.restore_damaged);
        String location = backup.external
            ? getString(R.string.backup_external_location_label)
            : getString(R.string.backup_external_location_internal);
        List<String> labels = new ArrayList<>();
        for (UnifiedBackup.BackupItemRow item : backup.items) labels.add(item.label);
        String line = Formatter.formatShortFileSize(this, backup.totalSize)
            + " · " + location;
        if (!labels.isEmpty()) line += " · " + joinWithComma(labels);
        return line;
    }

    /** One-line label for the auto-prompt's setItems list. */
    private String formatBackupLabel(UnifiedBackup backup) {
        return backupTitleLine(backup) + " · " + backupSubtitleLine(backup);
    }

    /** Two-line list adapter for backups. In the restore picker damaged
     * rows are disabled (cannot be selected); in the manager they stay
     * enabled so a damaged backup can be opened and deleted. Manager
     * rows also carry direct Restore / Delete buttons (the picker
     * keeps plain two-line selection rows, no buttons). */
    private ArrayAdapter<UnifiedBackup> backupListAdapter(final List<UnifiedBackup> backups,
            final boolean forRestorePicker) {
        return new ArrayAdapter<UnifiedBackup>(this, android.R.layout.simple_list_item_2,
            android.R.id.text1, backups) {
            @Override
            public boolean isEnabled(int position) {
                UnifiedBackup backup = getItem(position);
                return backup == null || !(forRestorePicker && backup.damaged);
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                final UnifiedBackup backup = getItem(position);
                if (forRestorePicker) {
                    View view = super.getView(position, convertView, parent);
                    if (backup != null) {
                        ((TextView) view.findViewById(android.R.id.text1))
                            .setText(backupTitleLine(backup));
                        ((TextView) view.findViewById(android.R.id.text2))
                            .setText(backupSubtitleLine(backup));
                    }
                    return view;
                }
                // Manager row: same two lines, plus direct Restore /
                // Delete buttons. The buttons are not focusable, so
                // tapping the row itself still opens the detail view.
                LinearLayout row = new LinearLayout(DistroActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout texts = new LinearLayout(DistroActivity.this);
                texts.setOrientation(LinearLayout.VERTICAL);
                texts.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                TextView titleView = new TextView(DistroActivity.this);
                titleView.setText(backup == null ? "" : backupTitleLine(backup));
                texts.addView(titleView);
                TextView subtitleView = new TextView(DistroActivity.this);
                subtitleView.setText(backup == null ? "" : backupSubtitleLine(backup));
                texts.addView(subtitleView);
                row.addView(texts);
                android.widget.Button restoreButton =
                    new android.widget.Button(DistroActivity.this);
                restoreButton.setText(R.string.backup_row_restore);
                restoreButton.setFocusable(false);
                restoreButton.setOnClickListener(v -> {
                    if (backup == null) return;
                    if (backup.damaged) {
                        Toast.makeText(DistroActivity.this,
                            R.string.restore_damaged, Toast.LENGTH_LONG).show();
                        return;
                    }
                    showRestoreContainerPicker(backup);
                });
                row.addView(restoreButton);
                android.widget.Button deleteButton =
                    new android.widget.Button(DistroActivity.this);
                deleteButton.setText(R.string.backup_manager_delete);
                deleteButton.setFocusable(false);
                deleteButton.setOnClickListener(v -> {
                    if (backup == null) return;
                    confirmDeleteBackup(backup);
                });
                row.addView(deleteButton);
                return row;
            }
        };
    }

    private static String labelForItem(UnifiedBackup backup, String itemId) {
        for (UnifiedBackup.BackupItemRow item : backup.items) {
            if (item.id.equals(itemId)) return item.label;
        }
        return itemId;
    }

    /** True when the backup lists the given item id (used for the
     * system item of external backups, whose manifest is not local). */
    private static boolean hasBackupItemRow(UnifiedBackup backup, String itemId) {
        for (UnifiedBackup.BackupItemRow item : backup.items) {
            if (item.id.equals(itemId)) return true;
        }
        return false;
    }

    /** "distroId equal" is the whole same/cross-distro test (design.md
     * §4): only the distro family is compared, never the version
     * number. A missing/empty backup distroId is cross-distro. */
    private static boolean isSameDistroForRestore(UnifiedBackup backup, String targetDistroId) {
        return backup.distroId != null && !backup.distroId.isEmpty()
            && backup.distroId.equals(targetDistroId);
    }

    private static DistroDef findDistroDef(String distroId) {
        if (distroId == null) return null;
        for (DistroDef distro : DISTROS) {
            if (distro.id.equals(distroId)) return distro;
        }
        return null;
    }

    /** Open the AI-assistant installer for one container, reusing the
     * same intent as the "安装 AI 助手" menu item. Used by the restore
     * result's "重装这些助手" button (cross-distro restores only). */
    private void openAiAgentsForContainer(String container, String display) {
        DistroDef distro = findDistroDef(DistroUtils.distroIdForContainer(container));
        if (distro == null) return;
        openAiAgents(distro, container, display);
    }

    // ---- Manual restore: "从备份恢复" in showDistroDialog ----

    /** Manual restore entry: list all backups (both locations) on a
     * background thread, then show the picker on the UI thread. */
    private void startRestoreFlow(final String container, final String display) {
        final File rootfs = DistroUtils.containerRootfs(container);
        if (rootfs == null) {
            Toast.makeText(this, R.string.restore_target_missing, Toast.LENGTH_LONG).show();
            return;
        }
        new Thread(() -> {
            final List<UnifiedBackup> backups = collectUnifiedBackups();
            runOnUiThread(() -> showBackupPicker(container, display, rootfs, backups));
        }).start();
    }

    /** Backup picker: every backup from both locations, labelled with
     * source distro + date + size + location. Damaged rows are shown
     * but disabled; tapping one toasts instead of restoring. */
    private void showBackupPicker(final String container, final String display,
            final File rootfs, final List<UnifiedBackup> backups) {
        if (isFinishing() || isDestroyed()) return;
        if (backups.isEmpty()) {
            Toast.makeText(this, R.string.restore_no_backups, Toast.LENGTH_LONG).show();
            return;
        }
        ListView listView = new ListView(this);
        listView.setAdapter(backupListAdapter(backups, true));
        final AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(R.string.restore_pick_backup_title)
            .setView(listView)
            .setNegativeButton(android.R.string.cancel, null)
            .create();
        listView.setOnItemClickListener((parent, view, position, id) -> {
            UnifiedBackup backup = backups.get(position);
            if (backup.damaged) {
                Toast.makeText(DistroActivity.this,
                    R.string.restore_damaged, Toast.LENGTH_LONG).show();
                return;
            }
            dialog.dismiss();
            showItemPicker(container, display, rootfs, backup);
        });
        dialog.show();
    }

    /** Item picker for one backup: checklist of its items (user files +
     * agents) with sizes where known, DEFAULT ALL CHECKED. The header
     * states whether this is a same-distro restore (whole items) or a
     * cross-distro one (data only, programs need reinstalling). */
    private void showItemPicker(final String container, final String display,
            final File rootfs, final UnifiedBackup backup) {
        if (isFinishing() || isDestroyed()) return;
        String targetDistroId = DistroUtils.distroIdForContainer(container);
        // The system item (DistroBackup.SYSTEM_ITEM_ID) only exists in
        // backups made after this round. Read the manifest when it is
        // local; external backups have no local manifest, so use the
        // listed item ids instead (they come from the same manifest).
        // Same-distro is likewise read from the manifest when available.
        JSONObject manifest = backup.dir != null
            ? DistroBackup.readManifest(backup.dir) : null;
        boolean hasSystem = manifest != null
            ? DistroBackup.manifestHasItem(manifest, DistroBackup.SYSTEM_ITEM_ID)
            : hasBackupItemRow(backup, DistroBackup.SYSTEM_ITEM_ID);
        boolean sameDistro = manifest != null
            ? DistroBackup.isSameDistro(manifest, targetDistroId)
            : isSameDistroForRestore(backup, targetDistroId);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad / 2, pad, pad / 2);
        TextView distroLine = new TextView(this);
        if (sameDistro) {
            distroLine.setText(getString(R.string.restore_same_distro, backup.distroDisplay));
        } else {
            distroLine.setText(getString(R.string.restore_cross_distro_note,
                backup.distroDisplay, display));
        }
        layout.addView(distroLine);
        if (hasSystem && !sameDistro) {
            // Cross-distro: system files cannot be restored to a
            // different distro — the system item is skipped below and
            // never passed to restoreItems.
            TextView skipNote = new TextView(this);
            skipNote.setText(R.string.restore_system_skipped_cross_distro);
            layout.addView(skipNote);
        } else if (hasSystem && sameDistro && isTargetRootNonEmpty(rootfs)) {
            // Same distro onto a non-empty target: restoring the system
            // files will overwrite the target distro's system files.
            // Warn prominently, but do not block — the existing
            // target-not-empty confirm still fires on confirm.
            TextView systemWarning = new TextView(this);
            systemWarning.setText(R.string.restore_system_overwrite_warning);
            systemWarning.setTypeface(Typeface.DEFAULT_BOLD);
            layout.addView(systemWarning);
        }
        final Map<String, CheckBox> checks = new LinkedHashMap<>();
        for (UnifiedBackup.BackupItemRow item : backup.items) {
            if (!sameDistro && DistroBackup.SYSTEM_ITEM_ID.equals(item.id)) {
                continue; // system files cannot be restored cross-distro
            }
            CheckBox checkBox = new CheckBox(this);
            String text = item.label;
            if (item.sizeKnown) {
                text += " (" + Formatter.formatShortFileSize(this, item.size) + ")";
            }
            checkBox.setText(text);
            checkBox.setChecked(true);
            layout.addView(checkBox);
            checks.put(item.id, checkBox);
        }
        ScrollView scroll = new ScrollView(this);
        scroll.addView(layout);
        final AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(R.string.restore_pick_items_title)
            .setView(scroll)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            .setOnClickListener(v -> {
                List<String> selected = new ArrayList<>();
                for (Map.Entry<String, CheckBox> e : checks.entrySet()) {
                    if (e.getValue().isChecked()) selected.add(e.getKey());
                }
                if (selected.isEmpty()) {
                    Toast.makeText(DistroActivity.this,
                        R.string.restore_no_items_selected, Toast.LENGTH_SHORT).show();
                    return;
                }
                dialog.dismiss();
                if (isTargetRootNonEmpty(rootfs)) {
                    new AlertDialog.Builder(DistroActivity.this)
                        .setTitle(R.string.restore_overwrite_title)
                        .setMessage(R.string.restore_target_not_empty)
                        .setPositiveButton(android.R.string.ok, (dd, ww) ->
                            runRestore(container, display, rootfs, backup, selected))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
                } else {
                    runRestore(container, display, rootfs, backup, selected);
                }
            }));
        dialog.show();
    }

    /** True when the target container's /root already holds files, so a
     * restore would overwrite same-named files. */
    private static boolean isTargetRootNonEmpty(File rootfs) {
        File root = new File(rootfs, "root");
        String[] children = root.isDirectory() ? root.list() : null;
        return children != null && children.length > 0;
    }

    /** Runs the restore on a background thread behind a non-cancelable
     * progress dialog. External backups are staged locally first
     * (copyBackupFromExternal into getCacheDir()/restore-stage) and the
     * staging dir is deleted afterwards, whether the restore succeeded
     * or not. Each item is restored individually so the result dialog
     * can report per-item success/failure; verification runs once at
     * the end over all selected items. */
    private void runRestore(final String container, final String display, final File rootfs,
            final UnifiedBackup backup, final List<String> selectedItemIds) {
        final AlertDialog progress = buildProgressDialog(R.string.restore_restoring);
        progress.show();
        new Thread(() -> {
            File effectiveDir = backup.dir;
            File stageParent = null;
            if (backup.external) {
                stageParent = new File(getCacheDir(), "restore-stage");
                File stagedDir = ExternalBackupStore.copyBackupFromExternal(
                    DistroActivity.this, backup.externalUri, stageParent);
                if (stagedDir == null) {
                    final File stageToClean = stageParent;
                    runOnUiThread(() -> {
                        progress.dismiss();
                        Toast.makeText(DistroActivity.this,
                            R.string.restore_stage_failed, Toast.LENGTH_LONG).show();
                    });
                    deleteRecursively(stageToClean);
                    return;
                }
                effectiveDir = stagedDir;
            }
            String targetDistroId = DistroUtils.distroIdForContainer(container);
            final Map<String, Boolean> extracted = new LinkedHashMap<>();
            for (String itemId : selectedItemIds) {
                extracted.put(itemId, DistroBackup.restoreItems(effectiveDir, rootfs,
                    targetDistroId, Collections.singletonList(itemId)));
            }
            final List<String> failedVerify = DistroBackup.verifyRestoredItems(
                effectiveDir, rootfs, targetDistroId, selectedItemIds);
            if (stageParent != null) deleteRecursively(stageParent);
            runOnUiThread(() -> {
                progress.dismiss();
                showRestoreResult(container, display, backup, selectedItemIds,
                    extracted, failedVerify);
            });
        }).start();
    }

    private AlertDialog buildProgressDialog(int messageRes) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);
        layout.addView(new ProgressBar(this));
        TextView text = new TextView(this);
        text.setText(messageRes);
        int textPad = (int) (12 * getResources().getDisplayMetrics().density);
        text.setPadding(textPad, 0, 0, 0);
        layout.addView(text);
        return new AlertDialog.Builder(this)
            .setView(layout)
            .setCancelable(false)
            .create();
    }

    /** Per-item restore result. When the restore was cross-distro and
     * at least one agent item was restored (data-only), a "重装这些助手"
     * button opens AiAgentsActivity for this container so the user can
     * reinstall the programs (the restored config is picked up). */
    private void showRestoreResult(String container, String display, UnifiedBackup backup,
            List<String> selectedItemIds, Map<String, Boolean> extracted,
            List<String> failedVerify) {
        if (isFinishing() || isDestroyed()) return;
        String targetDistroId = DistroUtils.distroIdForContainer(container);
        boolean sameDistro = isSameDistroForRestore(backup, targetDistroId);
        StringBuilder message = new StringBuilder();
        boolean allOk = true;
        List<String> restoredAgentIds = new ArrayList<>();
        for (String itemId : selectedItemIds) {
            boolean okExtract = Boolean.TRUE.equals(extracted.get(itemId));
            boolean okVerify = !failedVerify.contains(itemId);
            String status;
            if (okExtract && okVerify) {
                status = getString(R.string.restore_item_ok);
                if (itemId.startsWith(DistroBackup.AGENT_ITEM_ID_PREFIX)) {
                    restoredAgentIds.add(itemId.substring(
                        DistroBackup.AGENT_ITEM_ID_PREFIX.length()));
                }
            } else if (!okExtract) {
                status = getString(R.string.restore_item_failed_extract);
                allOk = false;
            } else {
                status = getString(R.string.restore_item_failed_verify);
                allOk = false;
            }
            message.append(labelForItem(backup, itemId)).append("：")
                .append(status).append("\n");
        }
        if (!sameDistro && !restoredAgentIds.isEmpty()) {
            message.append("\n").append(getString(R.string.restore_cross_distro_note,
                backup.distroDisplay, display));
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
            .setTitle(allOk ? R.string.restore_done_title
                : R.string.restore_done_some_failed_title)
            .setMessage(message.toString().trim())
            .setPositiveButton(android.R.string.ok, null);
        if (!sameDistro && !restoredAgentIds.isEmpty()
            && findDistroDef(targetDistroId) != null) {
            builder.setNeutralButton(R.string.restore_reinstall_agents,
                (d, w) -> openAiAgentsForContainer(container, display));
        }
        builder.show();
    }

    // ---- Auto-prompt after a distro install (design.md §4) ----

    /** Offer a restore when a container appears that this screen has
     * never seen before. Installs run in a terminal session with no
     * callback, so like the mirror sync (syncPendingInstalls) and the
     * model-config re-apply prompt, this is detected on resume with a
     * fresh container scan — here against a persisted "known
     * containers" set, so a container installed before this feature
     * first ran is never prompted (the known set is seeded silently on
     * first run). A container is prompted at most once ever: it is
     * written to the persisted prompted set BEFORE the dialog shows, so
     * dismissing it never re-prompts. At most one prompt per scan.
     * Call on a background thread, e.g. from onCreate/onResume. */
    private void checkRestoreAutoPrompt() {
        if (!mRestoreAutoPromptRunning.compareAndSet(false, true)) return;
        try {
            Set<String> installed = DistroUtils.scanInstalledContainers();
            SharedPreferences prefs = SharedPreferenceUtils.getPrivateSharedPreferences(
                this, RESTORE_PREFS_NAME);
            Set<String> known = prefs.getStringSet(KEY_RESTORE_KNOWN_CONTAINERS, null);
            if (known == null) {
                prefs.edit().putStringSet(KEY_RESTORE_KNOWN_CONTAINERS,
                    new LinkedHashSet<>(installed)).apply();
                return;
            }
            Set<String> newlyInstalled = new LinkedHashSet<>(installed);
            newlyInstalled.removeAll(known);
            prefs.edit().putStringSet(KEY_RESTORE_KNOWN_CONTAINERS,
                new LinkedHashSet<>(installed)).apply();
            if (newlyInstalled.isEmpty()) return;
            List<UnifiedBackup> usable = new ArrayList<>();
            for (UnifiedBackup backup : collectUnifiedBackups()) {
                if (!backup.damaged) usable.add(backup);
            }
            if (usable.isEmpty()) return;
            Set<String> prompted = new LinkedHashSet<>(prefs.getStringSet(
                KEY_RESTORE_PROMPTED_CONTAINERS, new LinkedHashSet<String>()));
            for (String container : newlyInstalled) {
                if (prompted.contains(container)) continue;
                prompted.add(container);
                prefs.edit().putStringSet(KEY_RESTORE_PROMPTED_CONTAINERS, prompted).apply();
                final String display = displayNameForContainer(container);
                final List<UnifiedBackup> choices = usable;
                runOnUiThread(() ->
                    showRestoreAutoPromptDialog(container, display, choices));
                return;
            }
        } finally {
            mRestoreAutoPromptRunning.set(false);
        }
    }

    private void showRestoreAutoPromptDialog(final String container, final String display,
            final List<UnifiedBackup> backups) {
        if (isFinishing() || isDestroyed()) return;
        String[] labels = new String[backups.size()];
        for (int i = 0; i < backups.size(); i++) labels[i] = formatBackupLabel(backups.get(i));
        new AlertDialog.Builder(this)
            .setTitle(R.string.restore_auto_prompt_title)
            .setMessage(getString(R.string.restore_auto_prompt_message, display))
            .setItems(labels, (dialog, which) ->
                startRestoreFlowForBackup(container, display, backups.get(which)))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void startRestoreFlowForBackup(String container, String display,
            UnifiedBackup backup) {
        File rootfs = DistroUtils.containerRootfs(container);
        if (rootfs == null) {
            Toast.makeText(this, R.string.restore_target_missing, Toast.LENGTH_LONG).show();
            return;
        }
        showItemPicker(container, display, rootfs, backup);
    }

    // ---- Backup manager (design.md §6) ----

    /** Entry point: the "备份" item added to the options menu in
     * onCreateOptionsMenu. Lists both locations, newest first. */
    private void showBackupManager() {
        new Thread(() -> {
            final List<UnifiedBackup> backups = collectUnifiedBackups();
            runOnUiThread(() -> showBackupManagerDialog(backups));
        }).start();
    }

    /** Re-list and re-show the manager (after a delete or a folder
     * pick). Only when it is currently open. */
    private void refreshBackupManager() {
        if (mBackupManagerDialog != null && mBackupManagerDialog.isShowing()) {
            mBackupManagerDialog.dismiss();
            showBackupManager();
        }
    }

    private void showBackupManagerDialog(final List<UnifiedBackup> backups) {
        if (isFinishing() || isDestroyed()) return;
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad / 2, pad, pad / 2);

        // Default backup location (design.md §6): internal (default) or
        // external. The uninstall confirm dialog defaults to this
        // setting; picking external here with no folder set opens the
        // system folder picker (see onActivityResult).
        TextView settingTitle = new TextView(this);
        settingTitle.setText(R.string.backup_external_default_location);
        layout.addView(settingTitle);
        RadioGroup locationGroup = new RadioGroup(this);
        locationGroup.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton internalRadio = new RadioButton(this);
        internalRadio.setId(View.generateViewId());
        internalRadio.setText(R.string.backup_external_location_internal);
        RadioButton externalRadio = new RadioButton(this);
        externalRadio.setId(View.generateViewId());
        externalRadio.setText(R.string.backup_external_location_external);
        locationGroup.addView(internalRadio);
        locationGroup.addView(externalRadio);
        if (isExternalBackupDefault()) {
            externalRadio.setChecked(true);
        } else {
            internalRadio.setChecked(true);
        }
        layout.addView(locationGroup);
        final TextView folderStatus = new TextView(this);
        layout.addView(folderStatus);
        android.widget.Button chooseFolderButton = new android.widget.Button(this);
        chooseFolderButton.setText(R.string.backup_external_change_folder);
        chooseFolderButton.setOnClickListener(v ->
            ExternalBackupStore.pickExternalFolder(DistroActivity.this));
        layout.addView(chooseFolderButton);
        locationGroup.setOnCheckedChangeListener((group, checkedId) -> {
            boolean external = checkedId == externalRadio.getId();
            setExternalBackupDefault(external);
            updateExternalFolderStatus(folderStatus);
            if (external && !ExternalBackupStore.hasExternalFolder(DistroActivity.this)) {
                ExternalBackupStore.pickExternalFolder(DistroActivity.this);
            }
        });
        updateExternalFolderStatus(folderStatus);

        TextView listTitle = new TextView(this);
        listTitle.setText(R.string.backup_manager_list_title);
        layout.addView(listTitle);
        ListView listView = new ListView(this);
        listView.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (int) (360 * getResources().getDisplayMetrics().density)));
        if (backups.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.backup_manager_empty);
            layout.addView(empty);
        } else {
            listView.setAdapter(backupListAdapter(backups, false));
            layout.addView(listView);
        }
        final AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(R.string.backup_manager_title)
            .setView(layout)
            .setNegativeButton(android.R.string.cancel, null)
            .create();
        listView.setOnItemClickListener((parent, view, position, id) ->
            showBackupDetail(backups.get(position)));
        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            confirmDeleteBackup(backups.get(position));
            return true;
        });
        dialog.setOnDismissListener(d -> {
            if (mBackupManagerDialog == dialog) mBackupManagerDialog = null;
        });
        mBackupManagerDialog = dialog;
        dialog.show();
    }

    /** Detail view for one backup: header lines (source distro, date,
     * total size, location) plus one row per item — label, archive name
     * and size where known — each with its own delete button. The
     * dialog buttons are restore / delete whole backup / cancel. A
     * damaged backup keeps the old behaviour: the damaged note only,
     * no file list, no restore button, whole-delete + cancel only. */
    private void showBackupDetail(final UnifiedBackup backup) {
        if (isFinishing() || isDestroyed()) return;
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad / 2, pad, pad / 2);

        String source = !backup.distroDisplay.isEmpty()
            ? backup.distroDisplay : backup.container;
        TextView sourceLine = new TextView(this);
        sourceLine.setText(source);
        layout.addView(sourceLine);
        String date = formatCreatedAt(backup.createdAt);
        if (!date.isEmpty()) {
            TextView dateLine = new TextView(this);
            dateLine.setText(date);
            layout.addView(dateLine);
        }
        TextView sizeLine = new TextView(this);
        sizeLine.setText(Formatter.formatShortFileSize(this, backup.totalSize));
        layout.addView(sizeLine);
        TextView locationLine = new TextView(this);
        if (backup.external) {
            String location = getString(R.string.backup_external_location_label);
            String folderPath = ExternalBackupStore.displayFolderPath(this);
            if (folderPath != null && !folderPath.isEmpty()) {
                location += ": " + folderPath;
            }
            locationLine.setText(location);
        } else {
            locationLine.setText(R.string.backup_external_location_internal);
        }
        layout.addView(locationLine);

        if (backup.damaged) {
            TextView damagedLine = new TextView(this);
            damagedLine.setText(R.string.restore_damaged);
            layout.addView(damagedLine);
        } else {
            for (UnifiedBackup.BackupItemRow item : backup.items) {
                String text = item.label;
                String archive = item.archive == null ? "" : item.archive;
                if (!archive.isEmpty() && item.sizeKnown) {
                    text += " (" + archive + ", "
                        + Formatter.formatShortFileSize(this, item.size) + ")";
                } else if (!archive.isEmpty()) {
                    text += " (" + archive + ")";
                } else if (item.sizeKnown) {
                    text += " (" + Formatter.formatShortFileSize(this, item.size) + ")";
                }
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                TextView labelView = new TextView(this);
                labelView.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                labelView.setText(text);
                row.addView(labelView);
                android.widget.Button deleteButton = new android.widget.Button(this);
                deleteButton.setText(R.string.backup_manager_delete);
                deleteButton.setOnClickListener(v -> confirmDeleteBackupItem(backup, item));
                row.addView(deleteButton);
                layout.addView(row);
            }
        }
        ScrollView scroll = new ScrollView(this);
        scroll.addView(layout);
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
            .setTitle(backupTitleLine(backup))
            .setView(scroll)
            .setNeutralButton(R.string.backup_manager_delete,
                (d, w) -> confirmDeleteBackup(backup))
            .setNegativeButton(android.R.string.cancel, null);
        if (!backup.damaged) {
            builder.setPositiveButton(R.string.backup_detail_restore,
                (d, w) -> showRestoreContainerPicker(backup));
        }
        final AlertDialog dialog = builder.create();
        dialog.setOnDismissListener(d -> {
            if (mBackupDetailDialog == dialog) mBackupDetailDialog = null;
        });
        mBackupDetailDialog = dialog;
        dialog.show();
    }

    /** Dismiss the detail dialog, if open (called when a per-file
     * delete completes, so the detail view never shows a stale list). */
    private void dismissBackupDetail() {
        if (mBackupDetailDialog != null && mBackupDetailDialog.isShowing()) {
            mBackupDetailDialog.dismiss();
        }
        mBackupDetailDialog = null;
    }

    /** Per-file delete for one row of the detail view. Last-file rule:
     * when this is the backup's only item there is no per-file delete
     * — offer to delete the whole backup instead. Otherwise confirm
     * with the item label, then delete on a background thread (SAF /
     * file IO off the UI thread, same pattern as confirmDeleteBackup).
     * The delete calls are the contracts the logic children implement:
     * DistroBackup.deleteBackupItem / ExternalBackupStore.deleteBackupItem,
     * both returning the DistroBackup.DELETE_ITEM_* codes. */
    private void confirmDeleteBackupItem(final UnifiedBackup backup,
            final UnifiedBackup.BackupItemRow item) {
        if (backup.items.size() == 1) {
            new AlertDialog.Builder(this)
                .setMessage(R.string.backup_detail_last_file_confirm)
                .setPositiveButton(R.string.backup_manager_delete,
                    (d, w) -> confirmDeleteBackup(backup))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
            return;
        }
        new AlertDialog.Builder(this)
            .setMessage(getString(R.string.backup_detail_delete_file_confirm, item.label))
            .setPositiveButton(R.string.backup_manager_delete, (dialog, which) ->
                new Thread(() -> {
                    int result;
                    if (backup.external) {
                        result = ExternalBackupStore.deleteBackupItem(
                            DistroActivity.this, backup.externalUri, item.id);
                    } else {
                        result = DistroBackup.deleteBackupItem(backup.dir, item.id);
                    }
                    final int code = result;
                    final String label = item.label;
                    runOnUiThread(() -> onDeleteBackupItemResult(backup, label, code));
                }).start())
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /** Result of a per-file delete, on the UI thread. OK: toast and
     * refresh the manager list. EMPTY: the backup has no files left —
     * ask whether to delete the whole backup (positive) or just refresh
     * (negative). FAILED / NOT_FOUND / anything else: toast failure and
     * refresh. The detail dialog is dismissed first in every branch so
     * it never shows the pre-delete item list. */
    private void onDeleteBackupItemResult(final UnifiedBackup backup, String label, int result) {
        if (result == DistroBackup.DELETE_ITEM_OK) {
            Toast.makeText(this,
                getString(R.string.backup_detail_deleted, label), Toast.LENGTH_LONG).show();
            dismissBackupDetail();
            refreshBackupManager();
        } else if (result == DistroBackup.DELETE_ITEM_EMPTY) {
            dismissBackupDetail();
            new AlertDialog.Builder(this)
                .setMessage(R.string.backup_detail_empty_prompt)
                .setPositiveButton(R.string.backup_manager_delete,
                    (d, w) -> confirmDeleteBackup(backup))
                .setNegativeButton(android.R.string.cancel,
                    (d, w) -> refreshBackupManager())
                .show();
        } else {
            Toast.makeText(this,
                R.string.backup_detail_delete_failed, Toast.LENGTH_LONG).show();
            dismissBackupDetail();
            refreshBackupManager();
        }
    }

    /** Restore from the detail view: dismiss the detail dialog (the
     * positive button already does) and the manager dialog, then list
     * the installed containers by display name. Tapping one opens the
     * existing item picker; the restore rules there are unchanged. */
    private void showRestoreContainerPicker(final UnifiedBackup backup) {
        if (isFinishing() || isDestroyed()) return;
        if (mBackupManagerDialog != null && mBackupManagerDialog.isShowing()) {
            mBackupManagerDialog.dismiss();
        }
        if (mInstalledContainers.isEmpty()) {
            Toast.makeText(this,
                R.string.backup_detail_no_containers, Toast.LENGTH_LONG).show();
            return;
        }
        final List<String> containers = new ArrayList<>(mInstalledContainers);
        Collections.sort(containers,
            (a, b) -> displayNameForContainer(a).compareTo(displayNameForContainer(b)));
        String[] labels = new String[containers.size()];
        for (int i = 0; i < containers.size(); i++) {
            labels[i] = displayNameForContainer(containers.get(i));
        }
        new AlertDialog.Builder(this)
            .setTitle(R.string.backup_detail_pick_container_title)
            .setItems(labels, (dialog, which) -> {
                String container = containers.get(which);
                String display = displayNameForContainer(container);
                File rootfs = DistroUtils.containerRootfs(container);
                if (rootfs == null) {
                    Toast.makeText(DistroActivity.this,
                        R.string.restore_target_missing, Toast.LENGTH_LONG).show();
                    return;
                }
                showItemPicker(container, display, rootfs, backup);
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /** Delete one whole backup, with a confirm dialog. Internal: delete
     * the backup dir. External: delete the SAF dir. The list is
     * refreshed afterwards. (Per-file delete is confirmDeleteBackupItem;
     * the last-file case routes here too.) */
    private void confirmDeleteBackup(final UnifiedBackup backup) {
        new AlertDialog.Builder(this)
            .setMessage(R.string.backup_manager_delete_confirm)
            .setPositiveButton(R.string.backup_manager_delete, (dialog, which) ->
                new Thread(() -> {
                    boolean deleted;
                    if (backup.external) {
                        deleted = ExternalBackupStore.deleteExternalBackup(
                            DistroActivity.this, backup.externalUri);
                    } else {
                        deleted = backup.dir != null && deleteRecursively(backup.dir);
                    }
                    final boolean done = deleted;
                    runOnUiThread(() -> {
                        Toast.makeText(DistroActivity.this,
                            done ? R.string.backup_manager_deleted
                                : R.string.backup_manager_delete_failed,
                            Toast.LENGTH_LONG).show();
                        refreshBackupManager();
                    });
                }).start())
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    // ---- External storage wiring (design.md §6) ----

    /** The "默认备份位置" setting (backup manager). Default is internal. */
    private boolean isExternalBackupDefault() {
        return BACKUP_LOCATION_EXTERNAL.equals(SharedPreferenceUtils
            .getPrivateSharedPreferences(this, BACKUP_PREFS_NAME)
            .getString(PREFS_KEY_DEFAULT_BACKUP_LOCATION, BACKUP_LOCATION_INTERNAL));
    }

    private void setExternalBackupDefault(boolean external) {
        SharedPreferenceUtils.getPrivateSharedPreferences(this, BACKUP_PREFS_NAME).edit()
            .putString(PREFS_KEY_DEFAULT_BACKUP_LOCATION,
                external ? BACKUP_LOCATION_EXTERNAL : BACKUP_LOCATION_INTERNAL)
            .apply();
    }

    /** Folder line in the backup manager's setting row: the
     * human-readable folder path, not the raw content:// URI. */
    private void updateExternalFolderStatus(TextView statusView) {
        if (statusView == null) return;
        String folderPath = ExternalBackupStore.displayFolderPath(this);
        if (folderPath != null) {
            statusView.setText(getString(R.string.backup_external_folder_chosen,
                folderPath));
        } else {
            statusView.setText(R.string.backup_external_folder_not_set);
        }
    }

    /** Remember that an internally packed backup still has to be copied
     * to external storage. Recorded when the backup/uninstall session
     * is launched; performed by the watcher (see
     * startExternalCopyWatcher) as soon as the backup dir is complete,
     * or later by checkPendingExternalCopies / copyPendingExternalBackup
     * when the container is confirmed gone. */
    private void markPendingExternalCopy(String container, File backupDir) {
        SharedPreferences prefs = SharedPreferenceUtils.getPrivateSharedPreferences(
            this, UNINSTALL_PREFS_NAME);
        Set<String> pending = new LinkedHashSet<>(prefs.getStringSet(
            KEY_PENDING_EXTERNAL_COPIES, new LinkedHashSet<String>()));
        pending.add(container + "|" + backupDir.getAbsolutePath());
        prefs.edit().putStringSet(KEY_PENDING_EXTERNAL_COPIES, pending).apply();
    }

    /** Drop a pending external-copy entry (e.g. the uninstall script
     * could not be written, so no backup was ever packed). */
    private void removePendingExternalCopy(String container) {
        SharedPreferences prefs = SharedPreferenceUtils.getPrivateSharedPreferences(
            this, UNINSTALL_PREFS_NAME);
        Set<String> pending = new LinkedHashSet<>(prefs.getStringSet(
            KEY_PENDING_EXTERNAL_COPIES, new LinkedHashSet<String>()));
        boolean changed = pending.removeIf(e -> e.startsWith(container + "|"));
        if (changed) prefs.edit().putStringSet(KEY_PENDING_EXTERNAL_COPIES, pending).apply();
    }

    /** Watch for a backup the terminal session is still packing, and
     * copy it to external storage as soon as it is complete — without
     * waiting for the user to come back to this screen (the old
     * behaviour). The session has no completion callback, so the
     * watcher polls isBackupDirComplete every few seconds; the script
     * writes manifest.json last, so a complete dir means packing is
     * done and copying cannot race the pack step. Stops when the copy
     * has run, when the activity is destroyed (see onDestroy), or
     * after EXTERNAL_COPY_WATCH_TIMEOUT_MS; the pending record and
     * the resume-time checkPendingExternalCopies remain the fallback
     * in all three cases. Call on the UI thread, right after the
     * terminal session is launched. */
    private void startExternalCopyWatcher(final String container, final File backupDir) {
        final Thread watcher = new Thread(() -> {
            try {
                long deadline =
                    System.currentTimeMillis() + EXTERNAL_COPY_WATCH_TIMEOUT_MS;
                while (System.currentTimeMillis() < deadline) {
                    if (Thread.currentThread().isInterrupted()) return;
                    if (isBackupDirComplete(backupDir)) {
                        copyPendingExternalBackup(container);
                        return;
                    }
                    try {
                        Thread.sleep(EXTERNAL_COPY_WATCH_INTERVAL_MS);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                Log.i(LOG_TAG, "External copy watcher timed out for " + container);
            } finally {
                mExternalCopyWatchers.remove(Thread.currentThread());
            }
        });
        watcher.setDaemon(true);
        mExternalCopyWatchers.add(watcher);
        watcher.start();
    }

    /** Interrupt every running external-copy watcher (activity going
     * away; see onDestroy). Copies already in flight are NOT
     * interrupted — only the polling stops. */
    private void stopExternalCopyWatchers() {
        synchronized (mExternalCopyWatchers) {
            for (Thread watcher : mExternalCopyWatchers) watcher.interrupt();
            mExternalCopyWatchers.clear();
        }
    }

    /** Post-pack external copy, triggered by the watcher
     * (startExternalCopyWatcher), by the resume-time check
     * (checkPendingUninstalls / checkPendingExternalCopies), or once
     * the container is confirmed gone:
     * <ol>
     *   <li>The session packs the backup INTERNALLY and (for an
     *       uninstall) runs `proot-distro remove` (the script only
     *       knows local paths).</li>
     *   <li>This copies the packed internal backup dir to the external
     *       folder via ExternalBackupStore.</li>
     *   <li>On success the internal copy is DELETED (external is now
     *       the only copy) and the pending entry is dropped; the Toast
     *       names the external folder.</li>
     *   <li>On failure the internal copy is KEPT and the pending entry
     *       is KEPT, so a later resume retries the copy; the user is
     *       told once per activity lifetime, with the reason.</li>
     * </ol>
     * A copy already in flight for this container makes a second call
     * return at once (see mExternalCopyInProgress). Call on a
     * background thread (the copy can move gigabytes over SAF). If the
     * user never returns to this screen the copy simply waits; the
     * backup stays internal meanwhile. */
    private void copyPendingExternalBackup(String container) {
        // Claim the container first: the watcher and the resume-time
        // check can fire at the same moment, and two SAF copies of the
        // same backup dir must never run concurrently.
        if (!mExternalCopyInProgress.add(container)) return;
        try {
            SharedPreferences prefs = SharedPreferenceUtils.getPrivateSharedPreferences(
                this, UNINSTALL_PREFS_NAME);
            Set<String> pending = new LinkedHashSet<>(prefs.getStringSet(
                KEY_PENDING_EXTERNAL_COPIES, new LinkedHashSet<String>()));
            String match = null;
            File backupDir = null;
            for (String entry : pending) {
                String[] parts = entry.split("\\|", 2);
                if (parts.length == 2 && parts[0].equals(container)) {
                    match = entry;
                    backupDir = new File(parts[1]);
                    break;
                }
            }
            if (match == null) return;
            runOnUiThread(() -> Toast.makeText(DistroActivity.this,
                R.string.backup_external_copying, Toast.LENGTH_LONG).show());
            int copyResult = ExternalBackupStore.COPY_NO_MANIFEST;
            if (backupDir.isDirectory()) {
                copyResult = ExternalBackupStore.copyBackupToExternalWithResult(
                    this, backupDir);
            }
            if (copyResult == ExternalBackupStore.COPY_OK) {
                deleteRecursively(backupDir);
                pending.remove(match);
                prefs.edit().putStringSet(KEY_PENDING_EXTERNAL_COPIES, pending).apply();
                runOnUiThread(() -> {
                    String folderPath = ExternalBackupStore.displayFolderPath(
                        DistroActivity.this);
                    Toast.makeText(DistroActivity.this,
                        getString(R.string.backup_external_copy_done,
                            folderPath == null ? "" : folderPath),
                        Toast.LENGTH_LONG).show();
                });
            } else {
                final int failure = copyResult;
                if (mExternalCopyFailedNoted.add(container)) {
                    runOnUiThread(() -> Toast.makeText(DistroActivity.this,
                        getString(R.string.backup_external_copy_failed,
                            copyErrorReason(failure)),
                        Toast.LENGTH_LONG).show());
                }
            }
        } finally {
            mExternalCopyInProgress.remove(container);
        }
    }

    /** Short user-facing reason for a COPY_* result code (see
     * ExternalBackupStore). Called on the UI thread. */
    private String copyErrorReason(int copyResult) {
        switch (copyResult) {
            case ExternalBackupStore.COPY_NO_FOLDER:
                return getString(R.string.backup_external_copy_reason_no_folder);
            case ExternalBackupStore.COPY_NO_MANIFEST:
                return getString(R.string.backup_external_copy_reason_no_manifest);
            case ExternalBackupStore.COPY_CREATE_FAILED:
                return getString(R.string.backup_external_copy_reason_create_failed);
            case ExternalBackupStore.COPY_VERIFY_FAILED:
                return getString(R.string.backup_external_copy_reason_verify_failed);
            default:
                return getString(R.string.backup_external_copy_reason_copy_failed);
        }
    }

    /** Recursive delete of a local file/dir. Returns true if gone. */
    private static boolean deleteRecursively(File file) {
        if (file == null || !file.exists()) return true;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursively(child);
            }
        }
        return file.delete();
    }

}
