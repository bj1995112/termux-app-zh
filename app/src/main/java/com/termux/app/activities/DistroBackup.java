// Selective backup for a distro uninstall (uninstall-draft/design.md §2-3, §6):
// one backup is one directory under ~/.termux/distro-backups/
// <container>-<yyyyMMdd-HHmm>/ holding manifest.json + system.tar.gz +
// home.tar.gz + one agent-<id>.tar.gz per selected agent (data/ +
// program/ split). The backup
// is built by a bash script that runs in a visible terminal session (same
// pattern as the distro install): pack -> write manifest -> verify ->
// `proot-distro remove`. Nothing is deleted when the backup or its
// verification fails.
//
// This file only builds the script and reads/writes manifests. It never
// deletes a container itself, and it never touches shared system packages
// (curl, Node, git, Hermes build deps): only the agent program/data paths
// returned by AiAgentUtils.programPaths/dataPaths are packed, and the
// container is removed wholesale by proot-distro, not file by file.
//
// Agent path tables come from AiAgentUtils (programPaths(AgentDef) /
// dataPaths(AgentDef), returning rootfs-relative paths, possibly with one
// "*" segment for the Node-tarball prefix — expanded against the real
// rootfs by DistroUtils.expandWildcardPaths before use; paths that do
// not exist are skipped when packing). Do NOT reimplement those tables
// here.

package com.termux.app.activities;

import android.content.Context;
import android.util.AtomicFile;
import android.util.Log;

