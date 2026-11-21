package com.termux.app.activities;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.termux.R;
import com.termux.shared.settings.preferences.SharedPreferenceUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Download-source (换下载源) settings.
 *
 * One global preset governs every distro and every AI agent install:
 * which distro package mirror, which registry pull-through mirror, which
 * npm registry and which PyPI index to use. The preset is stored in the
 * "download_mirror" SharedPreferences file.
 *
 * applyToContainer() rewrites a distro's package-source files inside its
 * rootfs (backup-before-write, restore via restoreOfficialInContainer()).
 * mirrorScriptExports() returns the shell exports an agent-install script
 * needs (npm / PyPI / uv / Hermes overrides). imageRefFor() rewrites a
 * Docker Hub image ref to go through the preset's registry mirror.
 */
public final class MirrorSettings {

    private static final String TAG = "MirrorSettings";

    public static final String PREFS_NAME = "download_mirror";
    public static final String KEY_MIRROR_ID = "mirror_id";
    public static final String KEY_REGISTRY_OVERRIDE = "registry_override";
    public static final String KEY_CUSTOM_DISTRO_HOST = "custom_distro_host";
    public static final String KEY_CUSTOM_REGISTRY = "custom_registry";
    public static final String KEY_CUSTOM_NPM = "custom_npm";
    public static final String KEY_CUSTOM_PYPI = "custom_pypi";
    public static final String KEY_CUSTOM_GITHUB_PROXY = "custom_github_proxy";
    public static final String KEY_PENDING_INSTALLS = "pending_installs";

    public static final String PRESET_OFFICIAL = "official";
    public static final String PRESET_TUNA = "tuna";
    public static final String PRESET_USTC = "ustc";
    public static final String PRESET_ALIYUN = "aliyun";
    public static final String PRESET_TENCENT = "tencent";
    public static final String PRESET_HUAWEI = "huawei";
    public static final String PRESET_CUSTOM = "custom";

    public static final List<String> PRESET_IDS = Arrays.asList(
            PRESET_OFFICIAL, PRESET_TUNA, PRESET_USTC, PRESET_ALIYUN,
            PRESET_TENCENT, PRESET_HUAWEI, PRESET_CUSTOM);

    /** Empty means "follow the preset". */
    public static final String REGISTRY_FOLLOW = "";
    /** Explicit "use Docker Hub directly" override. This is a marker,
     *  never a host: effectiveRegistry() maps it to "" (Docker Hub). It
     *  exists so "使用推荐" can pin Docker Hub even with a non-official
     *  preset, where REGISTRY_FOLLOW would mean that preset's mirror. */
    public static final String REGISTRY_DOCKER_HUB = "docker-hub";
    public static final String REGISTRY_DAOCLOUD = "docker.m.daocloud.io";
    public static final String REGISTRY_DOCKERPROXY = "dockerproxy.net";
    public static final String REGISTRY_1MS = "docker.1ms.run";

    /** Registry choices the detect-and-recommend test measures
     *  (REGISTRY_DOCKER_HUB = Docker Hub directly). */
    public static final List<String> REGISTRY_OPTIONS = Arrays.asList(
            REGISTRY_DOCKER_HUB, REGISTRY_DAOCLOUD, REGISTRY_DOCKERPROXY, REGISTRY_1MS);

    public static final String NPM_OFFICIAL_URL = "https://registry.npmjs.org";
    public static final String NPM_MIRROR_URL = "https://registry.npmmirror.com";
    public static final String PYPI_OFFICIAL_URL = "https://pypi.org/simple";
    public static final String UV_PYTHON_MIRROR_URL = "https://registry.npmmirror.com/-/binary/python-build-standalone";
    public static final String NODE_TARBALL_INDEX_URL = "https://npmmirror.com/mirrors/node/index.json";
    public static final String NODE_TARBALL_FALLBACK_VERSION = "v24.22.3";

    /** Marker appended to lines we insert ourselves, so re-apply/restore can find them. */
    public static final String MARKER = "# termux-app-zh mirror";

    private static final String BACKUP_DIR_NAME = ".termux-aiagents/mirror-backup";
    private static final long PENDING_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000;
    private static final java.util.concurrent.atomic.AtomicBoolean PENDING_GUARD =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** applyToContainer() outcomes. */
    public static final int APPLY_OK = 1;
    public static final int APPLY_NO_CHANGE = 0;
    public static final int APPLY_SKIPPED = -1;
    public static final int APPLY_FAILED = -2;

    /** Official package hosts, per distro, used when restoring official sources. */
    private static final String OFFICIAL_UBUNTU = "archive.ubuntu.com";
    private static final String OFFICIAL_DEBIAN = "deb.debian.org";
    private static final String OFFICIAL_ALPINE = "dl-cdn.alpinelinux.org";
    private static final String OFFICIAL_FEDORA = "download.fedoraproject.org";
    private static final String OFFICIAL_OPENSUSE = "download.opensuse.org";
    private static final String OFFICIAL_VOID = "repo-default.voidlinux.org";

    /** Hosts a package-source file may legitimately contain; all of them are
     *  rewritten to the target host when a mirror is applied. */
    private static final String[] KNOWN_HOSTS = {
            "archive.ubuntu.com", "security.ubuntu.com",
            "deb.debian.org", "security.debian.org",
            "dl-cdn.alpinelinux.org",
            "download.fedoraproject.org",
            "download.opensuse.org",
            "repo-default.voidlinux.org",
            "mirrors.tuna.tsinghua.edu.cn",
            "mirrors.ustc.edu.cn",
            "mirrors.aliyun.com",
            "mirrors.tencent.com",
            "mirrors.huaweicloud.com",
    };

    public static final class MirrorPreset {
        public final String id;
        /** Distro package mirror host, "" = official sources. */
        public final String distroHost;
        /** Registry pull-through mirror host, "" = Docker Hub directly. */
        public final String registry;
        public final String npm;
        public final String pypi;
        /** Third-party GitHub proxy origin, "" = none. Custom preset only. */
        public final String githubProxy;
        /** Registry override: "" = follow the preset. */
        public String registryOverride = REGISTRY_FOLLOW;

        public MirrorPreset(String id, String distroHost, String registry,
                            String npm, String pypi, String githubProxy) {
            this.id = id;
            this.distroHost = distroHost != null ? distroHost : "";
            this.registry = registry != null ? registry : "";
            this.npm = npm != null ? npm : NPM_OFFICIAL_URL;
            this.pypi = pypi != null ? pypi : PYPI_OFFICIAL_URL;
            this.githubProxy = githubProxy != null ? githubProxy : "";
        }
    }

    public static final class SyncResult {
        public final int synced;
        public final int failed;

        public SyncResult(int synced, int failed) {
            this.synced = synced;
            this.failed = failed;
        }
    }

    // ------------------------------------------------------------------
    // Detect and recommend (one-click mirror test)
    // ------------------------------------------------------------------

    /** One measured source: latency in ms (-1 = did not answer) and
     *  real download speed in KB/s (-1 = not measured or failed). */
    public static final class PresetDetectResult {
        public final String presetId;
        public final long latencyMs;
        public final long speedKBps;
        /** Labels of the preset's other endpoints that did not answer
         *  (npm / pypi / registry, as in probePreset). */
        public final List<String> unreachable;

        public PresetDetectResult(String presetId, long latencyMs, long speedKBps,
                                  List<String> unreachable) {
            this.presetId = presetId;
            this.latencyMs = latencyMs;
            this.speedKBps = speedKBps;
            this.unreachable = unreachable != null ? unreachable : new ArrayList<String>();
        }

        public boolean isReachable() {
            return latencyMs >= 0;
        }

        public boolean hasSpeed() {
            return speedKBps >= 0;
        }
    }

    public static final class RegistryDetectResult {
        /** REGISTRY_DOCKER_HUB = Docker Hub directly. */
        public final String registry;
        public final long latencyMs;
        public final long speedKBps;

        public RegistryDetectResult(String registry, long latencyMs, long speedKBps) {
            this.registry = registry != null ? registry : "";
            this.latencyMs = latencyMs;
            this.speedKBps = speedKBps;
        }

        public boolean isReachable() {
            return latencyMs >= 0;
        }

