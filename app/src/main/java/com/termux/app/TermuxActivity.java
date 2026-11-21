package com.termux.app;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.IBinder;
import android.util.TypedValue;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RelativeLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.termux.R;
import com.termux.app.api.file.FileReceiverActivity;
import com.termux.app.terminal.TermuxActivityRootView;
import com.termux.app.terminal.TermuxTerminalSessionActivityClient;
import com.termux.app.terminal.io.TermuxTerminalExtraKeys;
import com.termux.shared.activities.ReportActivity;
import com.termux.shared.activity.ActivityUtils;
import com.termux.shared.activity.media.AppCompatActivityUtils;
import com.termux.shared.data.IntentUtils;
import com.termux.shared.android.PermissionUtils;
import com.termux.shared.data.DataUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY;
import com.termux.app.activities.DistroActivity;
import com.termux.app.activities.DistroUtils;
import com.termux.app.activities.AiAgentUtils;
import com.termux.app.activities.AiAgentsActivity;
import com.termux.app.activities.HelpActivity;
import com.termux.app.activities.SettingsActivity;
import com.termux.app.activities.TermuxStyleActivity;
import com.termux.shared.termux.crash.TermuxCrashUtils;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.app.terminal.TermuxSessionsListViewController;
import com.termux.app.terminal.io.TerminalToolbarViewPager;
import com.termux.app.terminal.TermuxTerminalViewClient;
import com.termux.shared.termux.extrakeys.ExtraKeysView;
import com.termux.shared.termux.interact.TextInputDialogUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxUtils;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;
import com.termux.shared.termux.theme.TermuxThemeUtils;
import com.termux.shared.theme.NightMode;
import com.termux.shared.theme.ThemeUtils;
import com.termux.shared.view.ViewUtils;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.preference.PreferenceManager;
import androidx.viewpager.widget.ViewPager;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A terminal emulator activity.
 * <p/>
 * See
 * <ul>
 * <li>http://www.mongrel-phones.com.au/default/how_to_make_a_local_service_and_bind_to_it_in_android</li>
 * <li>https://code.google.com/p/android/issues/detail?id=6426</li>
 * </ul>
 * about memory leaks.
 */
public final class TermuxActivity extends AppCompatActivity implements ServiceConnection {

    /**
     * The connection to the {@link TermuxService}. Requested in {@link #onCreate(Bundle)} with a call to
     * {@link #bindService(Intent, ServiceConnection, int)}, and obtained and stored in
     * {@link #onServiceConnected(ComponentName, IBinder)}.
     */
    TermuxService mTermuxService;

    /**
     * The {@link TerminalView} shown in  {@link TermuxActivity} that displays the terminal.
     */
    TerminalView mTerminalView;

    /**
     *  The {@link TerminalViewClient} interface implementation to allow for communication between
     *  {@link TerminalView} and {@link TermuxActivity}.
     */
    TermuxTerminalViewClient mTermuxTerminalViewClient;

    /**
     *  The {@link TerminalSessionClient} interface implementation to allow for communication between
     *  {@link TerminalSession} and {@link TermuxActivity}.
     */
    TermuxTerminalSessionActivityClient mTermuxTerminalSessionActivityClient;

    /**
     * Termux app shared preferences manager.
     */
    private TermuxAppSharedPreferences mPreferences;

    /**
     * Termux app SharedProperties loaded from termux.properties
     */
    private TermuxAppSharedProperties mProperties;

    /**
     * The root view of the {@link TermuxActivity}.
     */
    TermuxActivityRootView mTermuxActivityRootView;

    /**
     * The space at the bottom of {@link @mTermuxActivityRootView} of the {@link TermuxActivity}.
     */
    View mTermuxActivityBottomSpaceView;

    /**
     * The terminal extra keys view.
     */
    ExtraKeysView mExtraKeysView;

    /**
     * The client for the {@link #mExtraKeysView}.
     */
    TermuxTerminalExtraKeys mTermuxTerminalExtraKeys;

    /**
     * The termux sessions list controller.
     */
    TermuxSessionsListViewController mTermuxSessionListViewController;

    /**
     * The {@link TermuxActivity} broadcast receiver for various things like terminal style configuration changes.
     */
    private final BroadcastReceiver mTermuxActivityBroadcastReceiver = new TermuxActivityBroadcastReceiver();

    /**
     * The last toast shown, used cancel current toast before showing new in {@link #showToast(String, boolean)}.
     */
    Toast mLastToast;

    /**
     * If between onResume() and onStop(). Note that only one session is in the foreground of the terminal view at the
     * time, so if the session causing a change is not in the foreground it should probably be treated as background.
     */
    private boolean mIsVisible;

    /**
     * If onResume() was called after onCreate().
     */
    private boolean mIsOnResumeAfterOnCreate = false;

    /**
     * If activity was restarted like due to call to {@link #recreate()} after receiving
     * {@link TERMUX_ACTIVITY#ACTION_RELOAD_STYLE}, system dark night mode was changed or activity
     * was killed by android.
     */
    private boolean mIsActivityRecreated = false;

    /**
     * The {@link TermuxActivity} is in an invalid state and must not be run.
     */
    private boolean mIsInvalidState;

    private int mNavBarHeight;

    private float mTerminalToolbarDefaultHeight;


    private static final int CONTEXT_MENU_SELECT_URL_ID = 0;
    private static final int CONTEXT_MENU_SHARE_TRANSCRIPT_ID = 1;
    private static final int CONTEXT_MENU_SHARE_SELECTED_TEXT = 10;
    private static final int CONTEXT_MENU_AUTOFILL_USERNAME = 11;
    private static final int CONTEXT_MENU_AUTOFILL_PASSWORD = 2;
    private static final int CONTEXT_MENU_RESET_TERMINAL_ID = 3;
    private static final int CONTEXT_MENU_KILL_PROCESS_ID = 4;
    private static final int CONTEXT_MENU_STYLING_ID = 5;
    private static final int CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON = 6;
    private static final int CONTEXT_MENU_HELP_ID = 7;
    private static final int CONTEXT_MENU_SETTINGS_ID = 8;
    private static final int CONTEXT_MENU_REPORT_ID = 9;

    private static final String ARG_TERMINAL_TOOLBAR_TEXT_INPUT = "terminal_toolbar_text_input";
    private static final String ARG_ACTIVITY_RECREATED = "activity_recreated";

    private static final String LOG_TAG = "TermuxActivity";

    /** Intent extra: ask TermuxActivity to request external-storage permission
     * (sent by the Settings → External storage entry). */
    public static final String EXTRA_REQUEST_STORAGE_PERMISSION = "com.termux.storage.request_permission";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        Logger.logDebug(LOG_TAG, "onCreate");
        mIsOnResumeAfterOnCreate = true;

        if (savedInstanceState != null)
            mIsActivityRecreated = savedInstanceState.getBoolean(ARG_ACTIVITY_RECREATED, false);

        // Delete ReportInfo serialized object files from cache older than 14 days
        ReportActivity.deleteReportInfoFilesOlderThanXDays(this, 14, false);

        // Load Termux app SharedProperties from disk
        mProperties = TermuxAppSharedProperties.getProperties();
        reloadProperties();