import com.termux.shared.termux.TermuxConstants;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class DistroBackup {

    private static final String LOG_TAG = "DistroBackup";

    /** Backup root dir name under ~/.termux/ (internal storage, the default). */
    public static final String BACKUP_DIR_NAME = "distro-backups";
    /** Manifest file name inside a backup dir. */
    public static final String MANIFEST_FILE = "manifest.json";
    /** User-files archive name inside a backup dir. */
    public static final String HOME_ARCHIVE = "home.tar.gz";

    /** Manifest item id for the user-files item. */
    public static final String HOME_ITEM_ID = "home";
    /** Manifest item id prefix for agent items ("agent:" + AgentDef id). */
    public static final String AGENT_ITEM_ID_PREFIX = "agent:";
    /** Manifest label for the user-files item. The script has no Context,
     * so the label is a constant; it matches the zh string
     * distro_uninstall_backup_home ("用户文件 (/root)"). Agent labels in
     * the manifest are the AgentDef display names. */
    public static final String HOME_ITEM_LABEL = "用户文件 (/root)";

    /** Manifest item id for the distro-system item. */
    public static final String SYSTEM_ITEM_ID = "system";
    /** Archive name for the distro-system item inside a backup dir. */
    public static final String SYSTEM_ARCHIVE = "system.tar.gz";
    /** Manifest label for the distro-system item. */
    public static final String SYSTEM_ITEM_LABEL = "发行版系统";

    private DistroBackup() {
        // No instances.
    }

    /** "agent-" + agentId + ".tar.gz", e.g. agent-codex.tar.gz. */
    public static String agentArchiveName(String agentId) {
        return "agent-" + agentId + ".tar.gz";
    }

    /** Manifest item id for an agent, e.g. "agent:codex". */
    public static String agentItemId(String agentId) {
        return AGENT_ITEM_ID_PREFIX + agentId;
    }

    /** Container names come from the proot-distro scan (letters, digits,
     * '-', '.', '_'); anything else is refused rather than interpolated
     * into a script. */
    public static boolean isValidContainerName(String container) {
        return container != null && container.matches("[A-Za-z0-9][A-Za-z0-9._-]*");
    }

    /** Single-quote a string for bash. Embedded literals only ever come
     * from app-known values, but quoting keeps a stray quote or $ from
     * breaking the generated script. */
    public static String shellQuote(String value) {
        if (value == null) return "''";
        return "'" + value.replace("'", "'\\''") + "'";
    }

    /** Internal backup root: ~/.termux/distro-backups/ — the same
     * ~/.termux/ dir that holds distro-list.json. The Context is unused
     * today (the home is fixed); it is kept so a later step can resolve
     * an external (SAF) root the same way. Does NOT create the dir. */
    public static File internalBackupRoot(Context context) {
        return new File(TermuxConstants.TERMUX_DATA_HOME_DIR, BACKUP_DIR_NAME);
    }

    /** Creates <root>/<container>-<yyyyMMdd-HHmm>/ (suffixed -2, -3, ...
     * when a backup for the same minute already exists) and returns it.
     * Throws IllegalStateException when the dir cannot be created. */
    public static File newBackupDir(File root, String container) {
        if (root == null) throw new IllegalArgumentException("backup root is null");
        if (!isValidContainerName(container)) {
            throw new IllegalArgumentException("bad container name: " + container);
        }
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date());
        File dir = new File(root, container + "-" + stamp);
        int suffix = 2;
        while (dir.exists()) {
            dir = new File(root, container + "-" + stamp + "-" + suffix);
            suffix++;
        }
        if (!dir.mkdirs()) {
            throw new IllegalStateException("Cannot create backup dir " + dir.getAbsolutePath());
        }
        return dir;
    }

    /** Writes the manifest into the backup dir (AtomicFile, same idiom as
     * the distro-list cache). Throws IllegalStateException on I/O failure
     * (no checked exception, so callers need no try/catch). */
    public static void writeManifest(File backupDir, JSONObject manifest) {
        if (backupDir == null || manifest == null) {
            throw new IllegalArgumentException("backupDir and manifest must not be null");
        }
        try {
            File manifestFile = new File(backupDir, MANIFEST_FILE);
            File parent = manifestFile.getParentFile();
            if (parent != null && !(parent.isDirectory() || parent.mkdirs())) {
                throw new IOException("Cannot create " + parent.getAbsolutePath());
            }
            AtomicFile atomicFile = new AtomicFile(manifestFile);
            FileOutputStream out = atomicFile.startWrite();
            try {
                out.write(manifest.toString(2).getBytes(StandardCharsets.UTF_8));
                atomicFile.finishWrite(out);
            } catch (IOException | JSONException e) {
                atomicFile.failWrite(out);
                throw e;
            }
        } catch (IOException | JSONException e) {
            Log.w(LOG_TAG, "Failed to write manifest", e);
            throw new IllegalStateException("Failed to write manifest: " + e.getMessage(), e);
        }
    }

    /** Reads the manifest from a backup dir, or null when it is missing
     * or unparseable (a backup with no readable manifest is "damaged"
     * and is never restored). */
    public static JSONObject readManifest(File backupDir) {
        if (backupDir == null) return null;
        File manifestFile = new File(backupDir, MANIFEST_FILE);
        if (!manifestFile.isFile()) return null;
        try {
            return new JSONObject(DistroUtils.readFile(manifestFile));
        } catch (Exception e) {
            Log.w(LOG_TAG, "Failed to read manifest in " + backupDir.getAbsolutePath(), e);
            return null;
        }
    }

    /** SHA-256 + byte count of one archive stream. */
    public static final class StreamDigest {
        /** Bytes read. */
        public final long size;
        /** Lowercase hex SHA-256. */
        public final String sha256;

        public StreamDigest(long size, String sha256) {
            this.size = size;
            this.sha256 = sha256;
        }
    }

    /** SHA-256 (lowercase hex) of one stream, with its byte count, or
     * null on any read/hash failure. Does NOT close the stream; the
     * caller owns it. The 64KB buffer matches the copy/download
     * buffer: hashing a hundreds-of-MB archive at 8KB a read would
     * waste syscalls on exactly the files this check exists to
     * protect. Hex style matches CertPackageBootstrap.toHex. */
    public static StreamDigest digestStream(InputStream in) {
        if (in == null) return null;
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            Log.w(LOG_TAG, "SHA-256 not available", e);
            return null;
        }
        byte[] buffer = new byte[65536];
        long total = 0;
        try {
            int n;
            while ((n = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, n);
                total += n;
            }
        } catch (IOException e) {
            Log.w(LOG_TAG, "Failed to hash stream", e);
            return null;
        }
        return new StreamDigest(total, toHex(digest.digest()));
    }

    /** SHA-256 (lowercase hex) of one local file, or null on failure. */
    public static String sha256Hex(File file) {
        if (file == null || !file.isFile()) return null;
        try (InputStream in = new FileInputStream(file)) {
            StreamDigest digest = digestStream(in);
            return digest == null ? null : digest.sha256;
        } catch (IOException e) {
            Log.w(LOG_TAG, "Failed to hash file: " + file.getAbsolutePath(), e);
            return null;
        }
    }

    /** Lowercase hex, same style as CertPackageBootstrap.toHex. */
    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format(Locale.US, "%02x", b & 0xff));
        return sb.toString();
    }

    /** Verifies every archive in a backup dir against its manifest:
     * the file must exist, be non-empty and match the manifest size,
     * and when the manifest records a sha256 the recomputed SHA-256
     * must match it. Returns the item ids that failed (empty when the
     * whole backup is intact), or null when the manifest itself is
     * missing/unparseable or has no items array. Old backups without
     * sha256 are checked by size only and never fail for the missing
     * hash. Pass null itemIds to check every manifest item, or a list
     * to check only those. Call on a background thread: hashing reads
     * every archive in full. Single source of truth for archive
     * verification — restore, external-copy staging and the
     * pre-remove gate all delegate here instead of reimplementing
     * the check. */
    public static List<String> verifyBackupArchives(File backupDir, List<String> itemIds) {
        JSONObject manifest = readManifest(backupDir);
        if (manifest == null) return null;
        JSONArray array = manifest.optJSONArray("items");
        if (array == null) return null;
        List<String> failed = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            String id = item.optString("id", "");
            if (id.isEmpty()) continue;
            if (itemIds != null && !itemIds.contains(id)) continue;
            String archive = item.optString("archive", "");
            long size = item.optLong("size", 0);
            String sha = item.optString("sha256", "");
            File archiveFile = new File(backupDir, archive);
            if (archive.isEmpty() || !archiveFile.isFile() || archiveFile.length() == 0) {
                failed.add(id);
                continue;
            }
            if (size > 0 && archiveFile.length() != size) {
                Log.w(LOG_TAG, "Archive size mismatch for " + id + ": manifest " + size
                    + ", actual " + archiveFile.length());
                failed.add(id);
                continue;
            }
            if (sha != null && !sha.isEmpty()) {
                String actual = sha256Hex(archiveFile);
                if (actual == null || !actual.equalsIgnoreCase(sha.trim())) {
                    Log.w(LOG_TAG, "Archive SHA-256 mismatch for " + id);
                    failed.add(id);
                }
            }
        }
        return failed;
    }

    /** One backup dir, as listed by the backup manager / restore picker. */
    public static final class BackupInfo {
        public final File dir;
        public final String container;
        public final String distroId;
        public final String distroDisplay;
        /** ISO-8601 creation time from the manifest, e.g. "2026-11-26T06:00:00+08:00". */
        public final String createdAt;
        /** Sum of the listed items' sizes (bytes). */
        public final long totalSize;
        public final List<Item> items;

        public BackupInfo(File dir, String container, String distroId, String distroDisplay,
                          String createdAt, long totalSize, List<Item> items) {
            this.dir = dir;
            this.container = container;
            this.distroId = distroId;
            this.distroDisplay = distroDisplay;
            this.createdAt = createdAt;
            this.totalSize = totalSize;
            this.items = items;
        }

        /** One item of a backup (one archive). */
        public static final class Item {
            /** "system", "home" or "agent:<AgentDef id>". */
            public final String id;
            public final String label;
            /** "system.tar.gz", "home.tar.gz" or "agent-<AgentDef id>.tar.gz". */
            public final String archive;
            /** Archive size in bytes (from the manifest). */
            public final long size;
            /** SHA-256 hex of the archive (from the manifest), or null
             * for backups written before it was recorded: those are
             * verified by size only, never failed for a missing hash. */
            public final String sha256;

            public Item(String id, String label, String archive, long size, String sha256) {
                this.id = id;
                this.label = label;
                this.archive = archive;
                this.size = size;
                this.sha256 = sha256;
            }
        }
    }

    /** Every backup under root that has a readable manifest, newest first
     * (by createdAt). Dirs with no readable manifest are skipped
     * (damaged backups). A manifest with no "items" array lists zero
     * items; its totalSize is 0. */
    public static List<BackupInfo> listBackups(File root) {
        List<BackupInfo> result = new ArrayList<>();
        if (root == null || !root.isDirectory()) return result;
        File[] children = root.listFiles();
        if (children == null) return result;
        for (File dir : children) {
            if (!dir.isDirectory()) continue;
            JSONObject manifest = readManifest(dir);
            if (manifest == null) continue;
            List<BackupInfo.Item> items = new ArrayList<>();
            long totalSize = 0;
            JSONArray array = manifest.optJSONArray("items");
            if (array != null) {
                for (int i = 0; i < array.length(); i++) {
                    JSONObject item = array.optJSONObject(i);
                    if (item == null) continue;
                    String id = item.optString("id", "");
                    String label = item.optString("label", "");
                    String archive = item.optString("archive", "");
                    long size = item.optLong("size", 0);
                    if (id.isEmpty() || archive.isEmpty()) continue;
                    items.add(new BackupInfo.Item(id, label, archive, size,
                        item.optString("sha256", null)));
                    totalSize += size;
                }
            }
            result.add(new BackupInfo(dir,
                manifest.optString("container", dir.getName()),
                manifest.optString("distroId", ""),
                manifest.optString("distroDisplay", ""),
                manifest.optString("createdAt", ""),
                totalSize, items));
        }
        Collections.sort(result, (a, b) -> b.createdAt.compareTo(a.createdAt));
        return result;
    }

    // ------------------------------------------------------------------
    // Per-file delete (backup manager detail view): remove ONE item
    // (archive) from a backup dir and update the manifest so the backup
    // stays consistent. FAIL-SAFE ORDER: the manifest is updated FIRST
    // and the archive is deleted only after the write succeeds — a
    // manifest that still lists a missing archive breaks restore, while
    // a leftover orphan archive is harmless (restore reads the manifest,
    // and a later whole-backup delete removes the file). The manifest
    // write goes through writeManifest, which already uses AtomicFile
    // (write-to-temp then rename).
    // ------------------------------------------------------------------

    /** Item removed; the backup still has items. */
    public static final int DELETE_ITEM_OK = 0;
    /** Item removed; the backup now has NO items left (the caller may
     * offer to delete the whole backup). */
    public static final int DELETE_ITEM_EMPTY = 1;
    /** Could not update the manifest — the archive was NOT deleted. */
    public static final int DELETE_ITEM_FAILED = 2;
    /** The item id is not in the manifest. */
    public static final int DELETE_ITEM_NOT_FOUND = 3;

    /** Deletes the item with the given id from a backup dir: removes it
     * from the manifest's "items" array (all other top-level fields —
     * version, container, distroId, distroDisplay, createdAt — are
     * copied unchanged) and then deletes its archive file. Returns one
     * of DELETE_ITEM_OK / DELETE_ITEM_EMPTY / DELETE_ITEM_FAILED /
     * DELETE_ITEM_NOT_FOUND. If the manifest cannot be read or written,
     * returns DELETE_ITEM_FAILED and the archive is left untouched. If
     * the archive file itself cannot be deleted after the manifest was
     * updated, still returns OK/EMPTY: the manifest is the source of
     * truth for restore. */
    public static int deleteBackupItem(File backupDir, String itemId) {
        JSONObject manifest = readManifest(backupDir);
        if (manifest == null) return DELETE_ITEM_FAILED;
        JSONArray items = manifest.optJSONArray("items");
        if (items == null) return DELETE_ITEM_NOT_FOUND;
        int index = -1;
        String archive = null;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            if (itemId != null && itemId.equals(item.optString("id", ""))) {
                index = i;
                archive = item.optString("archive", "");
                break;
            }
        }
        if (index < 0) return DELETE_ITEM_NOT_FOUND;

        // New manifest: a copy of the old one with that one item removed
        // from "items"; every other top-level field is carried over.
        JSONArray remaining = new JSONArray();
        for (int i = 0; i < items.length(); i++) {
            if (i == index) continue;
            remaining.put(items.opt(i));
        }
        JSONObject updated = new JSONObject();
        try {
            java.util.Iterator<String> keys = manifest.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if ("items".equals(key)) continue;
                updated.put(key, manifest.opt(key));
            }
            updated.put("items", remaining);
        } catch (JSONException e) {
            Log.w(LOG_TAG, "Failed to build updated manifest while deleting item " + itemId, e);
            return DELETE_ITEM_FAILED;
        }
        try {
            writeManifest(backupDir, updated);
        } catch (IllegalStateException e) {
            Log.w(LOG_TAG, "Failed to update manifest while deleting item " + itemId, e);
            return DELETE_ITEM_FAILED;
        }
        // Manifest first, archive second. A failed file delete still
        // counts as OK/EMPTY — the orphan is removed by a later
        // whole-backup delete.
        if (archive != null && !archive.isEmpty()) {
            File archiveFile = new File(backupDir, archive);
            if (archiveFile.isFile() && !archiveFile.delete()) {
                Log.w(LOG_TAG, "Manifest updated but archive not deleted: "
                        + archiveFile.getAbsolutePath());
            }
        }
        return remaining.length() == 0 ? DELETE_ITEM_EMPTY : DELETE_ITEM_OK;
    }

    // ------------------------------------------------------------------
    // Restore (design.md §4): unpack selected items from a backup dir
    // into a container's rootfs. Extraction runs synchronously with the
    // host GNU tar (the same tar the backup script uses), on a
    // background thread, via ProcessBuilder — NOT in a visible terminal
    // session: a visible session has no completion callback, and the
    // caller must verify each item and show the per-item result
    // immediately after the restore finishes. The backup dir is never
    // deleted here. Verified against GNU tar: `--strip-components=1
    // data program` extracts data/... and program/... to rootfs-relative
    // paths with exit 0 (the bare "data/" dir entry strips cleanly), but
    // naming a section that is absent from the archive exits 2 — so the
    // sections present are listed first (tar tzf) and only those are
    // requested.
    // ------------------------------------------------------------------

    /** Absolute path of the host tar, falling back to "tar" on PATH. */
    private static String tarExecutable() {
        File binTar = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "tar");
        return binTar.isFile() ? binTar.getAbsolutePath() : "tar";
    }

    /** Run one tar command (arguments after "tar"), draining its output
     * so a large listing cannot block on a full pipe. True iff tar
     * exits 0. Call on a background thread. */
    private static boolean runTar(List<String> args) {
        List<String> command = new ArrayList<>();
        command.add(tarExecutable());
        command.addAll(args);
        try {
            Process process = new ProcessBuilder(command)
                .redirectErrorStream(true).start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Log.d(LOG_TAG, "tar: " + line);
                }
            }
            int exit = process.waitFor();
            if (exit != 0) {
                Log.w(LOG_TAG, "tar exited " + exit + ": " + command);
                return false;
            }
            return true;
        } catch (Exception e) {
            Log.w(LOG_TAG, "Failed to run tar: " + command, e);
            return false;
        }
    }

    /** Member names of a .tar.gz (tar tzf), or null when the archive
     * cannot be listed. Call on a background thread. */
    private static List<String> listTarMembers(File archive) {
        List<String> command = new ArrayList<>();
        command.add(tarExecutable());
        command.add("-tzf");
        command.add(archive.getAbsolutePath());
        List<String> members = new ArrayList<>();
        try {
            Process process = new ProcessBuilder(command)
                .redirectErrorStream(true).start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    members.add(line);
                }
            }
            int exit = process.waitFor();
            if (exit != 0) {
                Log.w(LOG_TAG, "tar tzf exited " + exit + " for " + archive.getAbsolutePath());
                return null;
            }
            return members;
        } catch (Exception e) {
            Log.w(LOG_TAG, "Failed to list tar members: " + archive.getAbsolutePath(), e);
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Hard-link repair (design.md §4): Android SELinux forbids link()
    // in app data, so host GNU tar fails on hard-link members when
    // restoring an agent archive ("Cannot hard link ... Permission
    // denied", tar exits 2) — while every real file is already
    // extracted and only the link entries are missing. The members
    // were symlinks when packed (the backup script uses tar -h, which
    // follows symlinks; two packed paths to one inode become a hard
    // link), so recreating them as symlinks restores the original
    // layout. The repair below is one shared shell function, used by
    // both restore paths: restoreItems runs it through bash -s, and
    // DistroActivity embeds the same text in the restore-distro
    // script. No new dependency (tar + coreutils only), and the
    // backup format is untouched, so old backups are repaired too.
    // ------------------------------------------------------------------

    /** The repair_hard_links + verify_members shell functions, as
     *  text. Callers emit them once, then call
     *  repair_hard_links &lt;archive&gt; &lt;rootfs&gt; &lt;strip&gt;
     *  [sections...] and verify_members with the same arguments:
     *  strip is the --strip-components count the extraction used
     *  (1 for agent archives, 0 for home), sections are the
     *  top-level sections actually extracted (empty = whole
     *  archive). The link target is the target path relative
     *  to the link's directory (computed by _repair_relpath): the
     *  repaired link resolves exactly like the original npm link did
     *  inside the container, and the host side can follow it inside
     *  the rootfs too — a container-absolute target would not
     *  resolve from the host, so verifyRestoredItems would report
     *  the item failed even though the restore is correct.
     *  repair_hard_links returns 0 iff the archive lists cleanly,
     *  holds at least one hard-link member in the repaired sections,
     *  every such member's target is also a member of the archive
     *  (a target that is not in the archive at all means a genuinely
     *  damaged backup), and every such member now exists as a
     *  symlink (test -L, never -e: the target may live in a section
     *  that was not extracted). Link chains need no ordering: a
     *  symlink to another symlink resolves through it.
     *  verify_members is the script path's post-repair check (the
     *  Java path already has verifyRestoredItems): it lists every
     *  member of the extracted sections and requires each one to
     *  exist under the rootfs. A member counts as present when
     *  test -e OR test -L passes — a repaired symlink whose target
     *  lives in a section that was not extracted is dangling by
     *  design, and -e alone would misjudge it missing. Directory
     *  members (names ending in /) are checked without the trailing
     *  slash. It returns 0 iff the archive lists cleanly, holds at
     *  least one member in the checked sections, and every member
     *  is present. It only runs after a successful repair, never on
     *  the success path: a repair can return 0 while the extraction
     *  also failed for another reason (e.g. disk full left a real
     *  file missing), and without this check the script would
     *  report success. Member names with spaces are not supported
     *  (agent paths never contain them). */
    public static String hardLinkRepairFunction() {
        StringBuilder s = new StringBuilder();
        s.append("# repair_hard_links <archive> <rootfs> <strip> [sections...]\n");
        s.append("# Recreates every hard-link member of <archive> as a symlink under\n");
        s.append("# <rootfs> (see DistroBackup.hardLinkRepairFunction javadoc).\n");
        s.append("# verify_members <archive> <rootfs> <strip> [sections...]\n");
        s.append("# Requires every member of the extracted sections to exist under\n");
        s.append("# <rootfs> (-e or -L: a repaired symlink may dangle by design).\n");
        s.append("# _repair_relpath <link-dir> <target>: the target path relative\n");
        s.append("# to the link's directory (both rootfs-relative, no leading\n");
        s.append("# slash). The repaired link is relative on purpose: it\n");
        s.append("# resolves exactly like the original npm link did inside the\n");
        s.append("# container, and the host side can follow it inside the\n");
        s.append("# rootfs too (a container-absolute target would not resolve\n");
        s.append("# from the host, so verifyRestoredItems would report the\n");
        s.append("# item failed even though the restore is correct).\n");
        s.append("_repair_relpath() {\n");
        s.append("  local from=\"$1\" to=\"$2\"\n");
        s.append("  local -a fparts=() tparts=()\n");
        s.append("  local common=0 rel=\"\" i\n");
        s.append("  if [ \"$from\" != \".\" ] && [ -n \"$from\" ]; then\n");
        s.append("    IFS=/ read -r -a fparts <<< \"$from\"\n");
        s.append("  fi\n");
        s.append("  IFS=/ read -r -a tparts <<< \"$to\"\n");
        s.append("  while [ \"$common\" -lt \"${#fparts[@]}\" ] && [ \"$common\" -lt \"${#tparts[@]}\" ] \\\n");
        s.append("    && [ \"${fparts[$common]}\" = \"${tparts[$common]}\" ]; do\n");
        s.append("    common=$((common + 1))\n");
        s.append("  done\n");
        s.append("  for ((i = common; i < ${#fparts[@]}; i++)); do rel=\"$rel../\"; done\n");
        s.append("  for ((i = common; i < ${#tparts[@]}; i++)); do rel=\"$rel${tparts[$i]}/\"; done\n");
        s.append("  rel=\"${rel%/}\"\n");
        s.append("  printf '%s\\n' \"$rel\"\n");
        s.append("}\n");
        s.append("repair_hard_links() {\n");
        s.append("  if [ \"$#\" -lt 3 ]; then return 1; fi\n");
        s.append("  local archive=\"$1\" rootfs=\"$2\" strip=\"$3\"\n");
        s.append("  shift 3\n");
        s.append("  local tarbin=\"${REPAIR_TAR:-tar}\"\n");
        s.append("  local list members checked total ok pair name target in_section dest want line sec\n");
        s.append("  total=0\n");
        s.append("  ok=1\n");
        s.append("  list=\"$(mktemp)\" || return 1\n");
        s.append("  members=\"$(mktemp)\" || { rm -f \"$list\"; return 1; }\n");
        s.append("  checked=\"$(mktemp)\" || { rm -f \"$list\" \"$members\"; return 1; }\n");
        s.append("  if ! \"$tarbin\" -tzf \"$archive\" > \"$members\" 2>/dev/null; then\n");
        s.append("    rm -f \"$list\" \"$members\" \"$checked\"\n");
        s.append("    return 1\n");
        s.append("  fi\n");
        s.append("  if ! \"$tarbin\" -tzvf \"$archive\" > \"$list\" 2>/dev/null; then\n");
        s.append("    rm -f \"$list\" \"$members\" \"$checked\"\n");
        s.append("    return 1\n");
        s.append("  fi\n");
        s.append("  while IFS= read -r line; do\n");
        s.append("    case \"$line\" in\n");
        s.append("      h*) ;;\n");
        s.append("      *) continue ;;\n");
        s.append("    esac\n");
        s.append("    pair=\"$(printf '%s\\n' \"$line\" | sed -E -n 's/^h[^ ]+[ ]+[^ ]+[ ]+[^ ]+[ ]+[^ ]+[ ]+[^ ]+[ ]+(.*) link to (.*)$/\\1|\\2/p')\"\n");
        // NOTE: tar pads the owner column, so the five fields before
        // the name (perms, owner, size, date, time) are separated by
        // one OR MORE spaces ([ ]+), never exactly one.
        s.append("    if [ -z \"$pair\" ]; then ok=0; continue; fi\n");
        s.append("    name=\"${pair%%|*}\"\n");
        s.append("    target=\"${pair#*|}\"\n");
        s.append("    if [ \"$#\" -gt 0 ]; then\n");
        s.append("      in_section=0\n");
        s.append("      for sec in \"$@\"; do\n");
        s.append("        case \"$name\" in\n");
        s.append("          \"$sec\"/*) in_section=1 ;;\n");
        s.append("        esac\n");
        s.append("      done\n");
        s.append("      if [ \"$in_section\" -eq 0 ]; then continue; fi\n");
        s.append("    fi\n");
        s.append("    total=$((total + 1))\n");
        s.append("    if ! grep -qxF -- \"$target\" \"$members\"; then ok=0; continue; fi\n");
        s.append("    if [ \"$strip\" -eq 1 ]; then\n");
        s.append("      name=\"${name#*/}\"\n");
        s.append("      target=\"${target#*/}\"\n");
        s.append("    fi\n");
        s.append("    if [ -z \"$name\" ] || [ -z \"$target\" ]; then ok=0; continue; fi\n");
        s.append("    dest=\"$rootfs/$name\"\n");
        s.append("    want=\"$(_repair_relpath \"$(dirname \"$name\")\" \"$target\")\"\n");
        s.append("    printf '%s\\n' \"$dest\" >> \"$checked\"\n");
        s.append("    if [ -L \"$dest\" ]; then\n");
        s.append("      if [ \"$(readlink \"$dest\")\" != \"$want\" ]; then ok=0; fi\n");
        s.append("      continue\n");
        s.append("    fi\n");
        s.append("    if [ -e \"$dest\" ]; then ok=0; continue; fi\n");
        s.append("    mkdir -p \"$(dirname \"$dest\")\" || { ok=0; continue; }\n");
        s.append("    ln -s \"$want\" \"$dest\" || ok=0\n");
        s.append("  done < \"$list\"\n");
        s.append("  while IFS= read -r dest; do\n");
        s.append("    if [ ! -L \"$dest\" ]; then ok=0; fi\n");
        s.append("  done < \"$checked\"\n");
        s.append("  rm -f \"$list\" \"$members\" \"$checked\"\n");
        s.append("  [ \"$total\" -gt 0 ] && [ \"$ok\" -eq 1 ]\n");
        s.append("}\n");
        s.append("verify_members() {\n");
        s.append("  if [ \"$#\" -lt 3 ]; then return 1; fi\n");
        s.append("  local archive=\"$1\" rootfs=\"$2\" strip=\"$3\"\n");
        s.append("  shift 3\n");
        s.append("  local tarbin=\"${REPAIR_TAR:-tar}\"\n");
        s.append("  local members member in_section count ok sec\n");
        s.append("  count=0\n");
        s.append("  ok=1\n");
        s.append("  members=\"$(mktemp)\" || return 1\n");
        s.append("  if ! \"$tarbin\" -tzf \"$archive\" > \"$members\" 2>/dev/null; then\n");
        s.append("    rm -f \"$members\"\n");
        s.append("    return 1\n");
        s.append("  fi\n");
        s.append("  while IFS= read -r member; do\n");
        s.append("    if [ \"$#\" -gt 0 ]; then\n");
        s.append("      in_section=0\n");
        s.append("      for sec in \"$@\"; do\n");
        s.append("        case \"$member\" in\n");
        s.append("          \"$sec\"/*) in_section=1 ;;\n");
        s.append("        esac\n");
        s.append("      done\n");
        s.append("      if [ \"$in_section\" -eq 0 ]; then continue; fi\n");
        s.append("    fi\n");
        s.append("    if [ \"$strip\" -eq 1 ]; then\n");
        s.append("      member=\"${member#*/}\"\n");
        s.append("    fi\n");
        s.append("    member=\"${member%/}\"\n");
        s.append("    if [ -z \"$member\" ]; then continue; fi\n");
        s.append("    count=$((count + 1))\n");
        s.append("    if [ ! -e \"$rootfs/$member\" ] && [ ! -L \"$rootfs/$member\" ]; then\n");
        s.append("      ok=0\n");
        s.append("    fi\n");
        s.append("  done < \"$members\"\n");
        s.append("  rm -f \"$members\"\n");
        s.append("  [ \"$count\" -gt 0 ] && [ \"$ok\" -eq 1 ]\n");
        s.append("}\n");
        return s.toString();
    }

    /** Runs the shared repair_hard_links function for one archive
     *  whose extraction just failed. The function text is fed to
     *  bash -s on stdin, with the archive, rootfs, strip count and
     *  sections as arguments; REPAIR_TAR points at the host tar
     *  (ProcessBuilder does not inherit the Termux session PATH).
     *  True iff the repair reports every hard-link member present
     *  as a symlink. Only call after a failed extraction — a
     *  successful extraction needs no repair. Call on a background
     *  thread. */
    private static boolean repairHardLinks(File archive, File rootfs, int strip,
            List<String> sections) {
        if (archive == null || !archive.isFile() || rootfs == null || !rootfs.isDirectory()) {
            return false;
        }
        File bash = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "bash");
        String bashPath = bash.isFile() ? bash.getAbsolutePath() : "bash";
        List<String> command = new ArrayList<>();
        command.add(bashPath);
        command.add("-s");
        command.add(archive.getAbsolutePath());
        command.add(rootfs.getAbsolutePath());
        command.add(Integer.toString(strip));
        if (sections != null) command.addAll(sections);
        StringBuilder script = new StringBuilder();
        script.append(hardLinkRepairFunction());
        script.append("repair_hard_links \"$@\"\n");
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        builder.environment().put("REPAIR_TAR", tarExecutable());
        try {
            Process process = builder.start();
            try (OutputStream out = process.getOutputStream()) {
                out.write(script.toString().getBytes(StandardCharsets.UTF_8));
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Log.d(LOG_TAG, "repair: " + line);
                }
            }
            int exit = process.waitFor();
            if (exit != 0) {
                Log.w(LOG_TAG, "repair_hard_links exited " + exit + " for "
                    + archive.getAbsolutePath());
                return false;
            }
            Log.i(LOG_TAG, "Hard-link members repaired as symlinks for "
                + archive.getAbsolutePath());
            return true;
        } catch (Exception e) {
            Log.w(LOG_TAG, "Failed to run repair_hard_links for "
                + archive.getAbsolutePath(), e);
            return false;
        }
    }

    /** Same-distro rule (design.md §4): the manifest's distroId must be
     * non-empty and equal to the target container's distroId. A
     * missing/empty manifest distroId is NEVER same-distro (safe
     * default: cross-distro, data-only). The version number is never
     * compared — distroId is the distro family. Public so the restore
     * dialog can check it. */
    public static boolean isSameDistro(JSONObject manifest, String targetDistroId) {
        if (manifest == null) return false;
        String manifestDistroId = manifest.optString("distroId", "");
        return manifestDistroId != null && !manifestDistroId.isEmpty()
            && manifestDistroId.equals(targetDistroId);
    }

    /** True when the manifest's items array contains the given item id
     * (for the restore dialog to test for the system item). False for a
     * null manifest, a null item id, or a manifest with no items array. */
    public static boolean manifestHasItem(JSONObject manifest, String itemId) {
        if (manifest == null || itemId == null) return false;
        JSONArray array = manifest.optJSONArray("items");
        if (array == null) return false;
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item != null && itemId.equals(item.optString("id", ""))) return true;
        }
        return false;
    }

    /** Manifest item id -> archive file name, for the items the manifest
     * lists. Archive names containing '/' or ".." are rejected (path
     * traversal): such an item is skipped by the caller. */
    private static java.util.Map<String, String> manifestItemArchives(JSONObject manifest) {
        java.util.Map<String, String> result = new java.util.HashMap<>();
        JSONArray array = manifest.optJSONArray("items");
        if (array == null) return result;
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            String id = item.optString("id", "");
            String archive = item.optString("archive", "");
            if (id.isEmpty() || archive.isEmpty()) continue;
            if (archive.contains("/") || archive.contains("\\")
                || archive.contains("..")) continue;
            result.put(id, archive);
        }
        return result;
    }

    /** True when any listed member is under the given top-level section
     * ("data/" or "program/"), counting the bare "data/" dir entry. */
    private static boolean hasSection(List<String> members, String section) {
        for (String member : members) {
            if (member.equals(section + "/") || member.startsWith(section + "/")) return true;
        }
        return false;
    }

    /**
     * Restores the selected items from a backup dir into a container's
     * rootfs. Call on a background thread (runs tar synchronously).
     *
     * <p>Rules (design.md §4): the manifest is re-read here — null or
     * unparseable returns false and the caller marks the backup damaged.
     * The home item extracts home.tar.gz whole into the rootfs (its
     * paths are rootfs-relative, e.g. root/.bashrc). The system item
     * extracts system.tar.gz whole into the rootfs, but ONLY when the
     * manifest distroId matches the target (same distro family);
     * cross-distro it is skipped silently, never a failure. An agent item
     * extracts agent-&lt;id&gt;.tar.gz with the data/ or program/ prefix
     * stripped (tar --strip-components=1): same distroId extracts BOTH
     * data/ and program/; cross-distro (or missing manifest distroId)
     * extracts ONLY data/, never program/. Only sections actually
     * present in the archive are requested (a missing section makes
     * tar exit 2). The backup is never deleted.
     *
     * @param backupDir       backup dir holding manifest.json + archives
     *                        (an internal dir, or a locally staged copy
     *                        of an external backup)
     * @param rootfs          target container's rootfs (host path)
     * @param targetDistroId  target container's distroId, e.g. "ubuntu"
     *                        (null when unknown — then every agent item
     *                        restores data-only)
     * @param selectedItemIds item ids to restore ("system", "home" and/or "agent:&lt;id&gt;")
     * @return true only if every selected item extracted without error
     */
    public static boolean restoreItems(File backupDir, File rootfs, String targetDistroId,
            java.util.List<String> selectedItemIds) {
        if (backupDir == null || rootfs == null || !rootfs.isDirectory()) return false;
        if (selectedItemIds == null || selectedItemIds.isEmpty()) return true;
        JSONObject manifest = readManifest(backupDir);
        if (manifest == null) return false;
        boolean sameDistro = isSameDistro(manifest, targetDistroId);
        java.util.Map<String, String> archives = manifestItemArchives(manifest);
        boolean allOk = true;
        for (String itemId : selectedItemIds) {
            if (itemId == null) {
                allOk = false;
                continue;
            }
            if (SYSTEM_ITEM_ID.equals(itemId)) {
                if (!sameDistro) {
                    // Cross-distro: the system image belongs to another
                    // distro family — skip silently, not a failure (the
                    // restore dialog already warns the user).
                    continue;
                }
                String archiveName = archives.containsKey(SYSTEM_ITEM_ID)
                    ? archives.get(SYSTEM_ITEM_ID) : SYSTEM_ARCHIVE;
                File archive = new File(backupDir, archiveName);
                if (!archive.isFile()) {
                    Log.w(LOG_TAG, "System archive missing: " + archive.getAbsolutePath());
                    allOk = false;
                    continue;
                }
                List<String> args = new ArrayList<>();
                args.add("-xzf");
                args.add(archive.getAbsolutePath());
                args.add("-C");
                args.add(rootfs.getAbsolutePath());
                if (!runTar(args)) allOk = false;
            } else if (HOME_ITEM_ID.equals(itemId)) {
                String archiveName = archives.containsKey(HOME_ITEM_ID)
                    ? archives.get(HOME_ITEM_ID) : HOME_ARCHIVE;
                File archive = new File(backupDir, archiveName);
                if (!archive.isFile()) {
                    Log.w(LOG_TAG, "Home archive missing: " + archive.getAbsolutePath());
                    allOk = false;
                    continue;
                }
                List<String> args = new ArrayList<>();
                args.add("-xzf");
                args.add(archive.getAbsolutePath());
                args.add("-C");
                args.add(rootfs.getAbsolutePath());
                if (!runTar(args) && !repairHardLinks(archive, rootfs, 0, null)) allOk = false;
            } else if (itemId.startsWith(AGENT_ITEM_ID_PREFIX)) {
                String agentId = itemId.substring(AGENT_ITEM_ID_PREFIX.length());
                AiAgentUtils.AgentDef def = AiAgentUtils.findDef(agentId);
                if (def == null) {
                    Log.w(LOG_TAG, "Unknown agent in backup item: " + itemId);
                    allOk = false;
                    continue;
                }
                String archiveName = archives.containsKey(itemId)
                    ? archives.get(itemId) : agentArchiveName(agentId);
                File archive = new File(backupDir, archiveName);
                if (!archive.isFile()) {
                    Log.w(LOG_TAG, "Agent archive missing: " + archive.getAbsolutePath());
                    allOk = false;
                    continue;
                }
                List<String> members = listTarMembers(archive);
                if (members == null) {
                    allOk = false;
                    continue;
                }
                boolean hasData = hasSection(members, "data");
                boolean hasProgram = hasSection(members, "program");
                List<String> wanted = new ArrayList<>();
                if (hasData) wanted.add("data");
                if (sameDistro && hasProgram) wanted.add("program");
                if (wanted.isEmpty()) {
                    // Cross-distro agent with no data/ section (or an
                    // archive holding neither section): nothing
                    // restorable for this item.
                    Log.w(LOG_TAG, "Agent archive has no restorable section: "
                        + archive.getAbsolutePath());
                    allOk = false;
                    continue;
                }
                List<String> args = new ArrayList<>();
                args.add("-xzf");
                args.add(archive.getAbsolutePath());
                args.add("-C");
                args.add(rootfs.getAbsolutePath());
                args.add("--strip-components=1");
                args.addAll(wanted);
                if (!runTar(args) && !repairHardLinks(archive, rootfs, 1, wanted)) allOk = false;
            } else {
                Log.w(LOG_TAG, "Unknown backup item id: " + itemId);
                allOk = false;
            }
        }
        return allOk;
    }

    /**
     * Verifies the selected items after a restore, returning the ids
     * that FAILED verification (empty = all good). Call on a background
     * thread (lists tar members for the home item).
     *
     * <p>Exact verification rule, per item (design.md §4):
     * <ul>
     *   <li>system, same distroId: system.tar.gz must be listable, and
     *       a sample of the archive's file entries (up to 20, skipping
     *       dir entries) must exist under the rootfs. Cross-distro the
     *       item is skipped (it was never restored), never a failure.</li>
     *   <li>home: home.tar.gz must be listable, the target rootfs must
     *       have a root/ dir, and a sample of the archive's file
     *       entries (up to 20, skipping dir entries) must exist under
     *       the rootfs. (Only a sample is checked: a whole home tree
     *       can hold thousands of files.)</li>
     *   <li>agent, same distroId: AiAgentUtils.findBinPath(rootfs, def)
     *       must be non-null — the restored program shows as installed.</li>
     *   <li>agent, cross-distro: at least one of the agent's
     *       AiAgentUtils.dataPaths(def) entries (wildcards expanded
     *       against the rootfs) must exist under the rootfs. Agents
     *       with no named data paths (dsh, OpenCode, Hermes) have
     *       nothing on disk to check, so they pass iff their archive
     *       file exists in the backup dir.</li>
     * </ul>
     * A null/unparseable manifest fails every selected item. An
     * unknown agent id fails that item.
     */
    public static java.util.List<String> verifyRestoredItems(File backupDir, File rootfs,
            String targetDistroId, java.util.List<String> selectedItemIds) {
        List<String> failed = new ArrayList<>();
        if (selectedItemIds == null || selectedItemIds.isEmpty()) return failed;
        if (backupDir == null || rootfs == null || !rootfs.isDirectory()) {
            failed.addAll(selectedItemIds);
            return failed;
        }
        JSONObject manifest = readManifest(backupDir);
        if (manifest == null) {
            failed.addAll(selectedItemIds);
            return failed;
        }
        boolean sameDistro = isSameDistro(manifest, targetDistroId);
        java.util.Map<String, String> archives = manifestItemArchives(manifest);
        // Archive integrity first: a corrupt/truncated archive fails
        // the item even if files it once held are still on disk.
        // Old manifests without sha256 fall back to the size check.
        // (Cross-distro system items are skipped below, as before,
        // so a bad system archive never fails a cross-distro restore
        // that does not restore it.)
        List<String> badArchives = verifyBackupArchives(backupDir, selectedItemIds);
        if (badArchives == null) {
            failed.addAll(selectedItemIds);
            return failed;
        }
        for (String itemId : selectedItemIds) {
            if (itemId == null) {
                failed.add(itemId);
                continue;
            }
            if (SYSTEM_ITEM_ID.equals(itemId)) {
                if (!sameDistro) {
                    // Cross-distro: skipped at restore time, so there is
                    // nothing to verify — never a failure.
                    continue;
                }
                if (badArchives.contains(itemId)) {
                    failed.add(itemId);
                    continue;
                }
                String archiveName = archives.containsKey(SYSTEM_ITEM_ID)
                    ? archives.get(SYSTEM_ITEM_ID) : SYSTEM_ARCHIVE;
                File archive = new File(backupDir, archiveName);
                List<String> members = archive.isFile() ? listTarMembers(archive) : null;
                if (members == null) {
                    failed.add(itemId);
                    continue;
                }
                int checked = 0;
                boolean ok = true;
                for (String member : members) {
                    if (member.endsWith("/")) continue;
                    if (!new File(rootfs, member).exists()) {
                        ok = false;
                        break;
                    }
                    if (++checked >= 20) break;
                }
                if (!ok) failed.add(itemId);
            } else if (HOME_ITEM_ID.equals(itemId)) {
                if (badArchives.contains(itemId)) {
                    failed.add(itemId);
                    continue;
                }
                String archiveName = archives.containsKey(HOME_ITEM_ID)
                    ? archives.get(HOME_ITEM_ID) : HOME_ARCHIVE;
                File archive = new File(backupDir, archiveName);
                List<String> members = archive.isFile() ? listTarMembers(archive) : null;
                if (members == null || !new File(rootfs, "root").isDirectory()) {
                    failed.add(itemId);
                    continue;
                }
                int checked = 0;
                boolean ok = true;
                for (String member : members) {
                    if (member.endsWith("/")) continue;
                    if (!new File(rootfs, member).exists()) {
                        ok = false;
                        break;
                    }
                    if (++checked >= 20) break;
                }
                if (!ok) failed.add(itemId);
            } else if (itemId.startsWith(AGENT_ITEM_ID_PREFIX)) {
                String agentId = itemId.substring(AGENT_ITEM_ID_PREFIX.length());
                AiAgentUtils.AgentDef def = AiAgentUtils.findDef(agentId);
                if (def == null) {
                    failed.add(itemId);
                    continue;
                }
                if (badArchives.contains(itemId)) {
                    failed.add(itemId);
                    continue;
                }
                if (sameDistro) {
                    if (AiAgentUtils.findBinPath(rootfs, def) == null) failed.add(itemId);
                } else {
                    List<String> dataPaths = DistroUtils.expandWildcardPaths(
                        rootfs, AiAgentUtils.dataPaths(def));
                    if (dataPaths.isEmpty()) {
                        // No named data paths to check (dsh, OpenCode,
                        // Hermes): pass iff the archive exists.
                        String archiveName = archives.containsKey(itemId)
                            ? archives.get(itemId) : agentArchiveName(agentId);
                        if (!new File(backupDir, archiveName).isFile()) failed.add(itemId);
                    } else {
                        boolean anyExists = false;
                        for (String rel : dataPaths) {
                            File file = new File(rootfs, rel);
                            if (file.exists()) {
                                anyExists = true;
                                break;
                            }
                        }
                        if (!anyExists) failed.add(itemId);
                    }
                }
            } else {
                failed.add(itemId);
            }
        }
        return failed;
    }

    // ------------------------------------------------------------------
    // Backup + remove script (runs on the HOST in a visible terminal
    // session; host tools are Termux GNU tar / coreutils, not the
    // container's tools)
    // ------------------------------------------------------------------

    /**
     * Builds the backup + remove script that DistroActivity runs in a
     * visible terminal session. Order: pack system.tar.gz (whole rootfs
     * minus /root, every agent's program+data paths and var/cache,
     * var/log, tmp, var/tmp) -> pack home.tar.gz (rootfs /root,
     * excluding every agent's program+data paths) -> pack one
     * agent-&lt;id&gt;.tar.gz per selected agent (data/ + program/ split,
     * only paths that exist) -> write manifest.json (sizes from stat,
     * sha256 from sha256sum)
     * -> verify (manifest non-empty and mentions every archive; every
     * archive exists, is non-empty and holds at least one file) ->
     * `proot-distro remove &lt;container&gt;`. Any failure aborts BEFORE
     * the remove, so a failed backup never deletes the container.
     * Shared system packages are never deleted (the remove is wholesale;
     * no per-file deletes). The script ends with a read-pause on both
     * paths so the user can see the result (same as the install script).
     *
     * <p>Manifest keys (exact): version, container, distroId,
     * distroDisplay, createdAt, items[] with id, label, archive, size,
     * sha256 (lowercase hex; absent in backups written before this
     * round, which the Java side verifies by size only).
     * Item ids are "system", "home" or "agent:&lt;AgentDef id&gt;";
     * archives are "system.tar.gz", "home.tar.gz" or
     * "agent-&lt;AgentDef id&gt;.tar.gz". Agent archive layout:
     * data/&lt;rootfs-relative path&gt; and
     * program/&lt;rootfs-relative path&gt;; home and system archive
     * layout: rootfs-relative paths (e.g. root/.bashrc, etc/passwd).
     *
     * <p>Verification limit, stated honestly: there is no JSON parser
     * guaranteed on the host (no python/jq/node), so the script verifies
     * the manifest structurally (non-empty, contains "version": 1 and
     * every archive name) plus per-archive existence / non-empty /
     * holds-a-file checks and a recomputed SHA-256 compared with the
     * value recorded when the archive was packed. The manifest's
     * stored sha256 is re-checked by the Java side
     * (DistroBackup.verifyBackupArchives) at copy/restore time, since
     * the script cannot parse JSON. The manifest is generated from a fixed
     * template whose string values are app constants or validated
     * inputs (no embedded " or \), so it is well-formed by
     * construction; the restore side re-parses it with org.json and
     * treats an unparseable manifest as damaged.
     *
     * @param rootfs          container rootfs (host path)
     * @param container       container name, e.g. "ubuntu-26.10"
     * @param distroId        distro family id, e.g. "ubuntu" ("" when unknown)
     * @param distroDisplay   display name, e.g. "Ubuntu 26.10"
     * @param backupDir       backup dir (created by newBackupDir)
     * @param selectedItemIds item ids to back up ("system", "home" and/or "agent:<id>")
     * @param agents          all AgentDefs (home excludes every agent's
     *                        paths; only selected agents get an archive)
     * @param externalFolderDisplay human-readable external folder path
     *                        (see ExternalBackupStore.displayFolderPath),
     *                        or null for an internal backup. When non-null
     *                        the final message says the packed backup is
     *                        being copied there, instead of only printing
     *                        the internal $BACKUP_DIR.
     */
    public static String buildBackupAndRemoveScript(File rootfs, String container,
            String distroId, String distroDisplay, File backupDir,
            List<String> selectedItemIds, List<AiAgentUtils.AgentDef> agents,
            String externalFolderDisplay) {
        return buildBackupScript(rootfs, container, distroId, distroDisplay, backupDir,
            selectedItemIds, agents, true, externalFolderDisplay);
    }

    /**
     * Builds the backup-only script: same pack + manifest + verify steps
     * as buildBackupAndRemoveScript, but the container is NOT removed
     * afterwards (steps [1/3] backup, [2/3] manifest, [3/3] verify) and
     * no fail/trap wording mentions a removal. Same parameters as
     * buildBackupAndRemoveScript, including the nullable
     * externalFolderDisplay (see above).
     */
    public static String buildBackupOnlyScript(File rootfs, String container,
            String distroId, String distroDisplay, File backupDir,
            List<String> selectedItemIds, List<AiAgentUtils.AgentDef> agents,
            String externalFolderDisplay) {
        return buildBackupScript(rootfs, container, distroId, distroDisplay, backupDir,
            selectedItemIds, agents, false, externalFolderDisplay);
    }

    /** Shared pack + manifest + verify body for the two builders above.
     * With removeAfter the script ends with the [4/4] remove step
     * (`proot-distro remove`); without it the script ends after the
     * [3/3] verify with a "备份完成" message. */
    private static String buildBackupScript(File rootfs, String container,
            String distroId, String distroDisplay, File backupDir,
            List<String> selectedItemIds, List<AiAgentUtils.AgentDef> agents,
            boolean removeAfter, String externalFolderDisplay) {
        if (rootfs == null) throw new IllegalArgumentException("rootfs is null");
        if (!isValidContainerName(container)) {
            throw new IllegalArgumentException("bad container name: " + container);
        }
        if (backupDir == null) throw new IllegalArgumentException("backupDir is null");
        if (selectedItemIds == null) selectedItemIds = new ArrayList<>();
        if (agents == null) agents = new ArrayList<>();

        boolean backupSystem = selectedItemIds.contains(SYSTEM_ITEM_ID);
        boolean backupHome = selectedItemIds.contains(HOME_ITEM_ID);
        // Selected agents, in AGENTS order.
        List<AiAgentUtils.AgentDef> selectedAgents = new ArrayList<>();
        for (AiAgentUtils.AgentDef def : agents) {
            if (def != null && selectedItemIds.contains(agentItemId(def.id))) {
                selectedAgents.add(def);
            }
        }
        // Every agent's program+data paths, for the home exclusions (all
        // agents, not just the selected ones: home must never contain
        // agent files, whether or not that agent is backed up).
        List<String> allAgentPaths = new ArrayList<>();
        for (AiAgentUtils.AgentDef def : agents) {
            if (def == null) continue;
            allAgentPaths.addAll(AiAgentUtils.programPaths(def));
            allAgentPaths.addAll(AiAgentUtils.dataPaths(def));
        }
        List<String> homeExcludes = dedupPaths(
            DistroUtils.expandWildcardPaths(rootfs, allAgentPaths));
        // Manifest items, in order: system first, then home, then agents
        // in AGENTS order.
        // Each entry: {item id, label, archive, shell var holding its size,
        // shell var holding its sha256}.
        List<String[]> manifestItems = new ArrayList<>();
        if (backupSystem) {
            manifestItems.add(new String[]{SYSTEM_ITEM_ID, SYSTEM_ITEM_LABEL, SYSTEM_ARCHIVE, "SYSTEM_SIZE", "SYSTEM_SHA"});
        }
        if (backupHome) {
            manifestItems.add(new String[]{HOME_ITEM_ID, HOME_ITEM_LABEL, HOME_ARCHIVE, "HOME_SIZE", "HOME_SHA"});
        }
        for (AiAgentUtils.AgentDef def : selectedAgents) {
            manifestItems.add(new String[]{agentItemId(def.id), def.displayName,
                agentArchiveName(def.id), sizeVarFor(def.id), shaVarFor(def.id)});
        }
        String createdAt = isoNow();
        // Step labels: [1/4]..[4/4] when the container is removed after
        // the backup, [1/3]..[3/3] for a backup-only run.
        int totalSteps = removeAfter ? 4 : 3;
        String stepBackup = "[1/" + totalSteps + "]";
        String stepManifest = "[2/" + totalSteps + "]";
        String stepVerify = "[3/" + totalSteps + "]";
        String stepRemove = "[4/" + totalSteps + "]";

        StringBuilder s = new StringBuilder();
        s.append("#!/data/data/com.termux/files/usr/bin/bash\n");
        s.append("# Generated by Termux \"Linux 发行版\" (DistroBackup) - do not edit.\n");
        if (removeAfter) {
            s.append("# Backup of ").append(container).append(" then `proot-distro remove ")
                .append(container).append("`. Aborts before the remove when any\n");
            s.append("# backup step or verification fails, so the container is kept.\n");
        } else {
            s.append("# Backup of ").append(container).append(". Aborts when any\n");
            s.append("# backup step or verification fails.\n");
        }
        s.append("set -e\n");
        s.append("ROOTFS=").append(shellQuote(rootfs.getAbsolutePath())).append("\n");
        s.append("BACKUP_DIR=").append(shellQuote(backupDir.getAbsolutePath())).append("\n");
        s.append("CONTAINER=").append(shellQuote(container)).append("\n");
        s.append("DISTRO_ID=").append(shellQuote(distroId == null ? "" : distroId)).append("\n");
        s.append("DISTRO_DISPLAY=").append(shellQuote(distroDisplay == null ? "" : distroDisplay)).append("\n");
        s.append("CREATED_AT=").append(shellQuote(createdAt)).append("\n");
        // Explicit failure helper (pauses so the user sees the error) plus
        // an ERR-trap backstop for unexpected failures (tar/stat errors).
        // `read` needs the terminal stdin, which a visible session has.
        s.append("fail() {\n");
        s.append("  echo \"备份失败：$1\"\n");
        if (removeAfter) {
            s.append("  echo \"容器未删除。\"\n");
        }
        s.append("  read -r -p \"按回车关闭此会话\" || true\n");
        s.append("  exit 1\n");
        s.append("}\n");
        if (removeAfter) {
            s.append("trap 'code=$?; echo \"备份或卸载失败（退出码 $code）。容器未删除。\"; read -r -p \"按回车关闭此会话\" || true; exit $code' ERR\n");
        } else {
            s.append("trap 'code=$?; echo \"备份失败（退出码 $code）。\"; read -r -p \"按回车关闭此会话\" || true; exit $code' ERR\n");
        }
        s.append("echo \"").append(stepBackup).append(" 正在备份 ").append(shellQuote(distroDisplay == null ? "" : distroDisplay)).append(" ...\"\n");
        s.append("mkdir -p \"$BACKUP_DIR\"\n");

        // --- system.tar.gz: whole rootfs minus /root, every agent
        // program+data path (same deduped list as the home exclusions),
        // the cache/log/tmp dirs, and the Android bind-mount + runtime
        // top dirs (DistroUtils.SYSTEM_EXCLUDED_TOP_DIRS — not part of
        // the distro; tar cannot read them from the host and aborts).
        // --ignore-failed-read (placed after the archive name: GNU tar
        // takes the word right after `czf` as the file name, so it must
        // not go there): one unreadable file only warns instead of
        // failing the whole backup (same for home and agent tars). ---
        if (backupSystem) {
            s.append("echo \"正在打包发行版系统 ...\"\n");
            s.append("tar czf \"$BACKUP_DIR/").append(SYSTEM_ARCHIVE).append("\" --ignore-failed-read -C \"$ROOTFS\"");
            s.append(" --exclude=").append(shellQuote("root"));
            for (String path : homeExcludes) {
                s.append(" --exclude=").append(shellQuote(path));
            }
            s.append(" --exclude=").append(shellQuote("var/cache"));
            s.append(" --exclude=").append(shellQuote("var/log"));
            s.append(" --exclude=").append(shellQuote("tmp"));
            s.append(" --exclude=").append(shellQuote("var/tmp"));
            for (String dir : DistroUtils.SYSTEM_EXCLUDED_TOP_DIRS) {
                s.append(" --exclude=").append(shellQuote(dir));
            }
            s.append(" .\n");
        }

        // --- home.tar.gz: /root minus every agent program+data path ---
        if (backupHome) {
            s.append("echo \"正在打包用户文件 (/root) ...\"\n");
            s.append("tar czf \"$BACKUP_DIR/").append(HOME_ARCHIVE).append("\" --ignore-failed-read -C \"$ROOTFS\"");
            for (String path : homeExcludes) {
                s.append(" --exclude=").append(shellQuote(path));
            }
            s.append(" root\n");
        }

        // --- one agent-<id>.tar.gz per selected agent, data/ + program/ ---
        // Paths are staged as symlinks (cheap: no copy) and tar -h packs
        // the real contents under data/ or program/. Only paths that
        // exist are staged ([ -e ] also skips broken symlinks).
        if (!selectedAgents.isEmpty()) {
            s.append("STAGE=\"$(mktemp -d \"$BACKUP_DIR/.stage-XXXXXX\")\"\n");
            s.append("trap '[ -n \"$STAGE\" ] && rm -rf \"$STAGE\"' EXIT\n");
            for (AiAgentUtils.AgentDef def : selectedAgents) {
                s.append("echo \"正在打包 ").append(def.displayName).append(" ...\"\n");
                appendStageLinks(s, def.id, "program", dedupPaths(
                    DistroUtils.expandWildcardPaths(rootfs, AiAgentUtils.programPaths(def))));
                appendStageLinks(s, def.id, "data", dedupPaths(
                    DistroUtils.expandWildcardPaths(rootfs, AiAgentUtils.dataPaths(def))));
                s.append("tar czhf \"$BACKUP_DIR/").append(agentArchiveName(def.id))
                    .append("\" --ignore-failed-read -C \"$STAGE\"/").append(shellQuote(def.id))
                    .append(" data program\n");
            }
            s.append("rm -rf \"$STAGE\"\n");
            s.append("STAGE=\"\"\n");
        }

        // --- manifest.json (sizes from stat; archives missing -> abort) ---
        s.append("echo \"").append(stepManifest).append(" 正在写备份清单 ...\"\n");
        if (backupSystem) {
            s.append("SYSTEM_SIZE=$(stat -c %s \"$BACKUP_DIR/").append(SYSTEM_ARCHIVE).append("\")\n");
            s.append("SYSTEM_SHA=$(sha256sum \"$BACKUP_DIR/").append(SYSTEM_ARCHIVE).append("\" | cut -d' ' -f1)\n");
        }
        if (backupHome) {
            s.append("HOME_SIZE=$(stat -c %s \"$BACKUP_DIR/").append(HOME_ARCHIVE).append("\")\n");
            s.append("HOME_SHA=$(sha256sum \"$BACKUP_DIR/").append(HOME_ARCHIVE).append("\" | cut -d' ' -f1)\n");
        }
        for (AiAgentUtils.AgentDef def : selectedAgents) {
            s.append(sizeVarFor(def.id)).append("=$(stat -c %s \"$BACKUP_DIR/")
                .append(agentArchiveName(def.id)).append("\")\n");
            s.append(shaVarFor(def.id)).append("=$(sha256sum \"$BACKUP_DIR/")
                .append(agentArchiveName(def.id)).append("\" | cut -d' ' -f1)\n");
        }
        s.append("{\n");
        s.append("  printf '%s\\n' '{'\n");
        s.append("  printf '  \"version\": 1,\\n'\n");
        s.append("  printf '  \"container\": \"%s\",\\n' \"$CONTAINER\"\n");
        s.append("  printf '  \"distroId\": \"%s\",\\n' \"$DISTRO_ID\"\n");
        s.append("  printf '  \"distroDisplay\": \"%s\",\\n' \"$DISTRO_DISPLAY\"\n");
        s.append("  printf '  \"createdAt\": \"%s\",\\n' \"$CREATED_AT\"\n");
        if (manifestItems.isEmpty()) {
            s.append("  printf '  \"items\": []\\n'\n");
        } else {
            s.append("  printf '  \"items\": [\\n'\n");
            for (int i = 0; i < manifestItems.size(); i++) {
                String[] item = manifestItems.get(i);
                String comma = i + 1 < manifestItems.size() ? "," : "";
                s.append("  printf '    {\"id\": \"%s\", \"label\": \"%s\", \"archive\": \"%s\", \"size\": %s, \"sha256\": \"%s\"}%s\\n' ")
                    .append(shellQuote(item[0])).append(" ")
                    .append(shellQuote(item[1])).append(" ")
                    .append(shellQuote(item[2])).append(" ")
                    .append("\"$").append(item[3]).append("\" ")
                    .append("\"$").append(item[4]).append("\" ")
                    .append(shellQuote(comma)).append("\n");
            }
            s.append("  printf '  ]\\n'\n");
        }
        s.append("  printf '%s\\n' '}'\n");
        s.append("} > \"$BACKUP_DIR/").append(MANIFEST_FILE).append("\"\n");

        // --- verify, then remove (never the other way round) ---
        s.append("echo \"").append(stepVerify).append(" 正在校验备份 ...\"\n");
        s.append("[ -s \"$BACKUP_DIR/").append(MANIFEST_FILE)
            .append("\" ] || fail \"manifest.json 为空或缺失\"\n");
        s.append("grep -Fq '\"version\": 1' \"$BACKUP_DIR/").append(MANIFEST_FILE)
            .append("\" || fail \"manifest.json 格式异常\"\n");
        for (String[] item : manifestItems) {
            String archive = item[2];
            s.append("[ -s \"$BACKUP_DIR/").append(archive)
                .append("\" ] || fail \"").append(archive).append(" 缺失或为空\"\n");
            s.append("grep -Fq ").append(shellQuote(archive)).append(" \"$BACKUP_DIR/")
                .append(MANIFEST_FILE).append("\" || fail \"manifest.json 里没有 ")
                .append(archive).append("\"\n");
            // At least one real file (not just the data/ and program/ dirs).
            s.append("tar tzf \"$BACKUP_DIR/").append(archive)
                .append("\" | grep -qv '/$' || fail \"").append(archive)
                .append(" 里没有文件\"\n");
            // Recompute the checksum and compare it with the value
            // recorded when the archive was packed: a file truncated
            // or corrupted between packing and this check fails here,
            // before any remove step runs. (The manifest's stored
            // sha256 is re-checked by the Java side at copy/restore
            // time; the script compares against the in-memory value
            // because it cannot parse JSON.)
            s.append("[ -n \"$").append(item[4]).append("\" ] || fail \"").append(archive)
                .append(" 校验和缺失\"\n");
            s.append("ACTUAL_SHA=$(sha256sum \"$BACKUP_DIR/").append(archive)
                .append("\" | cut -d' ' -f1)\n");
            s.append("[ \"$ACTUAL_SHA\" = \"$").append(item[4]).append("\" ] || fail \"")
                .append(archive).append(" 校验和不符（可能已损坏）\"\n");
        }
        if (removeAfter) {
            s.append("echo \"").append(stepRemove).append(" 正在删除 ").append(shellQuote(distroDisplay == null ? "" : distroDisplay)).append(" ...\"\n");
            // proot-distro remove does not ask for confirmation (upstream
            // README: "Be careful as it does not ask for confirmation"), so
            // the app's own confirm dialog is the only prompt.
            s.append("proot-distro remove ").append(container).append("\n");
            if (externalFolderDisplay != null) {
                // The whole message is shellQuoted as one string: the
                // folder name is user-chosen, so $ / ` / " in it must
                // not expand, and ' in it must not break the quoting.
                s.append("echo ").append(shellQuote(
                    "卸载完成。备份已打包，正在复制到外部存储：" + externalFolderDisplay)).append("\n");
            } else {
                s.append("echo \"卸载完成。备份保存在：$BACKUP_DIR\"\n");
            }
        } else {
            if (externalFolderDisplay != null) {
                s.append("echo ").append(shellQuote(
                    "备份完成。备份已打包，正在复制到外部存储：" + externalFolderDisplay)).append("\n");
            } else {
                s.append("echo \"备份完成。备份保存在：$BACKUP_DIR\"\n");
            }
        }
        s.append("read -r -p \"按回车关闭此会话\" || true\n");
        return s.toString();
    }

    /** Shell var holding one agent archive's size, e.g. SIZE_CODEX. */
    private static String sizeVarFor(String agentId) {
        return "SIZE_" + agentId.toUpperCase(Locale.US).replaceAll("[^A-Z0-9_]", "_");
    }

    /** Shell var holding one agent archive's SHA-256, e.g. SHA_CODEX. */
    private static String shaVarFor(String agentId) {
        return "SHA_" + agentId.toUpperCase(Locale.US).replaceAll("[^A-Z0-9_]", "_");
    }

    /** Now as ISO-8601 with a colon in the offset, e.g.
     * 2026-11-26T06:00:00+08:00. Built from pattern "Z" (+0800) with a
     * colon inserted, instead of "XXX", so it works on every API level. */
    private static String isoNow() {
        String basic = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US)
            .format(new Date());
        return basic.substring(0, basic.length() - 2) + ":" + basic.substring(basic.length() - 2);
    }

    /** "root/.codex/..." — strip a leading '/', drop empties and anything
     * with control characters (never expected from the app tables). */
    private static String normalizeRelPath(String path) {
        if (path == null) return "";
        String p = path.trim();
        while (p.startsWith("/")) p = p.substring(1);
        if (p.isEmpty()) return "";
        for (int i = 0; i < p.length(); i++) {
            if (p.charAt(i) < 0x20) return "";
        }
        return p;
    }

    /** Drop exact duplicates and any path already covered by a kept
     * ancestor ("root/.codex" covers "root/.codex/config.toml"), so the
     * staging symlinks never nest (see appendStageLinks). Sorted
     * shallow-first so ancestors are kept before their children. */
    private static List<String> dedupPaths(List<String> paths) {
        List<String> cleaned = new ArrayList<>();
        if (paths != null) {
            for (String raw : paths) {
                String p = normalizeRelPath(raw);
                if (!p.isEmpty() && !cleaned.contains(p)) cleaned.add(p);
            }
        }
        Collections.sort(cleaned, (a, b) -> {
            int depthA = a.split("/").length;
            int depthB = b.split("/").length;
            if (depthA != depthB) return Integer.compare(depthA, depthB);
            return Integer.compare(a.length(), b.length());
        });
        List<String> kept = new ArrayList<>();
        for (String p : cleaned) {
            boolean covered = false;
            for (String k : kept) {
                if (p.equals(k) || p.startsWith(k + "/")) {
                    covered = true;
                    break;
                }
            }
            if (!covered) kept.add(p);
        }
        return kept;
    }

    /** Symlink each existing path into the staging dir so tar -h packs
     * the real contents under data/ or program/. Only paths that exist
     * are staged ([ -e ] also skips broken symlinks). dedupPaths
     * guarantees no staged path is an ancestor of another, so mkdir -p
     * never writes through a staged symlink into the real rootfs. */
    private static void appendStageLinks(StringBuilder s, String agentId,
            String area, List<String> paths) {
        for (String path : paths) {
            int slash = path.lastIndexOf('/');
            String parent = slash < 0 ? "." : path.substring(0, slash);
            String stageArea = agentId + "/" + area;
            s.append("if [ -e \"$ROOTFS\"/").append(shellQuote(path)).append(" ]; then\n");
            s.append("  mkdir -p \"$STAGE\"/")
                .append(shellQuote(stageArea + "/" + parent)).append("\n");
            s.append("  ln -s \"$ROOTFS\"/").append(shellQuote(path)).append(" \"$STAGE\"/")
                .append(shellQuote(stageArea + "/" + path)).append("\n");
            s.append("fi\n");
        }
    }
}
