package com.termux.app.activities;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import android.util.Base64;
import android.util.Log;

import com.termux.shared.settings.preferences.SharedPreferenceUtils;
import com.termux.shared.termux.TermuxConstants;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Unified model config (模型配置): one provider entry carries a name, a
 * type (OpenAI-compatible / Anthropic-compatible / Gemini), a base URL, an
 * API key, a list of models with one primary model, and the targets
 * (agents + distros) it applies to.
 *
 * <p>Entries live in a private SharedPreferences file; the API key is
 * stored encrypted with Android Keystore + AES-256-GCM (hand-written
 * helper, zero new dependencies — EncryptedSharedPreferences is NOT used:
 * security-crypto 1.1.0-alpha07 deprecated all of its APIs). The
 * per-entry provider id ("termux-&lt;slug&gt;") is fixed at creation so
 * renaming an entry never orphans the configs already written to a
 * container.
 *
 * <p>Apply records (which entry was applied to which container + agents)
 * live in ~/.termux/model-config.json, the same idiom as
 * AiAgentUtils' aiagents-updates.json (AtomicFile).
 */
public final class ModelConfigStore {

    private static final String LOG_TAG = "ModelConfigStore";

    public static final int TYPE_OPENAI = 0;
    public static final int TYPE_ANTHROPIC = 1;
    public static final int TYPE_GEMINI = 2;

    /** SharedPreferences file holding the entries (keys encrypted). */
    private static final String PREFS_NAME = "model_config";
    private static final String PREF_ENTRIES = "entries";

    /** AndroidKeyStore alias for the AES-GCM key. */
    private static final String KEYSTORE_ALIAS = "termux_model_config_key";
    private static final String KEYSTORE_PROVIDER = "AndroidKeyStore";
    private static final String CIPHER_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    /** Apply record file (Termux home, same place as aiagents-updates.json). */
    private static final String APPLY_RECORD_FILE_NAME = "model-config.json";

    /** Timeouts for the /models fetch (same class as the update check). */
    private static final int FETCH_CONNECT_TIMEOUT_MS = 10000;
    private static final int FETCH_READ_TIMEOUT_MS = 20000;

    private ModelConfigStore() {
        // No instances.
    }

    // ------------------------------------------------------------------
    // Entry
    // ------------------------------------------------------------------

    /** One provider entry. The apiKey is held encrypted (see getApiKey);
     * everything else is plain metadata. id / providerId / createdAt are
     * fixed at creation; the rest is edited in place by the activity and
     * persisted with saveEntries. */
    public static final class Entry {
        public final String id;
        public final String providerId;
        public final long createdAt;
        public String name;
        public int type;
        public String baseUrl;
        /** API path override: "" = auto, "/" = root, else a path like
         * "/v1". When set it replaces the path of baseUrl wholesale and
         * the per-agent /v1 rules no longer apply. */
        public String apiPath = "";
        /** Base64(IV + ciphertext + GCM tag), or "" when no key is stored. */
        public String encryptedKey;
        public List<String> models;
        /** Must be one of models (or "" when models is empty). */
        public String primaryModel;
        public boolean setDefault;
        public List<String> agentIds;
        public List<String> distroNames;
        public boolean allDistros;
        public long lastAppliedAt;

        public Entry(String id, String name, int type, String baseUrl,
                     String providerId, String encryptedKey, List<String> models,
                     String primaryModel, boolean setDefault, List<String> agentIds,
                     List<String> distroNames, boolean allDistros,
                     long createdAt, long lastAppliedAt) {
            this.id = id;
            this.name = name;
            this.type = type;
            this.baseUrl = baseUrl == null ? "" : baseUrl;
            this.providerId = providerId;
            this.encryptedKey = encryptedKey == null ? "" : encryptedKey;
            this.models = models == null ? new ArrayList<>() : models;
            this.primaryModel = primaryModel == null ? "" : primaryModel;
            this.setDefault = setDefault;
            this.agentIds = agentIds == null ? new ArrayList<>() : agentIds;
            this.distroNames = distroNames == null ? new ArrayList<>() : distroNames;
            this.allDistros = allDistros;
            this.createdAt = createdAt;
            this.lastAppliedAt = lastAppliedAt;
        }

