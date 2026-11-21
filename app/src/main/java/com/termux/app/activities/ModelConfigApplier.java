package com.termux.app.activities;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Writes one model-config entry into the containers it targets.
 *
 * <p>Per-agent mapping (design §3 + §11 amendments). Slim scope: only the
 * agents WITHOUT a web UI of their own, plus Pi (its /login cannot add a
 * custom provider). dsh, OpenCode and Hermes are NOT here — configure them
 * in their web UIs instead.
 *
 * <ul>
 * <li>Codex: ~/.codex/config.toml — managed marker section; top-level
 * {@code model} = primary, {@code model_provider} = termux-&lt;slug&gt;;
 * {@code [model_providers.termux-&lt;slug&gt;]} with name, base_url,
 * {@code env_key = "TERMUX_MODEL_API_KEY"} (Codex has no api_key field)
 * and {@code wire_api = "responses"}. DeepSeek base is
 * https://api.deepseek.com with NO /v1 (Codex appends /responses).
 * <li>Pi: ~/.pi/agent/models.json — providers.termux-&lt;slug&gt;
 * {baseUrl, api, apiKey, models: [{id}]} — each model is an OBJECT, plain
 * strings are rejected by Pi's schema ("models.0: must be object") — +
 * settings.json defaultProvider / defaultModel = primary. Anthropic
 * baseUrl has no trailing /v1 (the SDK appends it).
 * <li>Aider: ~/.aider.conf.yml — model: openai/&lt;primary&gt; (or
 * gemini/&lt;primary&gt;); openai-api-base + openai-api-key for OpenAI
 * type; Gemini key via env only.
 * <li>Claude Code: ~/.claude/settings.json — env {ANTHROPIC_BASE_URL,
 * ANTHROPIC_AUTH_TOKEN, ANTHROPIC_API_KEY} (both token names: Bearer
 * gateways read the former, x-api-key gateways the latter) + ANTHROPIC_MODEL
 * and ANTHROPIC_DEFAULT_*_MODEL when default; model = primary,
 * availableModels = full list. Never touches ~/.claude.json.
 * <li>agy: ~/.gemini/antigravity-cli/settings.json — modelProvider
 * "gemini", model = primary; key only via env GEMINI_API_KEY. Custom
 * OpenAI-compatible providers are NOT supported by agy (Gemini only).
 * </ul>
 *
 * <p>Rules (design §6.2): backup first (.termux-bak, written once, never
 * overwritten); upsert only, never wipe a whole file; model-env.sh +
 * .bashrc marker so interactive sessions see the key; remove restores the
 * default-model value from .termux-bak when present, else deletes the
 * field. YAML files are re-read and re-dumped by SnakeYAML, so comments
 * are lost — the backup holds the original.
 *
 * <p>All methods that touch a rootfs must be called on a background thread.
 */
public final class ModelConfigApplier {

    private static final String LOG_TAG = "ModelConfigApplier";

    /** Backup suffix (design §6.2 step 1: write once, never overwrite). */
    public static final String BACKUP_SUFFIX = ".termux-bak";

    /** Env vars the agents read at launch. */
    public static final String ENV_OPENAI_KEY = "TERMUX_MODEL_API_KEY";
    public static final String ENV_GEMINI_KEY = "GEMINI_API_KEY";

    /** Root-home-relative path of the generated env file. */
    private static final String MODEL_ENV_DIR_NAME = ".termux-aiagents";
    private static final String MODEL_ENV_FILE_NAME = "model-env.sh";

    private static final String BASHRC_MARKER_BEGIN = "# BEGIN TERMUX_MODEL_ENV";
    private static final String BASHRC_MARKER_END = "# END TERMUX_MODEL_ENV";
    private static final String BASHRC_MANAGED_COMMENT = "# Managed by Termux 模型配置";
    private static final String BASHRC_SOURCE_LINE =
        "[ -f \"$HOME/.termux-aiagents/model-env.sh\" ] && . \"$HOME/.termux-aiagents/model-env.sh\"";

    /** Codex TOML managed section. One block per provider id — two
     * entries applied to the same container must coexist, each with its
     * own provider table; only the last-applied entry's block carries the
     * top-level model / model_provider lines (there can be only one of
     * each in a TOML document). */
    private static final String CODEX_MARKER_BEGIN_PREFIX = "# BEGIN TERMUX-MODEL-CONFIG (";
    private static final String CODEX_MARKER_END_PREFIX = "# END TERMUX-MODEL-CONFIG (";
    private static final String CODEX_MANAGED_COMMENT =
        "# Managed by Termux 模型配置, do not edit";

    private static String codexBlockBegin(String providerId) {
        return CODEX_MARKER_BEGIN_PREFIX + providerId + ")";
    }

    private static String codexBlockEnd(String providerId) {
        return CODEX_MARKER_END_PREFIX + providerId + ")";
    }

    private ModelConfigApplier() {
        // No instances.
    }

    // ------------------------------------------------------------------
    // Result
    // ------------------------------------------------------------------

    public static final class ApplyResult {
        public int appliedDistros;
        public int appliedAgents;
        /** "container/agent: reason" lines for the status text. */
        public final List<String> errors = new ArrayList<>();
        /** "container/agent" pairs skipped because the agent is not installed there. */
        public final List<String> skipped = new ArrayList<>();
    }

    // ------------------------------------------------------------------
    // Capability matrix (design §3; hardcoded, not runtime-fetched)
    // ------------------------------------------------------------------

    /** Whether the agent can take a provider of this type in v1. Slim scope:
     * only Pi, Codex, Aider, Claude and agy are offered at all (dsh,
     * OpenCode and Hermes are configured in their web UIs). Aider
     * Anthropic is NOT in v1. */
    public static boolean supportsType(String agentId, int type) {
        if (agentId == null) return false;
        switch (agentId) {
            case "codex":
                return type == ModelConfigStore.TYPE_OPENAI;
            case "pi":
                return true;
            case "agy":
                return type == ModelConfigStore.TYPE_GEMINI;
            case "aider":
                return type != ModelConfigStore.TYPE_ANTHROPIC;
            case "claude":
                return type == ModelConfigStore.TYPE_ANTHROPIC;
            default:
                return false;
        }
    }

