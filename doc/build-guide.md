# EzCodeMarks 构建与验证指南

## 环境

- JDK 21
- IntelliJ IDEA 2025.3 SDK（Gradle 自动解析）
- Kotlin 2.4.0
- IntelliJ Platform Gradle Plugin 2.16.0
- 可选：`codex-cli 0.144.5+`，仅用于 ChatGPT / Codex Provider

项目构建声明见 [build.gradle.kts](../build.gradle.kts#L23)。提交信息助手显式依赖 VCS、Git4Idea 与 IDE 随附的 Velocity 模块。

## 本地运行

macOS / Linux：

```bash
./gradlew runIde
```

Windows：

```bat
gradlew.bat runIde
```

`runIde` 会启动安装当前插件的沙盒 IDE。提交信息助手需分别人工检查非模态 Commit ToolWindow 与传统 Commit Dialog。

## 测试与完整验证

按以下顺序串行执行：

```bash
./gradlew test
./gradlew buildPlugin
./gradlew verifyPluginProjectConfiguration
./gradlew verifyPluginStructure
./gradlew verifyPlugin
```

各任务用途：

| 任务 | 用途 |
| --- | --- |
| `test` | 运行 domain、data、Mock HTTP 与 IntelliJ fixture 测试 |
| `buildPlugin` | 构建可安装 ZIP |
| `verifyPluginProjectConfiguration` | 校验 Gradle/SDK/plugin.xml 项目配置 |
| `verifyPluginStructure` | 校验插件包结构与描述符 |
| `verifyPlugin` | 使用 JetBrains Plugin Verifier 检查兼容性 |

若 `verifyPlugin` 因网络不可用而无法解析动态 IDE 元数据，Gradle `--offline` 也可能在已有解压 IDE 缓存时报告无可用缓存。只有在 Plugin Verifier CLI 与每个精确 build 的本地 IDE 都已存在时，才可按[已验证的离线回退路线](../vibe/knowledge/error-memory/plugin-verifier-gradle-offline-metadata-fallback.md)直接运行本地矩阵；必须检查每个 verdict，不能把依赖解析失败当成兼容性结论。

项目已禁用 `buildSearchableOptions`。IDEA 2025.3 的测试运行时使用 JetBrains 修订版协程；构建脚本会从 `testRuntimeClasspath` 排除会遮蔽它的外部 Kotlin/Coroutine runtime。主代码与测试代码的 IntelliJ instrumentation 任务也会串行执行，避免共享 Ant instrumenter 竞态。

## 构建插件 ZIP

```bash
./gradlew buildPlugin
```

当前版本的可安装包输出为：

```text
build/distributions/EzCodeMarks-1.2.0.zip
```

安装步骤：

1. 打开 IntelliJ IDEA。
2. 进入 `Settings → Plugins`。
3. 点击齿轮并选择 `Install Plugin from Disk…`。
4. 选择生成的 ZIP 并按提示重启。

普通 JAR 位于 `build/libs/`，仅用于构建内部产物；分发应使用 `buildPlugin` 生成的 ZIP。

## 人工验收清单

在 `./gradlew runIde` 沙盒中检查：

- Commit ToolWindow 与传统 Commit Dialog 都显示 Create、Generate、Generate With Additional Requirements；Format 默认隐藏。
- Action 顺序、两个默认快捷键、Find Action 与 Keymap 分组正确；Generate 在 Windows/Linux 为 `Ctrl+Alt+X`，macOS 为 `Control+Option+X`。
- 工具栏隐藏不会影响快捷键调用；运行中再次触发可以取消，同文档兄弟 Action 暂时禁用。
- 未配置 Provider 可打开 LLM Settings；API Key 输入框宽度合理，密钥在设置会话与 Apply 后保持掩码，未 Apply 的输入可用于连接测试/模型获取，显式 Clear 后仅在 Apply 时删除；Model 输入触发实时模糊筛选并保留自定义值；Fetch/Test/关闭对话框能取消请求，过期结果不覆盖当前模型列表。
- 首次源码共享确认、Endpoint/Provider 变化后的重新确认，以及敏感/二进制/生成文件过滤正确。
- 默认 AI 结果在校验后直接原子写回；启用预览选项时只在 Preview Apply 后写回。两种模式下取消、失败、校验失败、原文中途修改与过期结果均保留原提交信息。
- 预览页显示“使用 AI 再优化…”与“优化记录”；提示词优化和提交优化都先展示实际 SYSTEM/USER 主请求供确认。成功操作记录最开始/首次 AI/当前最终结果、before/after 与原始/优化/确认提示，复制包含完整会话；关闭预览后记录不持久化。
- 在取消或结束原 Action handle 后尝试二次优化会被拒绝；响应返回后 handle 过期也不会覆盖结果或追加记录。二次优化及其 JSON 修复不含最开始的提交信息或 Git 上下文，并共用 3 次 Provider 请求预算。
- Format 先显示可选的一次性优化提示词；取消不发起请求，留空仍按当前风格优化。请求只含当前提交文本/模板/风格/本次提示词，不含 Git status、diff、未版本化内容、revision 或最近提交。
- Commit Template 页包含 Template / Type / Style 三个页签；风格模板实时预览，AI 风格建议必须确认且不会覆盖请求期间的新编辑。
- Select Commit Style 可从 Find Action、Keymap 和 Tools 菜单调用，且不会进入固定四 Action 提交工具栏组。
- 两个项目或两个提交文档之间的运行/取消状态互不影响。
- 英语、简体中文、日语和韩语界面无缺失 key 或枚举名泄漏。
- 分发 ZIP 包含 `META-INF/THIRD-PARTY-NOTICES.txt` 与上游 Apache-2.0 完整许可文本。
- 全局配置在同机 common-data 与漫游状态间收敛；分支冲突必须显式选择。项目共享配置写入 `.idea/ezCodeMarkCommitMessage.xml`，项目私有 Profile/草稿/同意仍在 workspace，密钥仍在 PasswordSafe。
- ChatGPT / Codex 的自动化验收使用假 App Server；常规验证不得执行真实登录、退出、推理或源码发送。需要人工账户验收时应单独确认外部操作边界，并检查独立 Codex home、模型列表、最小 Test、取消和共享机器退出提示。

## 发布边界

构建与本地验证不会发布插件。发布 JetBrains Marketplace 属于外部写入，需要单独确认并配置发布凭据；不要把 Token 写入仓库或构建脚本。
