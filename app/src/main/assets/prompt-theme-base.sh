# prompt-theme-base.sh — managed by termux-app-zh (Settings > 命令行样式).
# This file is the template for ~/.termux/prompt-theme.sh: PromptStyleActivity
# copies it and substitutes __THEME_ID__ (a number), __ACCENT_RGB__ (an
# "R;G;B" triplet) and __ACCENT_IS_CUSTOM__ (1 when the user picked a colour,
# 0 for a theme default), then ~/.bashrc sources the result (see the
# BEGIN/END TERMUX_PROMPT_THEME markers). Do not edit the generated file by
# hand; use Settings > 命令行样式.
#
# Design: all dynamic prompt parts are computed by __termux_prompt_precmd
# (runs before every prompt via PROMPT_COMMAND) into _PROMPT_* variables, so
# the PS1 templates below contain NO command substitutions — only bash prompt
# escapes (\w \W \A \# \$ \n) and ${...} variable expansions. PS1 is
# re-assigned in precmd, so it always wins over anything that set PS1 at
# startup (e.g. /etc/profile ordering). The file is safe to source again in a
# running session: functions are redefined, variables and templates are
# re-assigned, PROMPT_COMMAND is appended only once, and the DEBUG trap is
# installed only for theme 19 (removed again when switching away from it).
# Colour variables carry "R;G;B" triplets (digits and semicolons only); the
# \e[38;2;...m / \e[48;2;...m escapes themselves stay literal in the
# templates, so the picked colour is rendered exactly (truecolor), never
# remapped through the terminal colour scheme.
#
# Why not \u / \h: on Android getpwuid() has no passwd entry for the app uid,
# so \u is unreliable in Termux; user/host are computed once at source time
# from $USER / $HOSTNAME with fallbacks instead.

# No early-return guard here on purpose: re-sourcing re-applies the theme
# (see the design comment above). PROMPT_COMMAND / trap installation below
# are guarded individually.
_TERMUX_PROMPT_THEME_ID=__THEME_ID__
_TERMUX_PROMPT_ACCENT_RGB="__ACCENT_RGB__"
_TERMUX_PROMPT_ACCENT_CUSTOM=__ACCENT_IS_CUSTOM__
_PROMPT_ACCENT_RGB="$_TERMUX_PROMPT_ACCENT_RGB"

_PROMPT_EXIT=0
_PROMPT_STATUS_COLOR=32
_PROMPT_STATUS_TXT=''
_PROMPT_SMILEY_COLOR=32
_PROMPT_SMILEY_TXT=''
_PROMPT_GIT_STR=''
_PROMPT_DURATION=''
_PROMPT_DURATION_TXT=''
_PROMPT_DATE_STR=''
_PROMPT_LAST2=''
_PROMPT_USER=''
_PROMPT_HOST=''
# Theme 24 ("Root Turns Red") colours the prompt with the picked accent when
# the user chose one, and falls back to green (red for root) otherwise.
if [ "$_TERMUX_PROMPT_ACCENT_CUSTOM" = 1 ]; then
    _PROMPT_USER_COLOR_RGB="$_TERMUX_PROMPT_ACCENT_RGB"
elif [ "$EUID" -eq 0 ]; then
    _PROMPT_USER_COLOR_RGB='255;59;48'
else
    _PROMPT_USER_COLOR_RGB='0;205;0'
fi
_PROMPT_CMD_START=''

# Theme 21 (Random Colour) picks one vivid triplet per prompt.
_PROMPT_VIVID_RGB=('255;0;0' '255;128;0' '255;255;0' '128;255;0' '0;255;0' '0;255;128' '0;255;255' '0;128;255' '0;0;255' '128;0;255' '255;0;255' '255;0;128')
_PROMPT_RAINBOW_COLORS=(31 33 32 36 34 35)

# User/host are fixed for the session: compute once, not on every prompt.
_PROMPT_USER=${USER:-$(id -un 2>/dev/null)}
[ -z "$_PROMPT_USER" ] && _PROMPT_USER=user
_PROMPT_HOST=${HOSTNAME:-$(hostname 2>/dev/null)}
[ -z "$_PROMPT_HOST" ] && _PROMPT_HOST=phone

__termux_prompt_git() {
    local branch dirty
    branch=$(git rev-parse --abbrev-ref HEAD 2>/dev/null) || return 0
    [ -n "$branch" ] || return 0
    dirty=$(git status --porcelain 2>/dev/null)
    if [ -n "$dirty" ]; then dirty='*'; else dirty=''; fi
    printf ' (%s%s)' "$branch" "$dirty"
}