    /** Pi "api" value per provider type. */
    private static String piApiForType(int type) {
        switch (type) {
            case ModelConfigStore.TYPE_ANTHROPIC:
                return "anthropic-messages";
            case ModelConfigStore.TYPE_GEMINI:
                return "google-generative-ai";
            case ModelConfigStore.TYPE_OPENAI:
            default:
                return "openai-completions";
        }
    }

    /** Strip a trailing "/v1" (and trailing slashes) from a base URL. */
    private static String stripTrailingV1(String url) {
        if (url == null) return null;
        String stripped = url;
        while (stripped.endsWith("/")) {
            stripped = stripped.substring(0, stripped.length() - 1);
        }
        if (stripped.endsWith("/v1")) {
            stripped = stripped.substring(0, stripped.length() - 3);
        }
        return stripped;
    }

    /** Base URL for Pi: the anthropic-messages SDK appends /v1 itself,
     * so a user-typed .../v1 would double up (/v1/v1/messages 404s).
     * openai-completions and google-generative-ai keep the URL verbatim. */
    private static String piBaseUrl(ModelConfigStore.Entry entry) {
        String url = ModelConfigStore.effectiveBaseUrl(entry);
        if (entry.type == ModelConfigStore.TYPE_ANTHROPIC) {
            return stripTrailingV1(url);
        }
        return url;
    }

    /** Base URL for Codex: Codex appends /responses to base_url, and
     * DeepSeek's documented Codex base is https://api.deepseek.com with NO
     * /v1 (else /v1/responses 404s). Other gateways keep their URL verbatim
     * (OpenRouter/LiteLLM use /v1 + /v1/responses). */
    private static String codexBaseUrl(ModelConfigStore.Entry entry) {
        String url = ModelConfigStore.effectiveBaseUrl(entry);
        if (url != null && url.contains("api.deepseek.com")) {
            return stripTrailingV1(url);
        }
        return url;
    }

    /** Pi models.json entries: a list of {"id": ...} objects. Plain strings
     * are rejected by Pi's schema ("models.0: must be object"). Only "id"
     * is required. */
    private static JSONArray piModelJsonArray(ModelConfigStore.Entry entry) {
        JSONArray array = new JSONArray();
        try {
            for (String model : entry.models) {
                JSONObject obj = new JSONObject();
                obj.put("id", model);
                array.put(obj);
            }
        } catch (Exception e) {
            Log.w(LOG_TAG, "Failed to build Pi models array", e);
        }
        return array;
    }

    /** Env vars an agent reads at launch (design §4.2). Empty when the
     * agent takes its key from its own config file. */
    private static List<String> envVarNamesForAgent(String agentId, int type) {
        if ("codex".equals(agentId)) {
            return Collections.singletonList(ENV_OPENAI_KEY);
        }
        if ("agy".equals(agentId)) {
            return Collections.singletonList(ENV_GEMINI_KEY);
        }
        if ("aider".equals(agentId) && type == ModelConfigStore.TYPE_GEMINI) {
            return Collections.singletonList(ENV_GEMINI_KEY);
        }
        return Collections.emptyList();
    }

    /** Env vars a quick terminal launch must prefix (design §4.2):
     * Codex, agy and aider/Gemini. A quick launch is not a login shell,
     * so .bashrc never runs; the other agents read their key from their
     * own config file. Aider only needs the prefix for Gemini
     * entries (GEMINI_API_KEY): an OpenAI aider entry carries its key in
     * ~/.aider.conf.yml, and model-env.sh only holds GEMINI_API_KEY when
     * a Gemini aider entry was applied, so envArgsForAgent adds no arg
     * otherwise. */
    private static List<String> launchEnvVarNamesForAgent(String agentId) {
        if ("codex".equals(agentId)) {
            return Collections.singletonList(ENV_OPENAI_KEY);
        }
        if ("agy".equals(agentId)) {
            return Collections.singletonList(ENV_GEMINI_KEY);
        }
        if ("aider".equals(agentId)) {
            return Collections.singletonList(ENV_GEMINI_KEY);
        }
        return Collections.emptyList();
    }

    // ------------------------------------------------------------------
    // Apply / remove / probe
    // ------------------------------------------------------------------

    /** Apply one entry to the given containers. The entry must already be
     * saved in the store (rebuildModelEnvFile re-reads it from there).
     * Call on a background thread. */
    public static ApplyResult applyEntry(Context context, ModelConfigStore.Entry entry,
                                         String apiKey, List<String> containers) {
        ApplyResult result = new ApplyResult();
        if (entry == null || apiKey == null || apiKey.isEmpty()) {
            result.errors.add("no key");
            return result;
        }
        for (String container : containers) {
            File rootfs = DistroUtils.containerRootfs(container);
            if (rootfs == null) {
                result.errors.add(container + ": no rootfs");
                continue;
            }
            List<String> appliedAgents = new ArrayList<>();
            for (String agentId : entry.agentIds) {
                if (!supportsType(agentId, entry.type)) continue;
                if (!isAgentInstalled(rootfs, agentId)) {
                    result.skipped.add(container + "/" + agentId);
                    continue;
                }
                if (applyToAgent(rootfs, entry, apiKey, agentId)) {
                    appliedAgents.add(agentId);
                } else {
                    result.errors.add(container + "/" + agentId);
                }
            }
            if (!appliedAgents.isEmpty()) {
                // Record FIRST: the env rebuild below re-reads the records,
                // so the newly applied entry's key must already be in them
                // (and, on remove, already out of them).
                ModelConfigStore.recordApplied(entry.id, container, appliedAgents);
                rebuildModelEnvFile(context, rootfs, container);
                result.appliedDistros++;
                result.appliedAgents += appliedAgents.size();
            }
        }
        return result;
    }