        public boolean hasSpeed() {
            return speedKBps >= 0;
        }
    }

    public static final class DetectOutcome {
        public final List<PresetDetectResult> presets;
        public final List<RegistryDetectResult> registries;
        /** Fastest preset by measured speed; null when none measured. */
        public final PresetDetectResult bestPreset;
        /** Fastest registry by measured speed; null when none measured. */
        public final RegistryDetectResult bestRegistry;

        public DetectOutcome(List<PresetDetectResult> presets,
                             List<RegistryDetectResult> registries,
                             PresetDetectResult bestPreset,
                             RegistryDetectResult bestRegistry) {
            this.presets = presets;
            this.registries = registries;
            this.bestPreset = bestPreset;
            this.bestRegistry = bestRegistry;
        }
    }

    /** Progress callback for detectAll(), invoked on the calling (background) thread. */
    public interface DetectProgressListener {
        void onProgress(int done, int total);
    }

    private MirrorSettings() {
    }

    // ------------------------------------------------------------------
    // Presets
    // ------------------------------------------------------------------

    public static MirrorPreset presetForId(String id) {
        if (PRESET_TUNA.equals(id)) {
            return new MirrorPreset(PRESET_TUNA, "mirrors.tuna.tsinghua.edu.cn",
                    REGISTRY_DAOCLOUD, NPM_MIRROR_URL,
                    "https://pypi.tuna.tsinghua.edu.cn/simple", "");
        }
        if (PRESET_USTC.equals(id)) {
            return new MirrorPreset(PRESET_USTC, "mirrors.ustc.edu.cn",
                    REGISTRY_DAOCLOUD, NPM_MIRROR_URL,
                    "https://mirrors.ustc.edu.cn/pypi/web/simple", "");
        }
        if (PRESET_ALIYUN.equals(id)) {
            return new MirrorPreset(PRESET_ALIYUN, "mirrors.aliyun.com",
                    REGISTRY_DAOCLOUD, NPM_MIRROR_URL,
                    "https://mirrors.aliyun.com/pypi/simple", "");
        }
        if (PRESET_TENCENT.equals(id)) {
            // Tencent's own Docker mirror is dead, so the registry stays
            // DaoCloud; only the distro host and PyPI are Tencent's.
            return new MirrorPreset(PRESET_TENCENT, "mirrors.tencent.com",
                    REGISTRY_DAOCLOUD, NPM_MIRROR_URL,
                    "https://mirrors.cloud.tencent.com/pypi/simple", "");
        }
        if (PRESET_HUAWEI.equals(id)) {
            return new MirrorPreset(PRESET_HUAWEI, "mirrors.huaweicloud.com",
                    REGISTRY_DAOCLOUD, NPM_MIRROR_URL,
                    "https://mirrors.huaweicloud.com/repository/pypi/simple", "");
        }
        if (PRESET_CUSTOM.equals(id)) {
            return new MirrorPreset(PRESET_CUSTOM, "", "", NPM_MIRROR_URL, PYPI_OFFICIAL_URL, "");
        }
        return new MirrorPreset(PRESET_OFFICIAL, "", "", NPM_OFFICIAL_URL, PYPI_OFFICIAL_URL, "");
    }

    /** Old stored value "dockerproxy.com" now means dockerproxy.net
     *  (the host moved; .com only 301-redirects there). */
    public static String normalizeRegistryOverride(String override) {
        if ("dockerproxy.com".equals(override)) return REGISTRY_DOCKERPROXY;
        // Anything else (including the REGISTRY_DOCKER_HUB sentinel)
        // passes through unchanged.
        return override != null ? override : REGISTRY_FOLLOW;
    }

    public static MirrorPreset loadPreset(Context context) {
        SharedPreferences prefs = SharedPreferenceUtils.getPrivateSharedPreferences(context, PREFS_NAME);
        String id = prefs.getString(KEY_MIRROR_ID, PRESET_OFFICIAL);
        if (PRESET_CUSTOM.equals(id)) return loadCustomPreset(context);
        MirrorPreset preset = presetForId(id);
        preset.registryOverride = normalizeRegistryOverride(
                prefs.getString(KEY_REGISTRY_OVERRIDE, REGISTRY_FOLLOW));
        return preset;
    }

    /** The custom preset as the user configured it (stored custom fields). */
    public static MirrorPreset loadCustomPreset(Context context) {
        SharedPreferences prefs = SharedPreferenceUtils.getPrivateSharedPreferences(context, PREFS_NAME);
        MirrorPreset preset = new MirrorPreset(PRESET_CUSTOM,
                prefs.getString(KEY_CUSTOM_DISTRO_HOST, ""),
                prefs.getString(KEY_CUSTOM_REGISTRY, ""),
                orDefault(prefs.getString(KEY_CUSTOM_NPM, ""), NPM_MIRROR_URL),
                orDefault(prefs.getString(KEY_CUSTOM_PYPI, ""), PYPI_OFFICIAL_URL),
                prefs.getString(KEY_CUSTOM_GITHUB_PROXY, ""));
        preset.registryOverride = normalizeRegistryOverride(
                prefs.getString(KEY_REGISTRY_OVERRIDE, REGISTRY_FOLLOW));
        return preset;
    }

    /** True when the user has configured any custom field worth detecting. */
    public static boolean isCustomConfigured(Context context) {
        MirrorPreset custom = loadCustomPreset(context);
        return !custom.distroHost.isEmpty() || !custom.registry.isEmpty()
                || !custom.githubProxy.isEmpty();
    }

    public static void savePreset(Context context, MirrorPreset preset) {
        SharedPreferences prefs = SharedPreferenceUtils.getPrivateSharedPreferences(context, PREFS_NAME);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(KEY_MIRROR_ID, preset.id);
        editor.putString(KEY_REGISTRY_OVERRIDE,
                normalizeRegistryOverride(preset.registryOverride));
        if (PRESET_CUSTOM.equals(preset.id)) {
            editor.putString(KEY_CUSTOM_DISTRO_HOST, preset.distroHost);
            editor.putString(KEY_CUSTOM_REGISTRY, preset.registry);
            editor.putString(KEY_CUSTOM_NPM, preset.npm);
            editor.putString(KEY_CUSTOM_PYPI, preset.pypi);
            editor.putString(KEY_CUSTOM_GITHUB_PROXY, preset.githubProxy);
        }
        editor.apply();
    }

    /** String resource for a preset id (settings summary). Unknown ids fall back to Official. */
    public static int presetNameResId(String id) {
        if (PRESET_TUNA.equals(id)) return R.string.mirror_preset_tuna;
        if (PRESET_USTC.equals(id)) return R.string.mirror_preset_ustc;
        if (PRESET_ALIYUN.equals(id)) return R.string.mirror_preset_aliyun;
        if (PRESET_TENCENT.equals(id)) return R.string.mirror_preset_tencent;
        if (PRESET_HUAWEI.equals(id)) return R.string.mirror_preset_huawei;
        if (PRESET_CUSTOM.equals(id)) return R.string.mirror_preset_custom;
        return R.string.mirror_preset_official;
    }

    public static boolean isOfficial(MirrorPreset preset) {
        return preset == null || PRESET_OFFICIAL.equals(preset.id);
    }

    /** Effective registry host: the override wins, otherwise the preset's. "" = Docker Hub. */
    public static String effectiveRegistry(MirrorPreset preset) {
        if (preset == null) return "";
        if (REGISTRY_DOCKER_HUB.equals(preset.registryOverride)) return "";
        if (preset.registryOverride != null && !preset.registryOverride.isEmpty()) {
            return preset.registryOverride;
        }
        return preset.registry;
    }

    /** Rewrite a Docker Hub image ref to go through the preset's registry mirror.
     *  "ubuntu:26.04" -> "docker.m.daocloud.io/library/ubuntu:26.04",
     *  "opensuse/leap:16.1" -> "docker.m.daocloud.io/opensuse/leap:16.1".
     *  Refs that already name a registry are returned unchanged. */
    public static String imageRefFor(String imageRef, MirrorPreset preset) {
        if (imageRef == null || imageRef.isEmpty()) return imageRef;
        String registry = effectiveRegistry(preset);
        if (registry == null || registry.isEmpty()) return imageRef;
        int firstSlash = imageRef.indexOf('/');
        if (firstSlash > 0) {
            String first = imageRef.substring(0, firstSlash);
            if (first.contains(".") || first.contains(":")) return imageRef;
            return registry + "/" + imageRef;
        }
        return registry + "/library/" + imageRef;
    }