# Last two path components of $PWD, with $HOME shown as ~.
__termux_prompt_last2() {
    local d=${PWD/#$HOME/\~} parent base grand
    case "$d" in
        */*)
            parent=${d%/*}; base=${d##*/}; grand=${parent##*/}
            if [ "$parent" = "$d" ] || [ -z "$grand" ] || [ "$grand" = "$parent" ]; then
                printf '%s' "$d"
            else
                printf '%s/%s' "$grand" "$base"
            fi
            ;;
        *) printf '%s' "$d" ;;
    esac
}

__termux_prompt_rainbow_ps1() {
    local i=0 c ch
    _PROMPT_RAINBOW_PS1=''
    while [ $i -lt ${#_PROMPT_USER} ]; do
        ch=${_PROMPT_USER:$i:1}
        c=${_PROMPT_RAINBOW_COLORS[$((i % 6))]}
        _PROMPT_RAINBOW_PS1="${_PROMPT_RAINBOW_PS1}\\[\\e[${c}m\\]${ch}\\[\\e[0m\\]"
        i=$((i + 1))
    done
    _PROMPT_RAINBOW_PS1="${_PROMPT_RAINBOW_PS1} \[\e[34m\]\w\[\e[0m\] \[\e[38;2;${_PROMPT_ACCENT_RGB}m\]\\$\[\e[0m\] "
}

# Records the start time of each user command. The DEBUG trap fires for the
# PROMPT_COMMAND invocation itself but NOT for commands inside precmd, and
# PS1 has no command substitutions, so the only guard needed is the function
# name prefix (verified by tests/trap_experiment.py). The _PROMPT_CMD_START=*
# pattern covers the clearing assignment at the end of this file.
__termux_prompt_preexec() {
    case "$BASH_COMMAND" in
        __termux_prompt_*|_termux_prompt_*|_PROMPT_CMD_START=*) return 0 ;;
    esac
    _PROMPT_CMD_START=$EPOCHREALTIME
}

__termux_prompt_precmd() {
    _PROMPT_EXIT=$?
    if [ "$_PROMPT_EXIT" -eq 0 ]; then
        _PROMPT_STATUS_COLOR=32
        _PROMPT_STATUS_TXT='✓ '
        _PROMPT_SMILEY_COLOR=32
        _PROMPT_SMILEY_TXT=':) '
    else
        _PROMPT_STATUS_COLOR=31
        _PROMPT_STATUS_TXT="✗ $_PROMPT_EXIT "
        _PROMPT_SMILEY_COLOR=31
        _PROMPT_SMILEY_TXT=":( $_PROMPT_EXIT "
    fi
    _PROMPT_PS1_SET=''
    case "$_TERMUX_PROMPT_THEME_ID" in
        5|13) _PROMPT_GIT_STR=$(__termux_prompt_git) ;;
        19)
            if [ -n "$_PROMPT_CMD_START" ] && [ -n "$EPOCHREALTIME" ] \
                    && command -v awk >/dev/null 2>&1; then
                _PROMPT_DURATION=$(awk -v s="$_PROMPT_CMD_START" -v e="$EPOCHREALTIME" \
                    'BEGIN { printf "%.1f", e - s }')
                _PROMPT_DURATION_TXT="[${_PROMPT_DURATION}s] "
            fi
            _PROMPT_CMD_START=''
            ;;
        20) _PROMPT_DATE_STR=$(date '+%Y-%m-%d %H:%M') ;;
        21) _PROMPT_ACCENT_RGB=${_PROMPT_VIVID_RGB[$((RANDOM % ${#_PROMPT_VIVID_RGB[@]}))]} ;;
        22)
            __termux_prompt_rainbow_ps1
            PS1="$_PROMPT_RAINBOW_PS1"
            _PROMPT_PS1_SET=1
            ;;
        23) _PROMPT_LAST2=$(__termux_prompt_last2) ;;
    esac
    if [ -z "$_PROMPT_PS1_SET" ]; then
        PS1="$_PROMPT_PS1_TEMPLATE"
    fi
}

case "$_TERMUX_PROMPT_THEME_ID" in
    1) _PROMPT_PS1_TEMPLATE='\[\e[38;2;${_PROMPT_ACCENT_RGB}m\]${_PROMPT_USER}@${_PROMPT_HOST}\[\e[0m\]:\[\e[34m\]\w\[\e[0m\] \[\e[38;2;${_PROMPT_ACCENT_RGB}m\]\$\[\e[0m\] '; ;;
    2) _PROMPT_PS1_TEMPLATE='\[\e[38;2;${_PROMPT_ACCENT_RGB}m\]➜ \[\e[0m\]\[\e[34m\]\W\[\e[0m\] \[\e[38;2;${_PROMPT_ACCENT_RGB}m\]\$\[\e[0m\] '; ;;
    3) _PROMPT_PS1_TEMPLATE='\[\e[34m\]\w\[\e[0m\] \[\e[38;2;${_PROMPT_ACCENT_RGB}m\]\$\[\e[0m\] '; ;;
    4) _PROMPT_PS1_TEMPLATE='\[\e[38;2;${_PROMPT_ACCENT_RGB}m\]${_PROMPT_USER}@${_PROMPT_HOST}\[\e[0m\] \[\e[34m\]\w\[\e[0m\] \[\e[90m\]\A\[\e[0m\]\n\[\e[38;2;${_PROMPT_ACCENT_RGB}m\]\$\[\e[0m\] '; ;;
    5) _PROMPT_PS1_TEMPLATE='\[\e[34m\]\w\[\e[0m\]\[\e[32m\]${_PROMPT_GIT_STR}\[\e[0m\] \[\e[38;2;${_PROMPT_ACCENT_RGB}m\]\$\[\e[0m\] '; ;;
    6) _PROMPT_PS1_TEMPLATE='\[\e[90m\][\A]\[\e[0m\] \[\e[34m\]\w\[\e[0m\] \[\e[38;2;${_PROMPT_ACCENT_RGB}m\]\$\[\e[0m\] '; ;;
    7) _PROMPT_PS1_TEMPLATE='\[\e[${_PROMPT_STATUS_COLOR}m\]${_PROMPT_STATUS_TXT}\[\e[0m\]\[\e[34m\]\W\[\e[0m\] \[\e[38;2;${_PROMPT_ACCENT_RGB}m\]\$\[\e[0m\] '; ;;
    8) _PROMPT_PS1_TEMPLATE='\[\e[30;48;2;${_PROMPT_ACCENT_RGB}m\] ${_PROMPT_USER} \[\e[48;2;${_PROMPT_ACCENT_RGB};44m\]\[\e[30;44m\] \w \[\e[0m\]\[\e[34m\]\[\e[0m\] \[\e[38;2;${_PROMPT_ACCENT_RGB}m\]\$\[\e[0m\] '; ;;
    9) _PROMPT_PS1_TEMPLATE='\[\e[38;2;${_PROMPT_ACCENT_RGB}m\]❯ \[\e[0m\]'; ;;
    10) _PROMPT_PS1_TEMPLATE='╭─\[\e[38;2;${_PROMPT_ACCENT_RGB}m\]${_PROMPT_USER}@${_PROMPT_HOST}\[\e[0m\] \[\e[34m\]\w\[\e[0m\]\n╰─\[\e[38;2;${_PROMPT_ACCENT_RGB}m\]\$\[\e[0m\] '; ;;
    11) _PROMPT_PS1_TEMPLATE='[\[\e[38;2;${_PROMPT_ACCENT_RGB}m\]${_PROMPT_USER}@${_PROMPT_HOST}\[\e[0m\]]-[\[\e[34m\]\w\[\e[0m\]]\[\e[38;2;${_PROMPT_ACCENT_RGB}m\]\$\[\e[0m\] '; ;;
    12) _PROMPT_PS1_TEMPLATE='\[\e[38;2;${_PROMPT_ACCENT_RGB}m\]📁 \[\e[0m\]\[\e[34m\]\w\[\e[0m\] \[\e[38;2;${_PROMPT_ACCENT_RGB}m\]\$\[\e[0m\] '; ;;
    13) _PROMPT_PS1_TEMPLATE='[\[\e[${_PROMPT_STATUS_COLOR}m\]${_PROMPT_STATUS_TXT}\[\e[0m\]\A] \[\e[34m\]\w\[\e[0m\]\[\e[32m\]${_PROMPT_GIT_STR}\[\e[0m\] \[\e[38;2;${_PROMPT_ACCENT_RGB}m\]\$\[\e[0m\] '; ;;
    14) _PROMPT_PS1_TEMPLATE='\[\e[34m\]\w\[\e[0m\]\[\e[38;2;${_PROMPT_ACCENT_RGB}m\]>\[\e[0m\] '; ;;
    15) _PROMPT_PS1_TEMPLATE='\[\e[34m\]\w\[\e[0m\]\n\[\e[38;2;${_PROMPT_ACCENT_RGB}m\]\$\[\e[0m\] '; ;;
    16) _PROMPT_PS1_TEMPLATE='\[\e[38;2;${_PROMPT_ACCENT_RGB};7m\]${_PROMPT_USER}\[\e[0m\] \[\e[34m\]\w\[\e[0m\] \[\e[38;2;${_PROMPT_ACCENT_RGB}m\]\$\[\e[0m\] '; ;;
    17) _PROMPT_PS1_TEMPLATE='\[\e[${_PROMPT_SMILEY_COLOR}m\]${_PROMPT_SMILEY_TXT}\[\e[0m\]\[\e[34m\]\W\[\e[0m\] \[\e[38;2;${_PROMPT_ACCENT_RGB}m\]\$\[\e[0m\] '; ;;
    18) _PROMPT_PS1_TEMPLATE='\[\e[90m\][\#]\[\e[0m\] \[\e[34m\]\w\[\e[0m\] \[\e[38;2;${_PROMPT_ACCENT_RGB}m\]\$\[\e[0m\] '; ;;
    19) _PROMPT_PS1_TEMPLATE='\[\e[36m\]${_PROMPT_DURATION_TXT}\[\e[0m\]\[\e[34m\]\w\[\e[0m\] \[\e[38;2;${_PROMPT_ACCENT_RGB}m\]\$\[\e[0m\] '; ;;
    20) _PROMPT_PS1_TEMPLATE='\[\e[90m\][${_PROMPT_DATE_STR}]\[\e[0m\] \[\e[34m\]\w\[\e[0m\] \[\e[38;2;${_PROMPT_ACCENT_RGB}m\]\$\[\e[0m\] '; ;;
    21) _PROMPT_PS1_TEMPLATE='\[\e[38;2;${_PROMPT_ACCENT_RGB}m\]${_PROMPT_USER}@${_PROMPT_HOST}\[\e[0m\]:\[\e[34m\]\w\[\e[0m\] \[\e[38;2;${_PROMPT_ACCENT_RGB}m\]\$\[\e[0m\] '; ;;
    22) _PROMPT_PS1_TEMPLATE='\[\e[38;2;${_PROMPT_ACCENT_RGB}m\]${_PROMPT_USER}@${_PROMPT_HOST}\[\e[0m\]:\[\e[34m\]\w\[\e[0m\] \[\e[38;2;${_PROMPT_ACCENT_RGB}m\]\$\[\e[0m\] '; ;;
    23) _PROMPT_PS1_TEMPLATE='\[\e[34m\]${_PROMPT_LAST2}\[\e[0m\] \[\e[38;2;${_PROMPT_ACCENT_RGB}m\]\$\[\e[0m\] '; ;;
    24) _PROMPT_PS1_TEMPLATE='\[\e[38;2;${_PROMPT_USER_COLOR_RGB}m\]${_PROMPT_USER}@${_PROMPT_HOST}\[\e[0m\]:\[\e[34m\]\w\[\e[0m\] \[\e[38;2;${_PROMPT_USER_COLOR_RGB}m\]\$\[\e[0m\] '; ;;

    *) _PROMPT_PS1_TEMPLATE='\[\e[32m\]${_PROMPT_USER}@${_PROMPT_HOST}\[\e[0m\]:\[\e[34m\]\w\[\e[0m\] \[\e[32m\]\$\[\e[0m\] ' ;;
esac

PS1="$_PROMPT_PS1_TEMPLATE"

case " $PROMPT_COMMAND " in
    *" __termux_prompt_precmd "*) ;;
    *) PROMPT_COMMAND="${PROMPT_COMMAND:+$PROMPT_COMMAND; }__termux_prompt_precmd" ;;
esac

# Only the duration theme needs the DEBUG trap. Note: once installed, the
# trap stays for the life of the shell. Bash cannot remove a DEBUG trap from
# inside a sourced file or a function — `trap - DEBUG` there is a no-op and
# `trap -p` prints nothing there — so switching away from theme 19 in a
# running session leaves the trap installed. That is harmless: the handler
# only records a start timestamp that other themes ignore, and a new session
# never installs the trap unless theme 19 is selected.
if [ "$_TERMUX_PROMPT_THEME_ID" -eq 19 ]; then
    trap '__termux_prompt_preexec' DEBUG
fi

# Clear any start time recorded while sourcing this file (the trap fires for
# the setup commands above); the first prompt then shows no duration.
_PROMPT_CMD_START=''