    /** Remove one entry's configs from one container (best effort per
     * agent) and rebuild the env file / marker / records. Call on a
     * background thread. */
    public static void removeEntryFromContainer(Context context,
                                                ModelConfigStore.Entry entry,
                                                String container) {
        if (entry == null || container == null) return;
        File rootfs = DistroUtils.containerRootfs(container);
        if (rootfs == null) {
            ModelConfigStore.removeAppliedRecord(entry.id, container);
            return;
        }
        for (String agentId : entry.agentIds) {
            if (!supportsType(agentId, entry.type)) continue;
            removeFromAgent(rootfs, entry, agentId);
        }
        // Drop the record BEFORE rebuilding: the rebuild re-reads the
        // records, and the removed entry's key must not survive in the
        // env file.
        ModelConfigStore.removeAppliedRecord(entry.id, container);
        boolean stillApplied = anyOtherEntryAppliedToContainer(context, entry.id, container);
        rebuildModelEnvFile(context, rootfs, container);
        if (!stillApplied) {
            removeBashrcMarker(rootfs);
        }
    }

    /** True when any target agent of the entry has the provider present in
     * this container (best-effort probe for the list status). */
    public static boolean isEntryAppliedToContainer(File rootfs, ModelConfigStore.Entry entry) {
        return !probeAppliedAgents(rootfs, entry).isEmpty();
    }

    /** Target agent ids that probe as applied in this container. */
    public static List<String> probeAppliedAgents(File rootfs, ModelConfigStore.Entry entry) {
        List<String> applied = new ArrayList<>();
        if (rootfs == null || entry == null) return applied;
        for (String agentId : entry.agentIds) {
            if (supportsType(agentId, entry.type) && isApplied(rootfs, entry, agentId)) {
                applied.add(agentId);
            }
        }
        return applied;
    }

    /** Entries recorded as applied to this container whose provider is
     * nowhere to be found in it any more — the reinstall signature
     * (design §11: prompt to re-apply, never auto). Only entries with
     * zero probed agents count: a partly-applied entry (the user removed
     * one agent's config by hand) is left to the list's "未应用" status. */
    public static List<ModelConfigStore.Entry> entriesNeedingReapply(Context context,
                                                                     File rootfs,
                                                                     String container) {
        List<ModelConfigStore.Entry> result = new ArrayList<>();
        if (rootfs == null || container == null) return result;
        Map<String, Map<String, List<String>>> records = ModelConfigStore.loadApplyRecords();
        for (ModelConfigStore.Entry entry : ModelConfigStore.loadEntries(context)) {
            Map<String, List<String>> perContainer = records.get(entry.id);
            if (perContainer == null) continue;
            List<String> agents = perContainer.get(container);
            if (agents == null || agents.isEmpty()) continue;
            if (probeAppliedAgents(rootfs, entry).isEmpty()) result.add(entry);
        }
        return result;
    }

    private static boolean anyOtherEntryAppliedToContainer(Context context, String entryId,
                                                           String container) {
        Map<String, Map<String, List<String>>> records =
            ModelConfigStore.loadApplyRecords();
        for (Map.Entry<String, Map<String, List<String>>> e : records.entrySet()) {
            if (e.getKey().equals(entryId)) continue;
            List<String> agents = e.getValue().get(container);
            if (agents != null && !agents.isEmpty()) return true;
        }
        return false;
    }

    /** Whether the agent binary exists in this rootfs (same binPaths as
     * AiAgentUtils detection). */
    public static boolean isAgentInstalled(File rootfs, String agentId) {
        if (rootfs == null) return false;
        AiAgentUtils.AgentDef def = AiAgentUtils.findDef(agentId);
        if (def == null || def.binPaths == null) return false;
        for (String binPath : def.binPaths) {
            if (new File(rootfs, binPath).isFile()) return true;
        }
        return false;
    }

    private static boolean applyToAgent(File rootfs, ModelConfigStore.Entry entry,
                                        String apiKey, String agentId) {
        switch (agentId) {
            case "codex": return applyCodex(rootfs, entry, apiKey);
            case "pi": return applyPi(rootfs, entry, apiKey);
            case "aider": return applyAider(rootfs, entry, apiKey);
            case "claude": return applyClaude(rootfs, entry, apiKey);
            case "agy": return applyAgy(rootfs, entry, apiKey);
            default: return false;
        }
    }

    private static void removeFromAgent(File rootfs, ModelConfigStore.Entry entry,
                                        String agentId) {
        switch (agentId) {
            case "codex": removeCodex(rootfs, entry); break;
            case "pi": removePi(rootfs, entry); break;
            case "aider": removeAider(rootfs, entry); break;
            case "claude": removeClaude(rootfs, entry); break;
            case "agy": removeAgy(rootfs, entry); break;
            default: break;
        }
    }

    private static boolean isApplied(File rootfs, ModelConfigStore.Entry entry,
                                     String agentId) {
        switch (agentId) {
            case "codex": return isAppliedCodex(rootfs, entry);
            case "pi": return isAppliedPi(rootfs, entry);
            case "aider": return isAppliedAider(rootfs, entry);
            case "claude": return isAppliedClaude(rootfs, entry);
            case "agy": return isAppliedAgy(rootfs, entry);
            default: return false;
        }
    }

    // ------------------------------------------------------------------
    // model-env.sh + .bashrc marker
    // ------------------------------------------------------------------

    /** env VAR=value args for a quick terminal launch (Codex/agy only,
     * design §4.2). Reads the container's model-env.sh — passed as argv,
     * never shell-parsed, so any key characters are safe. */
    public static List<String> envArgsForAgent(File rootfs, String agentId) {
        List<String> varNames = launchEnvVarNamesForAgent(agentId);
        if (varNames.isEmpty() || rootfs == null) return Collections.emptyList();
        Map<String, String> env = readModelEnvFile(rootfs);
        List<String> args = new ArrayList<>();
        for (String name : varNames) {
            String value = env.get(name);
            if (value != null && !value.isEmpty()) args.add(name + "=" + value);
        }
        return args;
    }

