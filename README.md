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
- 根据已包含的 Git 变更生成提交信息；也可只针对当前提交文本输入一次性提示词进行 AI 优化，无需加载 Git diff。
- AI 结果默认在校验后直接写回，也可开启“写回前预览”；取消、失败或校验失败不会改写提交框。
- 写回前预览支持继续用 AI 优化：可先让 AI 优化本次修改提示，确认实际发送的 SYSTEM/USER 提示词，再生成新结果并查看本次会话的完整优化记录。
- 管理 Velocity 模板、提交类型、标准/精简/自定义提交风格，以及多个 OpenAI Compatible、Anthropic 或 ChatGPT / Codex Provider Profile。
- API 密钥只进入 IntelliJ PasswordSafe；ChatGPT 登录和令牌只由用户安装的 Codex CLI 管理；源码上下文经过敏感过滤、长度限制和首次发送确认。
- Action、设置和错误文案提供英语、简体中文、日语和韩语资源。

## 环境要求

- IntelliJ IDEA 2025.3+（since build 253）
- JDK 21
- Kotlin 2.4.0
- IntelliJ Platform Gradle Plugin 2.16.0
- Git4Idea（随 IDE 提供）
- 可选：使用 ChatGPT / Codex Provider 时需安装 `codex-cli 0.144.5+`

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
| Generate Commit Message | 是 | `Ctrl+Alt+X` | `⌃⌥X` |
| Generate With Additional Requirements | 是 | 未预设 | 未预设 |
| Format Commit Message | 否 | 未预设 | 未预设 |

工具栏显隐只影响 Commit Message 位置；隐藏后仍可通过快捷键或 Find Action 调用。运行中的 Action 会显示取消图标，再次触发可取消；同一提交文档的其他提交助手 Action 会暂时禁用。

`Format Commit Message` 会先询问仅本次生效的可选优化提示词；留空则按当前 Commit Style 优化，取消不会发送请求。Format 只发送当前提交文本、模板、风格和本次提示词，不读取或发送 Git status、diff、未版本化文件、revision 或最近提交。

启用“写回前预览”后，可在结果页点击“使用 AI 再优化…”。每次操作会保留最开始的提交信息、首次 AI 结果、当前最终结果，以及修改前后文本、原始提示、AI 优化提示、用户确认提示和已确认提示词请求；“优化记录”可查看或复制这些内容。记录仅存在于当前预览会话，不会进入插件 XML、workspace、`.codemark`、日志或分析数据。二次优化只发送当前结果、已确认指令、模板和风格，不发送最开始的提交信息或 Git 上下文；一次操作最多共享 3 次 Provider 请求。

`Select Commit Style` 可通过 Find Action、Keymap 或 `Tools | EzCodeMarks | Git Commit Message` 快速切换当前项目风格；默认不占用提交工具栏和快捷键。

设置位于 `Settings | Tools | EzCodeMarks | Git Commit Message`：

- `Git Commit Message`：工具栏显隐、当前 Keymap/冲突、字段显示、类型展示、skip-ci、可选的 AI 写回前预览，以及跨 JetBrains 产品同步的全局附加指令/冲突处理。
- `Commit Template`：按原助手布局提供 Template / Type / Style 三个页签；支持模板与类型维护、Velocity 安全校验/实时预览、风格预览，以及根据自然语言用 AI 生成风格提示词和模板。
- `LLM Settings`：按原助手布局提供顶部活动模型与全局参数、Profile 表格和 Profile 编辑对话框。API Key 使用宽幅掩码输入并在当前设置会话保留；模型下拉支持实时模糊筛选和自定义 ID；Fetch models 与 Test 都可取消并显示阶段状态。此页也配置 Codex 可执行文件、ChatGPT 浏览器/设备码登录、状态检查和共享账户退出。
- `Project Private`：仅当前 workspace 使用的模板/风格选择和未完成草稿。
- `Project Providers`：仅当前 workspace 使用的私有 Profile、活动选择和 PasswordSafe 凭据；不设置时回退到全局活动 Profile。
- `Project Shared`：可提交到项目的附加指令、模板、风格及共享默认值；项目指令支持继承、追加或替换全局附加指令。

`Test` 先调用模型列表接口；已选择模型时再发送最多 8 token 的最小推理请求。未选择模型时测试在列表阶段结束并提示选择，避免无意义推理；界面会明确提示该操作可能产生 Provider 请求或费用。

AI Provider 未配置时，Action 会给出可恢复提示并可直接打开对应 Provider 设置。ChatGPT / Codex Profile 不使用 API Key：EzCodeMarks 通过官方稳定 App Server 协议调用独立 Codex home 中的共享机器账户，并在每次生成时使用无历史、无项目指令、无项目/用户目录读取、无网络工具的临时结构化线程。

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

提交信息助手不接入 Bookmark ToolWindow、BookmarkViewModel、SelectionBus 或 `.codemark`。全局配置同时写入 JetBrains common-data 原子快照和可漫游的 `TOOLS` 状态；分支冲突必须在设置页明确选择。项目共享定义写入 `.idea/ezCodeMarkCommitMessage.xml`，私有 Profile/选择/草稿/同意状态只写 workspace state。API 密钥只由 PasswordSafe 保存；Codex 可执行文件、账户代次和 Codex home 仅保存在机器公共数据目录，OAuth 令牌始终由 Codex 管理。

OpenAI Compatible / Anthropic 请求使用 IDE Proxy，默认连接超时 15 秒、读取上限 120 秒并支持取消；ChatGPT 请求由隔离的 Codex App Server 管理。发送的 Git 上下文会过滤二进制、生成文件、`.env*`、密钥/证书/SSH/credentials/service-account/secrets 等路径或内容；v1 不提供绕过开关。ChatGPT 登录、更换账户或退出会使已有源码发送同意失效。

## 验证

```bash
./gradlew test
./gradlew buildPlugin
./gradlew verifyPluginProjectConfiguration
./gradlew verifyPluginStructure
./gradlew verifyPlugin
```

测试覆盖结构化解析/渲染、Velocity、双载体同步/冲突、项目共享/私有状态、PasswordSafe 边界、Codex App Server 隔离/取消/工具拒绝、上下文过滤与裁剪、Provider/SSE/回退，以及 Action、快捷键、DataKey 和设置生命周期。

## 项目文档

- [用户指南](doc/USER_GUIDE.md)
- [构建指南](doc/build-guide.md)
- [变更日志](doc/change-log.md)
- [书签树操作规格](doc/260604-cursor-tree-operations-spec.md)
- [当前项目状态](vibe/specs/PROJECT_STATUS.md)

## 许可证与来源说明

本项目采用 [LICENSE](LICENSE) 中的许可证；第三方文本归属见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

代码书签能力参考了 [CodeTour](https://github.com/LefterisXris/CodeTour) 与 [Bookmark-X](https://github.com/Nonoas/Bookmark-X)。提交信息助手根据 Apache-2.0 项目 [Git Commit Message Helper](https://github.com/AutismSuperman/git-commit-message-helper) 的产品行为重新设计，并按 EzCodeMarks 当前 Kotlin/JDK 21/IntelliJ 2025.3 架构实现；默认 Velocity 模板和提交类型描述属于经适配的上游文本并随分发包保留 Apache-2.0 许可，其 Swing `.form`、反射、裸 Git/HTTP 或明文密钥实现未被复制。