        setActivityTheme();

        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_termux);

        // Load termux shared preferences
        // This will also fail if TermuxConstants.TERMUX_PACKAGE_NAME does not equal applicationId
        mPreferences = TermuxAppSharedPreferences.build(this, true);
        if (mPreferences == null) {
            // An AlertDialog should have shown to kill the app, so we don't continue running activity code
            mIsInvalidState = true;
            return;
        }

        // Apply the "show welcome message" setting (creates or removes
        // ~/.hushlogin). Also applied in addNewSession() for the very first
        // launch, where the home directory does not exist yet at this point.
        syncWelcomeMessageVisibility(this);

        setMargins();

        mTermuxActivityRootView = findViewById(R.id.activity_termux_root_view);
        mTermuxActivityRootView.setActivity(this);
        mTermuxActivityBottomSpaceView = findViewById(R.id.activity_termux_bottom_space_view);
        mTermuxActivityRootView.setOnApplyWindowInsetsListener(new TermuxActivityRootView.WindowInsetsListener());

        View content = findViewById(android.R.id.content);
        content.setOnApplyWindowInsetsListener((v, insets) -> {
            mNavBarHeight = insets.getSystemWindowInsetBottom();
            return insets;
        });

        if (mProperties.isUsingFullScreen()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }

        setTermuxTerminalViewAndClients();

        setTerminalToolbarView(savedInstanceState);

        setSettingsButtonView();

        setNewSessionButtonView();

        setToggleKeyboardView();

        setDistroButtonView();

        setInstalledDistrosView();

        setDrawerSplitHandle();

        // Update badges: refresh the latest-version cache when it is
        // stale (one hour). The helper no-ops when the cache is fresh or
        // a refresh is already running; the drawer reads the cache.
        AiAgentUtils.refreshLatestVersionsIfStale(null);

        DistroActivity.ensureDistroCommand();

        AiAgentUtils.ensureAiCommand();

        registerForContextMenu(mTerminalView);

        FileReceiverActivity.updateFileReceiverActivityComponentsState(this);

        try {
            // Start the {@link TermuxService} and make it run regardless of who is bound to it
            Intent serviceIntent = new Intent(this, TermuxService.class);
            startService(serviceIntent);

            // Attempt to bind to the service, this will call the {@link #onServiceConnected(ComponentName, IBinder)}
            // callback if it succeeds.
            if (!bindService(serviceIntent, this, 0))
                throw new RuntimeException("bindService() failed");
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG,"TermuxActivity failed to start TermuxService", e);
            Logger.showToast(this,
                getString(e.getMessage() != null && e.getMessage().contains("app is in background") ?
                    R.string.error_termux_service_start_failed_bg : R.string.error_termux_service_start_failed_general),
                true);
            mIsInvalidState = true;
            return;
        }

        // Send the {@link TermuxConstants#BROADCAST_TERMUX_OPENED} broadcast to notify apps that Termux
        // app has been opened.
        TermuxUtils.sendTermuxOpenedBroadcast(this);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // singleTask: a new intent (e.g. the Settings "External storage" entry)
        // arrives here, not as a fresh launch — adopt it so onStart() can see
        // the EXTRA_REQUEST_STORAGE_PERMISSION flag. Without this, getIntent()
        // keeps the original launch intent and the tap does nothing.
        setIntent(intent);
    }

    @Override
    public void onStart() {
        super.onStart();

        Logger.logDebug(LOG_TAG, "onStart");

        if (mIsInvalidState) return;

        mIsVisible = true;

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onStart();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onStart();

        if (mPreferences.isTerminalMarginAdjustmentEnabled())
            addTermuxActivityRootViewGlobalLayoutListener();

        // Settings → External storage entry: the activity is visible again,
        // so the permission dialog can open now. Fires once per extra.
        if (getIntent() != null && getIntent().getBooleanExtra(EXTRA_REQUEST_STORAGE_PERMISSION, false)) {
            getIntent().removeExtra(EXTRA_REQUEST_STORAGE_PERMISSION);
            requestStoragePermission(false);
        }

        registerTermuxActivityBroadcastReceiver();
    }

    @Override
    public void onResume() {
        super.onResume();

        Logger.logVerbose(LOG_TAG, "onResume");

        if (mIsInvalidState) return;

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onResume();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onResume();

        // Check if a crash happened on last run of the app or if a plugin crashed and show a
        // notification with the crash details if it did
        TermuxCrashUtils.notifyAppCrashFromCrashLogFile(this, LOG_TAG);

        // Coming back to the app: refresh the latest-version cache when
        // it is stale, and repaint the drawer badge if the drawer is
        // open. The helper no-ops when the cache is fresh.
        AiAgentUtils.refreshLatestVersionsIfStale(
            () -> refreshAgentsBadgeIfDrawerOpen());

        mIsOnResumeAfterOnCreate = false;
    }

    @Override
    protected void onStop() {
        super.onStop();

        Logger.logDebug(LOG_TAG, "onStop");

        if (mIsInvalidState) return;

        mIsVisible = false;

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onStop();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onStop();

        removeTermuxActivityRootViewGlobalLayoutListener();

        unregisterTermuxActivityBroadcastReceiver();
        getDrawer().closeDrawers();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        Logger.logDebug(LOG_TAG, "onDestroy");

        if (mIsInvalidState) return;

        if (mTermuxService != null) {
            // Do not leave service and session clients with references to activity.
            mTermuxService.unsetTermuxTerminalSessionClient();
            mTermuxService = null;
        }

        try {
            unbindService(this);
        } catch (Exception e) {
            // ignore.
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle savedInstanceState) {
        Logger.logVerbose(LOG_TAG, "onSaveInstanceState");

        super.onSaveInstanceState(savedInstanceState);
        saveTerminalToolbarTextInput(savedInstanceState);
        savedInstanceState.putBoolean(ARG_ACTIVITY_RECREATED, true);
    }





    /**
     * Part of the {@link ServiceConnection} interface. The service is bound with
     * {@link #bindService(Intent, ServiceConnection, int)} in {@link #onCreate(Bundle)} which will cause a call to this
     * callback method.
     */
    @Override
    public void onServiceConnected(ComponentName componentName, IBinder service) {
        Logger.logDebug(LOG_TAG, "onServiceConnected");

        mTermuxService = ((TermuxService.LocalBinder) service).service;

        setTermuxSessionsListView();

        final Intent intent = getIntent();
        setIntent(null);

        if (mTermuxService.isTermuxSessionsEmpty()) {
            if (mIsVisible) {
                TermuxInstaller.setupBootstrapIfNeeded(TermuxActivity.this, () -> {
                    if (mTermuxService == null) return; // Activity might have been destroyed.
                    try {
                        boolean launchFailsafe = false;
                        if (intent != null && intent.getExtras() != null) {
                            launchFailsafe = intent.getExtras().getBoolean(TERMUX_ACTIVITY.EXTRA_FAILSAFE_SESSION, false);
                        }
                        mTermuxTerminalSessionActivityClient.addNewSession(launchFailsafe, null);
                    } catch (WindowManager.BadTokenException e) {
                        // Activity finished - ignore.
                    }
                });
            } else {
                // The service connected while not in foreground - just bail out.
                finishActivityIfNotFinishing();
            }
        } else {
            // If termux was started from launcher "New session" shortcut and activity is recreated,
            // then the original intent will be re-delivered, resulting in a new session being re-added
            // each time.
            if (!mIsActivityRecreated && intent != null && Intent.ACTION_RUN.equals(intent.getAction())) {
                // Android 7.1 app shortcut from res/xml/shortcuts.xml.
                boolean isFailSafe = intent.getBooleanExtra(TERMUX_ACTIVITY.EXTRA_FAILSAFE_SESSION, false);
                mTermuxTerminalSessionActivityClient.addNewSession(isFailSafe, null);
            } else {
                mTermuxTerminalSessionActivityClient.setCurrentSession(mTermuxTerminalSessionActivityClient.getCurrentStoredSessionOrLast());
            }
        }

        // Update the {@link TerminalSession} and {@link TerminalEmulator} clients.
        mTermuxService.setTermuxTerminalSessionClient(mTermuxTerminalSessionActivityClient);
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        Logger.logDebug(LOG_TAG, "onServiceDisconnected");

        // Respect being stopped from the {@link TermuxService} notification action.
        finishActivityIfNotFinishing();
    }






    private void reloadProperties() {
        mProperties.loadTermuxPropertiesFromDisk();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onReloadProperties();
    }



    private void setActivityTheme() {
        // Update NightMode.APP_NIGHT_MODE
        TermuxThemeUtils.setAppNightMode(mProperties.getNightMode());

        // Set activity night mode. If NightMode.SYSTEM is set, then android will automatically
        // trigger recreation of activity when uiMode/dark mode configuration is changed so that
        // day or night theme takes affect.
        AppCompatActivityUtils.setNightMode(this, NightMode.getAppNightMode().getName(), true);
    }

    private void setMargins() {
        RelativeLayout relativeLayout = findViewById(R.id.activity_termux_root_relative_layout);
        int marginHorizontal = mProperties.getTerminalMarginHorizontal();
        int marginVertical = mProperties.getTerminalMarginVertical();
        ViewUtils.setLayoutMarginsInDp(relativeLayout, marginHorizontal, marginVertical, marginHorizontal, marginVertical);
    }



    public void addTermuxActivityRootViewGlobalLayoutListener() {
        getTermuxActivityRootView().getViewTreeObserver().addOnGlobalLayoutListener(getTermuxActivityRootView());
    }

    public void removeTermuxActivityRootViewGlobalLayoutListener() {
        if (getTermuxActivityRootView() != null)
            getTermuxActivityRootView().getViewTreeObserver().removeOnGlobalLayoutListener(getTermuxActivityRootView());
    }



    private void setTermuxTerminalViewAndClients() {
        // Set termux terminal view and session clients
        mTermuxTerminalSessionActivityClient = new TermuxTerminalSessionActivityClient(this);
        mTermuxTerminalViewClient = new TermuxTerminalViewClient(this, mTermuxTerminalSessionActivityClient);

        // Set termux terminal view
        mTerminalView = findViewById(R.id.terminal_view);
        mTerminalView.setTerminalViewClient(mTermuxTerminalViewClient);

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onCreate();

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onCreate();
    }

    private void setTermuxSessionsListView() {
        ListView termuxSessionsListView = findViewById(R.id.terminal_sessions_list);
        mTermuxSessionListViewController = new TermuxSessionsListViewController(this, mTermuxService.getTermuxSessions());
        termuxSessionsListView.setAdapter(mTermuxSessionListViewController);
        termuxSessionsListView.setOnItemClickListener(mTermuxSessionListViewController);
        termuxSessionsListView.setOnItemLongClickListener(mTermuxSessionListViewController);
    }



    private void setTerminalToolbarView(Bundle savedInstanceState) {
        mTermuxTerminalExtraKeys = new TermuxTerminalExtraKeys(this, mTerminalView,
            mTermuxTerminalViewClient, mTermuxTerminalSessionActivityClient);

        final ViewPager terminalToolbarViewPager = getTerminalToolbarViewPager();
        if (mPreferences.shouldShowTerminalToolbar()) terminalToolbarViewPager.setVisibility(View.VISIBLE);

        ViewGroup.LayoutParams layoutParams = terminalToolbarViewPager.getLayoutParams();
        mTerminalToolbarDefaultHeight = layoutParams.height;

        setTerminalToolbarHeight();

        String savedTextInput = null;
        if (savedInstanceState != null)
            savedTextInput = savedInstanceState.getString(ARG_TERMINAL_TOOLBAR_TEXT_INPUT);

        terminalToolbarViewPager.setAdapter(new TerminalToolbarViewPager.PageAdapter(this, savedTextInput));
        terminalToolbarViewPager.addOnPageChangeListener(new TerminalToolbarViewPager.OnPageChangeListener(this, terminalToolbarViewPager));
    }

    private void setTerminalToolbarHeight() {
        final ViewPager terminalToolbarViewPager = getTerminalToolbarViewPager();
        if (terminalToolbarViewPager == null) return;

        ViewGroup.LayoutParams layoutParams = terminalToolbarViewPager.getLayoutParams();
        layoutParams.height = Math.round(mTerminalToolbarDefaultHeight *
            (mTermuxTerminalExtraKeys.getExtraKeysInfo() == null ? 0 : mTermuxTerminalExtraKeys.getExtraKeysInfo().getMatrix().length) *
            mProperties.getTerminalToolbarHeightScaleFactor());
        terminalToolbarViewPager.setLayoutParams(layoutParams);
    }

    public void toggleTerminalToolbar() {
        final ViewPager terminalToolbarViewPager = getTerminalToolbarViewPager();
        if (terminalToolbarViewPager == null) return;

        final boolean showNow = mPreferences.toogleShowTerminalToolbar();
        Logger.showToast(this, (showNow ? getString(R.string.msg_enabling_terminal_toolbar) : getString(R.string.msg_disabling_terminal_toolbar)), true);
        terminalToolbarViewPager.setVisibility(showNow ? View.VISIBLE : View.GONE);
        if (showNow && isTerminalToolbarTextInputViewSelected()) {
            // Focus the text input view if just revealed.
            findViewById(R.id.terminal_toolbar_text_input).requestFocus();
        }
    }

    private void saveTerminalToolbarTextInput(Bundle savedInstanceState) {
        if (savedInstanceState == null) return;

        final EditText textInputView = findViewById(R.id.terminal_toolbar_text_input);
        if (textInputView != null) {
            String textInput = textInputView.getText().toString();
            if (!textInput.isEmpty()) savedInstanceState.putString(ARG_TERMINAL_TOOLBAR_TEXT_INPUT, textInput);
        }
    }



    private void setSettingsButtonView() {
        ImageButton settingsButton = findViewById(R.id.settings_button);
        settingsButton.setOnClickListener(v -> {
            ActivityUtils.startActivity(this, new Intent(this, SettingsActivity.class));
        });
    }

    private void setNewSessionButtonView() {
        View newSessionButton = findViewById(R.id.new_session_button);
        newSessionButton.setOnClickListener(v -> mTermuxTerminalSessionActivityClient.addNewSession(false, null));
        newSessionButton.setOnLongClickListener(v -> {
            TextInputDialogUtils.textInput(TermuxActivity.this, R.string.title_create_named_session, null,
                R.string.action_create_named_session_confirm, text -> mTermuxTerminalSessionActivityClient.addNewSession(false, text),
                R.string.action_new_session_failsafe, text -> mTermuxTerminalSessionActivityClient.addNewSession(true, text),
                -1, null, null);
            return true;
        });
    }

    private void setToggleKeyboardView() {
        findViewById(R.id.toggle_keyboard_button).setOnClickListener(v -> {
            mTermuxTerminalViewClient.onToggleSoftKeyboardRequest();
            getDrawer().closeDrawers();
        });

        findViewById(R.id.toggle_keyboard_button).setOnLongClickListener(v -> {
            toggleTerminalToolbar();
            return true;
        });
    }

    // ------------------------------------------------------------------
    // Drawer sections: collapsible headers + draggable split
    // ------------------------------------------------------------------

    /** Activity-private prefs for the drawer UI state. */
    private static final String DRAWER_UI_PREFS = "drawer_ui";
    private static final String PREF_DRAWER_DISTROS_COLLAPSED = "distros_collapsed";
    private static final String PREF_DRAWER_AGENTS_COLLAPSED = "agents_collapsed";
    private static final String PREF_DRAWER_SESSIONS_HEIGHT_DP = "sessions_height_dp";
    /** Session list height limits for the drag (dp). */
    private static final int DRAWER_SESSIONS_MIN_HEIGHT_DP = 80;
    private static final int DRAWER_LOWER_MIN_HEIGHT_DP = 48;

    private SharedPreferences drawerPrefs() {
        return getSharedPreferences(DRAWER_UI_PREFS, Context.MODE_PRIVATE);
    }

    private boolean isDistrosSectionCollapsed() {
        return drawerPrefs().getBoolean(PREF_DRAWER_DISTROS_COLLAPSED, false);
    }

    private boolean isAgentsSectionCollapsed() {
        return drawerPrefs().getBoolean(PREF_DRAWER_AGENTS_COLLAPSED, false);
    }

    private void setSectionCollapsed(boolean distros, boolean collapsed) {
        drawerPrefs().edit()
            .putBoolean(distros
                ? PREF_DRAWER_DISTROS_COLLAPSED : PREF_DRAWER_AGENTS_COLLAPSED,
                collapsed)
            .apply();
    }

    /** The lower scroll area (and its drag handle) show only when at
     * least one of the two sections is non-empty; the divider between
     * the sections shows only when both are. */
    private void updateLowerScrollVisibility() {
        ScrollView lower = findViewById(R.id.drawer_lower_scroll);
        View handle = findViewById(R.id.drawer_split_handle);
        View divider = findViewById(R.id.drawer_sections_divider);
        LinearLayout distros = findViewById(R.id.installed_distros_container);
        LinearLayout agents = findViewById(R.id.installed_agents_container);
        boolean distrosVisible = distros != null
            && distros.getVisibility() == View.VISIBLE;
        boolean agentsVisible = agents != null
            && agents.getVisibility() == View.VISIBLE;
        boolean anyVisible = distrosVisible || agentsVisible;
        if (lower != null) lower.setVisibility(anyVisible ? View.VISIBLE : View.GONE);
        if (handle != null) handle.setVisibility(anyVisible ? View.VISIBLE : View.GONE);
        if (divider != null) {
            divider.setVisibility(distrosVisible && agentsVisible
                ? View.VISIBLE : View.GONE);
        }
    }

    /** Draggable split between the session list and the lower sections:
     * dragging the handle vertically resizes the session area (the lower
     * area takes the rest). The height is saved in dp and restored on
     * the next open; with no saved height the two areas share the
     * drawer evenly (both weight 1 in the layout). Only vertical drags
     * are handled — a horizontal swipe still closes the drawer. */
    private void setDrawerSplitHandle() {
        View handle = findViewById(R.id.drawer_split_handle);
        ListView sessions = findViewById(R.id.terminal_sessions_list);
        ScrollView lower = findViewById(R.id.drawer_lower_scroll);
        if (handle == null || sessions == null || lower == null) return;
        applySavedDrawerSplit();
        final float[] dragStart = new float[2]; // raw Y, sessions height at down
        handle.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    getDrawer().requestDisallowInterceptTouchEvent(true);
                    dragStart[0] = event.getRawY();
                    dragStart[1] = sessions.getHeight();
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    int total = sessions.getHeight() + lower.getHeight();
                    int min = dpToPx(DRAWER_SESSIONS_MIN_HEIGHT_DP);
                    int max = total - dpToPx(DRAWER_LOWER_MIN_HEIGHT_DP);
                    int height = (int) (dragStart[1] + (event.getRawY() - dragStart[0]));
                    setDrawerSessionsHeight(Math.max(min, Math.min(max, height)));
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    getDrawer().requestDisallowInterceptTouchEvent(false);
                    saveDrawerSessionsHeight(sessions.getHeight());
                    return true;
                default:
                    return false;
            }
        });
    }

    private void setDrawerSessionsHeight(int heightPx) {
        ListView sessions = findViewById(R.id.terminal_sessions_list);
        if (sessions == null) return;
        LinearLayout.LayoutParams params =
            (LinearLayout.LayoutParams) sessions.getLayoutParams();
        params.height = heightPx;
        params.weight = 0;
        sessions.setLayoutParams(params);
    }

    private void applySavedDrawerSplit() {
        int savedDp = drawerPrefs().getInt(PREF_DRAWER_SESSIONS_HEIGHT_DP, -1);
        if (savedDp <= 0) return;
        setDrawerSessionsHeight(dpToPx(savedDp));
    }

    private void saveDrawerSessionsHeight(int heightPx) {
        int dp = Math.round(heightPx / getResources().getDisplayMetrics().density);
        drawerPrefs().edit().putInt(PREF_DRAWER_SESSIONS_HEIGHT_DP, dp).apply();
    }

    private int dpToPx(int dp) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
            getResources().getDisplayMetrics()));
    }

    private void setDistroButtonView() {
        findViewById(R.id.distro_button).setOnClickListener(v -> {
            ActivityUtils.startActivity(this, new Intent(this, DistroActivity.class));
            getDrawer().closeDrawers();
        });
    }

    /** Installed-distros section in the drawer: rebuild on open, tap to enter. */
    private void setInstalledDistrosView() {
        getDrawer().addDrawerListener(new DrawerLayout.SimpleDrawerListener() {
            @Override
            public void onDrawerOpened(View drawerView) {
                refreshInstalledDistrosInDrawer();
                refreshInstalledAgentsInDrawer();
                // A stale update cache refreshes in the background; the
                // badge repaints when it lands (only if still open).
                AiAgentUtils.refreshLatestVersionsIfStale(
                    () -> refreshAgentsBadgeIfDrawerOpen());
            }
        });
        refreshInstalledDistrosInDrawer();
        refreshInstalledAgentsInDrawer();
    }

    private void refreshInstalledDistrosInDrawer() {
        LinearLayout container = findViewById(R.id.installed_distros_container);
        LinearLayout rows = findViewById(R.id.installed_distros_rows);
        if (container == null || rows == null) return;
        rows.removeAllViews();
        List<DistroUtils.InstalledDistro> installed = DistroUtils.getInstalledDistros();
        if (installed.isEmpty()) {
            container.setVisibility(View.GONE);
            updateLowerScrollVisibility();
            return;
        }
        container.setVisibility(View.VISIBLE);
        // Collapsible header: title + count stay visible when folded,
        // the rows hide. The state is remembered across drawer opens.
        boolean collapsed = isDistrosSectionCollapsed();
        TextView arrow = findViewById(R.id.installed_distros_arrow);
        TextView count = findViewById(R.id.installed_distros_count);
        LinearLayout header = findViewById(R.id.installed_distros_header);
        if (arrow != null) arrow.setText(collapsed ? "▸" : "▾");
        if (count != null) count.setText("(" + installed.size() + ")");
        if (header != null) {
            header.setOnClickListener(v -> {
                setSectionCollapsed(true, !isDistrosSectionCollapsed());
                refreshInstalledDistrosInDrawer();
            });
        }
        rows.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        updateLowerScrollVisibility();
        if (collapsed) return;
        int shown = Math.min(installed.size(), DistroUtils.MAX_DRAWER_DISTRO_ROWS);
        for (int i = 0; i < shown; i++) {
            final DistroUtils.InstalledDistro distro = installed.get(i);
            TextView row = newInstalledDistroRow(rows);
            row.setText(distro.display);
            row.setOnClickListener(v -> {
                getDrawer().closeDrawers();
                DistroUtils.startLoginSession(TermuxActivity.this, distro.container, distro.display);
            });
            row.setLongClickable(true);
            row.setOnLongClickListener(v -> {
                openDistroForContainer(distro.container);
                return true;
            });
            rows.addView(row);
        }
        if (installed.size() > DistroUtils.MAX_DRAWER_DISTRO_ROWS) {
            TextView moreRow = newInstalledDistroRow(rows);
            moreRow.setText(getString(R.string.distro_more_in_drawer,
                installed.size() - DistroUtils.MAX_DRAWER_DISTRO_ROWS));
            moreRow.setOnClickListener(v -> {
                ActivityUtils.startActivity(this, new Intent(this, DistroActivity.class));
                getDrawer().closeDrawers();
            });
            rows.addView(moreRow);
        }
    }


    /** "AI 助手" section in the drawer: one row per installed agent across
     * all distros ("Codex · Ubuntu 26.04"). Tap opens a terminal session
     * that runs the agent directly inside its container; more than 5 rows
     * get an overflow row that opens the distro list. Hidden when no agent
     * is installed anywhere. Detection is a rootfs binary scan (no session),
     * the same cost class as the distro scan above; the update badge adds
     * one small version-file read per agent plus the cached latest map
     * (no network on drawer open — a stale cache refreshes in the
     * background and repaints the badge when it lands). */
    private void refreshInstalledAgentsInDrawer() {
        LinearLayout container = findViewById(R.id.installed_agents_container);
        LinearLayout rows = findViewById(R.id.installed_agents_rows);
        if (container == null || rows == null) return;
        rows.removeAllViews();
        List<AiAgentUtils.InstalledAgent> installed = AiAgentUtils.detectInstalledAgents();
        if (installed.isEmpty()) {
            container.setVisibility(View.GONE);
            updateAgentsBadge(0);
            updateLowerScrollVisibility();
            return;
        }
        container.setVisibility(View.VISIBLE);
        boolean collapsed = isAgentsSectionCollapsed();
        TextView arrow = findViewById(R.id.installed_agents_arrow);
        TextView count = findViewById(R.id.installed_agents_count);
        LinearLayout header = findViewById(R.id.installed_agents_header);
        if (arrow != null) arrow.setText(collapsed ? "▸" : "▾");
        if (count != null) count.setText("(" + installed.size() + ")");
        if (header != null) {
            header.setOnClickListener(v -> {
                setSectionCollapsed(false, !isAgentsSectionCollapsed());
                refreshInstalledAgentsInDrawer();
            });
        }
        rows.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        updateLowerScrollVisibility();
        AiAgentUtils.UpdateCache cache = AiAgentUtils.loadUpdateCache();
        int shown = Math.min(installed.size(), AiAgentUtils.MAX_DRAWER_AGENT_ROWS);
        int updates = 0;
        for (int i = 0; i < installed.size(); i++) {
            final AiAgentUtils.InstalledAgent agent = installed.get(i);
            final String installedVersion = agentInstalledVersion(agent);
            final String latestVersion = cache.latest.get(agent.agentId);
            final boolean hasUpdate = AiAgentUtils.isNewerVersionAvailable(
                installedVersion, latestVersion);
            if (hasUpdate) updates++;
            if (collapsed || i >= shown) continue;
            newAgentRow(rows, agent, hasUpdate, installedVersion, latestVersion);
        }
        if (!collapsed && installed.size() > AiAgentUtils.MAX_DRAWER_AGENT_ROWS) {
            TextView moreRow = newInstalledDistroRow(rows);
            moreRow.setText(getString(R.string.distro_more_in_drawer,
                installed.size() - AiAgentUtils.MAX_DRAWER_AGENT_ROWS));
            moreRow.setOnClickListener(v -> {
                ActivityUtils.startActivity(this, new Intent(this, DistroActivity.class));
                getDrawer().closeDrawers();
            });
            rows.addView(moreRow);
        }
        updateAgentsBadge(updates);
    }

    /** Installed version of one drawer agent row (local file reads only),
     * or "" when unknown. agy reads the version recorded at install time
     * (~/.termux-aiagents/versions/agy), so an agy installed before that
     * record existed still shows "版本未知" until its next install/update. */
    private String agentInstalledVersion(AiAgentUtils.InstalledAgent agent) {
        AiAgentUtils.AgentDef def = AiAgentUtils.findDef(agent.agentId);
        if (def == null) return "";
        File rootfs = DistroUtils.containerRootfs(agent.container);
        return rootfs == null ? "" : AiAgentUtils.probeVersion(rootfs, def);
    }

    /** "N 可更新" pill on the AI-assistant header; hidden when nothing
     * has an update. The count covers every installed agent, not just
     * the rows shown (the overflow row hides the rest). */
    private void updateAgentsBadge(int updates) {
        TextView badge = findViewById(R.id.installed_agents_badge);
        if (badge == null) return;
        if (updates <= 0) {
            badge.setVisibility(View.GONE);
            return;
        }
        badge.setText(getString(R.string.drawer_ai_updates_count, updates));
        badge.setVisibility(View.VISIBLE);
    }

    /** Repaint the agents section when a background update refresh lands,
     * but only while the drawer is open (otherwise the next open reads
     * the fresh cache anyway). */
    private void refreshAgentsBadgeIfDrawerOpen() {
        if (getDrawer().isDrawerOpen(Gravity.LEFT)) {
            refreshInstalledAgentsInDrawer();
        }
    }

    /** One drawer AI-assistant row: the name on the left and, when an
     * update is available, a tappable "可更新 ↑" pill on the right.
     * A second, smaller line under the name shows the installed version
     * ("1.6.0", "1.6.0 · 有新版本 1.7.0", or "版本未知" when the version
     * cannot be read). The version line is display-only — tapping it
     * does the same as tapping the name — and the pill is untouched.
     * Tapping the name enters the agent (as before); tapping the pill
     * updates it. The row background matches the distro rows. */
    private void newAgentRow(LinearLayout rows, final AiAgentUtils.InstalledAgent agent,
                             boolean hasUpdate, final String installedVersion,
                             final String latestVersion) {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.HORIZONTAL);
        container.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (ThemeUtils.shouldEnableDarkTheme(this, NightMode.getAppNightMode().getName())) {
            container.setBackground(ContextCompat.getDrawable(this,
                R.drawable.session_background_black_selected));
        } else {
            container.setBackground(ContextCompat.getDrawable(this,
                R.drawable.session_background_selected));
        }
        LinearLayout textColumn = new LinearLayout(this);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        textColumn.setLayoutParams(new LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView name = (TextView) getLayoutInflater()
            .inflate(R.layout.item_terminal_sessions_list, textColumn, false);
        name.setBackground(null);
        name.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        String rowText = getString(R.string.aiagents_drawer_row,
            agent.displayName, agent.distroDisplay);
        if (AiAgentUtils.hasWeb(agent.agentId)) {
            rowText += " · " + getString(R.string.aiagents_web_open);
        }
        name.setText(rowText);
        TextView version = new TextView(this);
        version.setTextSize(12);
        TypedValue secondaryColor = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.textColorSecondary, secondaryColor, true);
        version.setTextColor(secondaryColor.data);
        version.setPadding(dpToPx(6), 0, 0, dpToPx(2));
        if (hasUpdate) {
            // The "已安装" prefix of aiagents_installed_with_update would
            // repeat what the section already says (every row here IS
            // installed), so the drawer has its own shorter wording.
            version.setText(getString(R.string.aiagents_drawer_version_with_update,
                installedVersion, latestVersion));
        } else if (installedVersion == null || installedVersion.isEmpty()) {
            version.setText(R.string.aiagents_version_unknown);
        } else {
            version.setText(installedVersion);
        }
        View.OnClickListener enterListener = v -> {
            getDrawer().closeDrawers();
            if (AiAgentUtils.hasTerminalEntry(agent.agentId)) {
                AiAgentUtils.startAgentSession(TermuxActivity.this, agent);
            } else {
                // dsh has no terminal entry (upstream documents only
                // `dsh web`): tapping the row opens its web UI instead.
                AiAgentUtils.startWebSession(TermuxActivity.this, agent, null);
            }
        };
        View.OnLongClickListener menuListener = v -> {
            showAgentDrawerMenu(agent);
            return true;
        };
        name.setOnClickListener(enterListener);
        version.setOnClickListener(enterListener);
        name.setLongClickable(true);
        version.setLongClickable(true);
        name.setOnLongClickListener(menuListener);
        version.setOnLongClickListener(menuListener);
        textColumn.addView(name);
        textColumn.addView(version);
        container.addView(textColumn);
        if (hasUpdate) {
            TextView label = new TextView(this);
            label.setText(R.string.aiagents_update_available);
            label.setTextSize(12);
            label.setTextColor(ContextCompat.getColor(this, android.R.color.white));
            label.setBackground(ContextCompat.getDrawable(this,
                R.drawable.drawer_update_badge));
            label.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
            labelParams.setMarginEnd(dpToPx(12));
            label.setLayoutParams(labelParams);
            label.setOnClickListener(v -> showAgentUpdateDialog(
                agent, installedVersion, latestVersion));
            container.addView(label);
        }
        rows.addView(container);
    }

    /** Confirm dialog for a drawer "可更新 ↑" pill: same wording as the
     * AI-assistant screen's update dialog, then the update runs there
     * via the auto-update extra (the user already confirmed here). */
    private void showAgentUpdateDialog(final AiAgentUtils.InstalledAgent agent,
                                       String installedVersion, String latestVersion) {
        AiAgentUtils.AgentDef def = AiAgentUtils.findDef(agent.agentId);
        if (def == null) return;
        String message = getString(R.string.aiagents_update_confirm, agent.displayName);
        if (AiAgentUtils.isNewerVersionAvailable(installedVersion, latestVersion)) {
            message += "\n" + getString(R.string.aiagents_update_new_version,
                latestVersion, installedVersion);
        }
        if ("dsh".equals(agent.agentId)) {
            message += "\n" + getString(R.string.aiagents_update_dsh_note);
        }
        new AlertDialog.Builder(this)
            .setTitle(getString(R.string.aiagents_update_title, agent.displayName))
            .setMessage(message)
            .setPositiveButton(R.string.aiagents_update, (dialog, which) -> {
                getDrawer().closeDrawers();
                Intent intent = new Intent(this, AiAgentsActivity.class);
                intent.putExtra(AiAgentsActivity.EXTRA_CONTAINER_NAME, agent.container);
                intent.putExtra(AiAgentsActivity.EXTRA_DISTRO_ID, agent.distroId);
                intent.putExtra(AiAgentsActivity.EXTRA_DISPLAY_NAME, agent.distroDisplay);
                intent.putExtra(AiAgentsActivity.EXTRA_AUTO_UPDATE_AGENT_ID, agent.agentId);
                ActivityUtils.startActivity(this, intent);
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /** Long-press menu on a drawer "AI 助手" row: enter the terminal, or
     * (web-capable agents only) open the web UI through the shared
     * dialog-first startWebSession flow. dsh has no terminal entry
     * (upstream documents only `dsh web`), so its menu shows only the web
     * item. The drawer has nothing to finish, so the onDialogDismissed
     * callback is null (startWebSession documents null as fine). */
    private void showAgentDrawerMenu(final AiAgentUtils.InstalledAgent agent) {
        List<String> names = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (AiAgentUtils.hasTerminalEntry(agent.agentId)) {
            names.add(getString(R.string.aiagents_drawer_enter_terminal));
            actions.add(() -> {
                getDrawer().closeDrawers();
                AiAgentUtils.startAgentSession(TermuxActivity.this, agent);
            });
        }
        if (AiAgentUtils.hasWeb(agent.agentId)) {
            names.add(getString(R.string.aiagents_web_quick_item, agent.displayName));
            actions.add(() -> {
                getDrawer().closeDrawers();
                AiAgentUtils.startWebSession(TermuxActivity.this, agent, null);
            });
        }
        new AlertDialog.Builder(this)
            .setTitle(getString(R.string.aiagents_drawer_row,
                agent.displayName, agent.distroDisplay))
            .setItems(names.toArray(new String[0]), (dialog, which) -> actions.get(which).run())
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /** One drawer row (installed distros and installed AI assistants):
     * same layout as a session row, but with the dark background the session
     * list applies at runtime. The layout's own background is a white ripple,
     * which renders as a solid white box with invisible white text on the
     * dark drawer. */
    private TextView newInstalledDistroRow(LinearLayout rows) {
        TextView row = (TextView) getLayoutInflater()
            .inflate(R.layout.item_terminal_sessions_list, rows, false);
        if (ThemeUtils.shouldEnableDarkTheme(this, NightMode.getAppNightMode().getName())) {
            row.setBackground(ContextCompat.getDrawable(this,
                R.drawable.session_background_black_selected));
        }
        return row;
    }

    /** Long-press on a drawer distro row: open DistroActivity at that distro's options dialog. */
    private void openDistroForContainer(String container) {
        Intent intent = new Intent(this, DistroActivity.class);
        intent.putExtra(DistroActivity.EXTRA_CONTAINER_NAME, container);
        ActivityUtils.startActivity(this, intent);
        getDrawer().closeDrawers();
    }





    @SuppressLint("RtlHardcoded")
    @Override
    public void onBackPressed() {
        if (getDrawer().isDrawerOpen(Gravity.LEFT)) {
            getDrawer().closeDrawers();
        } else {
            finishActivityIfNotFinishing();
        }
    }

    public void finishActivityIfNotFinishing() {
        // prevent duplicate calls to finish() if called from multiple places
        if (!TermuxActivity.this.isFinishing()) {
            finish();
        }
    }

    /** Show a toast and dismiss the last one if still visible. */
    public void showToast(String text, boolean longDuration) {
        if (text == null || text.isEmpty()) return;
        if (mLastToast != null) mLastToast.cancel();
        mLastToast = Toast.makeText(TermuxActivity.this, text, longDuration ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT);
        mLastToast.setGravity(Gravity.TOP, 0, 0);
        mLastToast.show();
    }



    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        TerminalSession currentSession = getCurrentSession();
        if (currentSession == null) return;

        boolean autoFillEnabled = mTerminalView.isAutoFillEnabled();

        menu.add(Menu.NONE, CONTEXT_MENU_SELECT_URL_ID, Menu.NONE, R.string.action_select_url);
        menu.add(Menu.NONE, CONTEXT_MENU_SHARE_TRANSCRIPT_ID, Menu.NONE, R.string.action_share_transcript);
        if (!DataUtils.isNullOrEmpty(mTerminalView.getStoredSelectedText()))
            menu.add(Menu.NONE, CONTEXT_MENU_SHARE_SELECTED_TEXT, Menu.NONE, R.string.action_share_selected_text);
        if (autoFillEnabled)
            menu.add(Menu.NONE, CONTEXT_MENU_AUTOFILL_USERNAME, Menu.NONE, R.string.action_autofill_username);
        if (autoFillEnabled)
            menu.add(Menu.NONE, CONTEXT_MENU_AUTOFILL_PASSWORD, Menu.NONE, R.string.action_autofill_password);
        menu.add(Menu.NONE, CONTEXT_MENU_RESET_TERMINAL_ID, Menu.NONE, R.string.action_reset_terminal);
        menu.add(Menu.NONE, CONTEXT_MENU_KILL_PROCESS_ID, Menu.NONE, getResources().getString(R.string.action_kill_process, getCurrentSession().getPid())).setEnabled(currentSession.isRunning());
        menu.add(Menu.NONE, CONTEXT_MENU_STYLING_ID, Menu.NONE, R.string.action_style_terminal);
        menu.add(Menu.NONE, CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON, Menu.NONE, R.string.action_toggle_keep_screen_on).setCheckable(true).setChecked(mPreferences.shouldKeepScreenOn());
        menu.add(Menu.NONE, CONTEXT_MENU_HELP_ID, Menu.NONE, R.string.action_open_help);
        menu.add(Menu.NONE, CONTEXT_MENU_SETTINGS_ID, Menu.NONE, R.string.action_open_settings);
        menu.add(Menu.NONE, CONTEXT_MENU_REPORT_ID, Menu.NONE, R.string.action_report_issue);
    }

    /** Hook system menu to show context menu instead. */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        mTerminalView.showContextMenu();
        return false;
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        TerminalSession session = getCurrentSession();

        switch (item.getItemId()) {
            case CONTEXT_MENU_SELECT_URL_ID:
                mTermuxTerminalViewClient.showUrlSelection();
                return true;
            case CONTEXT_MENU_SHARE_TRANSCRIPT_ID:
                mTermuxTerminalViewClient.shareSessionTranscript();
                return true;
            case CONTEXT_MENU_SHARE_SELECTED_TEXT:
                mTermuxTerminalViewClient.shareSelectedText();
                return true;
            case CONTEXT_MENU_AUTOFILL_USERNAME:
                mTerminalView.requestAutoFillUsername();
                return true;
            case CONTEXT_MENU_AUTOFILL_PASSWORD:
                mTerminalView.requestAutoFillPassword();
                return true;
            case CONTEXT_MENU_RESET_TERMINAL_ID:
                onResetTerminalSession(session);
                return true;
            case CONTEXT_MENU_KILL_PROCESS_ID:
                showKillSessionDialog(session);
                return true;
            case CONTEXT_MENU_STYLING_ID:
                showStylingDialog();
                return true;
            case CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON:
                toggleKeepScreenOn();
                return true;
            case CONTEXT_MENU_HELP_ID:
                ActivityUtils.startActivity(this, new Intent(this, HelpActivity.class));
                return true;
            case CONTEXT_MENU_SETTINGS_ID:
                ActivityUtils.startActivity(this, new Intent(this, SettingsActivity.class));
                return true;
            case CONTEXT_MENU_REPORT_ID:
                mTermuxTerminalViewClient.reportIssueFromTranscript();
                return true;
            default:
                return super.onContextItemSelected(item);
        }
    }

    @Override
    public void onContextMenuClosed(Menu menu) {
        super.onContextMenuClosed(menu);
        // onContextMenuClosed() is triggered twice if back button is pressed to dismiss instead of tap for some reason
        mTerminalView.onContextMenuClosed(menu);
    }

    private void showKillSessionDialog(TerminalSession session) {
        if (session == null) return;

        final AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setIcon(android.R.drawable.ic_dialog_alert);
        b.setMessage(R.string.title_confirm_kill_process);
        b.setPositiveButton(android.R.string.yes, (dialog, id) -> {
            dialog.dismiss();
            session.finishIfRunning();
        });
        b.setNegativeButton(android.R.string.no, null);
        b.show();
    }

    private void onResetTerminalSession(TerminalSession session) {
        if (session != null) {
            session.reset();
            showToast(getResources().getString(R.string.msg_terminal_reset), true);

            if (mTermuxTerminalSessionActivityClient != null)
                mTermuxTerminalSessionActivityClient.onResetTerminalSession();
        }
    }

    private void showStylingDialog() {
        ActivityUtils.startActivity(this, new Intent(this, TermuxStyleActivity.class));
    }
    private void toggleKeepScreenOn() {
        if (mTerminalView.getKeepScreenOn()) {
            mTerminalView.setKeepScreenOn(false);
            mPreferences.setKeepScreenOn(false);
        } else {
            mTerminalView.setKeepScreenOn(true);
            mPreferences.setKeepScreenOn(true);
        }
    }



    /**
     * For processes to access primary external storage (/sdcard, /storage/emulated/0, ~/storage/shared),
     * termux needs to be granted legacy WRITE_EXTERNAL_STORAGE or MANAGE_EXTERNAL_STORAGE permissions
     * if targeting targetSdkVersion 30 (android 11) and running on sdk 30 (android 11) and higher.
     */
    public void requestStoragePermission(boolean isPermissionCallback) {
        new Thread() {
            @Override
            public void run() {
                // Do not ask for permission again
                int requestCode = isPermissionCallback ? -1 : PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION;

                // If permission is granted, then also setup storage symlinks.
                if(PermissionUtils.checkAndRequestLegacyOrManageExternalStoragePermission(
                    TermuxActivity.this, requestCode, true, !isPermissionCallback)) {
                    if (isPermissionCallback)
                        Logger.logInfoAndShowToast(TermuxActivity.this, LOG_TAG,
                            getString(com.termux.shared.R.string.msg_storage_permission_granted_on_request));

                    TermuxInstaller.setupStorageSymlinks(TermuxActivity.this);
                } else {
                    if (isPermissionCallback)
                        Logger.logInfoAndShowToast(TermuxActivity.this, LOG_TAG,
                            getString(com.termux.shared.R.string.msg_storage_permission_not_granted_on_request));
                }
            }
        }.start();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Logger.logVerbose(LOG_TAG, "onActivityResult: requestCode: " + requestCode + ", resultCode: "  + resultCode + ", data: "  + IntentUtils.getIntentString(data));
        if (requestCode == PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION) {
            requestStoragePermission(true);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        Logger.logVerbose(LOG_TAG, "onRequestPermissionsResult: requestCode: " + requestCode + ", permissions: "  + Arrays.toString(permissions) + ", grantResults: "  + Arrays.toString(grantResults));
        if (requestCode == PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION) {
            requestStoragePermission(true);
        }
    }



    public int getNavBarHeight() {
        return mNavBarHeight;
    }

    public TermuxActivityRootView getTermuxActivityRootView() {
        return mTermuxActivityRootView;
    }

    public View getTermuxActivityBottomSpaceView() {
        return mTermuxActivityBottomSpaceView;
    }

    public ExtraKeysView getExtraKeysView() {
        return mExtraKeysView;
    }

    public TermuxTerminalExtraKeys getTermuxTerminalExtraKeys() {
        return mTermuxTerminalExtraKeys;
    }

    public void setExtraKeysView(ExtraKeysView extraKeysView) {
        mExtraKeysView = extraKeysView;
    }

    public DrawerLayout getDrawer() {
        return (DrawerLayout) findViewById(R.id.drawer_layout);
    }


    public ViewPager getTerminalToolbarViewPager() {
        return (ViewPager) findViewById(R.id.terminal_toolbar_view_pager);
    }

    public float getTerminalToolbarDefaultHeight() {
        return mTerminalToolbarDefaultHeight;
    }

    public boolean isTerminalViewSelected() {
        return getTerminalToolbarViewPager().getCurrentItem() == 0;
    }

    public boolean isTerminalToolbarTextInputViewSelected() {
        return getTerminalToolbarViewPager().getCurrentItem() == 1;
    }


    public void termuxSessionListNotifyUpdated() {
        mTermuxSessionListViewController.notifyDataSetChanged();
    }

    public boolean isVisible() {
        return mIsVisible;
    }

    public boolean isOnResumeAfterOnCreate() {
        return mIsOnResumeAfterOnCreate;
    }

    public boolean isActivityRecreated() {
        return mIsActivityRecreated;
    }



    public TermuxService getTermuxService() {
        return mTermuxService;
    }

    public TerminalView getTerminalView() {
        return mTerminalView;
    }

    public TermuxTerminalViewClient getTermuxTerminalViewClient() {
        return mTermuxTerminalViewClient;
    }

    public TermuxTerminalSessionActivityClient getTermuxTerminalSessionClient() {
        return mTermuxTerminalSessionActivityClient;
    }

    @Nullable
    public TerminalSession getCurrentSession() {
        if (mTerminalView != null)
            return mTerminalView.getCurrentSession();
        else
            return null;
    }

    /**
     * Apply the "show welcome message at startup" setting.
     *
     * The welcome text of a fresh session is shown by the {@code login}
     * script (termux-tools), which displays the MOTD unless
     * {@code ~/.hushlogin} exists. Truncating {@code $PREFIX/etc/motd}
     * alone is NOT enough: the default MOTD path is the dynamic
     * {@code $PREFIX/etc/motd.sh} script, so the only reliable switch is
     * the hushlogin file itself. It survives {@code pkg upgrade} (it lives
     * in HOME, not in the package tree) and destroys nothing, so no backup
     * is needed: showing the message again is just deleting the file.
     *
     * Called from {@link #onCreate(Bundle)} and from
     * {@link TermuxTerminalSessionActivityClient#addNewSession(boolean, String)}
     * so the file state matches the setting before any new session's login
     * script runs. Silently does nothing when the home directory does not
     * exist yet (first launch, before the bootstrap is installed).
     */
    public static void syncWelcomeMessageVisibility(@NonNull Context context) {
        if (!TermuxConstants.TERMUX_HOME_DIR.isDirectory()) return;

        boolean showWelcomeMessage = PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean("show_welcome_message", false);
        File hushloginFile = new File(TermuxConstants.TERMUX_HOME_DIR, ".hushlogin");

        try {
            if (showWelcomeMessage) {
                if (hushloginFile.exists()) hushloginFile.delete();
            } else {
                if (!hushloginFile.exists()) hushloginFile.createNewFile();
            }
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to update ~/.hushlogin for welcome message setting", e);
        }
    }

    public TermuxAppSharedPreferences getPreferences() {
        return mPreferences;
    }

    public TermuxAppSharedProperties getProperties() {
        return mProperties;
    }




    public static void updateTermuxActivityStyling(Context context, boolean recreateActivity) {
        // Make sure that terminal styling is always applied.
        Intent stylingIntent = new Intent(TERMUX_ACTIVITY.ACTION_RELOAD_STYLE);
        stylingIntent.putExtra(TERMUX_ACTIVITY.EXTRA_RECREATE_ACTIVITY, recreateActivity);
        context.sendBroadcast(stylingIntent);
    }

    private void registerTermuxActivityBroadcastReceiver() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(TERMUX_ACTIVITY.ACTION_NOTIFY_APP_CRASH);
        intentFilter.addAction(TERMUX_ACTIVITY.ACTION_RELOAD_STYLE);
        intentFilter.addAction(TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS);

        registerReceiver(mTermuxActivityBroadcastReceiver, intentFilter);
    }

    private void unregisterTermuxActivityBroadcastReceiver() {
        unregisterReceiver(mTermuxActivityBroadcastReceiver);
    }

    private void fixTermuxActivityBroadcastReceiverIntent(Intent intent) {
        if (intent == null) return;

        String extraReloadStyle = intent.getStringExtra(TERMUX_ACTIVITY.EXTRA_RELOAD_STYLE);
        if ("storage".equals(extraReloadStyle)) {
            intent.removeExtra(TERMUX_ACTIVITY.EXTRA_RELOAD_STYLE);
            intent.setAction(TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS);
        }
    }

    class TermuxActivityBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;

            if (mIsVisible) {
                fixTermuxActivityBroadcastReceiverIntent(intent);

                switch (intent.getAction()) {
                    case TERMUX_ACTIVITY.ACTION_NOTIFY_APP_CRASH:
                        Logger.logDebug(LOG_TAG, "Received intent to notify app crash");
                        TermuxCrashUtils.notifyAppCrashFromCrashLogFile(context, LOG_TAG);
                        return;
                    case TERMUX_ACTIVITY.ACTION_RELOAD_STYLE:
                        Logger.logDebug(LOG_TAG, "Received intent to reload styling");
                        reloadActivityStyling(intent.getBooleanExtra(TERMUX_ACTIVITY.EXTRA_RECREATE_ACTIVITY, true));
                        return;
                    case TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS:
                        Logger.logDebug(LOG_TAG, "Received intent to request storage permissions");
                        requestStoragePermission(false);
                        return;
                    default:
                }
            }
        }
    }

    private void reloadActivityStyling(boolean recreateActivity) {
        if (mProperties != null) {
            reloadProperties();

            if (mExtraKeysView != null) {
                mExtraKeysView.setButtonTextAllCaps(mProperties.shouldExtraKeysTextBeAllCaps());
                mExtraKeysView.reload(mTermuxTerminalExtraKeys.getExtraKeysInfo(), mTerminalToolbarDefaultHeight);
            }

            // Update NightMode.APP_NIGHT_MODE
            TermuxThemeUtils.setAppNightMode(mProperties.getNightMode());
        }

        setMargins();
        setTerminalToolbarHeight();

        FileReceiverActivity.updateFileReceiverActivityComponentsState(this);

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onReloadActivityStyling();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onReloadActivityStyling();

        // To change the activity and drawer theme, activity needs to be recreated.
        // It will destroy the activity, including all stored variables and views, and onCreate()
        // will be called again. Extra keys input text, terminal sessions and transcripts will be preserved.
        if (recreateActivity) {
            Logger.logDebug(LOG_TAG, "Recreating activity");
            TermuxActivity.this.recreate();
        }
    }



    public static void startTermuxActivity(@NonNull final Context context) {
        ActivityUtils.startActivity(context, newInstance(context));
    }

    public static Intent newInstance(@NonNull final Context context) {
        Intent intent = new Intent(context, TermuxActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

}