    // ------------------------------------------------------------------
    // Agent-install script exports
    // ------------------------------------------------------------------

    /** Shell exports for the top of an agent-install script, so npm / uv /
     *  Hermes downloads inside the distro use the selected mirrors.
     *  npm is set with an env var (not "npm config set -g") so it works
     *  even before npm exists, when the Node tarball step runs later. */
    public static String mirrorScriptExports(MirrorPreset preset) {
        if (preset == null) preset = presetForId(PRESET_OFFICIAL);
        StringBuilder sb = new StringBuilder();
        sb.append("export npm_config_registry=").append(preset.npm).append("\n");
        sb.append("export UV_DEFAULT_INDEX=").append(preset.pypi).append("\n");
        sb.append("export UV_INDEX_URL=").append(preset.pypi).append("\n");
        if (!PYPI_OFFICIAL_URL.equals(preset.pypi)) {
            sb.append("export UV_PYTHON_INSTALL_MIRROR=").append(UV_PYTHON_MIRROR_URL).append("\n");
        }
        if (!preset.githubProxy.isEmpty()) {
            // uv's installer rewrites its GitHub root; the proxy form is
            // "<proxy>/https://github.com" (see design notes).
            sb.append("export UV_INSTALLER_GITHUB_BASE_URL=").append(preset.githubProxy)
                    .append("/https://github.com\n");
            // Hermes install.sh: REPO_URL="${HERMES_REPO_URL:-https://github.com/NousResearch/hermes-agent.git}"
            sb.append("export HERMES_REPO_URL=").append(preset.githubProxy)
                    .append("/https://github.com/NousResearch/hermes-agent.git\n");
        }
        return sb.toString();
    }

    /** Shell snippet that installs Node 24 from the npmmirror binary tarball
     *  (NodeSource has no Chinese mirror). Resolves the newest v24.x from
     *  the npmmirror index, falls back to a known-good version, then verifies
     *  Node >= 24.9 with the same node_ok() check the script already uses. */
    public static String nodeTarballInstallCommand() {
        return "NODE_VER=$(curl -fsSL --max-time 15 " + NODE_TARBALL_INDEX_URL
                + " 2>/dev/null | grep -o '\"version\":\"v24\\.[0-9.]*\"' | grep -o 'v24\\.[0-9.]*' | sort -V | tail -1)\n"
                + "NODE_VER=${NODE_VER:-" + NODE_TARBALL_FALLBACK_VERSION + "}\n"
                + "curl -fsSL --max-time 60 https://npmmirror.com/mirrors/node/$NODE_VER/node-$NODE_VER-linux-arm64.tar.xz -o /tmp/node.tar.xz"
                + " && mkdir -p /usr/local/lib/nodejs"
                + " && tar -xJf /tmp/node.tar.xz -C /usr/local/lib/nodejs"
                + " && ln -sf /usr/local/lib/nodejs/node-$NODE_VER-linux-arm64/bin/node /usr/local/bin/node"
                + " && ln -sf /usr/local/lib/nodejs/node-$NODE_VER-linux-arm64/bin/npm /usr/local/bin/npm"
                + " && ln -sf /usr/local/lib/nodejs/node-$NODE_VER-linux-arm64/bin/npx /usr/local/bin/npx";
    }

    // ------------------------------------------------------------------
    // Applying a preset to an installed distro (rootfs writes)
    // ------------------------------------------------------------------

    /** Apply the preset's distro package mirror to one installed container.
     *  Returns APPLY_OK / APPLY_NO_CHANGE / APPLY_SKIPPED / APPLY_FAILED.
     *  Void is only mirrored for TUNA or a custom preset (other presets keep
     *  its official source and return APPLY_SKIPPED). */
    public static int applyToContainer(File rootfs, String distroId, MirrorPreset preset) {
        if (rootfs == null || !rootfs.isDirectory() || distroId == null) return APPLY_FAILED;
        if (preset == null) preset = presetForId(PRESET_OFFICIAL);
        String target = preset.distroHost; // "" = official
        try {
            if ("ubuntu".equals(distroId)) {
                return applyUbuntuDebian(rootfs, target, OFFICIAL_UBUNTU,
                        "etc/apt/sources.list.d/ubuntu.sources", "etc/apt/sources.list");
            }
            if ("debian".equals(distroId)) {
                return applyUbuntuDebian(rootfs, target, OFFICIAL_DEBIAN,
                        "etc/apt/sources.list.d/debian.sources", "etc/apt/sources.list");
            }
            if ("arch".equals(distroId)) {
                return applyArch(rootfs, target);
            }
            if ("alpine".equals(distroId)) {
                return applyAlpine(rootfs, target);
            }
            if ("fedora".equals(distroId)) {
                return applyFedora(rootfs, target);
            }
            if ("opensuse".equals(distroId)) {
                return applyOpenSuse(rootfs, target);
            }
            if ("void".equals(distroId)) {
                if (!target.isEmpty() && !PRESET_TUNA.equals(preset.id)
                        && !PRESET_CUSTOM.equals(preset.id)) {
                    return APPLY_SKIPPED;
                }
                return applyVoid(rootfs, target);
            }
            return APPLY_SKIPPED;
        } catch (IOException e) {
            Log.e(TAG, "applyToContainer failed for " + distroId, e);
            return APPLY_FAILED;
        }
    }

    /** Restore official sources in one container from the backup made by
     *  applyToContainer(); files we never modified are left alone. */
    public static int restoreOfficialInContainer(File rootfs, String distroId) {
        if (rootfs == null || !rootfs.isDirectory() || distroId == null) return APPLY_FAILED;
        try {
            if ("ubuntu".equals(distroId) || "debian".equals(distroId)) {
                String official = "ubuntu".equals(distroId) ? OFFICIAL_UBUNTU : OFFICIAL_DEBIAN;
                return restoreUbuntuDebian(rootfs, official,
                        "etc/apt/sources.list.d/" + distroId + ".sources", "etc/apt/sources.list");
            }
            if ("arch".equals(distroId)) return restoreArch(rootfs);
            if ("alpine".equals(distroId)) return restoreGeneric(rootfs, "etc/apk/repositories", OFFICIAL_ALPINE);
            if ("fedora".equals(distroId)) return restoreFedora(rootfs);
            if ("opensuse".equals(distroId)) return restoreOpenSuse(rootfs);
            if ("void".equals(distroId)) return restoreVoid(rootfs);
            return APPLY_SKIPPED;
        } catch (IOException e) {
            Log.e(TAG, "restoreOfficialInContainer failed for " + distroId, e);
            return APPLY_FAILED;
        }
    }

    // --- Ubuntu / Debian: swap hosts in the DEB822 .sources file and/or sources.list ---

    private static int applyUbuntuDebian(File rootfs, String target, String official,
                                         String sourcesFile, String legacyFile) throws IOException {
        File primary = new File(rootfs, sourcesFile);
        File legacy = new File(rootfs, legacyFile);
        if (!primary.exists() && !legacy.exists()) return APPLY_SKIPPED;
        String host = target.isEmpty() ? official : target;
        boolean changed = false;
        changed |= rewriteFileHosts(rootfs, sourcesFile, host);
        changed |= rewriteFileHosts(rootfs, legacyFile, host);
        return changed ? APPLY_OK : APPLY_NO_CHANGE;
    }

    private static int restoreUbuntuDebian(File rootfs, String official,
                                           String sourcesFile, String legacyFile) throws IOException {
        boolean changed = false;
        // A backup means we modified the file: restore it exactly.
        // No backup means we never touched it: only swap a known mirror host
        // back to official, never rewrite a user's own pre-existing config.
        if (restoreFile(rootfs, sourcesFile)) {
            changed = true;
        } else {
            changed |= rewriteFileHosts(rootfs, sourcesFile, official);
        }
        if (restoreFile(rootfs, legacyFile)) {
            changed = true;
        } else {
            changed |= rewriteFileHosts(rootfs, legacyFile, official);
        }
        return changed ? APPLY_OK : APPLY_NO_CHANGE;
    }

