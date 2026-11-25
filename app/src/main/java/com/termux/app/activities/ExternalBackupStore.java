// External backup storage via the Storage Access Framework (SAF).
//
// Standalone module for the uninstall / selective-backup feature: the user
// picks one folder on shared storage (e.g. a TermuxBackups folder under
// /sdcard/) with the system folder picker, the app keeps a persistable
// read+write grant for it, and whole backup dirs (manifest.json +
// *.tar.gz, exactly the layout DistroBackup writes internally) can be
// copied there, listed from there, copied back, or deleted.
//
// This class is deliberately decoupled: it knows nothing about
// DistroBackup or any other new class, and it never packs or unpacks
// tarballs itself. Per the design, external writes always go via a local
// temp copy first (pack internally, then copy here); the copy methods
// below implement exactly that transfer step.
//
// Implementation note: androidx.documentfile:documentfile is NOT a
// dependency of this app (see app/build.gradle), so DocumentFile is not
// available. Everything here uses android.provider.DocumentsContract +
// ContentResolver directly, which has been available since API 19/21 and
// therefore covers this app's target SDK 28. There was no pre-existing
// SAF usage anywhere in the codebase to reuse.
//
// All public entry points fail soft on revoked permission: they catch
// SecurityException and return false / an empty list / null instead of
// crashing.

package com.termux.app.activities;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Log;

