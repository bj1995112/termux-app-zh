#!/usr/bin/env bash
# update-agents.sh — 一键更新手机 Ubuntu 里的 AI agent，每个工具可单独选
# 必须在 Ubuntu 26.04 里跑（proot-distro login ubuntu 之后，root 身份）。
# 在 Termux 里直接跑会提示你先进 Ubuntu。
#
# 用法：
#   bash ~/update-agents.sh                 # 交互选择：挑要更新的几个（终端里跑）
#   bash ~/update-agents.sh all             # 全部更新
#   bash ~/update-agents.sh codex pi        # 只更新 codex 和 pi
#   bash ~/update-agents.sh --list          # 只看各工具当前版本
#   bash ~/update-agents.sh --help          # 看用法
# 可选名字：apt codex pi agy dsh opencode aider
#
# Termux 里一键跑（第一次先确认这行在手机上能用）：
#   proot-distro login ubuntu -- bash ~/update-agents.sh all
#
# 每个工具单独记成功/失败，一个失败不中断后面的；最后打印版本对照表。
# 只更新已经装好的工具，没装的跳过（想装新的按安装清单第 2–7 步装，不会偷偷给你装上）。
# dsh 是开发者预览版，更新前会先记下旧版本号，万一新版 break 可回退：
#   npm install -g @deepseek-ai/dsh@<旧版本号>

ALL="apt codex pi agy dsh opencode aider"
FAILURES=""
SELECTED=""

# Codex、agy、aider 装在 ~/.local/bin，非交互跑的时候 PATH 里可能没有，先加上
export PATH="$HOME/.local/bin:$PATH"

usage() {
  echo "用法：bash ~/update-agents.sh [选项]"
  echo "  （不带参数）   交互选择要更新的"
  echo "  all            全部更新"
  echo "  codex pi ...   只更新指定的几个"
  echo "  --list         只看各工具当前版本"
  echo "可选名字：$ALL"
}

ver() { command -v "$1" >/dev/null 2>&1 && "$1" --version 2>/dev/null | head -n 1 || echo "未安装"; }

show_versions() {
  echo "node:      $(node --version 2>/dev/null || echo 未安装)"
  echo "codex:     $(ver codex)"
  echo "pi:        $(ver pi)"
  echo "agy:       $(ver agy)"
  echo "dsh:       $(ver dsh)"
  echo "opencode:  $(ver opencode)"
  echo "aider:     $(ver aider)"
}

# --- --help / --list 不用在 Ubuntu 里也能跑 ---
if [ "$1" = "--help" ] || [ "$1" = "-h" ]; then
  usage; exit 0
fi
if [ "$1" = "--list" ]; then
  echo "--- 当前版本 ---"
  show_versions
  exit 0
fi

# --- 前提检查：必须在 Ubuntu 里 ---
if [ ! -f /etc/os-release ] || ! grep -q '^ID=ubuntu' /etc/os-release; then
  echo "!! 这不是 Ubuntu。请先在 Termux 里跑："
  echo "     proot-distro login ubuntu"
  echo "   然后再在 Ubuntu 里跑 bash ~/update-agents.sh"
  exit 1
fi

# --- 解析要更新的 ---
if [ $# -gt 0 ]; then
  if [ "$1" = "all" ]; then
    SELECTED="$ALL"
  else
    for a in "$@"; do
      case " $ALL " in
        *" $a "*) case " $SELECTED " in *" $a "*) ;; *) SELECTED="$SELECTED $a";; esac ;;
        *) echo "!! 不认识：$a（可选：$ALL）"; usage; exit 1 ;;
      esac
    done
  fi