    /** Rebuild the container's model-env.sh from every entry applied to
     * it (last-applied entry wins per var — see DESIGN-NOTES.md §4).
     * Deletes the file when nothing remains. Also ensures the .bashrc
     * marker for bash containers. */
    public static void rebuildModelEnvFile(Context context, File rootfs, String container) {
        if (rootfs == null || container == null) return;
        File homeDir = homeDir(rootfs);
        File envDir = new File(homeDir, MODEL_ENV_DIR_NAME);
        File envFile = new File(envDir, MODEL_ENV_FILE_NAME);

        Map<String, String> vars = new LinkedHashMap<>();
        List<ModelConfigStore.Entry> entries = ModelConfigStore.loadEntries(context);
        Map<String, Map<String, List<String>>> records = ModelConfigStore.loadApplyRecords();
        // Oldest applied first so the last-applied entry overwrites.
        entries.sort((a, b) -> Long.compare(a.lastAppliedAt, b.lastAppliedAt));
        for (ModelConfigStore.Entry entry : entries) {
            Map<String, List<String>> perContainer = records.get(entry.id);
            if (perContainer == null) continue;
            List<String> agents = perContainer.get(container);
            if (agents == null || agents.isEmpty()) continue;
            String apiKey = entry.getApiKey();
            if (apiKey == null || apiKey.isEmpty()) continue;
            for (String agentId : agents) {
                for (String var : envVarNamesForAgent(agentId, entry.type)) {
                    vars.put(var, apiKey);
                }
            }
        }

        if (vars.isEmpty()) {
            if (envFile.isFile() && !envFile.delete()) {
                Log.w(LOG_TAG, "Failed to delete " + envFile.getAbsolutePath());
            }
            return;
        }
        if (!(envDir.isDirectory() || envDir.mkdirs())) {
            Log.w(LOG_TAG, "Cannot create " + envDir.getAbsolutePath());
            return;
        }
        StringBuilder builder = new StringBuilder();
        builder.append("# Generated by Termux 模型配置 - do not edit.\n");
        builder.append("# One value per variable: the last-applied entry wins.\n");
        for (Map.Entry<String, String> var : vars.entrySet()) {
            builder.append("export ").append(var.getKey()).append("=")
                .append(shellSingleQuote(var.getValue())).append("\n");
        }
        if (!PromptThemeSync.writeFileAtomically(envFile, builder.toString())) return;
        if (PromptThemeSync.isBashContainer(rootfs)) {
            ensureBashrcMarker(rootfs);
        }
    }