import com.termux.shared.settings.preferences.SharedPreferenceUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public final class ExternalBackupStore {

    private static final String LOG_TAG = "ExternalBackupStore";

    /** Request code for the ACTION_OPEN_DOCUMENT_TREE folder picker. */
    public static final int REQUEST_PICK_EXTERNAL_FOLDER = 1401;

    /** SharedPreferences file (app convention: one named file per feature)
     * holding the backup settings: the external folder Uri and the
     * default backup location. */
    private static final String PREFS_NAME = "distro_backup";

    /** SharedPreferences key holding the persisted folder Uri string. */
    public static final String PREFS_KEY_EXTERNAL_FOLDER_URI = "external_backup_folder_uri";

    /** File every backup dir must contain; also the manifest read by listExternalBackups. */
    private static final String MANIFEST_FILE_NAME = "manifest.json";

    private static final int BUFFER_SIZE = 8192;

    private ExternalBackupStore() {
    }

    /** One backup dir found under the external folder, described by its manifest.json.
     *
     * <p>Fields taken straight from the manifest: {@code container},
     * {@code distroId}, {@code distroDisplay} and {@code createdAt} are null
     * when the manifest omits them; {@code totalSize} is the sum of the
     * {@code size} values of the manifest's items; {@code itemIds} and
     * {@code itemLabels} list the items in manifest order.
     */
    public static final class ExternalBackupInfo {
        /** Uri of the backup dir itself (a document Uri under the picked folder). */
        public final Uri uri;
        public final String container;
        public final String distroId;
        public final String distroDisplay;
        public final String createdAt;
        public final long totalSize;
        public final List<String> itemIds;
        public final List<String> itemLabels;

        public ExternalBackupInfo(Uri uri, String container, String distroId,
                String distroDisplay, String createdAt, long totalSize,
                List<String> itemIds, List<String> itemLabels) {
            this.uri = uri;
            this.container = container;
            this.distroId = distroId;
            this.distroDisplay = distroDisplay;
            this.createdAt = createdAt;
            this.totalSize = totalSize;
            this.itemIds = itemIds != null
                    ? Collections.unmodifiableList(new ArrayList<>(itemIds))
                    : Collections.<String>emptyList();
            this.itemLabels = itemLabels != null
                    ? Collections.unmodifiableList(new ArrayList<>(itemLabels))
                    : Collections.<String>emptyList();
        }
    }

    /** Launches the system folder picker so the user can choose the external
     * backup folder. Call {@link #onFolderPicked} from the calling
     * Activity's onActivityResult with the result.
     */
    public static void pickExternalFolder(Activity activity) {
        if (activity == null) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        // The persistable flag is what makes takePersistableUriPermission()
        // work on the returned Uri; without it the grant would die with the
        // process. Read+write because backups are written as well as read.
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            activity.startActivityForResult(intent, REQUEST_PICK_EXTERNAL_FOLDER);
        } catch (ActivityNotFoundException e) {
            // No system file picker on this device/ROM; nothing we can do.
            Log.e(LOG_TAG, "No activity found for ACTION_OPEN_DOCUMENT_TREE", e);
        }
    }

    /** Handles the folder-picker result. Call from the Activity's
     * onActivityResult. Returns true if this was our request and the pick
     * was handled (persistable permission taken and Uri saved); false if
     * it was not our request, the user cancelled, or the permission could
     * not be taken.
     */
    public static boolean onFolderPicked(Activity activity, int requestCode,
            int resultCode, Intent data) {
        if (activity == null || requestCode != REQUEST_PICK_EXTERNAL_FOLDER) return false;
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return false;
        }
        Uri treeUri = data.getData();
        // Only the read/write bits the system actually granted may be taken.
        int takeFlags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if (takeFlags == 0) {
            Log.w(LOG_TAG, "Folder pick returned no read/write grant flags");
            return false;
        }
        try {
            activity.getContentResolver().takePersistableUriPermission(treeUri, takeFlags);
        } catch (SecurityException e) {
            Log.e(LOG_TAG, "Could not take persistable permission for " + treeUri, e);
            return false;
        }
        // Release the grant for a previously picked folder, if it differs,
        // so stale grants do not accumulate.
        Uri oldUri = getExternalFolderUri(activity);
        if (oldUri != null && !oldUri.equals(treeUri)) {
            try {
                activity.getContentResolver().releasePersistableUriPermission(oldUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (SecurityException e) {
                Log.w(LOG_TAG, "Could not release old external folder grant for " + oldUri, e);
            }
        }
        SharedPreferenceUtils.getPrivateSharedPreferences(activity, PREFS_NAME).edit()
                .putString(PREFS_KEY_EXTERNAL_FOLDER_URI, treeUri.toString()).apply();
        return true;
    }

    /** The persisted external folder Uri, or null if the user has not
     * picked one yet.
     */
    public static Uri getExternalFolderUri(Context context) {
        if (context == null) return null;
        SharedPreferences prefs = SharedPreferenceUtils.getPrivateSharedPreferences(context, PREFS_NAME);
        String uriString = prefs.getString(PREFS_KEY_EXTERNAL_FOLDER_URI, null);
        if (uriString == null || uriString.isEmpty()) return null;
        try {
            return Uri.parse(uriString);
        } catch (RuntimeException e) {
            Log.w(LOG_TAG, "Stored external folder Uri is not parseable: " + uriString, e);
            return null;
        }
    }

    /** True if a folder Uri is persisted AND the app still holds a
     * persistable read+write grant for it. False when no folder was ever
     * picked, or when the user revoked access (e.g. from the system file
     * manager); callers should then prompt to pick the folder again.
     */
    public static boolean hasExternalFolder(Context context) {
        Uri folderUri = getExternalFolderUri(context);
        if (context == null || folderUri == null) return false;
        try {
            List<UriPermission> grants =
                    context.getContentResolver().getPersistedUriPermissions();
            for (UriPermission grant : grants) {
                if (folderUri.equals(grant.getUri())
                        && grant.isReadPermission() && grant.isWritePermission()) {
                    return true;
                }
            }
        } catch (SecurityException e) {
            Log.w(LOG_TAG, "Could not read persisted Uri permissions", e);
        }
        return false;
    }

    /** Lists the backup dirs under the external folder, newest first
     * (by the manifest's createdAt string). Each child dir is described by
     * its manifest.json; dirs with no readable manifest are skipped.
     * Returns an empty list (never null) when no folder is set, the
     * permission is gone, or the folder holds no valid backups.
     */
    public static List<ExternalBackupInfo> listExternalBackups(Context context) {
        List<ExternalBackupInfo> result = new ArrayList<>();
        if (context == null) return result;
        Uri folderUri = getExternalFolderUri(context);
        if (folderUri == null || !hasExternalFolder(context)) return result;
        ContentResolver resolver = context.getContentResolver();
        try {
            String treeId = DocumentsContract.getTreeDocumentId(folderUri);
            Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(folderUri, treeId);
            for (ChildDoc child : queryChildren(resolver, childrenUri)) {
                if (!DocumentsContract.Document.MIME_TYPE_DIR.equals(child.mimeType)) continue;
                Uri backupDirUri =
                        DocumentsContract.buildDocumentUriUsingTree(folderUri, child.documentId);
                ExternalBackupInfo info = readBackupInfo(resolver, folderUri, backupDirUri);
                if (info != null) result.add(info);
            }
        } catch (SecurityException | IllegalArgumentException e) {
            // Permission revoked (or the tree is gone): fail soft, as agreed.
            Log.w(LOG_TAG, "Could not list external backups", e);
            return result;
        }
        Collections.sort(result, new Comparator<ExternalBackupInfo>() {
            @Override
            public int compare(ExternalBackupInfo a, ExternalBackupInfo b) {
                // Newest first; null createdAt sorts last. createdAt is
                // compared as a string, which orders correctly for the
                // ISO-like timestamps the manifest writer produces.
                if (a.createdAt == null && b.createdAt == null) return 0;
                if (a.createdAt == null) return 1;
                if (b.createdAt == null) return -1;
                return b.createdAt.compareTo(a.createdAt);
            }
        });
        return result;
    }

    /** Copies a whole internal backup dir (manifest.json + *.tar.gz) into
     * the external folder under the same dir name. An existing external
     * dir of the same name is replaced. Returns true on success.
     * The caller packs the backup locally first; this only transfers it.
     */
    public static boolean copyBackupToExternal(Context context, File internalBackupDir) {
        if (context == null || internalBackupDir == null || !internalBackupDir.isDirectory()) {
            return false;
        }
        if (!new File(internalBackupDir, MANIFEST_FILE_NAME).isFile()) {
            Log.w(LOG_TAG, "Internal backup dir has no manifest.json: " + internalBackupDir);
            return false;
        }
        Uri folderUri = getExternalFolderUri(context);
        if (folderUri == null || !hasExternalFolder(context)) return false;
        ContentResolver resolver = context.getContentResolver();
        String dirName = internalBackupDir.getName();
        Uri newDirUri = null;
        try {
            Uri treeDocUri = DocumentsContract.buildDocumentUriUsingTree(
                    folderUri, DocumentsContract.getTreeDocumentId(folderUri));
            // Replace an existing same-named backup dir, if any.
            Uri existing = findChildUri(resolver, folderUri,
                    DocumentsContract.getTreeDocumentId(folderUri), dirName);
            if (existing != null && !DocumentsContract.deleteDocument(resolver, existing)) {
                Log.w(LOG_TAG, "Could not remove existing external backup dir: " + dirName);
                return false;
            }
            newDirUri = DocumentsContract.createDocument(resolver, treeDocUri,
                    DocumentsContract.Document.MIME_TYPE_DIR, dirName);
            if (newDirUri == null) {
                Log.w(LOG_TAG, "Could not create external backup dir: " + dirName);
                return false;
            }
            File[] files = internalBackupDir.listFiles();
            if (files == null) {
                deleteExternalDirQuietly(resolver, newDirUri);
                return false;
            }
            for (File file : files) {
                if (!file.isFile()) continue; // backup dirs are flat; skip subdirs
                Uri fileUri = DocumentsContract.createDocument(
                        resolver, newDirUri, mimeTypeFor(file.getName()), file.getName());
                if (fileUri == null) {
                    Log.w(LOG_TAG, "Could not create external file: " + file.getName());
                    deleteExternalDirQuietly(resolver, newDirUri);
                    return false;
                }
                if (!copyFileToUri(resolver, file, fileUri)) {
                    deleteExternalDirQuietly(resolver, newDirUri);
                    return false;
                }
            }
            // The manifest must have landed, or the copy is not a backup.
            if (findChildUri(resolver, folderUri,
                    DocumentsContract.getDocumentId(newDirUri), MANIFEST_FILE_NAME) == null) {
                deleteExternalDirQuietly(resolver, newDirUri);
                return false;
            }
            return true;
        } catch (SecurityException | IllegalArgumentException e) {
            Log.w(LOG_TAG, "Could not copy backup to external folder: " + internalBackupDir, e);
            if (newDirUri != null) deleteExternalDirQuietly(resolver, newDirUri);
            return false;
        }
    }

    /** Copies an external backup dir into destParentDir (a local
     * temp/staging dir) and returns the local dir, or null on failure.
     * The caller unpacks from the returned local dir; nothing is read
     * directly out of the SAF tree. A partially copied dir is deleted.
     */
    public static File copyBackupFromExternal(Context context, Uri backupDirUri,
            File destParentDir) {
        if (context == null || backupDirUri == null || destParentDir == null) return null;
        Uri folderUri = getExternalFolderUri(context);
        if (folderUri == null || !hasExternalFolder(context)) return null;
        ContentResolver resolver = context.getContentResolver();
        File destDir = null;
        try {
            String docId = DocumentsContract.getDocumentId(backupDirUri);
            Uri childrenUri =
                    DocumentsContract.buildChildDocumentsUriUsingTree(folderUri, docId);
            List<ChildDoc> children = queryChildren(resolver, childrenUri);
            boolean hasManifest = false;
            for (ChildDoc child : children) {
                if (MANIFEST_FILE_NAME.equals(child.displayName)) {
                    hasManifest = true;
                    break;
                }
            }
            if (!hasManifest) {
                Log.w(LOG_TAG, "External backup dir has no manifest.json: " + backupDirUri);
                return null;
            }
            String dirName = queryDisplayName(resolver, backupDirUri);
            if (dirName == null || dirName.isEmpty()) {
                // Fall back to the last segment of the document id.
                int slash = docId.lastIndexOf('/');
                int colon = docId.lastIndexOf(':');
                int cut = Math.max(slash, colon);
                dirName = cut >= 0 && cut + 1 < docId.length()
                        ? docId.substring(cut + 1) : "backup";
            }
            if (!destParentDir.isDirectory() && !destParentDir.mkdirs()) return null;
            destDir = new File(destParentDir, dirName);
            if (destDir.exists()) deleteRecursively(destDir);
            if (!destDir.mkdirs()) return null;
            for (ChildDoc child : children) {
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(child.mimeType)) continue;
                if (child.displayName == null || child.displayName.isEmpty()) continue;
                Uri childUri =
                        DocumentsContract.buildDocumentUriUsingTree(folderUri, child.documentId);
                File outFile = new File(destDir, child.displayName);
                if (!copyUriToFile(resolver, childUri, outFile)) {
                    deleteRecursively(destDir);
                    return null;
                }
            }
            return destDir;
        } catch (SecurityException | IllegalArgumentException e) {
            Log.w(LOG_TAG, "Could not copy backup from external folder: " + backupDirUri, e);
            if (destDir != null) deleteRecursively(destDir);
            return null;
        }
    }

    /** Deletes a whole external backup dir (manifest.json + all archives).
     * Returns true on success. False when there is no folder/permission
     * or the delete failed.
     */
    public static boolean deleteExternalBackup(Context context, Uri backupDirUri) {
        if (context == null || backupDirUri == null) return false;
        if (!hasExternalFolder(context)) return false;
        try {
            // For a directory document this deletes recursively.
            return DocumentsContract.deleteDocument(
                    context.getContentResolver(), backupDirUri);
        } catch (SecurityException | IllegalArgumentException e) {
            Log.w(LOG_TAG, "Could not delete external backup: " + backupDirUri, e);
            return false;
        }
    }

    // ---- private helpers ----

    /** One row of a DocumentsProvider children query. */
    private static final class ChildDoc {
        final String documentId;
        final String displayName;
        final String mimeType;

        ChildDoc(String documentId, String displayName, String mimeType) {
            this.documentId = documentId;
            this.displayName = displayName;
            this.mimeType = mimeType;
        }
    }

    /** Reads the children of a tree document. Never returns null; returns
     * an empty list when the query fails (e.g. permission revoked).
     */
    private static List<ChildDoc> queryChildren(ContentResolver resolver, Uri childrenUri) {
        List<ChildDoc> result = new ArrayList<>();
        String[] projection = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
        };
        Cursor cursor = null;
        try {
            cursor = resolver.query(childrenUri, projection, null, null, null);
            if (cursor == null) return result;
            int idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
            while (cursor.moveToNext()) {
                String id = idCol >= 0 ? cursor.getString(idCol) : null;
                if (id == null) continue;
                String name = nameCol >= 0 ? cursor.getString(nameCol) : null;
                String mime = mimeCol >= 0 ? cursor.getString(mimeCol) : null;
                result.add(new ChildDoc(id, name, mime));
            }
        } catch (SecurityException | IllegalArgumentException e) {
            Log.w(LOG_TAG, "Could not query children of " + childrenUri, e);
            return new ArrayList<ChildDoc>();
        } finally {
            if (cursor != null) cursor.close();
        }
        return result;
    }

    /** Document Uri of the child named {@code displayName} directly under
     * the tree document {@code parentDocumentId}, or null if absent.
     */
    private static Uri findChildUri(ContentResolver resolver, Uri treeUri,
            String parentDocumentId, String displayName) {
        Uri childrenUri =
                DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId);
        for (ChildDoc child : queryChildren(resolver, childrenUri)) {
            if (displayName.equals(child.displayName)) {
                return DocumentsContract.buildDocumentUriUsingTree(treeUri, child.documentId);
            }
        }
        return null;
    }

    /** DISPLAY_NAME column of a single document Uri, or null. */
    private static String queryDisplayName(ContentResolver resolver, Uri docUri) {
        String[] projection = {DocumentsContract.Document.COLUMN_DISPLAY_NAME};
        Cursor cursor = null;
        try {
            cursor = resolver.query(docUri, projection, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int col = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                if (col >= 0) return cursor.getString(col);
            }
        } catch (SecurityException | IllegalArgumentException e) {
            Log.w(LOG_TAG, "Could not query display name of " + docUri, e);
        } finally {
            if (cursor != null) cursor.close();
        }
        return null;
    }

    /** Parses one backup dir's manifest.json into an ExternalBackupInfo,
     * or null when the dir has no readable/parseable manifest.
     */
    private static ExternalBackupInfo readBackupInfo(ContentResolver resolver,
            Uri treeUri, Uri backupDirUri) {
        try {
            String docId = DocumentsContract.getDocumentId(backupDirUri);
            Uri manifestUri = findChildUri(resolver, treeUri, docId, MANIFEST_FILE_NAME);
            if (manifestUri == null) return null;
            String jsonText = readUriAsString(resolver, manifestUri);
            if (jsonText == null) return null;
            JSONObject manifest = new JSONObject(jsonText);
            JSONArray items = manifest.optJSONArray("items");
            List<String> itemIds = new ArrayList<>();
            List<String> itemLabels = new ArrayList<>();
            long totalSize = 0;
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.optJSONObject(i);
                    if (item == null) continue;
                    itemIds.add(item.optString("id", ""));
                    itemLabels.add(item.optString("label", ""));
                    totalSize += item.optLong("size", 0);
                }
            }
            return new ExternalBackupInfo(backupDirUri,
                    manifest.optString("container", null),
                    manifest.optString("distroId", null),
                    manifest.optString("distroDisplay", null),
                    manifest.optString("createdAt", null),
                    totalSize, itemIds, itemLabels);
        } catch (JSONException | SecurityException | IllegalArgumentException e) {
            // Unreadable or malformed manifest: not a usable backup, skip it.
            Log.w(LOG_TAG, "Skipping external backup dir with unreadable manifest: "
                    + backupDirUri, e);
            return null;
        }
    }

    /** Reads a whole document Uri as a UTF-8 string, or null on failure. */
    private static String readUriAsString(ContentResolver resolver, Uri uri) {
        InputStream in = null;
        try {
            in = resolver.openInputStream(uri);
            if (in == null) return null;
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException | SecurityException e) {
            Log.w(LOG_TAG, "Could not read " + uri, e);
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // Best effort only.
                }
            }
        }
    }

    /** Streams a local file into a (freshly created) document Uri. */
    private static boolean copyFileToUri(ContentResolver resolver, File file, Uri uri) {
        try (InputStream in = new FileInputStream(file);
             OutputStream out = resolver.openOutputStream(uri)) {
            if (out == null) return false;
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            out.flush();
            return true;
        } catch (IOException | SecurityException e) {
            Log.w(LOG_TAG, "Could not copy " + file + " to " + uri, e);
            return false;
        }
    }

    /** Streams a document Uri into a local file (created/truncated). */
    private static boolean copyUriToFile(ContentResolver resolver, Uri uri, File outFile) {
        try (InputStream in = resolver.openInputStream(uri);
             OutputStream out = new FileOutputStream(outFile)) {
            if (in == null) return false;
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            out.flush();
            return true;
        } catch (IOException | SecurityException e) {
            Log.w(LOG_TAG, "Could not copy " + uri + " to " + outFile, e);
            return false;
        }
    }

    /** MIME type used when creating a file in the SAF tree. */
    private static String mimeTypeFor(String fileName) {
        if (MANIFEST_FILE_NAME.equals(fileName)) return "application/json";
        if (fileName.endsWith(".tar.gz")) return "application/gzip";
        if (fileName.endsWith(".gz")) return "application/gzip";
        if (fileName.endsWith(".json")) return "application/json";
        return "application/octet-stream";
    }

    /** Best-effort delete of an external dir created by a failed copy. */
    private static void deleteExternalDirQuietly(ContentResolver resolver, Uri dirUri) {
        try {
            DocumentsContract.deleteDocument(resolver, dirUri);
        } catch (SecurityException | IllegalArgumentException e) {
            Log.w(LOG_TAG, "Could not clean up partial external dir: " + dirUri, e);
        }
    }

    /** Recursive delete of a local file/dir. Returns true if gone. */
    private static boolean deleteRecursively(File file) {
        if (file == null || !file.exists()) return true;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        return file.delete();
    }
}