    // --- Arch: our Server line goes first in the mirrorlist (pacman tries in order) ---

    private static int applyArch(File rootfs, String target) throws IOException {
        File mirrorlist = new File(rootfs, "etc/pacman.d/mirrorlist");
        if (!mirrorlist.exists()) return APPLY_SKIPPED;
        backupFile(rootfs, "etc/pacman.d/mirrorlist");
        String content = readFile(mirrorlist);
        String stripped = removeMarkerLines(content);
        if (target.isEmpty()) {
            if (stripped.equals(content)) return APPLY_NO_CHANGE;
            writeFile(mirrorlist, stripped);
            return APPLY_OK;
        }
        String line = "Server = https://" + target + "/archlinux/$repo/os/$arch " + MARKER + "\n";
        // Compare against the full file: the line may already be first
        // (re-apply), in which case writing it again changes nothing.
        if ((line + stripped).equals(content)) return APPLY_NO_CHANGE;
        writeFile(mirrorlist, line + stripped);
        return APPLY_OK;
    }

    private static int restoreArch(File rootfs) throws IOException {
        if (restoreFile(rootfs, "etc/pacman.d/mirrorlist")) return APPLY_OK;
        File mirrorlist = new File(rootfs, "etc/pacman.d/mirrorlist");
        if (!mirrorlist.exists()) return APPLY_SKIPPED;
        String content = readFile(mirrorlist);
        String stripped = removeMarkerLines(content);
        if (stripped.equals(content)) return APPLY_NO_CHANGE;
        writeFile(mirrorlist, stripped);
        return APPLY_OK;
    }

    // --- Alpine: swap the host in /etc/apk/repositories ---

    private static int applyAlpine(File rootfs, String target) throws IOException {
        File repos = new File(rootfs, "etc/apk/repositories");
        String host = target.isEmpty() ? OFFICIAL_ALPINE : target;
        if (!repos.exists()) {
            String release = readFileTrimmed(new File(rootfs, "etc/alpine-release"));
            String ver = "v" + alpineMajorMinor(release);
            String content = "https://" + host + "/alpine/" + ver + "/main\n"
                    + "https://" + host + "/alpine/" + ver + "/community\n";
            writeFile(repos, content);
            markCreated(rootfs, "etc/apk/repositories");
            return APPLY_OK;
        }
        return rewriteFileHosts(rootfs, "etc/apk/repositories", host) ? APPLY_OK : APPLY_NO_CHANGE;
    }

    private static String alpineMajorMinor(String release) {
        if (release == null || release.isEmpty()) return "3.24";
        String[] parts = release.split("\\.");
        if (parts.length >= 2) return parts[0] + "." + parts[1];
        return "3.24";
    }

    // --- Fedora: comment out metalink, set baseurl to the target host ---

    private static int applyFedora(File rootfs, String target) throws IOException {
        File repoDir = new File(rootfs, "etc/yum.repos.d");
        File[] repos = repoDir.listFiles();
        if (repos == null || repos.length == 0) return APPLY_SKIPPED;
        String host = target.isEmpty() ? OFFICIAL_FEDORA : target;
        boolean changed = false;
        for (File repo : repos) {
            if (!repo.getName().endsWith(".repo")) continue;
            backupFile(rootfs, "etc/yum.repos.d/" + repo.getName());
            String content = readFile(repo);
            String rewritten = rewriteFedoraRepo(content, host);
            if (!rewritten.equals(content)) {
                writeFile(repo, rewritten);
                changed = true;
            }
        }
        return changed ? APPLY_OK : APPLY_NO_CHANGE;
    }