    /** Parse the env file back (export NAME='value'; single quotes are
     * escaped as '\'' by shellSingleQuote). */
    private static Map<String, String> readModelEnvFile(File rootfs) {
        Map<String, String> env = new LinkedHashMap<>();
        File envFile = new File(new File(homeDir(rootfs), MODEL_ENV_DIR_NAME),
            MODEL_ENV_FILE_NAME);
        String content = PromptThemeSync.readFileOrEmpty(envFile);
        for (String line : content.split("\n")) {
            line = line.trim();
            if (!line.startsWith("export ")) continue;
            int eq = line.indexOf('=', 7);
            if (eq < 0) continue;
            String name = line.substring(7, eq).trim();
            String value = line.substring(eq + 1).trim();
            if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
                value = value.substring(1, value.length() - 1).replace("'\\''", "'");
            } else if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            if (!name.isEmpty()) env.put(name, value);
        }
        return env;
    }

    /** Single-quote a shell value: ' -> '\'' (close, escaped quote, reopen). */
    private static String shellSingleQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static void ensureBashrcMarker(File rootfs) {
        File bashrc = new File(homeDir(rootfs), ".bashrc");
        String content = PromptThemeSync.readFileOrEmpty(bashrc);
        if (content.contains(BASHRC_MARKER_BEGIN)) return;
        StringBuilder builder = new StringBuilder(content);
        if (builder.length() > 0 && builder.charAt(builder.length() - 1) != '\n') {
            builder.append('\n');
        }
        builder.append(BASHRC_MANAGED_COMMENT).append('\n');
        builder.append(BASHRC_MARKER_BEGIN).append('\n');
        builder.append(BASHRC_SOURCE_LINE).append('\n');
        builder.append(BASHRC_MARKER_END).append('\n');
        if (!PromptThemeSync.writeFileAtomically(bashrc, builder.toString())) {
            Log.w(LOG_TAG, "Failed to write " + bashrc.getAbsolutePath());
        }
    }

    private static void removeBashrcMarker(File rootfs) {
        File bashrc = new File(homeDir(rootfs), ".bashrc");
        String content = PromptThemeSync.readFileOrEmpty(bashrc);
        if (!content.contains(BASHRC_MARKER_BEGIN)) return;
        String[] lines = content.split("\n", -1);
        StringBuilder builder = new StringBuilder();
        boolean inBlock = false;
        for (String line : lines) {
            if (line.equals(BASHRC_MARKER_BEGIN)) {
                inBlock = true;
                continue;
            }
            if (inBlock) {
                if (line.equals(BASHRC_MARKER_END)) inBlock = false;
                continue;
            }
            if (line.equals(BASHRC_MANAGED_COMMENT)) continue;
            builder.append(line).append('\n');
        }
        PromptThemeSync.writeFileAtomically(bashrc, builder.toString());
    }

    /** Root's home inside the container (from /etc/passwd, /root fallback). */
    private static File homeDir(File rootfs) {
        PromptThemeSync.RootLogin login = PromptThemeSync.rootLogin(rootfs);
        return new File(rootfs, PromptThemeSync.relativePath(login.home));
    }

    // ------------------------------------------------------------------
    // Small file helpers
    // ------------------------------------------------------------------

    /** Copy file -> file.termux-bak once; never overwrite an existing backup. */
    private static void backupOnce(File file) {
        if (!file.isFile()) return;
        File backup = new File(file.getParentFile(), file.getName() + BACKUP_SUFFIX);
        if (backup.isFile()) return;
        try (FileInputStream in = new FileInputStream(file);
             FileOutputStream out = new FileOutputStream(backup)) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        } catch (IOException e) {
            Log.w(LOG_TAG, "Failed to back up " + file.getAbsolutePath(), e);
        }
    }

    private static File backupFileFor(File file) {
        return new File(file.getParentFile(), file.getName() + BACKUP_SUFFIX);
    }

    /** Parse a JSON object file; null when missing or unparseable (the
     * caller must NOT clobber an unparseable user file — it skips the
     * write and reports an error instead). */
    private static JSONObject readJsonObject(File file) {
        String text = PromptThemeSync.readFileOrEmpty(file);
        if (text.isEmpty()) return new JSONObject();
        try {
            return new JSONObject(text);
        } catch (Exception e) {
            Log.w(LOG_TAG, "Unparseable JSON, refusing to overwrite: "
                + file.getAbsolutePath(), e);
            return null;
        }
    }

    private static boolean writeJsonObject(File file, JSONObject obj) {
        File parent = file.getParentFile();
        if (parent != null && !(parent.isDirectory() || parent.mkdirs())) {
            Log.w(LOG_TAG, "Cannot create " + parent.getAbsolutePath());
            return false;
        }
        try {
            return PromptThemeSync.writeFileAtomically(file, obj.toString(2) + "\n");
        } catch (Exception e) {
            Log.w(LOG_TAG, "Failed to write " + file.getAbsolutePath(), e);
            return false;
        }
    }

    /** Get-or-create a nested JSONObject (creates missing parents). */
    private static JSONObject getOrCreateJsonObject(JSONObject parent, String key) {
        JSONObject child = parent.optJSONObject(key);
        if (child == null) {
            child = new JSONObject();
            try {
                parent.put(key, child);
            } catch (Exception e) {
                Log.w(LOG_TAG, "Failed to put " + key, e);
            }
        }
        return child;
    }

    /** Parse a YAML mapping file; null when missing-unparseable or when the
     * top level is not a mapping (the caller skips the write). An empty or
     * absent file parses as an empty mapping. */
    private static Map<String, Object> readYamlMap(File file) {
        String text = PromptThemeSync.readFileOrEmpty(file);
        if (text.isEmpty()) return new LinkedHashMap<>();
        try {
            Object loaded = new Yaml().load(text);
            if (loaded == null) return new LinkedHashMap<>();
            if (loaded instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) loaded;
                return new LinkedHashMap<>(map);
            }
            Log.w(LOG_TAG, "YAML top level is not a mapping: " + file.getAbsolutePath());
            return null;
        } catch (Exception e) {
            Log.w(LOG_TAG, "Unparseable YAML, refusing to overwrite: "
                + file.getAbsolutePath(), e);
            return null;
        }
    }

    private static boolean writeYamlMap(File file, Map<String, Object> map) {
        File parent = file.getParentFile();
        if (parent != null && !(parent.isDirectory() || parent.mkdirs())) {
            Log.w(LOG_TAG, "Cannot create " + parent.getAbsolutePath());
            return false;
        }
        try {
            return PromptThemeSync.writeFileAtomically(file, yamlDumpString(map));
        } catch (Exception e) {
            Log.w(LOG_TAG, "Failed to write " + file.getAbsolutePath(), e);
            return false;
        }
    }

    /** Dump any YAML value (map or list) with the shared dumper options. */
    private static String yamlDumpString(Object data) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setIndent(2);
        // Indent list items under their key (not flush-left).
        options.setIndentWithIndicator(true);
        options.setSplitLines(false);
        Yaml yaml = new Yaml(options);
        StringWriter writer = new StringWriter();
        yaml.dump(data, writer);
        return writer.toString();
    }

    /** Get-or-create a nested YAML mapping (creates missing parents). */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> getOrCreateYamlMap(Map<String, Object> parent,
                                                           String key) {
        Object child = parent.get(key);
        if (child instanceof Map) return (Map<String, Object>) child;
        Map<String, Object> created = new LinkedHashMap<>();
        parent.put(key, created);
        return created;
    }

    /** TOML basic-string escape (backslash + double quote). */
    private static String tomlEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Remove the managed section from text (or return text unchanged). */
    private static String removeManagedSection(String text, String begin, String end) {
        int start = text.indexOf(begin);
        if (start < 0) return text;
        int endPos = text.indexOf(end, start);
        if (endPos < 0) return text;
        int after = endPos + end.length();
        if (after < text.length() && text.charAt(after) == '\n') after++;
        return text.substring(0, start) + text.substring(after);
    }

    // ------------------------------------------------------------------
    // Codex — ~/.codex/config.toml (marker-section text edit)
    // ------------------------------------------------------------------

    private static File codexConfigFile(File rootfs) {
        return new File(homeDir(rootfs), ".codex/config.toml");
    }

    private static boolean applyCodex(File rootfs, ModelConfigStore.Entry entry,
                                      String apiKey) {
        File file = codexConfigFile(rootfs);
        File parent = file.getParentFile();
        if (parent != null && !(parent.isDirectory() || parent.mkdirs())) return false;
        backupOnce(file);
        String begin = codexBlockBegin(entry.providerId);
        String end = codexBlockEnd(entry.providerId);
        String text = PromptThemeSync.readFileOrEmpty(file);
        text = removeManagedSection(text, begin, end);
        if (entry.setDefault) {
            // This entry becomes the default: take the top-level model /
            // model_provider lines out of every other entry's block (their
            // provider tables stay), and out of the unmanaged part of the
            // file (the original is preserved in .termux-bak; see
            // DESIGN-NOTES.md §1). TOML forbids duplicate keys, so there
            // can only be one set.
            text = stripModelLinesFromOtherCodexBlocks(text, entry.providerId);
            text = stripTomlTopLevelKey(text, "model");
            text = stripTomlTopLevelKey(text, "model_provider");
        }
        StringBuilder block = new StringBuilder();
        block.append(CODEX_MANAGED_COMMENT).append("\n");
        block.append(begin).append("\n");
        if (entry.setDefault) {
            block.append("model = \"").append(tomlEscape(entry.primaryModel)).append("\"\n");
            block.append("model_provider = \"").append(tomlEscape(entry.providerId))
                .append("\"\n");
        }
        block.append("[model_providers.").append(entry.providerId).append("]\n");
        block.append("name = \"").append(tomlEscape(entry.providerId)).append("\"\n");
        block.append("base_url = \"")
            .append(tomlEscape(codexBaseUrl(entry))).append("\"\n");
        block.append("env_key = \"").append(ENV_OPENAI_KEY).append("\"\n");
        // Responses API is required for custom providers ("chat" was removed
        // Feb 2026); write it explicitly rather than relying on the default.
        block.append("wire_api = \"responses\"\n");
        block.append(end).append("\n");
        text = insertCodexBlock(text, block.toString(), entry.setDefault);
        return PromptThemeSync.writeFileAtomically(file, text);
    }

    /** Remove the model / model_provider lines from every managed block
     * except the given provider's (its block was already removed). */
    private static String stripModelLinesFromOtherCodexBlocks(String text,
                                                              String ownProviderId) {
        String[] lines = text.split("\n", -1);
        StringBuilder builder = new StringBuilder();
        String currentBlockProvider = null;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith(CODEX_MARKER_BEGIN_PREFIX)) {
                currentBlockProvider = providerIdFromCodexMarker(trimmed);
            } else if (trimmed.startsWith(CODEX_MARKER_END_PREFIX)) {
                currentBlockProvider = null;
            } else if (currentBlockProvider != null
                    && !currentBlockProvider.equals(ownProviderId)
                    && (trimmed.matches("model\\s*=.*")
                        || trimmed.matches("model_provider\\s*=.*"))) {
                continue;
            }
            builder.append(line).append("\n");
        }
        return builder.toString();
    }

    private static String providerIdFromCodexMarker(String line) {
        int open = line.indexOf('(');
        int close = line.lastIndexOf(')');
        if (open < 0 || close <= open) return "";
        return line.substring(open + 1, close);
    }

    /** Insert the managed block: before the first [table] header when it
     * carries top-level keys (setDefault), otherwise at the end. Top-level
     * keys appended after a table header would belong to that table, not
     * to the document root — appending unconditionally broke files that
     * already had a [model_providers.*] table (caught by the mirror test).
     * The insertion point is indexForTopLevelInsert: before the first
     * table, or before the managed block that holds it. */
    private static String insertCodexBlock(String text, String block,
                                           boolean hasTopLevelKeys) {
        if (text.isEmpty()) return block;
        if (!hasTopLevelKeys) {
            StringBuilder builder = new StringBuilder(text);
            if (builder.charAt(builder.length() - 1) != '\n') builder.append('\n');
            builder.append(block);
            return builder.toString();
        }
        String[] lines = text.split("\n", -1);
        int tableIndex = indexForTopLevelInsert(lines);
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i == tableIndex) builder.append(block);
            builder.append(lines[i]).append("\n");
        }
        if (tableIndex < 0) builder.append(block);
        return builder.toString();
    }

    /** Index where a top-level item (managed block or restored key) must
     * be inserted: before the first [table] header in the file. When that
     * header sits inside a managed block, insert before the block's BEGIN
     * line instead, so the block stays intact and the top-level item still
     * lands before every table (caught by the mirror test: appending a
     * second entry's block after the first entry's table put its model =
     * line inside the first entry's table). -1 when the file has no
     * tables at all. */
    private static int indexForTopLevelInsert(String[] lines) {
        boolean inBlock = false;
        int blockBeginIndex = -1;
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.startsWith(CODEX_MARKER_BEGIN_PREFIX)) {
                inBlock = true;
                blockBeginIndex = i;
            } else if (trimmed.startsWith(CODEX_MARKER_END_PREFIX)) {
                inBlock = false;
                blockBeginIndex = -1;
            } else if (trimmed.startsWith("[")) {
                return inBlock ? blockBeginIndex : i;
            }
        }
        return -1;
    }

    private static void removeCodex(File rootfs, ModelConfigStore.Entry entry) {
        File file = codexConfigFile(rootfs);
        if (!file.isFile()) return;
        String begin = codexBlockBegin(entry.providerId);
        String end = codexBlockEnd(entry.providerId);
        String text = PromptThemeSync.readFileOrEmpty(file);
        if (!text.contains(begin)) return;
        text = removeManagedSection(text, begin, end);
        // Restore the pre-apply top-level defaults from the backup when
        // the file no longer has them (design §6.2 step 4) — e.g. when
        // this was the only entry and it owned the default. When another
        // entry still owns the default, its lines are still there and
        // nothing is restored.
        String backup = PromptThemeSync.readFileOrEmpty(backupFileFor(file));
        if (!backup.isEmpty()) {
            for (String key : new String[]{"model", "model_provider"}) {
                if (!hasTomlTopLevelKey(text, key)) {
                    String line = tomlTopLevelLine(backup, key);
                    if (line != null) {
                        // A top-level key must sit before the first [table]
                        // header — appending it after another entry's
                        // provider table would make it part of that table.
                        text = insertBeforeFirstTable(text, line);
                    }
                }
            }
        }
        PromptThemeSync.writeFileAtomically(file, text);
    }

    /** Insert a line before the first [table] header (or at the end when
     * the file has no tables). A header inside a managed block counts via
     * the block's BEGIN line (see indexForTopLevelInsert). */
    private static String insertBeforeFirstTable(String text, String line) {
        String[] lines = text.split("\n", -1);
        int tableIndex = indexForTopLevelInsert(lines);
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i == tableIndex) builder.append(line).append("\n");
            builder.append(lines[i]).append("\n");
        }
        if (tableIndex < 0) {
            if (builder.length() > 0
                    && builder.charAt(builder.length() - 1) != '\n') {
                builder.append('\n');
            }
            builder.append(line).append("\n");
        }
        return builder.toString();
    }

    private static boolean isAppliedCodex(File rootfs, ModelConfigStore.Entry entry) {
        return PromptThemeSync.readFileOrEmpty(codexConfigFile(rootfs))
            .contains(codexBlockBegin(entry.providerId));
    }

    /** Remove top-level `key = ...` lines (not inside [tables] — a
     * top-level key can only appear before the first table header). */
    private static String stripTomlTopLevelKey(String text, String key) {
        String[] lines = text.split("\n", -1);
        StringBuilder builder = new StringBuilder();
        boolean inTable = false;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("[")) inTable = true;
            if (!inTable && trimmed.matches(
                    java.util.regex.Pattern.quote(key) + "\\s*=.*")) {
                continue;
            }
            builder.append(line).append("\n");
        }
        return builder.toString();
    }

    private static boolean hasTomlTopLevelKey(String text, String key) {
        boolean inTable = false;
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("[")) inTable = true;
            if (!inTable && trimmed.matches(
                    java.util.regex.Pattern.quote(key) + "\\s*=.*")) return true;
        }
        return false;
    }

    private static String tomlTopLevelLine(String text, String key) {
        boolean inTable = false;
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("[")) inTable = true;
            if (!inTable && trimmed.matches(
                    java.util.regex.Pattern.quote(key) + "\\s*=.*")) return line;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Pi — ~/.pi/agent/models.json + ~/.pi/agent/settings.json
    // ------------------------------------------------------------------

    private static boolean applyPi(File rootfs, ModelConfigStore.Entry entry,
                                   String apiKey) {
        File modelsFile = new File(homeDir(rootfs), ".pi/agent/models.json");
        File settingsFile = new File(homeDir(rootfs), ".pi/agent/settings.json");
        backupOnce(modelsFile);
        backupOnce(settingsFile);
        JSONObject models = readJsonObject(modelsFile);
        if (models == null) return false;
        JSONObject providers = getOrCreateJsonObject(models, "providers");
        JSONObject provider = new JSONObject();
        try {
            provider.put("baseUrl", piBaseUrl(entry));
            provider.put("api", piApiForType(entry.type));
            provider.put("apiKey", apiKey);
            provider.put("models", piModelJsonArray(entry));
            providers.put(entry.providerId, provider);
        } catch (Exception e) {
            Log.w(LOG_TAG, "Failed to build Pi provider", e);
            return false;
        }
        if (!writeJsonObject(modelsFile, models)) return false;
        if (entry.setDefault) {
            JSONObject settings = readJsonObject(settingsFile);
            if (settings == null) return false;
            try {
                settings.put("defaultProvider", entry.providerId);
                settings.put("defaultModel", entry.primaryModel);
            } catch (Exception e) {
                Log.w(LOG_TAG, "Failed to build Pi settings", e);
                return false;
            }
            return writeJsonObject(settingsFile, settings);
        }
        return true;
    }

    private static void removePi(File rootfs, ModelConfigStore.Entry entry) {
        File modelsFile = new File(homeDir(rootfs), ".pi/agent/models.json");
        File settingsFile = new File(homeDir(rootfs), ".pi/agent/settings.json");
        JSONObject models = readJsonObject(modelsFile);
        if (models != null) {
            JSONObject providers = models.optJSONObject("providers");
            if (providers != null && providers.has(entry.providerId)) {
                providers.remove(entry.providerId);
                writeJsonObject(modelsFile, models);
            }
        }
        JSONObject settings = readJsonObject(settingsFile);
        if (settings != null
                && entry.providerId.equals(settings.optString("defaultProvider", ""))) {
            JSONObject backupSettings = readJsonObject(backupFileFor(settingsFile));
            if (backupSettings != null
                    && backupSettings.has("defaultProvider")) {
                try {
                    settings.put("defaultProvider",
                        backupSettings.optString("defaultProvider", ""));
                    if (backupSettings.has("defaultModel")) {
                        settings.put("defaultModel",
                            backupSettings.optString("defaultModel", ""));
                    } else {
                        settings.remove("defaultModel");
                    }
                } catch (Exception e) {
                    Log.w(LOG_TAG, "Failed to restore Pi settings", e);
                }
            } else {
                settings.remove("defaultProvider");
                settings.remove("defaultModel");
            }
            writeJsonObject(settingsFile, settings);
        }
    }

    private static boolean isAppliedPi(File rootfs, ModelConfigStore.Entry entry) {
        JSONObject models = readJsonObject(
            new File(homeDir(rootfs), ".pi/agent/models.json"));
        if (models == null) return false;
        JSONObject providers = models.optJSONObject("providers");
        return providers != null && providers.has(entry.providerId);
    }

    // ------------------------------------------------------------------
    // Aider — ~/.aider.conf.yml (SnakeYAML)
    // ------------------------------------------------------------------

    private static File aiderConfigFile(File rootfs) {
        return new File(homeDir(rootfs), ".aider.conf.yml");
    }

    private static boolean applyAider(File rootfs, ModelConfigStore.Entry entry,
                                      String apiKey) {
        File file = aiderConfigFile(rootfs);
        backupOnce(file);
        Map<String, Object> root = readYamlMap(file);
        if (root == null) return false;
        String prefix = entry.type == ModelConfigStore.TYPE_GEMINI ? "gemini" : "openai";
        if (entry.setDefault) {
            root.put("model", prefix + "/" + entry.primaryModel);
        }
        if (entry.type == ModelConfigStore.TYPE_OPENAI) {
            root.put("openai-api-base", ModelConfigStore.effectiveBaseUrl(entry));
            root.put("openai-api-key", apiKey);
        }
        // Gemini: key only via env GEMINI_API_KEY (no YAML key for it);
        // a custom Gemini base URL is not written (no standard key).
        return writeYamlMap(file, root);
    }

    private static void removeAider(File rootfs, ModelConfigStore.Entry entry) {
        File file = aiderConfigFile(rootfs);
        if (!file.isFile()) return;
        Map<String, Object> root = readYamlMap(file);
        if (root == null) return;
        String prefix = entry.type == ModelConfigStore.TYPE_GEMINI ? "gemini" : "openai";
        Object currentModel = root.get("model");
        if (currentModel instanceof String
                && ((String) currentModel).startsWith(prefix + "/")
                && entry.models.contains(
                    ((String) currentModel).substring(prefix.length() + 1))) {
            Map<String, Object> backup = readYamlMap(backupFileFor(file));
            if (backup != null && backup.containsKey("model")) {
                root.put("model", backup.get("model"));
            } else {
                root.remove("model");
            }
        }
        if (entry.type == ModelConfigStore.TYPE_OPENAI) {
            root.remove("openai-api-base");
            root.remove("openai-api-key");
        }
        writeYamlMap(file, root);
    }

    private static boolean isAppliedAider(File rootfs, ModelConfigStore.Entry entry) {
        Map<String, Object> root = readYamlMap(aiderConfigFile(rootfs));
        if (root == null) return false;
        if (entry.type == ModelConfigStore.TYPE_OPENAI) {
            return root.containsKey("openai-api-key");
        }
        Object model = root.get("model");
        return model instanceof String
            && ("gemini/" + entry.primaryModel).equals(model);
    }

    // ------------------------------------------------------------------
    // Claude Code — ~/.claude/settings.json (never ~/.claude.json)
    // ------------------------------------------------------------------

    private static boolean applyClaude(File rootfs, ModelConfigStore.Entry entry,
                                       String apiKey) {
        File file = new File(homeDir(rootfs), ".claude/settings.json");
        backupOnce(file);
        JSONObject root = readJsonObject(file);
        if (root == null) return false;
        try {
            JSONObject env = getOrCreateJsonObject(root, "env");
            env.put("ANTHROPIC_BASE_URL", ModelConfigStore.effectiveBaseUrl(entry));
            // Both token names: Bearer gateways read ANTHROPIC_AUTH_TOKEN,
            // x-api-key gateways read ANTHROPIC_API_KEY (wrong one → 401).
            env.put("ANTHROPIC_AUTH_TOKEN", apiKey);
            env.put("ANTHROPIC_API_KEY", apiKey);
            root.put("availableModels", new JSONArray(entry.models));
            if (entry.setDefault) {
                root.put("model", entry.primaryModel);
                // Otherwise subagents/background tasks fall back to
                // Anthropic-hosted models.
                env.put("ANTHROPIC_MODEL", entry.primaryModel);
                env.put("ANTHROPIC_DEFAULT_OPUS_MODEL", entry.primaryModel);
                env.put("ANTHROPIC_DEFAULT_SONNET_MODEL", entry.primaryModel);
                env.put("ANTHROPIC_DEFAULT_HAIKU_MODEL", entry.primaryModel);
            }
        } catch (Exception e) {
            Log.w(LOG_TAG, "Failed to build Claude Code settings", e);
            return false;
        }
        return writeJsonObject(file, root);
    }

    private static void removeClaude(File rootfs, ModelConfigStore.Entry entry) {
        File file = new File(homeDir(rootfs), ".claude/settings.json");
        if (!file.isFile()) return;
        JSONObject root = readJsonObject(file);
        if (root == null) return;
        JSONObject backup = readJsonObject(backupFileFor(file));
        JSONObject backupEnv = backup == null ? null : backup.optJSONObject("env");
        JSONObject env = root.optJSONObject("env");
        if (env != null) {
            for (String key : new String[]{"ANTHROPIC_BASE_URL",
                    "ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_API_KEY",
                    "ANTHROPIC_MODEL", "ANTHROPIC_DEFAULT_OPUS_MODEL",
                    "ANTHROPIC_DEFAULT_SONNET_MODEL",
                    "ANTHROPIC_DEFAULT_HAIKU_MODEL"}) {
                if (backupEnv != null && backupEnv.has(key)) {
                    try {
                        env.put(key, backupEnv.optString(key, ""));
                    } catch (Exception e) {
                        Log.w(LOG_TAG, "Failed to restore Claude env key", e);
                        env.remove(key);
                    }
                } else {
                    env.remove(key);
                }
            }
        }
        if (entry.primaryModel.equals(root.optString("model", ""))) {
            if (backup != null && backup.has("model")) {
                try {
                    root.put("model", backup.optString("model", ""));
                } catch (Exception e) {
                    Log.w(LOG_TAG, "Failed to restore Claude Code model", e);
                    root.remove("model");
                }
            } else {
                root.remove("model");
            }
        }
        if (backup != null && backup.has("availableModels")) {
            try {
                root.put("availableModels", backup.optJSONArray("availableModels"));
            } catch (Exception e) {
                Log.w(LOG_TAG, "Failed to restore Claude Code models", e);
                root.remove("availableModels");
            }
        } else {
            root.remove("availableModels");
        }
        writeJsonObject(file, root);
    }

    private static boolean isAppliedClaude(File rootfs, ModelConfigStore.Entry entry) {
        JSONObject root = readJsonObject(
            new File(homeDir(rootfs), ".claude/settings.json"));
        if (root == null) return false;
        JSONObject env = root.optJSONObject("env");
        return env != null && env.has("ANTHROPIC_AUTH_TOKEN");
    }

    // ------------------------------------------------------------------
    // agy — ~/.gemini/antigravity-cli/settings.json
    // ------------------------------------------------------------------

    private static boolean applyAgy(File rootfs, ModelConfigStore.Entry entry,
                                    String apiKey) {
        File file = new File(homeDir(rootfs), ".gemini/antigravity-cli/settings.json");
        backupOnce(file);
        JSONObject root = readJsonObject(file);
        if (root == null) return false;
        try {
            root.put("modelProvider", "gemini");
            if (entry.setDefault) {
                root.put("model", entry.primaryModel);
            }
        } catch (Exception e) {
            Log.w(LOG_TAG, "Failed to build agy settings", e);
            return false;
        }
        return writeJsonObject(file, root);
    }

    private static void removeAgy(File rootfs, ModelConfigStore.Entry entry) {
        File file = new File(homeDir(rootfs), ".gemini/antigravity-cli/settings.json");
        if (!file.isFile()) return;
        JSONObject root = readJsonObject(file);
        if (root == null) return;
        JSONObject backup = readJsonObject(backupFileFor(file));
        if (entry.primaryModel.equals(root.optString("model", ""))) {
            if (backup != null && backup.has("model")) {
                try {
                    root.put("model", backup.optString("model", ""));
                } catch (Exception e) {
                    Log.w(LOG_TAG, "Failed to restore agy model", e);
                    root.remove("model");
                }
            } else {
                root.remove("model");
            }
        }
        if ("gemini".equals(root.optString("modelProvider", ""))) {
            if (backup != null && backup.has("modelProvider")) {
                try {
                    root.put("modelProvider", backup.optString("modelProvider", ""));
                } catch (Exception e) {
                    Log.w(LOG_TAG, "Failed to restore agy provider", e);
                    root.remove("modelProvider");
                }
            } else {
                root.remove("modelProvider");
            }
        }
        writeJsonObject(file, root);
    }

    private static boolean isAppliedAgy(File rootfs, ModelConfigStore.Entry entry) {
        JSONObject root = readJsonObject(
            new File(homeDir(rootfs), ".gemini/antigravity-cli/settings.json"));
        if (root == null) return false;
        return "gemini".equals(root.optString("modelProvider", ""));
    }
}
