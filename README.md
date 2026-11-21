# Termux 中文版

[![Build](https://github.com/bj1995112/termux-app-zh/actions/workflows/debug_build.yml/badge.svg)](https://github.com/bj1995112/termux-app-zh/actions/workflows/debug_build.yml)
[![Release](https://img.shields.io/github/v/release/bj1995112/termux-app-zh)](https://github.com/bj1995112/termux-app-zh/releases)

Termux v0.119.0-beta.3 的简体中文版，装完即用。

基于上游 [termux/termux-app](https://github.com/termux/termux-app) 的 v0.119.0-beta.3 标签（commit `e634d8f`），界面完整汉化，并内置了多项开箱即用的功能。

## 下载

最新版本：[v0.119.0-beta.3-zh.0.03](https://github.com/bj1995112/termux-app-zh/releases/tag/v0.119.0-beta.3-zh.0.03)

- APK：`termux-app-zh-v0.119.0-beta.3-zh.0.03-arm64-v8a.apk`（41.9 MB，仅 arm64-v8a）
- 发布页的下载链接永久有效，无需登录 GitHub

## 安装说明

- 包名仍为 `com.termux`，安装前请先卸载官方 Termux（以及 Termux 的插件 App，签名不同无法共存）
- 仅支持 Android 7 及以上、arm64 设备
- APK 为 debug 构建（与上游 GitHub 构建一致，使用公开测试密钥签名）
- 建议在设置里把 Termux 的电池设为“无限制”，否则部分手机会在后台冻结 Termux，网页版等功能会卡住

## 功能

- **界面**：完整简体中文；内置 Termux:Styling（114 套配色 + 8 款精选字体），长按会话 → 样式即可切换
- **Linux 发行版**：抽屉 → Linux 发行版，一键安装 Ubuntu / Debian / Arch / Alpine / Fedora / openSUSE / Void；版本列表自动拉取最新版本号和更新日期，可自选版本；已安装的发行版显示在抽屉里，点一下直接进入
- **AI 助手**：在发行版里选择性安装 Codex / Pi / Antigravity CLI / DeepSeek Harness / OpenCode / Aider / Claude Code，支持单独更新；抽屉里有 AI 助手快捷进入，终端里可用 `ai` 命令
- **网页版**：dsh / OpenCode / Pi 可在手机浏览器里打开使用；OpenCode 密码每个发行版固定不变，首次登录后保持登录
- **命令行样式**：设置 → 命令行样式，24 套提示符主题 + 提示符颜色，换主题自动同步到已安装的发行版
- **下载源**：设置 → 下载源，一键检测并推荐（实测每个源的速度）；预设：官方 / 清华 / 中科大 / 阿里 / 腾讯 / 华为 / 自定义；镜像仓库：DaoCloud / dockerproxy / 1ms.run / Docker Hub；测速文件自动删
- **模型配置**：设置 → 模型配置，给每个助手配 API 地址、密钥和模型，可拉取模型列表、设主模型，支持自定义 API 路径；Codex 联网搜索开关；一键清除全部已应用
- **抽屉**：Linux / AI 助手分区可折叠；会话区和 Linux/AI 分区之间可拖动分隔线；AI 助手有更新时显示角标（每小时检查，打开 Termux 也查）
- **主页干净**：新会话不再显示欢迎文字（设置里可恢复）；会话长按可重命名或删除
- **常用工具**：装好发行版后，可选择性安装 git、curl、vim 等常用工具

## 版本号

格式：`v0.119.0-beta.3-zh.0.01`——前面是上游版本号，后面 `zh.0.01` 是中文版自己的版本号，从 0.01 开始，每次发布加 0.01（0.02、0.03……）。

## 自己编译

Push 到 main 分支会自动触发 Build（GitHub Actions），产物在 Actions 页面的 Artifacts 里（需要登录 GitHub 下载）。正式发布用 Actions 里的 Release 流程。

## 说明

- 本项目仅供个人使用，基于上游源码按 GPLv3 开源，保留上游版权信息
- 上游文档：[Termux Wiki](https://wiki.termux.com/wiki/)、[termux-app 原 README](https://github.com/termux/termux-app/blob/master/README.md)
