# 手机装 AI agent：安装清单（Ubuntu 26.04，proot-distro）
# 想省事直接跑一键安装：见 install-all.sh（一行命令全装好）；下面是手动一步一步装的清单。
# 核对时间：2026-11-20 上午；命令来自各官方安装渠道，版本号以当时最新为准。
# 顺序不能反：先装 Ubuntu（第 0 步），再装 Node（第 1 步），最后装 agent（第 2–7 步）。
# 第 2–7 步每步都可选：只装你想要的，跳过不要的；以后想装再按那一步装。
# 更新脚本只更新已装的，不会把跳过的偷偷装上。
# 账号准备（先不管，装的时候再说）：OpenAI（Codex）、DeepSeek API key（dsh、Aider、OpenCode、Pi 都能用）、个人 Gmail（Antigravity）。
# Antigravity 先只用 CLI，不装图形版。

## 0. 装 Ubuntu 26.04（Termux 里）
# 先确认手机有几 GB 空闲空间，连上 Wi-Fi 再装，下载要几分钟。
pkg update && pkg install -y proot-distro
proot-distro install ubuntu:26.04
proot-distro login ubuntu
# 登录后是 root 身份，下面命令都不用 sudo。后面所有步骤都在这个 Ubuntu 里跑。

## 1. 基础工具 + Node 24（关键：不能只用 apt 装 Node）
apt update && apt install -y git curl ca-certificates
# Ubuntu 26.04 自带 Node 是 22.22.1：够 Pi 用（要求 22.19+），但最新版 dsh 要求 Node >= 24.9，
# 所以必须用 NodeSource 装 Node 24，否则 dsh 装不上：
curl -fsSL https://deb.nodesource.com/setup_24.x | bash -
apt install -y nodejs
node --version   # 应为 v24.x

## 2. Codex（官方一键脚本，自动识别 ARM64，不需要 Node）
curl -fsSL https://chatgpt.com/codex/install.sh | sh
codex --version
# 登录：运行 codex，按提示用 ChatGPT 账号或 API key 登录。
# 注意：Codex 默认用 bubblewrap 沙箱，proot 里很可能跑不起来；实测时如报沙箱错，
# 先在自己的项目里把沙箱调松再试，不要直接在重要目录跑。

## 3. Pi（npm 装，官方命令带 --ignore-scripts，别漏）
npm install -g --ignore-scripts @earendil-works/pi-coding-agent
pi --version
# 登录：在 pi 里跑 /login，可选 Claude、ChatGPT、Copilot、Gemini、Antigravity 订阅，DeepSeek 走配置文件。
# 更新：npm 装的用 npm 重装更新，不要用 pi update（那是官方安装器装的才有的更新方式）。

## 4. Antigravity CLI（官方脚本，自动识别 aarch64）
curl -fsSL https://antigravity.google/cli/install.sh | bash
agy --version
# 登录：第一次跑 agy，用个人 Gmail 登录，免费版每周有额度；它自己每 15 分钟检查一次更新。
# 图形版以后再说，先只用 CLI。

## 5. DeepSeek Harness（dsh，开发者预览版，会有不兼容大改）
npm install -g @deepseek-ai/dsh
dsh web   # 浏览器打开 http://127.0.0.1:3080（只绑本机，别改 0.0.0.0）
# 登录要 DeepSeek API key。建议先记下装好的版本号，出问题可回退：
#   npm install -g @deepseek-ai/dsh@<旧版本号>

## 6. OpenCode（开源壳子，换任意模型；新装用 v2）
npm install -g @opencode/cli
opencode --version
# 登录：跑 opencode 后用 /connect 接模型，可用 DeepSeek 等 API key，也能用已有的 ChatGPT/Copilot 订阅。
# 注意：包名 @opencode/cli 是 v2；旧的 opencode-ai 是 v1，别装错。

## 7. Aider（Python 工具，改代码自动 git 提交，出错好撤销）
curl -LsSf https://aider.chat/install.sh | sh
aider --version
# 登录：跑 aider --model deepseek --api-key deepseek=<你的 key>，也可以用 Claude、OpenAI 等。
# 它自己带独立 Python 环境，不用你手动装 pip。

## 8. 一键更新（每个工具可单独选；没装的跳过，不会偷偷装上）
# 把同目录的 update-agents.sh 传到 Ubuntu 里（~/update-agents.sh），用法：
bash ~/update-agents.sh                 # 交互选择：挑要更新的几个
bash ~/update-agents.sh all             # 全部更新
bash ~/update-agents.sh codex pi        # 只更新 codex 和 pi
bash ~/update-agents.sh --list          # 只看各工具当前版本
# 可选名字：apt codex pi agy dsh opencode aider
# Termux 里一键跑（第一次先确认这行在手机上能用）：
#   proot-distro login ubuntu -- bash ~/update-agents.sh all
# 脚本会自己检查是不是在 Ubuntu 里，在 Termux 里直接跑会提示你先进 Ubuntu。