else
  # 没给参数：交互选择
  if [ -t 0 ]; then
    echo "要更新哪些？输入编号或名字，空格隔开；直接回车 = 全部；输入 0 = 退出"
    st() { command -v "$1" >/dev/null 2>&1 && echo "已装" || echo "未装"; }
    echo "  1 apt（系统）  2 codex($(st codex))  3 pi($(st pi))  4 agy($(st agy))  5 dsh($(st dsh))  6 opencode($(st opencode))  7 aider($(st aider))"
    printf "选："
    read -r CHOICE || CHOICE=""
    [ -z "$CHOICE" ] && CHOICE="all"
    [ "$CHOICE" = "0" ] && { echo "已取消。"; exit 0; }
    for c in $CHOICE; do
      case "$c" in
        all) SELECTED="$ALL"; break ;;
        1|apt) SELECTED="$SELECTED apt" ;;
        2|codex) SELECTED="$SELECTED codex" ;;
        3|pi) SELECTED="$SELECTED pi" ;;
        4|agy) SELECTED="$SELECTED agy" ;;
        5|dsh) SELECTED="$SELECTED dsh" ;;
        6|opencode) SELECTED="$SELECTED opencode" ;;
        7|aider) SELECTED="$SELECTED aider" ;;
        *) echo "!! 不认识：$c"; exit 1 ;;
      esac
    done
  else
    echo "!! 没指定要更新的，也不是交互终端。"
    usage
    exit 1
  fi
fi

# 去重
DEDUP=""
for s in $SELECTED; do
  case " $DEDUP " in *" $s "*) ;; *) DEDUP="$DEDUP $s";; esac
done
SELECTED="$DEDUP"

has() { case " $SELECTED " in *" $1 "*) return 0;; *) return 1;; esac; }
installed() { command -v "$1" >/dev/null 2>&1; }

note() { echo "== $1 =="; }
fail() { echo "!! 失败：$1"; FAILURES="$FAILURES $1"; }

echo "--- 更新前版本 ---"
show_versions
DSH_OLD="$(command -v dsh >/dev/null 2>&1 && dsh --version 2>/dev/null | head -n 1 || echo "")"
echo
echo "本次更新：$SELECTED"
echo

if has apt; then
  note "系统更新（apt）"
  apt update && apt upgrade -y || fail "apt"
  echo
fi

if has codex; then
  if installed codex; then
    note "Codex（重跑官方安装脚本即更新）"
    curl -fsSL https://chatgpt.com/codex/install.sh | sh || fail "codex"
  else
    echo "codex 未安装，跳过（想装按清单第 2 步）"
  fi
  echo
fi

if has pi; then
  if installed pi; then
    note "Pi（npm 装的用 npm 更新；pi update 只对官方安装器装的有效）"
    npm install -g --ignore-scripts @earendil-works/pi-coding-agent@latest || fail "pi"
  else
    echo "pi 未安装，跳过（想装按清单第 3 步）"
  fi
  echo
fi

if has agy; then
  if installed agy; then
    note "Antigravity CLI（重跑官方安装脚本即更新；它自己也会后台自动更新）"
    curl -fsSL https://antigravity.google/cli/install.sh | bash || fail "agy"
  else
    echo "agy 未安装，跳过（想装按清单第 4 步）"
  fi
  echo
fi

if has dsh; then
  if installed dsh; then
    note "DeepSeek Harness（预览版，留意下面的版本变化）"
    echo "dsh 更新前版本：${DSH_OLD:-未知}"
    npm install -g @deepseek-ai/dsh@latest || fail "dsh"
    if [ -n "$DSH_OLD" ]; then
      echo "dsh 回退命令（新版出问题时用）：npm install -g @deepseek-ai/dsh@${DSH_OLD}"
    fi
  else
    echo "dsh 未安装，跳过（想装按清单第 5 步）"
  fi
  echo
fi

if has opencode; then
  if installed opencode; then
    note "OpenCode（v2，npm 更新）"
    npm install -g @opencode/cli@latest || fail "opencode"
  else
    echo "opencode 未安装，跳过（想装按清单第 6 步）"
  fi
  echo
fi

if has aider; then
  if installed aider; then
    note "Aider（重跑官方安装脚本即更新）"
    curl -LsSf https://aider.chat/install.sh | sh || fail "aider"
  else
    echo "aider 未安装，跳过（想装按清单第 7 步）"
  fi
  echo
fi

echo "--- 更新后版本对照 ---"
show_versions
if [ -n "$FAILURES" ]; then
  echo; echo "!! 以下步骤失败：$FAILURES"
  exit 1
fi
echo; echo "全部完成。"
