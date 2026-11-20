package com.termux.app.terminal;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.StyleSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.shared.theme.NightMode;
import com.termux.shared.theme.ThemeUtils;
import com.termux.terminal.TerminalSession;

import java.util.ArrayList;
import java.util.List;

public class TermuxSessionsListViewController extends ArrayAdapter<TermuxSession> implements AdapterView.OnItemClickListener, AdapterView.OnItemLongClickListener {

    final TermuxActivity mActivity;

    final StyleSpan boldSpan = new StyleSpan(Typeface.BOLD);
    final StyleSpan italicSpan = new StyleSpan(Typeface.ITALIC);

    public TermuxSessionsListViewController(TermuxActivity activity, List<TermuxSession> sessionList) {
        super(activity.getApplicationContext(), R.layout.item_terminal_sessions_list, sessionList);
        this.mActivity = activity;
    }

    @SuppressLint("SetTextI18n")
    @NonNull
    @Override
    public View getView(int position, View convertView, @NonNull ViewGroup parent) {
        View sessionRowView = convertView;
        if (sessionRowView == null) {
            LayoutInflater inflater = mActivity.getLayoutInflater();
            sessionRowView = inflater.inflate(R.layout.item_terminal_sessions_list, parent, false);
        }

        TextView sessionTitleView = sessionRowView.findViewById(R.id.session_title);

        TerminalSession sessionAtRow = getItem(position).getTerminalSession();
        if (sessionAtRow == null) {
            sessionTitleView.setText("null session");
            return sessionRowView;
        }

        boolean shouldEnableDarkTheme = ThemeUtils.shouldEnableDarkTheme(mActivity, NightMode.getAppNightMode().getName());

        if (shouldEnableDarkTheme) {
            sessionTitleView.setBackground(
                ContextCompat.getDrawable(mActivity, R.drawable.session_background_black_selected)
            );
        }

        String name = sessionAtRow.mSessionName;
        String sessionTitle = sessionAtRow.getTitle();

        String numberPart = "[" + (position + 1) + "] ";
        String sessionNamePart = (TextUtils.isEmpty(name) ? "" : name);
        String sessionTitlePart = (TextUtils.isEmpty(sessionTitle) ? "" : ((sessionNamePart.isEmpty() ? "" : "\n") + sessionTitle));

        String fullSessionTitle = numberPart + sessionNamePart + sessionTitlePart;
        SpannableString fullSessionTitleStyled = new SpannableString(fullSessionTitle);
        fullSessionTitleStyled.setSpan(boldSpan, 0, numberPart.length() + sessionNamePart.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        fullSessionTitleStyled.setSpan(italicSpan, numberPart.length() + sessionNamePart.length(), fullSessionTitle.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        sessionTitleView.setText(fullSessionTitleStyled);

        boolean sessionRunning = sessionAtRow.isRunning();

        if (sessionRunning) {
            sessionTitleView.setPaintFlags(sessionTitleView.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
        } else {
            sessionTitleView.setPaintFlags(sessionTitleView.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
        }
        int defaultColor = shouldEnableDarkTheme ? Color.WHITE : Color.BLACK;
        int color = sessionRunning || sessionAtRow.getExitStatus() == 0 ? defaultColor : Color.RED;
        sessionTitleView.setTextColor(color);
        return sessionRowView;
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        TermuxSession clickedSession = getItem(position);
        mActivity.getTermuxTerminalSessionClient().setCurrentSession(clickedSession.getTerminalSession());
        mActivity.getDrawer().closeDrawers();
    }

    @Override
    public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
        final TermuxSession selectedSession = getItem(position);
        if (selectedSession == null) return true;
        final TerminalSession terminalSession = selectedSession.getTerminalSession();
        if (terminalSession == null) return true;

        List<String> names = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        names.add(mActivity.getString(R.string.session_menu_rename));
        actions.add(() -> mActivity.getTermuxTerminalSessionClient().renameSession(terminalSession));
        names.add(mActivity.getString(R.string.session_menu_delete));
        actions.add(() -> showDeleteSessionDialog(terminalSession));

        // Title is the session's display name ("[2] Ubuntu 26.10"); may be
        // null when the service is gone, in which case no title is shown.
        String title = mActivity.getTermuxTerminalSessionClient().toToastTitle(terminalSession);
        new AlertDialog.Builder(mActivity)
            .setTitle(title)
            .setItems(names.toArray(new String[0]), (dialog, which) -> actions.get(which).run())
            .setNegativeButton(android.R.string.cancel, null)
            .show();
        return true;
    }

    /** Confirm before deleting a session (mirrors the "kill process" dialog). */
    private void showDeleteSessionDialog(final TerminalSession sessionToDelete) {
        if (sessionToDelete == null) return;

        new AlertDialog.Builder(mActivity)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setTitle(R.string.session_menu_delete)
            .setMessage(R.string.session_delete_confirm_message)
            .setPositiveButton(android.R.string.yes, (dialog, id) -> {
                dialog.dismiss();
                mActivity.getTermuxTerminalSessionClient().deleteSession(sessionToDelete);
            })
            .setNegativeButton(android.R.string.no, null)
            .show();
    }

}