    /** metalink= -> #metalink= ; baseurl (active or commented) -> baseurl=https://<host><old path>. */
    private static String rewriteFedoraRepo(String content, String host) {
        StringBuilder out = new StringBuilder();
        for (String line : content.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("metalink=")) {
                out.append("#").append(line).append("\n");
            } else if (trimmed.startsWith("baseurl=") || trimmed.startsWith("#baseurl=")) {
                String value = trimmed.startsWith("#") ? trimmed.substring(1) : trimmed;
                int schemeEnd = value.indexOf("://");
                String path = "";
                if (schemeEnd >= 0) {
                    int hostEnd = value.indexOf('/', schemeEnd + 3);
                    if (hostEnd >= 0) path = value.substring(hostEnd);
                }
                out.append("baseurl=https://").append(host).append(path).append("\n");
            } else {
                out.append(line).append("\n");
            }
        }
        // split(-1) + one "\n" per element adds a single extra trailing
        // newline; strip it to keep the original line structure.
        String result = out.toString();
        if (result.endsWith("\n")) result = result.substring(0, result.length() - 1);
        return result;
    }

    private static int restoreFedora(File rootfs) throws IOException {
        File repoDir = new File(rootfs, "etc/yum.repos.d");
        File[] repos = repoDir.listFiles();
        if (repos == null || repos.length == 0) return APPLY_SKIPPED;
        boolean changed = false;
        for (File repo : repos) {
            if (!repo.getName().endsWith(".repo")) continue;
            if (restoreFile(rootfs, "etc/yum.repos.d/" + repo.getName())) {
                changed = true;
            } else {
                // No backup: only swap a known mirror host back to official;
                // leave metalink as-is (dnf still works via baseurl).
                changed |= rewriteFileHosts(rootfs, "etc/yum.repos.d/" + repo.getName(),
                        OFFICIAL_FEDORA);
            }
        }
        return changed ? APPLY_OK : APPLY_NO_CHANGE;
    }

    // --- openSUSE: sed-style host swap in every .repo file ---

    private static int applyOpenSuse(File rootfs, String target) throws IOException {
        File repoDir = new File(rootfs, "etc/zypp/repos.d");
        File[] repos = repoDir.listFiles();
        if (repos == null || repos.length == 0) return APPLY_SKIPPED;
        String host = target.isEmpty() ? OFFICIAL_OPENSUSE : target;
        boolean changed = false;
        for (File repo : repos) {
            if (!repo.getName().endsWith(".repo")) continue;
            changed |= rewriteFileHosts(rootfs, "etc/zypp/repos.d/" + repo.getName(), host);
        }
        return changed ? APPLY_OK : APPLY_NO_CHANGE;
    }

    private static int restoreOpenSuse(File rootfs) throws IOException {
        File repoDir = new File(rootfs, "etc/zypp/repos.d");
        File[] repos = repoDir.listFiles();
        if (repos == null || repos.length == 0) return APPLY_SKIPPED;
        boolean changed = false;
        for (File repo : repos) {
            if (!repo.getName().endsWith(".repo")) continue;
            if (restoreFile(rootfs, "etc/zypp/repos.d/" + repo.getName())) {
                changed = true;
            } else {
                changed |= rewriteFileHosts(rootfs, "etc/zypp/repos.d/" + repo.getName(),
                        OFFICIAL_OPENSUSE);
            }
        }
        return changed ? APPLY_OK : APPLY_NO_CHANGE;
    }

    // --- Void: /etc/xbps.d overrides /usr/share/xbps.d, so mirror there ---

    private static int applyVoid(File rootfs, String target) throws IOException {
        File shareDir = new File(rootfs, "usr/share/xbps.d");
        File etcDir = new File(rootfs, "etc/xbps.d");
        File[] shareFiles = shareDir.listFiles();
        if (shareFiles == null || shareFiles.length == 0) return APPLY_SKIPPED;
        String host = target; // "" = official; rewriteVoidConf handles the path prefix
        boolean changed = false;
        for (File shareFile : shareFiles) {
            if (!shareFile.getName().endsWith(".conf")) continue;
            File etcFile = new File(etcDir, shareFile.getName());
            boolean preExisted = etcFile.exists();
            if (!preExisted) {
                if (!etcDir.exists() && !etcDir.mkdirs()) return APPLY_FAILED;
                copyFile(shareFile, etcFile);
                markCreated(rootfs, "etc/xbps.d/" + shareFile.getName());
                changed = true;
            }
            // A copy we just created needs no backup: the /usr/share
            // original IS the pristine content, and restore deletes the
            // copy again (see the .created marker). Only a pre-existing
            // override gets backed up (backupFile skips marked files).
            // Void URLs need a host + path rewrite (see rewriteVoidConf),
            // not the plain host swap the other distros use.
            File file = new File(rootfs, "etc/xbps.d/" + shareFile.getName());
            String content = readFile(file);
            String rewritten = rewriteVoidConf(content, host);
            if (!rewritten.equals(content)) {
                backupFile(rootfs, "etc/xbps.d/" + shareFile.getName());
                writeFile(file, rewritten);
                changed = true;
            }
        }
        return changed ? APPLY_OK : APPLY_NO_CHANGE;
    }

    /** Void repository URLs: the official host serves /current directly,
     *  but mirrors serve it under /voidlinux (TUNA help page:
     *  sed 's|https://repo-default.voidlinux.org|https://mirrors.tuna.tsinghua.edu.cn/voidlinux|g').
     *  A plain host swap would produce the 404
     *  https://mirrors.tuna.tsinghua.edu.cn/current, so rewrite the
     *  repository= lines as host + path: mirror form is
     *  <host>/voidlinux/<path>, official form is repo-default.voidlinux.org/<path>.
     *  The /etc/xbps.d files handled here are copies of the stock
     *  /usr/share ones (or the user's backed-up override), so every
     *  repository= line in them is ours to rewrite. */
    private static String rewriteVoidConf(String content, String targetHost) {
        StringBuilder out = new StringBuilder();
        for (String line : content.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("repository=")) {
                out.append(rewriteVoidRepositoryLine(trimmed, targetHost)).append("\n");
            } else {
                out.append(line).append("\n");
            }
        }
        String result = out.toString();
        if (result.endsWith("\n")) result = result.substring(0, result.length() - 1);
        return result;
    }

    private static String rewriteVoidRepositoryLine(String line, String targetHost) {
        int eq = line.indexOf('=');
        if (eq < 0) return line;
        String url = line.substring(eq + 1).trim();
        int schemeEnd = url.indexOf("://");
        if (schemeEnd < 0) return line;
        String scheme = url.substring(0, schemeEnd);
        String rest = url.substring(schemeEnd + 3);
        int slash = rest.indexOf('/');
        String path = slash < 0 ? "" : rest.substring(slash);
        // Normalize: drop a /voidlinux prefix if the line already has one.
        if (path.startsWith("/voidlinux")) path = path.substring("/voidlinux".length());
        if (targetHost.isEmpty()) {
            return line.substring(0, eq + 1) + scheme + "://" + OFFICIAL_VOID + path;
        }
        return line.substring(0, eq + 1) + scheme + "://" + targetHost + "/voidlinux" + path;
    }

    private static int restoreVoid(File rootfs) throws IOException {
        File etcDir = new File(rootfs, "etc/xbps.d");
        File[] etcFiles = etcDir.listFiles();
        if (etcFiles == null || etcFiles.length == 0) return APPLY_SKIPPED;
        boolean changed = false;
        for (File etcFile : etcFiles) {
            if (!etcFile.getName().endsWith(".conf")) continue;
            String relPath = "etc/xbps.d/" + etcFile.getName();
            if (clearCreated(rootfs, relPath)) {
                // This copy only exists because we created it.
                if (etcFile.delete()) changed = true;
            } else if (restoreFile(rootfs, relPath)) {
                changed = true;
            } else {
                // No backup and no marker (e.g. written by an older
                // build): delete the copy if the stock original exists.
                File shareFile = new File(rootfs, "usr/share/xbps.d/" + etcFile.getName());
                if (shareFile.exists() && etcFile.delete()) changed = true;
            }
        }
        return changed ? APPLY_OK : APPLY_NO_CHANGE;
    }

    private static int restoreGeneric(File rootfs, String relPath, String officialHost) throws IOException {
        if (clearCreated(rootfs, relPath)) {
            // This file only exists because we created it.
            File file = new File(rootfs, relPath);
            return file.delete() ? APPLY_OK : APPLY_FAILED;
        }
        if (restoreFile(rootfs, relPath)) return APPLY_OK;
        File file = new File(rootfs, relPath);
        if (!file.exists()) return APPLY_SKIPPED;
        return rewriteFileHosts(rootfs, relPath, officialHost) ? APPLY_OK : APPLY_NO_CHANGE;
    }

    // --- Host-swap helper ---

    /** Replace any known package host in a file's content with the target
     *  host, keeping the URL scheme. Backs the file up first (unless
     *  backup=false, for files we just created). Returns true if anything
     *  changed. */
    private static boolean rewriteFileHosts(File rootfs, String relPath, String targetHost) throws IOException {
        return rewriteFileHosts(rootfs, relPath, targetHost, true);
    }

    private static boolean rewriteFileHosts(File rootfs, String relPath, String targetHost,
                                            boolean backup) throws IOException {
        File file = new File(rootfs, relPath);
        if (!file.exists()) return false;
        String content = readFile(file);
        String rewritten = swapHosts(content, targetHost);
        if (rewritten.equals(content)) return false;
        if (backup) backupFile(rootfs, relPath);
        writeFile(file, rewritten);
        return true;
    }

    private static String swapHosts(String content, String targetHost) {
        String out = content;
        for (String host : KNOWN_HOSTS) {
            if (host.equals(targetHost)) continue;
            out = out.replace("://" + host, "://" + targetHost);
        }
        return out;
    }

    private static String removeMarkerLines(String content) {
        StringBuilder out = new StringBuilder();
        for (String line : content.split("\n", -1)) {
            if (line.contains(MARKER)) continue;
            out.append(line).append("\n");
        }
        // split(-1) + one "\n" per element adds a single extra trailing
        // newline; strip it to keep the original line structure.
        String result = out.toString();
        if (result.endsWith("\n")) result = result.substring(0, result.length() - 1);
        return result;
    }

    // --- Backup / restore ---

    private static File backupFileFor(File rootfs, String relPath) {
        return new File(new File(rootfs, BACKUP_DIR_NAME), relPath.replace('/', '_'));
    }

    /** Copy the original file into the backup dir the first time we touch it.
     *  A missing backup afterwards means the file was never modified by us.
     *  Files we created ourselves are never backed up: their "before" state
     *  is "did not exist", recorded by the .created marker instead. */
    private static void backupFile(File rootfs, String relPath) throws IOException {
        File original = new File(rootfs, relPath);
        if (!original.exists()) return;
        if (isCreated(rootfs, relPath)) return;
        File backup = backupFileFor(rootfs, relPath);
        if (backup.exists()) return;
        File parent = backup.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("cannot create backup dir");
        }
        copyFile(original, backup);
    }

    /** Record that a file only exists because we created it (its restore
     *  is deletion, not a backup copy). */
    private static void markCreated(File rootfs, String relPath) throws IOException {
        File marker = createdMarkerFor(rootfs, relPath);
        File parent = marker.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("cannot create backup dir");
        }
        marker.createNewFile();
    }

    private static boolean isCreated(File rootfs, String relPath) {
        return createdMarkerFor(rootfs, relPath).exists();
    }

    /** Delete the .created marker; true if one existed. */
    private static boolean clearCreated(File rootfs, String relPath) {
        return createdMarkerFor(rootfs, relPath).delete();
    }

    private static File createdMarkerFor(File rootfs, String relPath) {
        return new File(new File(rootfs, BACKUP_DIR_NAME), relPath.replace('/', '_') + ".created");
    }

    /** Restore one file from its backup and delete the backup. True if restored. */
    private static boolean restoreFile(File rootfs, String relPath) throws IOException {
        File backup = backupFileFor(rootfs, relPath);
        if (!backup.exists()) return false;
        File original = new File(rootfs, relPath);
        File parent = original.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        copyFile(backup, original);
        backup.delete();
        return true;
    }

    private static void copyFile(File from, File to) throws IOException {
        FileInputStream in = null;
        FileOutputStream out = null;
        try {
            in = new FileInputStream(from);
            out = new FileOutputStream(to);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static String readFile(File file) throws IOException {
        FileInputStream in = null;
        try {
            in = new FileInputStream(file);
            byte[] bytes = new byte[(int) file.length()];
            int offset = 0;
            int n;
            while (offset < bytes.length && (n = in.read(bytes, offset, bytes.length - offset)) >= 0) {
                offset += n;
            }
            return new String(bytes, 0, offset, StandardCharsets.UTF_8);
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static String readFileTrimmed(File file) {
        if (!file.exists()) return "";
        try {
            return readFile(file).trim();
        } catch (IOException e) {
            return "";
        }
    }

    private static void writeFile(File file, String content) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(file);
            out.write(content.getBytes(StandardCharsets.UTF_8));
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Sync to installed distros / pending installs
    // ------------------------------------------------------------------

    /** Apply the preset to every installed distro. Call on a background thread. */
    public static SyncResult syncToAllDistros(Context context, MirrorPreset preset) {
        int synced = 0;
        int failed = 0;
        List<String> containers = new ArrayList<>(DistroUtils.scanInstalledContainers());
        for (String container : containers) {
            File rootfs = DistroUtils.containerRootfs(container);
            if (rootfs == null) {
                failed++;
                continue;
            }
            String distroId = DistroUtils.distroIdForContainer(container);
            int result = applyToContainer(rootfs, distroId, preset);
            if (result == APPLY_OK || result == APPLY_NO_CHANGE || result == APPLY_SKIPPED) {
                synced++;
            } else {
                failed++;
            }
        }
        return new SyncResult(synced, failed);
    }

    /** Restore official sources in every installed distro. Call on a background thread. */
    public static SyncResult restoreOfficialInAllDistros(Context context) {
        int synced = 0;
        int failed = 0;
        List<String> containers = new ArrayList<>(DistroUtils.scanInstalledContainers());
        for (String container : containers) {
            File rootfs = DistroUtils.containerRootfs(container);
            if (rootfs == null) {
                failed++;
                continue;
            }
            String distroId = DistroUtils.distroIdForContainer(container);
            int result = restoreOfficialInContainer(rootfs, distroId);
            if (result == APPLY_OK || result == APPLY_NO_CHANGE || result == APPLY_SKIPPED) {
                synced++;
            } else {
                failed++;
            }
        }
        return new SyncResult(synced, failed);
    }

    /** Remember a container whose install just started, so the preset can be
     *  applied once the container appears (installs run in a terminal
     *  session with no completion callback). */
    public static void markPendingInstall(Context context, String container) {
        if (container == null || container.isEmpty()) return;
        SharedPreferences prefs = SharedPreferenceUtils.getPrivateSharedPreferences(context, PREFS_NAME);
        Set<String> entries = new LinkedHashSet<>(prefs.getStringSet(KEY_PENDING_INSTALLS, new LinkedHashSet<String>()));
        entries.removeIf(e -> e.startsWith(container + "|"));
        entries.add(container + "|" + System.currentTimeMillis());
        prefs.edit().putStringSet(KEY_PENDING_INSTALLS, entries).apply();
    }

    /** Apply the current preset to pending installs whose container now
     *  exists; drop entries older than 7 days. Call on a background thread,
     *  e.g. from DistroActivity.onCreate/onResume. */
    public static void syncPendingInstalls(Context context) {
        if (!PENDING_GUARD.compareAndSet(false, true)) return;
        try {
            SharedPreferences prefs = SharedPreferenceUtils.getPrivateSharedPreferences(context, PREFS_NAME);
            Set<String> entries = new LinkedHashSet<>(prefs.getStringSet(KEY_PENDING_INSTALLS, new LinkedHashSet<String>()));
            if (entries.isEmpty()) return;
            MirrorPreset preset = loadPreset(context);
            long now = System.currentTimeMillis();
            Set<String> remaining = new LinkedHashSet<>();
            for (String entry : entries) {
                String[] parts = entry.split("\\|", 2);
                if (parts.length != 2) continue;
                String container = parts[0];
                long when;
                try {
                    when = Long.parseLong(parts[1]);
                } catch (NumberFormatException e) {
                    continue;
                }
                if (now - when > PENDING_MAX_AGE_MS) continue;
                File rootfs = DistroUtils.containerRootfs(container);
                if (rootfs == null) {
                    remaining.add(entry);
                    continue;
                }
                String distroId = DistroUtils.distroIdForContainer(container);
                int result = applyToContainer(rootfs, distroId, preset);
                if (result == APPLY_FAILED) remaining.add(entry);
            }
            prefs.edit().putStringSet(KEY_PENDING_INSTALLS, remaining).apply();
        } finally {
            PENDING_GUARD.set(false);
        }
    }

    // ------------------------------------------------------------------
    // Connectivity probe
    // ------------------------------------------------------------------

    /** Probe the preset's endpoints in parallel; returns the labels of the
     *  ones that did not answer within 5s. Empty list = all reachable.
     *  Same contract as before, but all probes run at once, so the whole
     *  probe finishes in ~5s instead of up to ~40s sequential.
     *  Call on a background thread. */
    public static List<String> probePreset(MirrorPreset preset) {
        List<String> unreachable = new ArrayList<>();
        if (preset == null) preset = presetForId(PRESET_OFFICIAL);
        List<String[]> probes = new ArrayList<>();
        if (!preset.distroHost.isEmpty()) {
            probes.add(new String[]{"distro", "https://" + preset.distroHost + "/"});
        } else {
            probes.add(new String[]{"distro", "https://archive.ubuntu.com/"});
        }
        probes.add(new String[]{"npm", preset.npm + "/-/ping"});
        probes.add(new String[]{"pypi", preset.pypi});
        String registry = effectiveRegistry(preset);
        if (!registry.isEmpty()) {
            probes.add(new String[]{"registry", "https://" + registry + "/v2/"});
        }
        ExecutorService pool = Executors.newFixedThreadPool(probes.size());
        List<Future<Boolean>> results = new ArrayList<>();
        for (String[] probe : probes) {
            final String url = probe[1];
            results.add(pool.submit(() -> probeUrl(url)));
        }
        pool.shutdown();
        for (int i = 0; i < probes.size(); i++) {
            try {
                if (!results.get(i).get()) unreachable.add(probes.get(i)[0]);
            } catch (InterruptedException | ExecutionException e) {
                // A probe that could not even run counts as unreachable;
                // a probe must never crash the caller.
                unreachable.add(probes.get(i)[0]);
            }
        }
        return unreachable;
    }

    private static boolean probeUrl(String urlString) {
        return probeLatencyMs(urlString) >= 0;
    }

    /** GET latency in ms: time from connect to the first HTTP response.
     *  -1 when the host does not answer within 5s. Any HTTP response
     *  (including 401 from a registry's /v2/) counts as an answer. */
    public static long probeLatencyMs(String urlString) {
        HttpURLConnection connection = null;
        try {
            long startMs = System.currentTimeMillis();
            URL url = new URL(urlString);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setRequestMethod("GET");
            connection.setInstanceFollowRedirects(true);
            connection.connect();
            connection.getResponseCode();
            return System.currentTimeMillis() - startMs;
        } catch (IOException | RuntimeException e) {
            // Any failure (including proxy/tunneling errors) means unreachable;
            // a probe must never crash the caller.
            return -1;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    // ------------------------------------------------------------------
    // Detect and recommend: latency + real download speed
    // ------------------------------------------------------------------

    /** Cache subdir for speed-test downloads. Files here are deleted
     *  right after measuring and are never kept. */
    public static File speedTestDir(Context context) {
        return new File(context.getCacheDir(), "mirror-speedtest");
    }

    /** Delete every leftover file in the speed-test dir (an interrupted
     *  test can leave one behind). Call when a detection starts. */
    public static void clearSpeedTestDir(Context context) {
        File dir = speedTestDir(context);
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isFile()) file.delete();
        }
    }

    /** Run the full detection: latency + real download speed for every
     *  preset and every registry option, all in parallel (4 downloads at
     *  a time). Progress is reported as tasks finish. Detection only
     *  measures and recommends — it never switches anything itself.
     *  Call on a background thread. */
    public static DetectOutcome detectAll(Context context, DetectProgressListener progress) {
        List<MirrorPreset> presets = new ArrayList<>();
        for (String id : PRESET_IDS) {
            if (PRESET_CUSTOM.equals(id)) {
                if (!isCustomConfigured(context)) continue;
                presets.add(loadCustomPreset(context));
            } else {
                presets.add(presetForId(id));
            }
        }
        List<String> registries = new ArrayList<>(REGISTRY_OPTIONS);
        int total = presets.size() + registries.size();
        File speedDir = speedTestDir(context);
        clearSpeedTestDir(context);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Future<PresetDetectResult>> presetFutures = new ArrayList<>();
        for (MirrorPreset preset : presets) {
            final MirrorPreset p = preset;
            presetFutures.add(pool.submit(() -> detectPreset(p, speedDir)));
        }
        List<Future<RegistryDetectResult>> registryFutures = new ArrayList<>();
        for (String registry : registries) {
            final String r = registry;
            registryFutures.add(pool.submit(() -> detectRegistry(r, speedDir)));
        }
        pool.shutdown();
        List<PresetDetectResult> presetResults = new ArrayList<>();
        List<RegistryDetectResult> registryResults = new ArrayList<>();
        int done = 0;
        for (int i = 0; i < presetFutures.size(); i++) {
            presetResults.add(getOrFailedPreset(presets.get(i), presetFutures.get(i)));
            if (progress != null) progress.onProgress(++done, total);
        }
        for (int i = 0; i < registryFutures.size(); i++) {
            registryResults.add(getOrFailedRegistry(registries.get(i), registryFutures.get(i)));
            if (progress != null) progress.onProgress(++done, total);
        }
        return new DetectOutcome(presetResults, registryResults,
                fastestPreset(presetResults), fastestRegistry(registryResults));
    }

    private static PresetDetectResult getOrFailedPreset(MirrorPreset preset,
                                                        Future<PresetDetectResult> future) {
        try {
            PresetDetectResult result = future.get();
            return result != null ? result
                    : new PresetDetectResult(preset.id, -1, -1, new ArrayList<String>());
        } catch (InterruptedException | ExecutionException e) {
            return new PresetDetectResult(preset.id, -1, -1, new ArrayList<String>());
        }
    }

    private static RegistryDetectResult getOrFailedRegistry(String registry,
                                                            Future<RegistryDetectResult> future) {
        try {
            RegistryDetectResult result = future.get();
            return result != null ? result : new RegistryDetectResult(registry, -1, -1);
        } catch (InterruptedException | ExecutionException e) {
            return new RegistryDetectResult(registry, -1, -1);
        }
    }

    /** Fastest by measured speed; ties broken by lower latency.
     *  A source whose speed test failed is never recommended. */
    private static PresetDetectResult fastestPreset(List<PresetDetectResult> results) {
        PresetDetectResult best = null;
        for (PresetDetectResult r : results) {
            if (r == null || !r.hasSpeed()) continue;
            if (best == null || r.speedKBps > best.speedKBps
                    || (r.speedKBps == best.speedKBps && r.latencyMs >= 0
                    && (best.latencyMs < 0 || r.latencyMs < best.latencyMs))) {
                best = r;
            }
        }
        return best;
    }

    private static RegistryDetectResult fastestRegistry(List<RegistryDetectResult> results) {
        RegistryDetectResult best = null;
        for (RegistryDetectResult r : results) {
            if (r == null || !r.hasSpeed()) continue;
            if (best == null || r.speedKBps > best.speedKBps
                    || (r.speedKBps == best.speedKBps && r.latencyMs >= 0
                    && (best.latencyMs < 0 || r.latencyMs < best.latencyMs))) {
                best = r;
            }
        }
        return best;
    }

    /** Measure one preset: distro-host latency, the usual endpoint probe,
     *  and the real download speed of the Ubuntu Release file from its
     *  distro host (same file on every mirror, ~255 KB). Never throws. */
    public static PresetDetectResult detectPreset(MirrorPreset preset, File speedDir) {
        if (preset == null) preset = presetForId(PRESET_OFFICIAL);
        try {
            String host = preset.distroHost.isEmpty() ? "archive.ubuntu.com" : preset.distroHost;
            long latencyMs = probeLatencyMs("https://" + host + "/");
            List<String> unreachable = probePreset(preset);
            long speedKBps = -1;
            if (latencyMs >= 0) {
                speedKBps = downloadSpeedKBps(
                        "https://" + host + "/ubuntu/dists/noble/Release",
                        speedDir, "release-" + preset.id, 15000);
            }
            return new PresetDetectResult(preset.id, latencyMs, speedKBps, unreachable);
        } catch (RuntimeException e) {
            return new PresetDetectResult(preset.id, -1, -1, new ArrayList<String>());
        }
    }

    /** Measure one registry: /v2/ latency, then the real download speed
     *  of an Alpine image layer via the Docker token flow. Never throws. */
    public static RegistryDetectResult detectRegistry(String registry, File speedDir) {
        // The sentinel means Docker Hub directly; it is never a host.
        String resultRegistry = registry != null ? registry : "";
        String host = REGISTRY_DOCKER_HUB.equals(resultRegistry) ? "" : resultRegistry;
        try {
            String base = "https://" + (host.isEmpty() ? "registry-1.docker.io" : host);
            long latencyMs = probeLatencyMs(base + "/v2/");
            long speedKBps = -1;
            if (latencyMs >= 0) {
                speedKBps = registryLayerSpeedKBps(host, speedDir);
            }
            return new RegistryDetectResult(resultRegistry, latencyMs, speedKBps);
        } catch (RuntimeException e) {
            return new RegistryDetectResult(resultRegistry, -1, -1);
        }
    }

    /** Download a URL to a file in speedDir and return the measured speed
     *  in KB/s (-1 on failure). The file is deleted before returning,
     *  even on failure, so speed-test downloads never accumulate. */
    public static long downloadSpeedKBps(String urlString, File speedDir,
                                         String fileName, int readTimeoutMs) {
        return downloadToFileSpeedKBps(urlString, null, speedDir, fileName, readTimeoutMs);
    }

    private static long downloadToFileSpeedKBps(String urlString, String authHeader,
                                                File speedDir, String fileName,
                                                int readTimeoutMs) {
        File out = new File(speedDir, fileName);
        HttpURLConnection connection = null;
        InputStream in = null;
        FileOutputStream fileOut = null;
        try {
            if (!speedDir.exists() && !speedDir.mkdirs()) return -1;
            URL url = new URL(urlString);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(readTimeoutMs);
            connection.setRequestMethod("GET");
            if (authHeader != null) connection.setRequestProperty("Authorization", authHeader);
            connection.setInstanceFollowRedirects(true);
            long startMs = System.currentTimeMillis();
            connection.connect();
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) return -1;
            in = connection.getInputStream();
            fileOut = new FileOutputStream(out);
            byte[] buf = new byte[8192];
            long bytes = 0;
            int n;
            while ((n = in.read(buf)) >= 0) {
                fileOut.write(buf, 0, n);
                bytes += n;
            }
            long elapsedMs = System.currentTimeMillis() - startMs;
            if (bytes <= 0 || elapsedMs <= 0) return -1;
            return bytes * 1000 / elapsedMs / 1024;
        } catch (IOException | RuntimeException e) {
            return -1;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
            if (fileOut != null) {
                try {
                    fileOut.close();
                } catch (IOException ignored) {
                }
            }
            if (connection != null) connection.disconnect();
            if (out.exists()) out.delete();
        }
    }

    // --- Registry token flow (Docker distribution spec) ---
    //
    // Verified live against DaoCloud and Docker Hub: GET /v2/ answers 401
    // with a Bearer challenge; the token endpoint returns "token" (or
    // "access_token"); the manifest for library/alpine:3.20 is a manifest
    // LIST, so the amd64 entry is fetched to get layers[0]; the blob GET
    // 307-redirects to storage and must be followed. Per-mirror quirks
    // handled here: redirects on every request, any WWW-Authenticate
    // parameter order/spacing, a challenge that only appears on the
    // manifest request (dockerproxy.net answers /v2/ with 200), and
    // "token" vs "access_token" in the token JSON. A failure at any step
    // means -1 (speed test failed), never a crash.

    private static final String SPEEDTEST_IMAGE = "library/alpine";
    private static final String SPEEDTEST_TAG = "3.20";

    /** Download the Alpine layer from a registry and return KB/s (-1 on failure). */
    private static long registryLayerSpeedKBps(String registry, File speedDir) {
        String host = registry != null && !registry.isEmpty() ? registry : "registry-1.docker.io";
        String base = "https://" + host;
        String token = registryBearerToken(base);
        if (token == null) return -1;
        String digest = registryAlpineLayerDigest(base, token);
        if (digest == null) return -1;
        String safeHost = host.replaceAll("[^A-Za-z0-9]", "_");
        return downloadToFileSpeedKBps(
                base + "/v2/" + SPEEDTEST_IMAGE + "/blobs/" + digest,
                "Bearer " + token, speedDir, "layer-" + safeHost, 20000);
    }

    /** Bearer token for library/alpine:pull. The challenge may come from
     *  /v2/ or, for registries that answer /v2/ with 200, from the
     *  manifest request's 401. Null when no token could be fetched. */
    private static String registryBearerToken(String base) {
        String challenge = wwwAuthenticateFor(base + "/v2/");
        if (challenge != null) {
            String token = fetchRegistryToken(challenge);
            if (token != null) return token;
        }
        challenge = wwwAuthenticateFor(base + "/v2/" + SPEEDTEST_IMAGE
                + "/manifests/" + SPEEDTEST_TAG);
        if (challenge != null) return fetchRegistryToken(challenge);
        return null;
    }

    /** WWW-Authenticate header of a GET, when the response is 401; null otherwise. */
    private static String wwwAuthenticateFor(String urlString) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(urlString);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setRequestMethod("GET");
            connection.setInstanceFollowRedirects(true);
            connection.connect();
            if (connection.getResponseCode() != HttpURLConnection.HTTP_UNAUTHORIZED) return null;
            return connection.getHeaderField("WWW-Authenticate");
        } catch (IOException | RuntimeException e) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /** Exchange a Bearer challenge for a token. Null on any failure. */
    private static String fetchRegistryToken(String challenge) {
        String realm = bearerParam(challenge, "realm");
        if (realm == null || realm.isEmpty()) return null;
        String service = bearerParam(challenge, "service");
        StringBuilder url = new StringBuilder(realm);
        url.append(realm.contains("?") ? "&" : "?");
        if (service != null && !service.isEmpty()) url.append("service=").append(service).append("&");
        url.append("scope=repository:").append(SPEEDTEST_IMAGE).append(":pull");
        String body = getJson(url.toString(), null, null);
        if (body == null) return null;
        String token = jsonString(body, "token");
        if (token == null) token = jsonString(body, "access_token");
        return token;
    }

    /** First layers[] digest of the Alpine manifest. When the tag resolves
     *  to a manifest list, the amd64 entry is fetched first. Null on failure. */
    private static String registryAlpineLayerDigest(String base, String token) {
        String accept = "application/vnd.docker.distribution.manifest.list.v2+json, "
                + "application/vnd.docker.distribution.manifest.v2+json";
        String body = getJson(base + "/v2/" + SPEEDTEST_IMAGE + "/manifests/" + SPEEDTEST_TAG,
                "Bearer " + token, accept);
        if (body == null) return null;
        String digest = findManifestDigestForArch(body, "amd64");
        if (digest != null) {
            body = getJson(base + "/v2/" + SPEEDTEST_IMAGE + "/manifests/" + digest,
                    "Bearer " + token,
                    "application/vnd.docker.distribution.manifest.v2+json");
            if (body == null) return null;
        }
        return findFirstLayerDigest(body);
    }

    /** In a manifest list, the digest of the entry for the given
     *  architecture; null when the body is a single manifest or has no
     *  such entry. */
    private static String findManifestDigestForArch(String body, String arch) {
        if (body.indexOf("\"manifests\"") < 0) return null;
        int searchFrom = 0;
        while (true) {
            int archAt = body.indexOf("\"architecture\":\"" + arch + "\"", searchFrom);
            if (archAt < 0) return null;
            // The entry's digest precedes its platform block.
            int platformAt = body.lastIndexOf("\"platform\"", archAt);
            if (platformAt < 0) {
                searchFrom = archAt + 1;
                continue;
            }
            int entryOpen = body.lastIndexOf('{', platformAt);
            int digestAt = body.indexOf("\"digest\":\"", entryOpen);
            if (digestAt < 0 || digestAt > platformAt) {
                searchFrom = archAt + 1;
                continue;
            }
            int start = digestAt + "\"digest\":\"".length();
            int end = body.indexOf('"', start);
            if (end <= start) {
                searchFrom = archAt + 1;
                continue;
            }
            // Skip non-Linux entries for the same architecture, if any.
            int platformEnd = body.indexOf('}', archAt);
            String platform = platformEnd > archAt
                    ? body.substring(archAt, platformEnd) : "";
            if (platform.contains("\"os\"") && !platform.contains("\"os\":\"linux\"")) {
                searchFrom = archAt + 1;
                continue;
            }
            return body.substring(start, end);
        }
    }

    /** First layers[] digest of a single manifest; null when absent. */
    private static String findFirstLayerDigest(String body) {
        int layersAt = body.indexOf("\"layers\"");
        if (layersAt < 0) return null;
        int digestAt = body.indexOf("\"digest\":\"", layersAt);
        if (digestAt < 0) return null;
        int start = digestAt + "\"digest\":\"".length();
        int end = body.indexOf('"', start);
        return end > start ? body.substring(start, end) : null;
    }

    /** GET a small JSON body; null unless the response is 200. */
    private static String getJson(String urlString, String authHeader, String accept) {
        HttpURLConnection connection = null;
        InputStream in = null;
        try {
            URL url = new URL(urlString);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setRequestMethod("GET");
            if (authHeader != null) connection.setRequestProperty("Authorization", authHeader);
            if (accept != null) connection.setRequestProperty("Accept", accept);
            connection.setInstanceFollowRedirects(true);
            connection.connect();
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
            in = connection.getInputStream();
            StringBuilder sb = new StringBuilder();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) >= 0) {
                sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                if (sb.length() > 1024 * 1024) return null; // never a manifest; bail out
            }
            return sb.toString();
        } catch (IOException | RuntimeException e) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
            if (connection != null) connection.disconnect();
        }
    }

    /** Tolerantly read a name="value" parameter from a WWW-Authenticate
     *  Bearer challenge: any parameter order, optional spaces after commas. */
    private static String bearerParam(String challenge, String name) {
        if (challenge == null) return null;
        String needle = name + "=\"";
        int at = challenge.indexOf(needle);
        if (at < 0) return null;
        int start = at + needle.length();
        int end = challenge.indexOf('"', start);
        return end > start ? challenge.substring(start, end) : null;
    }

    /** Value of a top-level "key": "value" pair in a small JSON object; null when absent. */
    private static String jsonString(String body, String key) {
        if (body == null) return null;
        String needle = "\"" + key + "\"";
        int at = body.indexOf(needle);
        if (at < 0) return null;
        int colon = body.indexOf(':', at + needle.length());
        if (colon < 0) return null;
        int start = body.indexOf('"', colon);
        if (start < 0) return null;
        int end = body.indexOf('"', start + 1);
        return end > start ? body.substring(start + 1, end) : null;
    }

    // ------------------------------------------------------------------
    // Input helpers
    // ------------------------------------------------------------------

    /** Strip a scheme, path and trailing slash from a host field. "" stays "". */
    public static String normalizeHost(String input) {
        if (input == null) return "";
        String host = input.trim();
        if (host.isEmpty()) return "";
        int schemeEnd = host.indexOf("://");
        if (schemeEnd >= 0) host = host.substring(schemeEnd + 3);
        int slash = host.indexOf('/');
        if (slash >= 0) host = host.substring(0, slash);
        while (host.endsWith("/")) host = host.substring(0, host.length() - 1);
        return host.trim();
    }

    /** Normalize a full URL field: prepend https:// when no scheme is given. */
    public static String normalizeUrl(String input) {
        if (input == null) return "";
        String url = input.trim();
        if (url.isEmpty()) return "";
        if (!url.contains("://")) url = "https://" + url;
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return url;
    }

    /** A custom URL must be https:// with no spaces. */
    public static boolean isValidHttpsUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        if (!url.startsWith("https://")) return false;
        if (url.contains(" ")) return false;
        return url.length() > "https://".length();
    }

    private static String orDefault(String value, String fallback) {
        return (value == null || value.isEmpty()) ? fallback : value;
    }
}
