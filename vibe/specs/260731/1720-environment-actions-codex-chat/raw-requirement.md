# Environment Actions / Codex Chat 原始需求

## Metadata

- Captured: 2026-07-31
- State: implemented and accepted; automated and local fake-runtime verification complete
- Privacy boundary: normalized semantic record; no prompt or transcript persistence

## Requirement

- 保留已验收的 Commit Message r7 垂直切片及其隔离 Codex App Server 策略，不因交互聊天放宽权限或引入 Bookmark ToolWindow 耦合。
- Environment Action 定义同机跨 JetBrains 产品共享，10 个稳定 slot Action ID 与用户 Keymap 保持兼容；显示顺序可持久化，当前 Environment 按项目保存。
- Codex Chat 使用用户正常 Codex CLI 配置、账号、Skills、插件和项目指令，以 App Server thread/turn 建立真实临时多轮会话，支持流式事件、权限展示、审批和停止。
- 保存型 Codex Action 仍是清晰标识的一次性任务；不得把多个 `codex exec` 输出伪装成对话。
- 旧 `GIT_COMMIT` Action 迁移为准备提交说明并打开 IDE 原生 Commit 流程；插件不得选择、暂存或提交文件，不得执行 raw Git commit。
- Shell、Script、Codex 进程必须有明确工作目录、隔离输出、超时和真实进程终止；ToolWindow 资源与项目生命周期绑定。
- 使用 JetBrains 原生 Action、Configurable、ToolWindow、资源包和持久化边界；设置的 Apply/Cancel 与立即生效的登录/登出操作必须清楚区分。
- 实现、测试、用户文档、项目当前状态和任务证据同批同步；不执行真实登录、模型调用、提交、发布或外部写入作为自动验证。

## 2026-08-01 Runtime Acceptance Delta

- 用户解锁 macOS 会话后，使用隔离 IntelliJ 2026.1.4 沙箱和本地 fake Codex App Server 完成真实宿主验收。
- 验收必须覆盖同一 thread 多 turn、流式权限摘要、显式审批、Stop、New conversation、设置 Apply/Cancel 与定向跳转、项目/ToolWindow 释放，以及原生 Commit 编辑器 Replace / Append / Cancel；不以源码形态或输出拼接代替运行证据。
- fake runtime 只能模拟协议和本地 UI，不执行真实登录、模型推理、源码发送、文件暂存、Git Commit、发布或其他外部写入。
