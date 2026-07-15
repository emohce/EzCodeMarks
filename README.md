# EzCodeMarks

面向 IntelliJ IDEA 的结构化代码书签与 Git 提交信息助手。插件把代码导览、Markdown 备注、编辑器联动，以及结构化/AI 辅助提交信息统一在 IDE 内，同时保持两套功能的数据与运行状态相互隔离。

[English](README_EN.md)

## 主要能力

### 代码书签与导览

- 用 Bookmark、Group、Process、DescriptiveBookmark 组织项目知识。
- 在工具窗口中搜索、拖拽、查看 Markdown 详情与按流程步进。
- 通过 gutter 图标、行尾提示和双向选择联动编辑器。
- 将书签数据保存在项目的 `.codemark/codemark.json`，便于按需纳入版本控制。

### Git 提交信息助手

- 使用 `type`、`scope`、`subject`、`body`、`BREAKING CHANGE`、`Closes` 和 `skip ci` 创建结构化提交信息。
- 根据已包含的 Git 变更生成提交信息，支持附加要求、格式化现有草稿和 Smart Echo。
- 使用可编辑的 AI 预览确认结果；取消、失败或校验失败不会改写提交框。
- 管理 Velocity 模板、提交类型，以及多个 OpenAI Compatible / Anthropic Provider Profile。
- API 密钥只进入 IntelliJ PasswordSafe；源码上下文经过敏感过滤、长度限制和首次发送确认。
- Action、设置和错误文案提供英语、简体中文、日语和韩语资源。

## 环境要求

- IntelliJ IDEA 2025.3+（since build 253）
- JDK 21
- Kotlin 2.4.0
- IntelliJ Platform Gradle Plugin 2.16.0
- Git4Idea（随 IDE 提供）

## 开发与安装

```bash
git clone <repository-url>
cd EzCodeMark
./gradlew runIde
```

构建可安装插件：

```bash
./gradlew buildPlugin
```

生成的 ZIP 位于 `build/distributions/`，可通过 `Settings | Plugins | Install Plugin from Disk…` 安装。完整打包与验证命令见[构建指南](doc/build-guide.md)。

## Git 提交信息助手使用方式

四个稳定 Action 会加入提交信息区域及 Keymap 分组：

| Action | 默认显示于提交工具栏 | Windows / Linux | macOS |
| --- | --- | --- | --- |
| Create Commit Message | 是 | `Ctrl+Alt+Shift+M` | `⌘⌥⇧M` |
| Generate Commit Message | 是 | `Ctrl+Alt+Shift+G` | `⌘⌥⇧G` |
| Generate With Additional Requirements | 是 | 未预设 | 未预设 |
| Format Commit Message | 否 | 未预设 | 未预设 |

工具栏显隐只影响 Commit Message 位置；隐藏后仍可通过快捷键或 Find Action 调用。运行中的 Action 会显示取消图标，再次触发可取消；同一提交文档的其他提交助手 Action 会暂时禁用。

设置位于 `Settings | Tools | EzCodeMarks | Git Commit Message`：

- `Git Commit Message`：工具栏显隐、当前 Keymap/冲突、字段显示、类型展示、skip-ci 与 Smart Echo。
- `Templates & Types`：模板增删复制、默认模板、Velocity 校验/实时预览、类型及描述排序。
- `AI Providers`：活动 Profile、协议、Endpoint、模型、温度、语言、Streaming、Reasoning compatibility、连接测试与模型获取。
- `Project Defaults`：项目模板覆盖、恢复全局默认和清除未完成草稿。

AI Provider 未配置时，Action 会给出可恢复提示并可直接打开 Provider 设置。项目草稿与模板覆盖保存在 IDE workspace state，不会写入 `.codemark`。

## 代码书签快速上手

在编辑器中使用：

| 快捷键 | 功能 |
| --- | --- |
| `Shift+F2` | 创建或编辑当前行 CodeMark |
| `Shift+F3` | 创建分组 |
| `Shift+F4` | 创建备注 |
| `F1` | 查看当前悬停/选中的树节点详情 |
| `Shift+F1` | 查看光标行 CodeMark 详情 |
| `Alt+Shift+↓` / `Alt+Shift+↑` | 下一个 / 上一个 CodeMark |
| `Shift+Delete` | 删除当前行 CodeMark |

通过 `View | Tool Windows | EzCodeMarks` 打开工具窗口。树视图支持拖拽、搜索、Markdown 详情与项目相对文件/行号链接；Description 或 Markdown 编辑字段中可按 `F2` 打开独立编辑窗口。

## 架构与数据边界

书签能力沿用现有 Repository、ViewModel、SelectionBus 与 ToolWindow 架构。提交信息助手则位于独立 `commitmessage` 垂直切片：

| 层 | 主要职责 | 入口 |
| --- | --- | --- |
| Domain | 结构化模型、解析、模板/Provider 契约、上下文策略 | [CommitMessageModels.kt](src/main/kotlin/emohce/domain/commitmessage/CommitMessageModels.kt#L5) |
| Data | 状态、PasswordSafe、Velocity、Provider HTTP、Git 上下文、协调器 | [CommitMessageAiService.kt](src/main/kotlin/emohce/data/commitmessage/CommitMessageAiService.kt#L22) |
| Presentation | VCS Action、提交面板适配器、对话框、设置页、Bundle | [CommitMessageActions.kt](src/main/kotlin/emohce/presentation/commitmessage/action/CommitMessageActions.kt#L42) |

提交信息助手不接入 Bookmark ToolWindow、BookmarkViewModel、SelectionBus 或 `.codemark`。应用设置写入 IDE 配置，项目模板 ID 与草稿只写 workspace state，API 密钥只由 PasswordSafe 保存。

Provider 网络请求使用 IDE Proxy，默认连接超时 15 秒、读取上限 120 秒并支持取消。发送的 Git 上下文会过滤二进制、生成文件、`.env*`、密钥/证书/SSH/credentials/service-account/secrets 等路径或内容；v1 不提供绕过开关。

## 验证

```bash
./gradlew test
./gradlew buildPlugin
./gradlew verifyPluginProjectConfiguration
./gradlew verifyPluginStructure
./gradlew verifyPlugin
```

测试覆盖结构化解析/渲染、Velocity、状态迁移、PasswordSafe 边界、上下文过滤与裁剪、Provider/SSE/取消/回退，以及 Action、快捷键、DataKey 和设置生命周期。

## 项目文档

- [用户指南](doc/USER_GUIDE.md)
- [构建指南](doc/build-guide.md)
- [变更日志](doc/change-log.md)
- [书签树操作规格](doc/260604-cursor-tree-operations-spec.md)
- [当前项目状态](vibe/specs/PROJECT_STATUS.md)

## 许可证与来源说明

本项目采用 [LICENSE](LICENSE) 中的许可证。

代码书签能力参考了 [CodeTour](https://github.com/LefterisXris/CodeTour) 与 [Bookmark-X](https://github.com/Nonoas/Bookmark-X)。提交信息助手根据 Apache-2.0 项目 [Git Commit Message Helper](https://github.com/AutismSuperman/git-commit-message-helper) 的产品行为重新设计，并按 EzCodeMarks 当前 Kotlin/JDK 21/IntelliJ 2025.3 架构独立实现；未复制其 Swing `.form`、反射、裸 Git/HTTP 或明文密钥实现。
