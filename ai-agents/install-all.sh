#!/usr/bin/env bash
# install-all.sh — 手机 Termux 里一键装 Ubuntu 26.04 + AI agent
# 跑一次就全装好，不用一步一步敲命令。
#
# 用法（Termux 里）：
#   curl -fsSL https://raw.githubusercontent.com/bj1995112/termux-app-zh/main/ai-agents/install-all.sh | bash
#   # 只装其中几个，先下载再跑：
#   curl -fsSL https://raw.githubusercontent.com/bj1995112/termux-app-zh/main/ai-agents/install-all.sh -o install-all.sh
#   bash install-all.sh codex pi agy
# 可选名字：codex pi agy dsh opencode aider（不给 = 全部 6 个）
#
# 需要：Wi-Fi、几 GB 空闲空间；全程十几二十分钟，别中途退出。
# 安装本身不用账号；装完第一次跑各 agent 时再登录（Codex、DeepSeek、Gmail 等）。
# 如果 Ubuntu 已经装过，会跳过安装，直接装 agent；已经装好的 agent 也会跳过。

REPO_RAW="https://raw.githubusercontent.com/bj1995112/termux-app-zh/main/ai-agents"
ALL_AGENTS="codex pi agy dsh opencode aider"
AGENTS="${*:-$ALL_AGENTS}"

# 检查名字对不对
for a in $AGENTS; do
  case " $ALL_AGENTS " in
    *" $a "*) ;;
    *) echo "!! 不认识：$a（可选：$ALL_AGENTS）"; exit 1 ;;
  esac
done

echo "== 1/4 准备 Termux：装 proot-distro =="
if ! command -v proot-distro >/dev/null 2>&1; then
  pkg update && pkg install -y proot-distro || { echo "!! proot-distro 安装失败，检查网络后重试"; exit 1; }
else
  echo "proot-distro 已装。"
fi

echo "== 2/4 装 Ubuntu 26.04（已装过就跳过）=="
if proot-distro list 2>/dev/null | grep -q ubuntu; then
  echo "Ubuntu 已装，跳过。"
else
  proot-distro install ubuntu:26.04 || { echo "!! Ubuntu 安装失败，检查网络和剩余空间后重试"; exit 1; }
fi

echo "== 3/4 下载安装脚本 =="
TMPD="${TMPDIR:-/tmp}/ai-agents-install"
mkdir -p "$TMPD"
curl -fsSL "$REPO_RAW/install-inside-ubuntu.sh" -o "$TMPD/install-inside-ubuntu.sh" || { echo "!! 下载 install-inside-ubuntu.sh 失败"; exit 1; }
curl -fsSL "$REPO_RAW/update-agents.sh" -o "$TMPD/update-agents.sh" || { echo "!! 下载 update-agents.sh 失败"; exit 1; }

ROOTFS="$PREFIX/var/lib/proot-distro/installed-rootfs/ubuntu"
if [ ! -d "$ROOTFS" ]; then
  echo "!! 找不到 Ubuntu 的安装目录：$ROOTFS"
  exit 1
fi
cp "$TMPD/install-inside-ubuntu.sh" "$ROOTFS/root/install-inside-ubuntu.sh"
cp "$TMPD/update-agents.sh" "$ROOTFS/root/update-agents.sh"

echo "== 4/4 进 Ubuntu 装 Node 24 和 agent：$AGENTS =="
proot-distro login ubuntu -- bash /root/install-inside-ubuntu.sh $AGENTS
STATUS=$?
if [ "$STATUS" -ne 0 ]; then
  echo "!! Ubuntu 里的安装没跑完（上面有失败的步骤），修好后可以再跑一次这个脚本接着装。"
  exit "$STATUS"
fi

echo
echo "全部完成。进 Ubuntu 用：proot-distro login ubuntu"
echo "以后更新 agent 用：proot-distro login ubuntu -- bash ~/update-agents.sh"
echo "第一次跑各 agent 时再登录账号（Codex、DeepSeek、Gmail 等）。"
