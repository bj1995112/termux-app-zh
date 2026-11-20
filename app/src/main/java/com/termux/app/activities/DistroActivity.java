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
import android.os.Bundle;
import android.util.AtomicFile;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.termux.R;
import com.termux.shared.activity.media.AppCompatActivityUtils;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class DistroActivity extends AppCompatActivity {

    private static final String LOG_TAG = "DistroActivity";

    /** Intent extra: container name whose options dialog opens automatically
     * (drawer long-press). */
    public static final String EXTRA_CONTAINER_NAME = "com.termux.distro.container_name";

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

    // The 7 supported distros. Only the Docker Hub image names are fixed here;
    // version numbers are fetched from the Docker Hub tags API at runtime.
    private static final List<DistroDef> DISTROS = Arrays.asList(
        new DistroDef("ubuntu", "Ubuntu", "可选最新版或长期支持版", "ubuntu",
            new TagSource("ubuntu", TagFilter.DOTTED_VERSION, "")),
        new DistroDef("debian", "Debian", "可选代号版本", "debian",
            new TagSource("debian", TagFilter.DEBIAN_CODENAME, "")),
        new DistroDef("arch", "Arch Linux", "滚动更新", "archlinux",
            new TagSource("archlinux", TagFilter.ROLLING, "")),
        new DistroDef("alpine", "Alpine Linux", "轻量小巧", "alpine",
            new TagSource("alpine", TagFilter.DOTTED_VERSION, "")),
        new DistroDef("fedora", "Fedora", "可选数字版本", "fedora",
            new TagSource("fedora", TagFilter.INTEGER_VERSION, "")),
        new DistroDef("opensuse", "openSUSE", "Leap 稳定版 / Tumbleweed 滚动版", "opensuse",
            new TagSource("opensuse/leap", TagFilter.DOTTED_VERSION, "Leap "),
            new TagSource("opensuse/tumbleweed", TagFilter.ROLLING, "Tumbleweed ")),
        new DistroDef("void", "Void Linux", "滚动更新（镜像较旧，装完请立即升级）", "voidlinux",
            new TagSource("voidlinux/voidlinux", TagFilter.ROLLING, ""))
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
    private boolean mListLoading = true;
    /** "latest" label, resolved on the UI thread once and reused in background threads. */
    private String mLatestLabel;
    /** When the cached list was fetched, 0 if there is no cache. */
    private long mCacheUpdatedAt;

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
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.distro_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_refresh_distro_list) {
            refreshVersionsInBackground();
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
                options.add(new VersionOption(mLatestLabel, "archlinux:latest", "2026-11-10"));
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
            case "void":
                options.add(new VersionOption(mLatestLabel, "voidlinux/voidlinux:latest", "2021-07-25"));
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
        new AlertDialog.Builder(this)
            .setTitle(getString(R.string.distro_install_title, distro.displayName + " " + version.display))
            .setMessage(getString(R.string.distro_install_confirm, version.imageRef))
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
            case "void":
                return "xbps-install -Sy " + packages;
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
        try {
            File parent = scriptFile.getParentFile();
            if (parent != null && !(parent.isDirectory() || parent.mkdirs())) {
                throw new IOException("Cannot create " + parent.getAbsolutePath());
            }
            try (FileOutputStream out = new FileOutputStream(scriptFile)) {
                out.write(buildInstallScript(distro, version).getBytes(StandardCharsets.UTF_8));
            }
            scriptFile.setExecutable(true, true);
        } catch (Exception e) {
            Log.w(LOG_TAG, "Failed to write install script", e);
            Toast.makeText(this, getString(R.string.distro_install_script_failed, e.getMessage()),
                Toast.LENGTH_LONG).show();
            return;
        }
        String sessionName = getString(R.string.distro_session_install,
            distro.displayName + " " + version.display);
        DistroUtils.startSessionWithExecutable(this, scriptFile.getAbsolutePath(), null, sessionName, sessionName);
        Toast.makeText(this, R.string.distro_install_started, Toast.LENGTH_LONG).show();
        finish();
    }

    private static String buildInstallScript(DistroDef distro, VersionOption version) {
        StringBuilder script = new StringBuilder();
        script.append("#!/data/data/com.termux/files/usr/bin/bash\n");
        script.append("# Generated by Termux \"Linux 发行版\" (DistroActivity) - do not edit.\n");
        script.append("echo \"正在安装 ").append(distro.displayName).append(" ").append(version.display)
            .append(" (").append(version.imageRef).append(") ...\"\n");
        script.append("if ! command -v proot-distro >/dev/null 2>&1; then\n");
        script.append("  echo \"正在安装 proot-distro ...\"\n");
        script.append("  pkg install -y proot-distro\n");
        script.append("  if ! command -v proot-distro >/dev/null 2>&1; then\n");
        script.append("    echo \"安装 proot-distro 失败，请检查网络后重试。\"\n");
        script.append("    read -r -p \"按回车关闭此会话\"\n");
        script.append("    exit 1\n");
        script.append("  fi\n");
        script.append("fi\n");
        script.append("proot-distro install ").append(version.imageRef)
            .append(" --name ").append(version.container).append("\n");
        script.append("status=$?\n");
        script.append("if [ \"$status\" -eq 0 ]; then\n");
        script.append("  echo \"安装成功！回到“Linux 发行版”界面，点“进入”即可使用。\"\n");
        script.append("else\n");
        script.append("  echo \"安装失败（退出码 $status）。常见原因：网络中断或 Docker Hub 限流，稍后重试。\"\n");
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
     * (dsh, OpenCode, Pi) get an extra "打开 X 网页版" item, which starts
     * the web server in a visible session and shows how to reach it; this
     * screen closes only after that dialog is dismissed. */
    private void showQuickStartDialog(final DistroDef distro, final String container,
                                      final String display,
                                      final List<AiAgentUtils.InstalledAgent> agents) {
        List<String> names = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        for (final AiAgentUtils.InstalledAgent agent : agents) {
            names.add(agent.displayName);
            actions.add(() -> {
                AiAgentUtils.startAgentSession(DistroActivity.this, agent);
                finish();
            });
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

}
