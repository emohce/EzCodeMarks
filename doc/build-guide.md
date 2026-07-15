# EzCodeMarks 构建与验证指南

## 环境

- JDK 21
- IntelliJ IDEA 2025.3 SDK（Gradle 自动解析）
- Kotlin 2.4.0
- IntelliJ Platform Gradle Plugin 2.16.0

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

项目已禁用 `buildSearchableOptions`。IDEA 2025.3 的测试运行时使用 JetBrains 修订版协程；构建脚本会从 `testRuntimeClasspath` 排除会遮蔽它的外部 Kotlin/Coroutine runtime。主代码与测试代码的 IntelliJ instrumentation 任务也会串行执行，避免共享 Ant instrumenter 竞态。

## 构建插件 ZIP

```bash
./gradlew buildPlugin
```

当前版本的可安装包输出为：

```text
build/distributions/EzCodeMarks-1.0.1.zip
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
- Action 顺序、两个默认快捷键、Find Action 与 Keymap 分组正确。
- 工具栏隐藏不会影响快捷键调用；运行中再次触发可以取消，同文档兄弟 Action 暂时禁用。
- 未配置 Provider 可打开 AI Providers；连接测试、模型获取与 PasswordSafe 保存正常。
- 首次源码共享确认、Endpoint/Provider 变化后的重新确认，以及敏感/二进制/生成文件过滤正确。
- AI 结果只在预览 Apply 后写回；取消、失败、校验失败、原文中途修改与过期结果均保留原提交信息。
- 两个项目或两个提交文档之间的运行/取消状态互不影响。
- 英语、简体中文、日语和韩语界面无缺失 key 或枚举名泄漏。

## 发布边界

构建与本地验证不会发布插件。发布 JetBrains Marketplace 属于外部写入，需要单独确认并配置发布凭据；不要把 Token 写入仓库或构建脚本。
