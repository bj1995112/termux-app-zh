#!/usr/bin/env bash
# install-inside-ubuntu.sh — 在 Ubuntu 26.04 里装 Node 24 + AI agent
# 由 install-all.sh 调用，别直接在 Termux 里跑。
# 用法：bash /root/install-inside-ubuntu.sh [codex pi agy dsh opencode aider]（不给 = 全部 6 个）
# 已装的会跳过；每个工具单独记成功/失败，一个失败不中断后面的。

export PATH="$HOME/.local/bin:$PATH"

ALL="codex pi agy dsh opencode aider"
SELECTED="${*:-$ALL}"
FAILURES=""

# 检查名字
for a in $SELECTED; do
  case " $ALL " in
    *" $a "*) ;;
    *) echo "!! 不认识：$a（可选：$ALL）"; exit 1 ;;
  esac
done

has() { case " $SELECTED " in *" $1 "*) return 0;; *) return 1;; esac; }
fail() { echo "!! 失败：$1"; FAILURES="$FAILURES $1"; }
ver() { command -v "$1" >/dev/null 2>&1 && "$1" --version 2>/dev/null | head -n 1 || echo "未安装"; }

echo "== 基础工具 + Node 24 =="
apt update && apt install -y git curl ca-certificates || { echo "!! 基础工具安装失败"; exit 1; }
# Ubuntu 26.04 自带 Node 是 22.22.1，最新版 dsh 要求 Node >= 24.9，必须用 NodeSource 装 Node 24
if node --version 2>/dev/null | grep -q '^v24\.'; then
  echo "Node 24 已装：$(node --version)"
else
  curl -fsSL https://deb.nodesource.com/setup_24.x | bash - || { echo "!! NodeSource 失败"; exit 1; }
  apt install -y nodejs || { echo "!! Node 24 安装失败"; exit 1; }
fi
node --version

# 让以后每次进 Ubuntu 都有 ~/.local/bin 在 PATH 里（Codex、agy、aider 装在那）
grep -q '\.local/bin' /root/.bashrc 2>/dev/null || echo 'export PATH="$HOME/.local/bin:$PATH"' >> /root/.bashrc

if has codex; then
  if command -v codex >/dev/null 2>&1; then
    echo "codex 已装，跳过"
  else
    echo "== 装 Codex =="
    curl -fsSL https://chatgpt.com/codex/install.sh | sh || fail "codex"
    # 注意：Codex 默认用 bubblewrap 沙箱，proot 里很可能跑不起来；实测时如报沙箱错，
    # 先在自己的项目里把沙箱调松再试，不要直接在重要目录跑。
  fi
fi

if has pi; then
  if command -v pi >/dev/null 2>&1; then
    echo "pi 已装，跳过"
  else
    echo "== 装 Pi =="
    npm install -g --ignore-scripts @earendil-works/pi-coding-agent || fail "pi"
  fi
fi

if has agy; then
  if command -v agy >/dev/null 2>&1; then
    echo "agy 已装，跳过"
  else
    echo "== 装 Antigravity CLI =="
    curl -fsSL https://antigravity.google/cli/install.sh | bash || fail "agy"
  fi
fi

if has dsh; then
  if command -v dsh >/dev/null 2>&1; then
    echo "dsh 已装，跳过"
  else
    echo "== 装 DeepSeek Harness（开发者预览版）=="
    npm install -g @deepseek-ai/dsh || fail "dsh"
  fi
fi

if has opencode; then
  if command -v opencode >/dev/null 2>&1; then
    echo "opencode 已装，跳过"
  else
    echo "== 装 OpenCode v2 =="
    npm install -g @opencode/cli || fail "opencode"
  fi
fi

if has aider; then
  if command -v aider >/dev/null 2>&1; then
    echo "aider 已装，跳过"
  else
    echo "== 装 Aider =="
    curl -LsSf https://aider.chat/install.sh | sh || fail "aider"
  fi
fi

echo
echo "--- 装好的版本 ---"
echo "node:      $(node --version 2>/dev/null || echo 未安装)"
echo "codex:     $(ver codex)"
echo "pi:        $(ver pi)"
echo "agy:       $(ver agy)"
echo "dsh:       $(ver dsh)"
echo "opencode:  $(ver opencode)"
echo "aider:     $(ver aider)"
if [ -n "$FAILURES" ]; then
  echo; echo "!! 以下失败：$FAILURES（装好的不受影响，失败的可以单独重装）"
  exit 1
fi
echo; echo "Ubuntu 里的安装全部完成。"
