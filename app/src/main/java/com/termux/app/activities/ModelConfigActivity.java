package com.termux.app.activities;

import android.app.AlertDialog;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.termux.R;
import com.termux.shared.activity.media.AppCompatActivityUtils;
import com.termux.shared.theme.NightMode;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Unified model config (模型配置): list + edit for provider entries.
 *
 * <p>One entry = name + type (OpenAI-compatible / Anthropic-compatible /
 * Gemini) + base URL + API key + a list of models with one primary model
 * + the agents and distros it applies to. The key is stored encrypted
 * (ModelConfigStore, Android Keystore + AES-256-GCM) and only decrypted
 * when applying or testing. The default model is always the primary
 * model, never list order (design §11); "设为默认模型" only controls
 * whether the default is written at all. A failed test connection warns
 * only and never blocks applying.
 *
 * <p>Rootfs reads/writes and network calls run on background threads;
 * the UI only touches the form state. Re-probes applied status on resume.
 */
public class ModelConfigActivity extends AppCompatActivity {

    private static final String LOG_TAG = "ModelConfigActivity";

    private static final int MODE_LIST = 0;
    private static final int MODE_EDIT = 1;

    private int mMode = MODE_LIST;

    // ---------- list mode ----------

    private List<ModelConfigStore.Entry> mEntries = new ArrayList<>();
    private ListView mListView;
    private TextView mListStatusView;
    private TextView mEmptyView;
    private ArrayAdapter<EntryRow> mAdapter;
    private final List<EntryRow> mRows = new ArrayList<>();
    private boolean mProbing;

    private static final class EntryRow {
        final ModelConfigStore.Entry entry;
        /** "" while probing; otherwise the applied status line. */
        String status = "";

        EntryRow(ModelConfigStore.Entry entry) {
            this.entry = entry;
        }
    }

    // ---------- edit mode ----------

    /** Working copy of the entry being edited (never the list's instance). */
    private ModelConfigStore.Entry mEditing;
    private boolean mIsNew;
    private boolean mDirty;
    private boolean mBusy;
    /** Plaintext key as loaded ("" for a new entry); used to detect edits. */
    private String mLoadedApiKey = "";

    private EditText mNameView;
    private RadioGroup mTypeGroup;
    private RadioButton mTypeOpenAiView;
    private RadioButton mTypeAnthropicView;
    private RadioButton mTypeGeminiView;
    private EditText mBaseUrlView;
    private EditText mApiKeyView;
    private CheckBox mShowKeyView;
    private LinearLayout mModelsContainer;
    private TextView mNoModelsView;
    private Button mFetchModelsButton;
    private Button mAddModelButton;
    private Switch mSetDefaultView;
    private LinearLayout mAgentsContainer;
    private TextView mNoAgentsView;
    private CheckBox mAllDistrosView;
    private LinearLayout mDistrosContainer;
    private TextView mNoDistrosView;
    private TextView mNoteKeyView;
    private TextView mNoteYamlView;
    private TextView mNoteCodexView;
    private TextView mNoteHermesView;
    private TextView mEditStatusView;
    private Button mTestButton;
    private Button mApplyButton;
    private Button mSaveButton;

    /** Working copies of the editable collections. */
    private final List<String> mModels = new ArrayList<>();
    private String mPrimaryModel = "";
    private final Set<String> mSelectedAgents = new LinkedHashSet<>();
    private final Set<String> mSelectedDistros = new LinkedHashSet<>();
    private boolean mAllDistros;
    private List<DistroUtils.InstalledDistro> mInstalledDistros = new ArrayList<>();
    private Map<String, Set<String>> mInstalledAgentsByContainer = new HashMap<>();
    private boolean mLoadingInstallState;
    /** Bumped on every showEdit; background callbacks ignore stale generations. */
    private int mEditGeneration;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        AppCompatActivityUtils.setNightMode(this, NightMode.getAppNightMode().getName(), true);