        /** New entry with a fresh id + provider id. The provider id is
         * derived from the name once and never recomputed, so renaming the
         * entry later cannot orphan already-written configs. */
        public static Entry create(String name, int type, List<String> existingProviderIds) {
            long now = System.currentTimeMillis();
            String providerId = newProviderId(name, existingProviderIds);
            return new Entry(UUID.randomUUID().toString(), name, type, "",
                providerId, "", new ArrayList<>(), "", true,
                new ArrayList<>(), new ArrayList<>(), false, now, 0);
        }

        /** Deep copy (used when handing an entry to a background thread). */
        public Entry deepCopy() {
            return fromJson(toJson());
        }

        /** The decrypted API key, or null when there is none or it can no
         * longer be decrypted (e.g. Keystore key lost after a backup
         * restore — the caller shows "re-enter the key", never a crash). */
        public String getApiKey() {
            if (encryptedKey == null || encryptedKey.isEmpty()) return null;
            return decryptKey(encryptedKey);
        }

        /** Encrypt and store the key. Returns false when encryption failed
         * (caller must not save the entry with a plaintext key). */
        public boolean setApiKey(String plain) {
            if (plain == null || plain.isEmpty()) {
                encryptedKey = "";
                return true;
            }
            String encrypted = encryptKey(plain);
            if (encrypted == null) return false;
            encryptedKey = encrypted;
            return true;
        }

        /** Keep primaryModel valid: it must be one of models; when the
         * primary was removed, the first remaining model becomes primary
         * (design §11). */
        public void fixPrimaryModel() {
            if (models.isEmpty()) {
                primaryModel = "";
                return;
            }
            if (primaryModel == null || !models.contains(primaryModel)) {
                primaryModel = models.get(0);
            }
        }

        public JSONObject toJson() {
            JSONObject obj = new JSONObject();
            try {
                obj.put("id", id);
                obj.put("name", name);
                obj.put("type", type);
                obj.put("baseUrl", baseUrl);
                obj.put("apiPath", apiPath);
                obj.put("providerId", providerId);
                obj.put("key", encryptedKey);
                obj.put("models", new JSONArray(models));
                obj.put("primaryModel", primaryModel);
                obj.put("setDefault", setDefault);
                obj.put("agents", new JSONArray(agentIds));
                obj.put("distros", new JSONArray(distroNames));
                obj.put("allDistros", allDistros);
                obj.put("createdAt", createdAt);
                obj.put("lastAppliedAt", lastAppliedAt);
            } catch (JSONException e) {
                Log.w(LOG_TAG, "Failed to serialize entry", e);
            }
            return obj;
        }

        public static Entry fromJson(JSONObject obj) {
            if (obj == null) return null;
            try {
                String id = obj.optString("id", "");
                String providerId = obj.optString("providerId", "");
                if (id.isEmpty() || providerId.isEmpty()) return null;
                List<String> models = stringList(obj.optJSONArray("models"));
                List<String> agentIds = stringList(obj.optJSONArray("agents"));
                List<String> distroNames = stringList(obj.optJSONArray("distros"));
                Entry entry = new Entry(id, obj.optString("name", ""),
                    obj.optInt("type", TYPE_OPENAI), obj.optString("baseUrl", ""),
                    providerId, obj.optString("key", ""), models,
                    obj.optString("primaryModel", ""), obj.optBoolean("setDefault", true),
                    agentIds, distroNames, obj.optBoolean("allDistros", false),
                    obj.optLong("createdAt", 0), obj.optLong("lastAppliedAt", 0));
                entry.apiPath = obj.optString("apiPath", "");
                entry.fixPrimaryModel();
                return entry;
            } catch (Exception e) {
                Log.w(LOG_TAG, "Failed to parse entry", e);
                return null;
            }
        }

        private static List<String> stringList(JSONArray array) {
            List<String> list = new ArrayList<>();
            if (array == null) return list;
            for (int i = 0; i < array.length(); i++) {
                String value = array.optString(i, "");
                if (!value.isEmpty()) list.add(value);
            }
            return list;
        }
    }

    // ------------------------------------------------------------------
    // Provider id
    // ------------------------------------------------------------------

    /** "My DeepSeek" -> "termux-my-deepseek"; pure-CJK names fall back to
     * "termux-provider". Collisions get a -2, -3, ... suffix. */
    public static String slugifyProviderId(String name) {
        if (name == null) name = "";
        String slug = name.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (slug.isEmpty()) slug = "provider";
        return "termux-" + slug;
    }

    public static String newProviderId(String name, List<String> existingProviderIds) {
        String base = slugifyProviderId(name);
        String candidate = base;
        int suffix = 2;
        while (existingProviderIds != null && existingProviderIds.contains(candidate)) {
            candidate = base + "-" + suffix;
            suffix++;
        }
        return candidate;
    }

    // ------------------------------------------------------------------
    // Load / save entries
    // ------------------------------------------------------------------

    public static List<Entry> loadEntries(Context context) {
        List<Entry> entries = new ArrayList<>();
        SharedPreferences prefs =
            SharedPreferenceUtils.getPrivateSharedPreferences(context, PREFS_NAME);
        String json = prefs.getString(PREF_ENTRIES, null);
        if (json == null || json.isEmpty()) return entries;
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                Entry entry = Entry.fromJson(array.optJSONObject(i));
                if (entry != null) entries.add(entry);
            }
        } catch (JSONException e) {
            Log.w(LOG_TAG, "Failed to parse model config entries", e);
        }
        return entries;
    }

    public static void saveEntries(Context context, List<Entry> entries) {
        JSONArray array = new JSONArray();
        for (Entry entry : entries) array.put(entry.toJson());
        SharedPreferenceUtils.getPrivateSharedPreferences(context, PREFS_NAME)
            .edit().putString(PREF_ENTRIES, array.toString()).apply();
    }

    // ------------------------------------------------------------------
    // Keystore AES-256-GCM (hand-written; no EncryptedSharedPreferences)
    // ------------------------------------------------------------------

    /** Base64(IV(12) + ciphertext + GCM tag), or null on failure. */
    public static String encryptKey(String plain) {
        try {
            SecretKey key = getOrCreateKey();
            if (key == null) return null;
            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] iv = cipher.getIV();
            byte[] cipherText =
                cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(cipherText, 0, combined, iv.length, cipherText.length);
            return Base64.encodeToString(combined, Base64.NO_WRAP);
        } catch (Exception e) {
            Log.w(LOG_TAG, "Failed to encrypt API key", e);
            return null;
        }
    }

    /** Plaintext, or null when there is nothing to decrypt or the key is
     * gone (Keystore keys are never backed up, so a restored
     * SharedPreferences file can hold ciphertext no device can open). */
    public static String decryptKey(String encrypted) {
        if (encrypted == null || encrypted.isEmpty()) return null;
        try {
            byte[] combined = Base64.decode(encrypted, Base64.DEFAULT);
            if (combined.length <= GCM_IV_BYTES) return null;
            byte[] iv = Arrays.copyOfRange(combined, 0, GCM_IV_BYTES);
            byte[] cipherText =
                Arrays.copyOfRange(combined, GCM_IV_BYTES, combined.length);
            SecretKey key = getOrCreateKey();
            if (key == null) return null;
            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] plain = cipher.doFinal(cipherText);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.w(LOG_TAG, "Failed to decrypt API key", e);
            return null;
        }
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER);
        keyStore.load(null);
        if (keyStore.containsAlias(KEYSTORE_ALIAS)) {
            Key key = keyStore.getKey(KEYSTORE_ALIAS, null);
            if (key instanceof SecretKey) return (SecretKey) key;
        }
        KeyGenerator keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER);
        keyGenerator.init(new KeyGenParameterSpec.Builder(KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(false)
            .setRandomizedEncryptionRequired(true)
            .build());
        return keyGenerator.generateKey();
    }

    // ------------------------------------------------------------------
    // Base URLs
    // ------------------------------------------------------------------

    /** Official default per type (used when the user leaves base URL empty). */
    public static String defaultBaseUrl(int type) {
        switch (type) {
            case TYPE_ANTHROPIC:
                return "https://api.anthropic.com/v1";
            case TYPE_GEMINI:
                // The list API lives under v1beta, so the default includes it.
                return "https://generativelanguage.googleapis.com/v1beta";
            case TYPE_OPENAI:
            default:
                return "https://api.openai.com/v1";
        }
    }

    /** Normalize a user-typed API path: "" = auto, "/" = root, otherwise
     * a leading-/ path with no trailing slash. */
    public static String normalizeApiPath(String path) {
        if (path == null) return "";
        String p = path.trim();
        if (p.isEmpty()) return "";
        if (!p.startsWith("/")) p = "/" + p;
        while (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    /** True when the entry pins an API path (the auto rules no longer
     * apply: no DeepSeek /v1 strip, no anthropic /v1 strip). */
    public static boolean hasApiPath(Entry entry) {
        return entry != null && !normalizeApiPath(entry.apiPath).isEmpty();
    }

    /** Origin (scheme://host[:port]) of a URL, or the URL itself when it
     * has no path to strip. */
    public static String originOf(String url) {
        if (url == null) return "";
        int scheme = url.indexOf("://");
        int start = scheme >= 0 ? scheme + 3 : 0;
        int slash = url.indexOf('/', start);
        return slash >= 0 ? url.substring(0, slash) : url;
    }

    /** Effective base URL for a (type, baseUrl, apiPath) triple: when a
     * path is pinned it replaces the base URL's path wholesale. */
    public static String effectiveBaseUrl(int type, String baseUrl, String apiPath) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        if (base.isEmpty()) base = defaultBaseUrl(type);
        String path = normalizeApiPath(apiPath);
        if (!path.isEmpty()) {
            return originOf(base) + ("/".equals(path) ? "" : path);
        }
        return base.replaceAll("/+$", "");
    }

    /** Effective base URL: the user's, or the type default; trailing
     * slashes stripped so "/models" appends cleanly. */
    public static String effectiveBaseUrl(Entry entry) {
        return effectiveBaseUrl(entry.type, entry.baseUrl, entry.apiPath);
    }

    // ------------------------------------------------------------------
    // /models fetch + test connection (same call; 0 token, list only)
    // ------------------------------------------------------------------

    public static final class FetchResult {
        public final boolean ok;
        public final List<String> models;
        /** Human-readable failure, null when ok. */
        public final String error;

        FetchResult(boolean ok, List<String> models, String error) {
            this.ok = ok;
            this.models = models;
            this.error = error;
        }
    }

    /** GET {base}/models (Gemini: .../models?key=...). OpenAI-compatible:
     * Bearer auth; Anthropic-compatible: x-api-key + anthropic-version
     * (best effort — custom gateways may not implement the endpoint, so a
     * non-200 is reported as a status, never a death sentence). */
    public static FetchResult fetchModels(int type, String baseUrl, String apiKey) {
        return fetchModels(type, baseUrl, "", apiKey);
    }

    /** Same, with a pinned API path ("" = auto). */
    public static FetchResult fetchModels(int type, String baseUrl, String apiPath, String apiKey) {
        String url = effectiveBaseUrl(type, baseUrl, apiPath) + "/models";
        if (type == TYPE_GEMINI) {
            try {
                url += "?key=" + java.net.URLEncoder.encode(apiKey, "UTF-8");
            } catch (Exception e) {
                url += "?key=" + apiKey;
            }
        }
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(FETCH_CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(FETCH_READ_TIMEOUT_MS);
            connection.setRequestProperty("User-Agent", "termux-app-zh-modelconfig/1.0");
            connection.setRequestProperty("Accept", "application/json");
            if (type == TYPE_OPENAI) {
                connection.setRequestProperty("Authorization", "Bearer " + apiKey);
            } else if (type == TYPE_ANTHROPIC) {
                connection.setRequestProperty("x-api-key", apiKey);
                connection.setRequestProperty("anthropic-version", "2023-06-01");
            }
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                return new FetchResult(false, new ArrayList<>(), "HTTP " + status);
            }
            List<String> ids = parseModelIds(type, readStream(connection.getInputStream()));
            return new FetchResult(true, ids, null);
        } catch (Exception e) {
            Log.w(LOG_TAG, "Fetch failed for " + url, e);
            String message = e.getMessage();
            return new FetchResult(false, new ArrayList<>(),
                message == null || message.isEmpty() ? e.toString() : message);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /** OpenAI/Anthropic: {"data": [{"id": ...}]}. Gemini:
     * {"models": [{"name": "models/gemini-2.0-flash", ...}]} — the
     * "models/" prefix is stripped. */
    private static List<String> parseModelIds(int type, String body) {
        List<String> ids = new ArrayList<>();
        try {
            JSONObject root = new JSONObject(body);
            JSONArray data = root.optJSONArray(type == TYPE_GEMINI ? "models" : "data");
            if (data == null) return ids;
            for (int i = 0; i < data.length(); i++) {
                JSONObject item = data.optJSONObject(i);
                if (item == null) continue;
                String id = item.optString(type == TYPE_GEMINI ? "name" : "id", "");
                if (type == TYPE_GEMINI && id.startsWith("models/")) {
                    id = id.substring("models/".length());
                }
                if (!id.isEmpty() && !ids.contains(id)) ids.add(id);
            }
        } catch (JSONException e) {
            Log.w(LOG_TAG, "Failed to parse /models response", e);
        }
        return ids;
    }

    private static String readStream(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toString(StandardCharsets.UTF_8.name());
    }

    // ------------------------------------------------------------------
    // Apply records (~/.termux/model-config.json)
    // ------------------------------------------------------------------

    /** entryId -> container -> {agents: [...], at: <epoch ms>}. */
    public static Map<String, Map<String, List<String>>> loadApplyRecords() {
        Map<String, Map<String, List<String>>> records = new LinkedHashMap<>();
        File recordFile = new File(TermuxConstants.TERMUX_DATA_HOME_DIR, APPLY_RECORD_FILE_NAME);
        if (!recordFile.isFile()) return records;
        try {
            JSONObject root = new JSONObject(DistroUtils.readFile(recordFile));
            JSONObject applied = root.optJSONObject("applied");
            if (applied == null) return records;
            Iterator<String> entryIds = applied.keys();
            while (entryIds.hasNext()) {
                String entryId = entryIds.next();
                JSONObject containers = applied.optJSONObject(entryId);
                if (containers == null) continue;
                Map<String, List<String>> perContainer = new LinkedHashMap<>();
                Iterator<String> names = containers.keys();
                while (names.hasNext()) {
                    String container = names.next();
                    JSONObject record = containers.optJSONObject(container);
                    if (record == null) continue;
                    List<String> agents = new ArrayList<>();
                    JSONArray agentArray = record.optJSONArray("agents");
                    if (agentArray != null) {
                        for (int i = 0; i < agentArray.length(); i++) {
                            String agent = agentArray.optString(i, "");
                            if (!agent.isEmpty()) agents.add(agent);
                        }
                    }
                    perContainer.put(container, agents);
                }
                records.put(entryId, perContainer);
            }
        } catch (Exception e) {
            Log.w(LOG_TAG, "Failed to read apply records", e);
        }
        return records;
    }

    /** Record that an entry was applied to one container (AtomicFile,
     * same idiom as AiAgentUtils.saveUpdateCache). */
    public static void recordApplied(String entryId, String container,
                                     List<String> agentIds) {
        try {
            Map<String, Map<String, List<String>>> records = loadApplyRecords();
            Map<String, List<String>> perContainer =
                records.get(entryId);
            if (perContainer == null) {
                perContainer = new LinkedHashMap<>();
                records.put(entryId, perContainer);
            }
            perContainer.put(container, new ArrayList<>(agentIds));
            JSONObject root = new JSONObject();
            JSONObject applied = new JSONObject();
            for (Map.Entry<String, Map<String, List<String>>> e : records.entrySet()) {
                JSONObject containers = new JSONObject();
                for (Map.Entry<String, List<String>> c : e.getValue().entrySet()) {
                    JSONObject record = new JSONObject();
                    record.put("agents", new JSONArray(c.getValue()));
                    record.put("at", System.currentTimeMillis());
                    containers.put(c.getKey(), record);
                }
                applied.put(e.getKey(), containers);
            }
            root.put("applied", applied);
            File recordFile = new File(TermuxConstants.TERMUX_DATA_HOME_DIR,
                APPLY_RECORD_FILE_NAME);
            File parent = recordFile.getParentFile();
            if (parent != null && !(parent.isDirectory() || parent.mkdirs())) return;
            AtomicFile atomicFile = new AtomicFile(recordFile);
            FileOutputStream out = atomicFile.startWrite();
            try {
                out.write(root.toString().getBytes(StandardCharsets.UTF_8));
                atomicFile.finishWrite(out);
            } catch (IOException e) {
                atomicFile.failWrite(out);
                throw e;
            }
        } catch (Exception e) {
            Log.w(LOG_TAG, "Failed to save apply records", e);
        }
    }

    /** Drop one entry's record for one container (after clearing its configs). */
    public static void removeAppliedRecord(String entryId, String container) {
        try {
            Map<String, Map<String, List<String>>> records = loadApplyRecords();
            Map<String, List<String>> perContainer = records.get(entryId);
            if (perContainer == null) return;
            if (perContainer.remove(container) == null) return;
            if (perContainer.isEmpty()) records.remove(entryId);
            JSONObject root = new JSONObject();
            JSONObject applied = new JSONObject();
            for (Map.Entry<String, Map<String, List<String>>> e : records.entrySet()) {
                JSONObject containers = new JSONObject();
                for (Map.Entry<String, List<String>> c : e.getValue().entrySet()) {
                    JSONObject record = new JSONObject();
                    record.put("agents", new JSONArray(c.getValue()));
                    record.put("at", System.currentTimeMillis());
                    containers.put(c.getKey(), record);
                }
                applied.put(e.getKey(), containers);
            }
            root.put("applied", applied);
            File recordFile = new File(TermuxConstants.TERMUX_DATA_HOME_DIR,
                APPLY_RECORD_FILE_NAME);
            AtomicFile atomicFile = new AtomicFile(recordFile);
            FileOutputStream out = atomicFile.startWrite();
            try {
                out.write(root.toString().getBytes(StandardCharsets.UTF_8));
                atomicFile.finishWrite(out);
            } catch (IOException e) {
                atomicFile.failWrite(out);
                throw e;
            }
        } catch (Exception e) {
            Log.w(LOG_TAG, "Failed to remove apply record", e);
        }
    }

    /** Containers an entry was ever applied to (for the delete +
     * clear-applied flow). */
    public static List<String> appliedContainers(String entryId) {
        Map<String, List<String>> perContainer = loadApplyRecords().get(entryId);
        if (perContainer == null) return new ArrayList<>();
        return new ArrayList<>(perContainer.keySet());
    }
}