        mEntries = ModelConfigStore.loadEntries(this);
        showList();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mMode == MODE_LIST) {
            mEntries = ModelConfigStore.loadEntries(this);
            buildListRows();
            probeAppliedInBackground();
        }
    }

    @Override
    public void onBackPressed() {
        if (mMode == MODE_EDIT) {
            if (mDirty) {
                new AlertDialog.Builder(this)
                    .setMessage(R.string.modelconfig_discard_changes)
                    .setPositiveButton(R.string.modelconfig_discard,
                        (dialog, which) -> showList())
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            } else {
                showList();
            }
            return;
        }
        super.onBackPressed();
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }

    // ------------------------------------------------------------------
    // List mode
    // ------------------------------------------------------------------

    private void showList() {
        mMode = MODE_LIST;
        mEditing = null;
        setContentView(R.layout.modelconfig_layout);
        AppCompatActivityUtils.setToolbar(this, com.termux.shared.R.id.toolbar);
        AppCompatActivityUtils.setShowBackButtonInActionBar(this, true);

        mListView = findViewById(R.id.modelconfig_list);
        mListStatusView = findViewById(R.id.modelconfig_status);
        mEmptyView = findViewById(R.id.modelconfig_empty);
        Button addButton = findViewById(R.id.modelconfig_add_button);
        addButton.setOnClickListener(v -> showEdit(null));

        mAdapter = new ArrayAdapter<EntryRow>(this, R.layout.modelconfig_row, mRows) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = convertView;
                if (view == null) {
                    view = getLayoutInflater().inflate(
                        R.layout.modelconfig_row, parent, false);
                }
                EntryRow row = getItem(position);
                if (row != null) {
                    TextView nameView = view.findViewById(R.id.modelconfig_row_name);
                    TextView typeView = view.findViewById(R.id.modelconfig_row_type);
                    TextView modelsView = view.findViewById(R.id.modelconfig_row_models);
                    TextView statusView = view.findViewById(R.id.modelconfig_row_status);
                    nameView.setText(row.entry.name);
                    typeView.setText(typeName(row.entry.type));
                    modelsView.setText(modelsSummary(row.entry));
                    statusView.setText(row.status);
                }
                return view;
            }
        };
        mListView.setAdapter(mAdapter);
        mListView.setOnItemClickListener((parent, view, position, id) ->
            showEdit(mRows.get(position).entry));
        mListView.setOnItemLongClickListener((parent, view, position, id) -> {
            showDeleteDialog(mRows.get(position).entry);
            return true;
        });

        buildListRows();
        probeAppliedInBackground();
    }

    private void buildListRows() {
        mRows.clear();
        for (ModelConfigStore.Entry entry : mEntries) {
            mRows.add(new EntryRow(entry));
        }
        if (mAdapter != null) mAdapter.notifyDataSetChanged();
        updateListViews();
    }

    private void updateListViews() {
        if (mListView == null) return;
        mEmptyView.setVisibility(mRows.isEmpty() ? View.VISIBLE : View.GONE);
        mListView.setVisibility(mRows.isEmpty() ? View.GONE : View.VISIBLE);
        mListStatusView.setText(getString(R.string.modelconfig_list_status, mRows.size()));
    }

    private String modelsSummary(ModelConfigStore.Entry entry) {
        if (entry.models.isEmpty()) return getString(R.string.modelconfig_no_models_yet);
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < entry.models.size(); i++) {
            if (i > 0) builder.append(", ");
            String model = entry.models.get(i);
            builder.append(model);
            if (model.equals(entry.primaryModel)) {
                builder.append(" (").append(getString(R.string.modelconfig_primary_model))
                    .append(")");
            }
        }
        return builder.toString();
    }

    private String typeName(int type) {
        switch (type) {
            case ModelConfigStore.TYPE_ANTHROPIC:
                return getString(R.string.modelconfig_type_anthropic);
            case ModelConfigStore.TYPE_GEMINI:
                return getString(R.string.modelconfig_type_gemini);
            case ModelConfigStore.TYPE_OPENAI:
            default:
                return getString(R.string.modelconfig_type_openai);
        }
    }

    /** Re-probe applied status for every row (rootfs reads, background). */
    private void probeAppliedInBackground() {
        if (mProbing || mMode != MODE_LIST) return;
        mProbing = true;
        final List<EntryRow> rows = new ArrayList<>(mRows);
        final List<DistroUtils.InstalledDistro> distros =
            DistroUtils.getInstalledDistros();
        new Thread() {
            @Override
            public void run() {
                Map<String, int[]> countsById = new HashMap<>();
                for (EntryRow row : rows) {
                    int appliedDistros = 0;
                    int appliedAgents = 0;
                    List<String> containers = targetContainers(row.entry, distros);
                    for (String container : containers) {
                        File rootfs = DistroUtils.containerRootfs(container);
                        if (rootfs == null) continue;
                        List<String> applied =
                            ModelConfigApplier.probeAppliedAgents(rootfs, row.entry);
                        if (!applied.isEmpty()) {
                            appliedDistros++;
                            appliedAgents += applied.size();
                        }
                    }
                    countsById.put(row.entry.id,
                        new int[]{appliedDistros, appliedAgents});
                }
                final Map<String, int[]> result = countsById;
                runOnUiThread(() -> {
                    mProbing = false;
                    if (mMode != MODE_LIST) return;
                    for (EntryRow row : mRows) {
                        int[] counts = result.get(row.entry.id);
                        if (counts == null) continue;
                        row.status = counts[0] > 0
                            ? getString(R.string.modelconfig_applied_status,
                                counts[0], counts[1])
                            : getString(R.string.modelconfig_not_applied);
                    }
                    if (mAdapter != null) mAdapter.notifyDataSetChanged();
                });
            }
        }.start();
    }

    /** Containers an entry targets: all installed, or its chosen list. */
    private static List<String> targetContainers(ModelConfigStore.Entry entry,
                                                 List<DistroUtils.InstalledDistro> distros) {
        List<String> containers = new ArrayList<>();
        if (entry.allDistros) {
            for (DistroUtils.InstalledDistro distro : distros) {
                containers.add(distro.container);
            }
        } else {
            containers.addAll(entry.distroNames);
        }
        return containers;
    }

    private void showDeleteDialog(ModelConfigStore.Entry entry) {
        new AlertDialog.Builder(this)
            .setTitle(entry.name)
            .setMessage(R.string.modelconfig_delete_confirm)
            .setPositiveButton(R.string.modelconfig_delete_entry, (dialog, which) ->
                deleteEntry(entry, false))
            .setNeutralButton(R.string.modelconfig_delete_entry_and_applied,
                (dialog, which) -> deleteEntry(entry, true))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void deleteEntry(ModelConfigStore.Entry entry, boolean clearApplied) {
        if (clearApplied) {
            final ModelConfigStore.Entry copy = entry.deepCopy();
            new Thread() {
                @Override
                public void run() {
                    for (String container : ModelConfigStore.appliedContainers(copy.id)) {
                        ModelConfigApplier.removeEntryFromContainer(
                            ModelConfigActivity.this, copy, container);
                    }
                    runOnUiThread(() -> deleteEntryOnly(copy));
                }
            }.start();
        } else {
            deleteEntryOnly(entry);
        }
    }

    private void deleteEntryOnly(ModelConfigStore.Entry entry) {
        List<ModelConfigStore.Entry> remaining = new ArrayList<>();
        for (ModelConfigStore.Entry e : mEntries) {
            if (!e.id.equals(entry.id)) remaining.add(e);
        }
        mEntries = remaining;
        ModelConfigStore.saveEntries(this, mEntries);
        buildListRows();
        Toast.makeText(this, R.string.modelconfig_deleted, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------
    // Edit mode
    // ------------------------------------------------------------------

    private void showEdit(ModelConfigStore.Entry entry) {
        mMode = MODE_EDIT;
        mEditGeneration++;
        mIsNew = entry == null;
        mEditing = entry == null ? null : entry.deepCopy();
        mDirty = false;
        mBusy = false;
        mLoadedApiKey = "";

        setContentView(R.layout.modelconfig_edit_layout);
        AppCompatActivityUtils.setToolbar(this, com.termux.shared.R.id.toolbar);
        AppCompatActivityUtils.setShowBackButtonInActionBar(this, true);

        mNameView = findViewById(R.id.modelconfig_edit_name);
        mTypeGroup = findViewById(R.id.modelconfig_edit_type);
        mTypeOpenAiView = findViewById(R.id.modelconfig_edit_type_openai);
        mTypeAnthropicView = findViewById(R.id.modelconfig_edit_type_anthropic);
        mTypeGeminiView = findViewById(R.id.modelconfig_edit_type_gemini);
        mBaseUrlView = findViewById(R.id.modelconfig_edit_base_url);
        mApiKeyView = findViewById(R.id.modelconfig_edit_api_key);
        mShowKeyView = findViewById(R.id.modelconfig_edit_show_key);
        mModelsContainer = findViewById(R.id.modelconfig_edit_models);
        mNoModelsView = findViewById(R.id.modelconfig_edit_no_models);
        mFetchModelsButton = findViewById(R.id.modelconfig_edit_fetch_models);
        mAddModelButton = findViewById(R.id.modelconfig_edit_add_model);
        mSetDefaultView = findViewById(R.id.modelconfig_edit_set_default);
        mAgentsContainer = findViewById(R.id.modelconfig_edit_agents);
        mNoAgentsView = findViewById(R.id.modelconfig_edit_no_agents);
        mAllDistrosView = findViewById(R.id.modelconfig_edit_all_distros);
        mDistrosContainer = findViewById(R.id.modelconfig_edit_distros);
        mNoDistrosView = findViewById(R.id.modelconfig_edit_no_distros);
        mNoteKeyView = findViewById(R.id.modelconfig_edit_note_key);
        mNoteYamlView = findViewById(R.id.modelconfig_edit_note_yaml);
        mNoteCodexView = findViewById(R.id.modelconfig_edit_note_codex);
        mNoteHermesView = findViewById(R.id.modelconfig_edit_note_hermes);
        mEditStatusView = findViewById(R.id.modelconfig_edit_status);
        mTestButton = findViewById(R.id.modelconfig_edit_test);
        mApplyButton = findViewById(R.id.modelconfig_edit_apply);
        mSaveButton = findViewById(R.id.modelconfig_edit_save);

        // Working copies.
        mModels.clear();
        mSelectedAgents.clear();
        mSelectedDistros.clear();
        if (mEditing != null) {
            mNameView.setText(mEditing.name);
            mBaseUrlView.setText(mEditing.baseUrl);
            mLoadedApiKey = mEditing.getApiKey();
            if (mLoadedApiKey == null) {
                mLoadedApiKey = "";
                Toast.makeText(this, R.string.modelconfig_key_invalid,
                    Toast.LENGTH_LONG).show();
            }
            mApiKeyView.setText(mLoadedApiKey);
            mModels.addAll(mEditing.models);
            mPrimaryModel = mEditing.primaryModel;
            mSetDefaultView.setChecked(mEditing.setDefault);
            mSelectedAgents.addAll(mEditing.agentIds);
            mSelectedDistros.addAll(mEditing.distroNames);
            mAllDistros = mEditing.allDistros;
        } else {
            mPrimaryModel = "";
            mSetDefaultView.setChecked(true);
            mAllDistros = false;
        }
        if (mPrimaryModel == null) mPrimaryModel = "";
        if (mModels.isEmpty()) mPrimaryModel = "";

        // Type is locked after saving (the provider id + per-agent mapping
        // depend on it); a new entry defaults to OpenAI-compatible.
        int type = mEditing == null ? ModelConfigStore.TYPE_OPENAI : mEditing.type;
        checkTypeRadio(type);
        if (mEditing != null) {
            mTypeOpenAiView.setEnabled(false);
            mTypeAnthropicView.setEnabled(false);
            mTypeGeminiView.setEnabled(false);
            findViewById(R.id.modelconfig_edit_type_locked)
                .setVisibility(View.VISIBLE);
        }
        mTypeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            mDirty = true;
            rebuildAgentRows();
            updateNotes();
        });

        TextWatcher dirtyWatcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                mDirty = true;
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        };
        mNameView.addTextChangedListener(dirtyWatcher);
        mBaseUrlView.addTextChangedListener(dirtyWatcher);
        mApiKeyView.addTextChangedListener(dirtyWatcher);
        mShowKeyView.setOnCheckedChangeListener((button, checked) -> {
            int start = mApiKeyView.getSelectionStart();
            int end = mApiKeyView.getSelectionEnd();
            mApiKeyView.setInputType(checked
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            mApiKeyView.setSelection(Math.max(0, start), Math.max(0, end));
        });
        mSetDefaultView.setOnCheckedChangeListener((button, checked) -> mDirty = true);
        mAllDistrosView.setChecked(mAllDistros);
        mAllDistrosView.setOnCheckedChangeListener((button, checked) -> {
            mAllDistros = checked;
            mDirty = true;
            rebuildDistroRows();
        });

        mFetchModelsButton.setOnClickListener(v -> fetchModelsInBackground());
        mAddModelButton.setOnClickListener(v -> showAddModelDialog());
        mTestButton.setOnClickListener(v -> testConnectionInBackground());
        // Both buttons persist first (the env rebuild re-reads the store);
        // Save returns to the list on success, Apply stays on the form.
        mApplyButton.setOnClickListener(v -> applyInBackground(false));
        mSaveButton.setOnClickListener(v -> applyInBackground(true));

        rebuildModelRows();
        rebuildAgentRows();
        rebuildDistroRows();
        updateNotes();
        loadInstallStateInBackground();
    }

    private void checkTypeRadio(int type) {
        switch (type) {
            case ModelConfigStore.TYPE_ANTHROPIC:
                mTypeAnthropicView.setChecked(true);
                break;
            case ModelConfigStore.TYPE_GEMINI:
                mTypeGeminiView.setChecked(true);
                break;
            case ModelConfigStore.TYPE_OPENAI:
            default:
                mTypeOpenAiView.setChecked(true);
                break;
        }
    }

    private int selectedType() {
        int checkedId = mTypeGroup.getCheckedRadioButtonId();
        if (checkedId == R.id.modelconfig_edit_type_anthropic) {
            return ModelConfigStore.TYPE_ANTHROPIC;
        }
        if (checkedId == R.id.modelconfig_edit_type_gemini) {
            return ModelConfigStore.TYPE_GEMINI;
        }
        return ModelConfigStore.TYPE_OPENAI;
    }

    private void setBusy(boolean busy) {
        mBusy = busy;
        mTestButton.setEnabled(!busy);
        mApplyButton.setEnabled(!busy);
        mSaveButton.setEnabled(!busy);
        mFetchModelsButton.setEnabled(!busy);
        mAddModelButton.setEnabled(!busy);
        if (busy) {
            mEditStatusView.setText(R.string.modelconfig_working);
        }
    }

    // ---------- model rows ----------

    private void rebuildModelRows() {
        mModelsContainer.removeAllViews();
        mNoModelsView.setVisibility(mModels.isEmpty() ? View.VISIBLE : View.GONE);
        LayoutInflater inflater = getLayoutInflater();
        for (String model : new ArrayList<>(mModels)) {
            View row = inflater.inflate(
                R.layout.modelconfig_model_row, mModelsContainer, false);
            CheckBox check = row.findViewById(R.id.modelconfig_model_check);
            TextView nameView = row.findViewById(R.id.modelconfig_model_name);
            RadioButton primaryView = row.findViewById(R.id.modelconfig_model_primary);
            Button deleteButton = row.findViewById(R.id.modelconfig_model_delete);
            check.setVisibility(View.GONE);
            nameView.setText(model);
            primaryView.setChecked(model.equals(mPrimaryModel));
            primaryView.setOnClickListener(v -> {
                mPrimaryModel = model;
                mDirty = true;
                rebuildModelRows();
            });
            deleteButton.setOnClickListener(v -> {
                mModels.remove(model);
                if (model.equals(mPrimaryModel)) {
                    mPrimaryModel = mModels.isEmpty() ? "" : mModels.get(0);
                }
                mDirty = true;
                rebuildModelRows();
            });
            mModelsContainer.addView(row);
        }
    }

    private void showAddModelDialog() {
        final EditText input = new EditText(this);
        input.setHint(R.string.modelconfig_add_model_hint);
        input.setSingleLine(true);
        new AlertDialog.Builder(this)
            .setTitle(R.string.modelconfig_add_model)
            .setView(input)
            .setPositiveButton(R.string.modelconfig_add, (dialog, which) -> {
                String model = input.getText().toString().trim();
                if (model.isEmpty() || mModels.contains(model)) return;
                mModels.add(model);
                if (mModels.size() == 1) mPrimaryModel = model;
                mDirty = true;
                rebuildModelRows();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    // ---------- agent / distro rows ----------

    private void rebuildAgentRows() {
        mAgentsContainer.removeAllViews();
        int type = selectedType();
        int shown = 0;
        for (AiAgentUtils.AgentDef def : AiAgentUtils.AGENTS) {
            if (!ModelConfigApplier.supportsType(def.id, type)) continue;
            shown++;
            CheckBox check = new CheckBox(this);
            check.setText(agentRowText(def));
            check.setChecked(mSelectedAgents.contains(def.id));
            check.setOnCheckedChangeListener((button, checked) -> {
                if (checked) {
                    mSelectedAgents.add(def.id);
                } else {
                    mSelectedAgents.remove(def.id);
                }
                mDirty = true;
                updateNotes();
            });
            mAgentsContainer.addView(check);
        }
        mNoAgentsView.setVisibility(shown == 0 ? View.VISIBLE : View.GONE);
    }

    private String agentRowText(AiAgentUtils.AgentDef def) {
        String text = def.displayName;
        if (!isAgentInstalledAnywhere(def.id)) {
            text += " (" + getString(R.string.modelconfig_not_installed) + ")";
        }
        return text;
    }

    private boolean isAgentInstalledAnywhere(String agentId) {
        for (Set<String> agents : mInstalledAgentsByContainer.values()) {
            if (agents.contains(agentId)) return true;
        }
        return false;
    }

    private void rebuildDistroRows() {
        mDistrosContainer.removeAllViews();
        mNoDistrosView.setVisibility(
            mInstalledDistros.isEmpty() ? View.VISIBLE : View.GONE);
        for (DistroUtils.InstalledDistro distro : mInstalledDistros) {
            CheckBox check = new CheckBox(this);
            check.setText(distro.display);
            check.setChecked(mSelectedDistros.contains(distro.container));
            check.setEnabled(!mAllDistros);
            check.setOnCheckedChangeListener((button, checked) -> {
                if (checked) {
                    mSelectedDistros.add(distro.container);
                } else {
                    mSelectedDistros.remove(distro.container);
                }
                mDirty = true;
            });
            mDistrosContainer.addView(check);
        }
    }

    private void updateNotes() {
        boolean yaml = false;
        boolean codex = false;
        boolean hermes = false;
        for (String agentId : mSelectedAgents) {
            if ("dsh".equals(agentId) || "aider".equals(agentId)
                    || "hermes".equals(agentId)) yaml = true;
            if ("codex".equals(agentId)) codex = true;
            if ("hermes".equals(agentId)) hermes = true;
        }
        mNoteYamlView.setVisibility(yaml ? View.VISIBLE : View.GONE);
        mNoteCodexView.setVisibility(codex ? View.VISIBLE : View.GONE);
        mNoteHermesView.setVisibility(hermes ? View.VISIBLE : View.GONE);
    }

    /** Installed distros + per-container installed agents (rootfs scan,
     * background; only used for the "(未安装)" suffix). */
    private void loadInstallStateInBackground() {
        if (mLoadingInstallState) return;
        mLoadingInstallState = true;
        final int generation = mEditGeneration;
        new Thread() {
            @Override
            public void run() {
                final List<DistroUtils.InstalledDistro> distros =
                    DistroUtils.getInstalledDistros();
                final Map<String, Set<String>> installed = new HashMap<>();
                for (AiAgentUtils.InstalledAgent agent :
                        AiAgentUtils.detectInstalledAgents()) {
                    Set<String> agents = installed.get(agent.container);
                    if (agents == null) {
                        agents = new HashSet<>();
                        installed.put(agent.container, agents);
                    }
                    agents.add(agent.agentId);
                }
                runOnUiThread(() -> {
                    mLoadingInstallState = false;
                    if (mMode != MODE_EDIT || generation != mEditGeneration) return;
                    mInstalledDistros = distros;
                    mInstalledAgentsByContainer = installed;
                    rebuildAgentRows();
                    rebuildDistroRows();
                });
            }
        }.start();
    }

    // ---------- fetch / test ----------

    private String formApiKey() {
        return mApiKeyView.getText().toString().trim();
    }

    private String formBaseUrl() {
        return mBaseUrlView.getText().toString().trim();
    }

    private void fetchModelsInBackground() {
        String apiKey = formApiKey();
        if (apiKey.isEmpty()) {
            Toast.makeText(this, R.string.modelconfig_need_key, Toast.LENGTH_SHORT).show();
            return;
        }
        final int type = selectedType();
        final String baseUrl = formBaseUrl();
        final int generation = mEditGeneration;
        setBusy(true);
        new Thread() {
            @Override
            public void run() {
                final ModelConfigStore.FetchResult result =
                    ModelConfigStore.fetchModels(type, baseUrl, apiKey);
                runOnUiThread(() -> {
                    setBusy(false);
                    if (mMode != MODE_EDIT || generation != mEditGeneration) return;
                    if (!result.ok) {
                        mEditStatusView.setText(getString(
                            R.string.modelconfig_fetch_failed, result.error));
                        return;
                    }
                    showFetchDialog(result.models);
                });
            }
        }.start();
    }

    /** Searchable multi-select dialog; the primary model is picked with
     * the radio in each row (single-select, first selected auto-becomes
     * primary — design §11). */
    private void showFetchDialog(List<String> fetched) {
        View dialogView = getLayoutInflater().inflate(
            R.layout.modelconfig_fetch_layout, null);
        EditText searchView = dialogView.findViewById(R.id.modelconfig_fetch_search);
        ListView listView = dialogView.findViewById(R.id.modelconfig_fetch_list);

        final List<String> full = new ArrayList<>(fetched);
        final List<String> shown = new ArrayList<>(fetched);
        final Set<String> selected = new LinkedHashSet<>(mModels);
        final String[] primary = {mPrimaryModel};
        if ((primary[0] == null || !selected.contains(primary[0]))
                && !selected.isEmpty()) {
            primary[0] = selected.iterator().next();
        }

        final ArrayAdapter<String> adapter = new ArrayAdapter<String>(
                this, R.layout.modelconfig_model_row, shown) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = convertView;
                if (view == null) {
                    view = getLayoutInflater().inflate(
                        R.layout.modelconfig_model_row, parent, false);
                }
                final String model = getItem(position);
                CheckBox check = view.findViewById(R.id.modelconfig_model_check);
                TextView nameView = view.findViewById(R.id.modelconfig_model_name);
                RadioButton primaryView =
                    view.findViewById(R.id.modelconfig_model_primary);
                Button deleteButton = view.findViewById(R.id.modelconfig_model_delete);
                deleteButton.setVisibility(View.GONE);
                nameView.setText(model);
                check.setVisibility(View.VISIBLE);
                check.setChecked(selected.contains(model));
                check.setOnClickListener(v -> {
                    if (selected.contains(model)) {
                        selected.remove(model);
                        if (model.equals(primary[0])) {
                            primary[0] = selected.isEmpty()
                                ? "" : selected.iterator().next();
                        }
                    } else {
                        selected.add(model);
                        if (primary[0] == null || primary[0].isEmpty()) {
                            primary[0] = model;
                        }
                    }
                    notifyDataSetChanged();
                });
                primaryView.setVisibility(View.VISIBLE);
                primaryView.setChecked(model.equals(primary[0]));
                primaryView.setOnClickListener(v -> {
                    primary[0] = model;
                    selected.add(model);
                    notifyDataSetChanged();
                });
                return view;
            }
        };
        listView.setAdapter(adapter);
        searchView.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String query = s.toString().toLowerCase();
                shown.clear();
                for (String model : full) {
                    if (model.toLowerCase().contains(query)) shown.add(model);
                }
                adapter.notifyDataSetChanged();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        new AlertDialog.Builder(this)
            .setTitle(R.string.modelconfig_choose_models)
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                mModels.clear();
                mModels.addAll(selected);
                mPrimaryModel = primary[0] == null ? "" : primary[0];
                if (!mModels.isEmpty()
                        && (mPrimaryModel.isEmpty() || !mModels.contains(mPrimaryModel))) {
                    mPrimaryModel = mModels.get(0);
                }
                mDirty = true;
                rebuildModelRows();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void testConnectionInBackground() {
        String apiKey = formApiKey();
        if (apiKey.isEmpty()) {
            Toast.makeText(this, R.string.modelconfig_need_key, Toast.LENGTH_SHORT).show();
            return;
        }
        final int type = selectedType();
        final String baseUrl = formBaseUrl();
        final int generation = mEditGeneration;
        setBusy(true);
        new Thread() {
            @Override
            public void run() {
                final ModelConfigStore.FetchResult result =
                    ModelConfigStore.fetchModels(type, baseUrl, apiKey);
                runOnUiThread(() -> {
                    setBusy(false);
                    if (mMode != MODE_EDIT || generation != mEditGeneration) return;
                    if (!result.ok) {
                        // Warns only, never blocks applying (design §6.4).
                        mEditStatusView.setText(getString(
                            R.string.modelconfig_test_failed, result.error));
                        return;
                    }
                    String status = getString(R.string.modelconfig_test_ok,
                        result.models.size());
                    if (!mPrimaryModel.isEmpty()
                            && !result.models.contains(mPrimaryModel)) {
                        status += " — " + getString(
                            R.string.modelconfig_test_primary_missing, mPrimaryModel);
                    }
                    mEditStatusView.setText(status);
                });
            }
        }.start();
    }

    // ---------- validate / save / apply ----------

    /** Collect the form into the working entry; null when invalid
     * (toast + status already shown). */
    private ModelConfigStore.Entry collectEntry() {
        String name = mNameView.getText().toString().trim();
        if (name.isEmpty()) {
            Toast.makeText(this, R.string.modelconfig_need_name, Toast.LENGTH_SHORT).show();
            return null;
        }
        String apiKey = formApiKey();
        if (apiKey.isEmpty()) {
            Toast.makeText(this, R.string.modelconfig_need_key, Toast.LENGTH_SHORT).show();
            return null;
        }
        if (mModels.isEmpty()) {
            Toast.makeText(this, R.string.modelconfig_need_models, Toast.LENGTH_SHORT).show();
            return null;
        }
        if (mSelectedAgents.isEmpty()) {
            Toast.makeText(this, R.string.modelconfig_need_agents, Toast.LENGTH_SHORT).show();
            return null;
        }
        if (!mAllDistros && mSelectedDistros.isEmpty()) {
            Toast.makeText(this, R.string.modelconfig_need_distros, Toast.LENGTH_SHORT).show();
            return null;
        }

        ModelConfigStore.Entry entry;
        if (mIsNew || mEditing == null) {
            List<String> existingIds = new ArrayList<>();
            for (ModelConfigStore.Entry e : mEntries) existingIds.add(e.providerId);
            entry = ModelConfigStore.Entry.create(name, selectedType(), existingIds);
        } else {
            entry = mEditing;
            entry.name = name;
            // Type is locked after saving; selectedType() is the saved one.
        }
        entry.baseUrl = formBaseUrl();
        entry.models = new ArrayList<>(mModels);
        entry.primaryModel = mPrimaryModel;
        entry.fixPrimaryModel();
        entry.setDefault = mSetDefaultView.isChecked();
        entry.agentIds = new ArrayList<>(mSelectedAgents);
        entry.distroNames = new ArrayList<>(mSelectedDistros);
        entry.allDistros = mAllDistros;

        // Only re-encrypt when the key actually changed; a stored key that
        // can no longer be decrypted (backup restore) still saves, and the
        // user is told to re-enter it.
        if (!apiKey.equals(mLoadedApiKey)) {
            if (!entry.setApiKey(apiKey)) {
                Toast.makeText(this, R.string.modelconfig_key_save_failed,
                    Toast.LENGTH_LONG).show();
                return null;
            }
            mLoadedApiKey = apiKey;
        }
        return entry;
    }

    /** Save then apply. Both buttons persist first — the env rebuild
     * re-reads the entries from the store, so applying unsaved edits
     * would rebuild from stale data. finishAfter returns to the list on
     * success (Save); otherwise the form stays open (Apply). */
    private void applyInBackground(final boolean finishAfter) {
        if (mBusy) return;
        final ModelConfigStore.Entry entry = collectEntry();
        if (entry == null) return;
        final String apiKey = entry.getApiKey();
        if (apiKey == null || apiKey.isEmpty()) {
            mEditStatusView.setText(R.string.modelconfig_key_invalid);
            return;
        }
        final int generation = mEditGeneration;
        setBusy(true);
        new Thread() {
            @Override
            public void run() {
                List<ModelConfigStore.Entry> entries =
                    ModelConfigStore.loadEntries(ModelConfigActivity.this);
                boolean replaced = false;
                for (int i = 0; i < entries.size(); i++) {
                    if (entries.get(i).id.equals(entry.id)) {
                        entries.set(i, entry);
                        replaced = true;
                        break;
                    }
                }
                if (!replaced) entries.add(entry);
                ModelConfigStore.saveEntries(ModelConfigActivity.this, entries);
                mEntries = entries;
                mEditing = entry.deepCopy();
                mIsNew = false;

                final List<DistroUtils.InstalledDistro> distros =
                    DistroUtils.getInstalledDistros();
                final List<String> containers = targetContainers(entry, distros);
                final ModelConfigApplier.ApplyResult result =
                    ModelConfigApplier.applyEntry(
                        ModelConfigActivity.this, entry, apiKey, containers);
                if (result.appliedDistros > 0) {
                    entry.lastAppliedAt = System.currentTimeMillis();
                    List<ModelConfigStore.Entry> saved =
                        ModelConfigStore.loadEntries(ModelConfigActivity.this);
                    for (ModelConfigStore.Entry e : saved) {
                        if (e.id.equals(entry.id)) {
                            e.lastAppliedAt = entry.lastAppliedAt;
                            break;
                        }
                    }
                    ModelConfigStore.saveEntries(ModelConfigActivity.this, saved);
                    mEntries = saved;
                }
                runOnUiThread(() -> {
                    setBusy(false);
                    if (mMode != MODE_EDIT || generation != mEditGeneration) return;
                    if (!result.errors.isEmpty()) {
                        mEditStatusView.setText(getString(
                            R.string.modelconfig_apply_failed,
                            result.errors.get(0)));
                    } else if (result.appliedDistros > 0) {
                        mEditStatusView.setText(getString(
                            R.string.modelconfig_applied_done, result.appliedDistros));
                        Toast.makeText(ModelConfigActivity.this,
                            R.string.modelconfig_saved, Toast.LENGTH_SHORT).show();
                        mDirty = false;
                        if (finishAfter) showList();
                    } else {
                        mEditStatusView.setText(
                            getString(R.string.modelconfig_not_applied));
                    }
                });
            }
        }.start();
    }
}
