# Environment Actions 与 Codex Chat 接口说明

## 设计边界

Environment Actions 保存“同机共享的动作定义”，项目只保存“当前选择的 Environment”。插件提供命令、脚本、Codex 一次性任务和提交说明准备能力，但不会替用户选择文件、暂存改动或创建 Git 提交。

Codex 有两条相互隔离的路径：

- **Codex Chat** 使用用户正常的 CLI home、账号、配置、Skills、插件和项目指令，通过 App Server 的同一临时 thread 承载多轮 turn。
- **Commit Message Provider** 继续使用 EzCodeMarks 管理的独立 home/账号和严格只读策略；Chat 不会放宽该策略。

主要入口见 [EnvironmentActionsPanel.kt](../src/main/kotlin/emohce/presentation/environmentaction/EnvironmentActionsPanel.kt#L41)、[InteractiveCodexSession.kt](../src/main/kotlin/emohce/data/environmentaction/InteractiveCodexSession.kt#L84) 与 [NativeCommitWorkflowLauncher.kt](../src/main/kotlin/emohce/presentation/environmentaction/NativeCommitWorkflowLauncher.kt#L113)。

## 数据接口

Environment 定义写入 JetBrains common-data 下的 `EzCodeMarks/environment-actions/global-settings.json`，不使用 IDE 漫游状态。schema v2 示例：

```json
{
  "schemaVersion": 2,
  "revision": "uuid",
  "defaultEnvironmentId": "uuid",
  "environments": [
    {
      "id": "uuid",
      "name": "Local tools",
      "workingDirectory": "/absolute/workspace",
      "variables": {"PYTHONUNBUFFERED": "1"},
      "actionOrder": [3, 1, 2, 4, 5, 6, 7, 8, 9, 10],
      "actions": [
        {
          "id": "uuid",
          "slot": 1,
          "name": "Run checks",
          "type": "SHELL",
          "command": "./gradlew test",
          "scriptPath": "",
          "arguments": "",
          "enabled": true,
          "allowNonGitDirectory": false
        }
      ]
    }
  ]
}
```

- 每个 Environment 固定补齐 10 个唯一槽位。`actionOrder` 只控制显示顺序，不改变 slot 或快捷键绑定。
- `revision` 是跨 IDE 乐观并发控制标识。存储使用文件锁、原子替换与 CAS；不同 Environment、slot 或字段的修改可三方合并，同字段冲突要求明确选择本地或远端版本。
- schema v1 的共享 `activeEnvironmentId` 首次读取时迁移为 `defaultEnvironmentId`；项目当前选择由 [EnvironmentActionProjectStateService.kt](../src/main/kotlin/emohce/data/environmentaction/EnvironmentActionProjectStateService.kt#L20) 保存到非漫游 workspace state。
- v1 `GIT_COMMIT` 无损迁移为 `PREPARE_COMMIT`，原 `command` 继续作为待填入的提交说明。
- Environment 变量只传给动作或该 Environment 新建的 Chat 进程。设置页拒绝疑似密钥变量名，输出与错误也会脱敏；仍不应在此保存密码、令牌或私钥。

存储、迁移和合并契约见 [EnvironmentActionSettingsService.kt](../src/main/kotlin/emohce/data/environmentaction/EnvironmentActionSettingsService.kt#L333)。

## Action 类型

| 类型 | 行为 |
| --- | --- |
| `SHELL` | macOS/Linux 通过 `/bin/sh -lc` 运行 `command`；Windows 通过 `cmd.exe /c` 运行。 |
| `SCRIPT` | 先应用 Environment 工作目录，再解析相对脚本路径；按扩展名选择 Python、Node.js、Shell、PowerShell、Ruby 或 PHP。 |
| `CODEX` | 以 stdin 传入提示词，执行 `codex exec --json --ephemeral`。只有显式开启“允许非 Git 目录”时才加入 `--skip-git-repo-check`。需要交互审批时任务失败并引导到 Codex Chat。 |
| `PREPARE_COMMIT` | 打开 IDE 原生 Commit 流程并准备提交说明；插件不选择文件、不暂存、不提交，也不启动 raw Git 进程。 |

`Detect type` 是本地确定性规则：常见脚本扩展名识别为 `SCRIPT`，`skill:`、`agent:` 或 `codex ` 前缀识别为 `CODEX`，历史 `git commit` / `commit:` 输入识别为 `PREPARE_COMMIT`，其他内容默认是 `SHELL`。

Shell、Script 与 Codex one-shot 统一由 [EnvironmentActionExecutionService.kt](../src/main/kotlin/emohce/data/environmentaction/EnvironmentActionExecutionService.kt#L135) 通过平台进程 API 启动。不同 slot 可以并行，同一项目内同一 Action 实例不会重复启动；每次执行最长 10 分钟，stdout/stderr 各保留最后 64,000 字符。Stop、超时和 ToolWindow/项目释放都会终止进程树。

## 原生 Commit 融合

`PREPARE_COMMIT` 的流程如下：

1. 校验项目存在活动 VCS；先从当前焦点 DataContext 查找传统/模态 Commit 编辑器，再在项目 frame 中查找已显示的非模态 Commit ToolWindow 编辑器。
2. 当前 Commit 编辑器可用时，空内容直接填入；非空内容由用户选择 Replace、Append 或 Cancel。没有编辑器时才调用 IDE 的 `CheckinProject` Action 打开或激活 Commit UI。
3. 编辑器尚未建立时，注册的 `vcs.commitMessageProvider` 最多消费一次、有效 5 秒的草稿。
4. IDE 未消费草稿时，将提交说明复制到剪贴板并提示用户手动粘贴；草稿不会延迟污染后续提交。

整个链路不存在 `git add`、`git commit`、自动暂存或自动提交。最终文件范围、检查和提交副作用仍由 IDE Commit UI 与用户控制。

## Codex Chat 会话

工具窗口每个项目维护一个不持久化的临时 App Server 会话：

- 首次 Send 启动 `thread/start`，设置当前项目或所选 Environment 的 cwd 与 `ephemeral=true`；不覆盖 sandbox 或 approval，因此继承用户正常 Codex CLI 配置。
- 同一 thread 上连续执行 `turn/start`，流式展示 assistant、tool、approval、completed 和 error 事件；同一时刻只允许一个 turn。
- 首轮前必须取得 App Server 报告的权限 profile、sandbox、approval、network、指令来源以及 Skills/插件状态。无法确认有效权限时阻止发送并提示升级 CLI。
- `dangerFullAccess`，或“可写且永不审批”等宽权限组合，每个新会话首轮前都要求一次明确确认，并持续显示权限摘要。
- 审批请求在 ToolWindow 内以内联、脱敏、非阻塞控件排队展示，只由用户 Allow/Deny；插件不自动代表用户批准，等待审批时 Stop 仍可操作。Stop 发送 `turn/interrupt`，New conversation 中断并关闭旧连接、清空内存 transcript。
- 切换 Environment 不会静默改变已有会话 cwd，必须开始新会话。ToolWindow 或项目关闭时先中断，再关闭连接并终止残留进程树。

聊天 transcript 和 thread ID 均不持久化，插件或 CLI 进程异常后只能开始新会话。

## 设置、快捷键与本地化

- `Settings → Tools → EzCodeMarks → Environment Actions` 使用原生 Configurable，支持 Apply/Reset/Cancel、目录选择器、排序和并发冲突处理。
- `Settings → Tools → EzCodeMarks → Git Commit Message → Codex CLI` 管理可执行文件路径。未 Apply 的路径不能用于检查、登录或设备登录。
- ToolWindow 中的两个设置入口按 Configurable class 打开对应页面，不把 descriptor ID 当作显示名，也不回落到上次访问的设置页。
- Codex CLI 页明确区分普通 Interactive CLI 身份与隔离 Commit Provider 身份。页面内登录/退出只操作隔离 Provider 账号，属于即时凭据副作用，执行前确认且不能由 Cancel 撤销。
- `EzCodeMarks.EnvironmentAction.Slot1` 至 `Slot10` 保持稳定；插件不修改用户 Keymap，只从设置页跳转到平台 Keymap 配置。
- Action、设置、通知、审批、错误和空状态均提供 English、简体中文、日文与韩文资源。

## 安全边界

- 已保存的 Shell、Script 与 Codex one-shot 会以用户权限启动本地进程；保存前应检查工作目录、命令和参数。
- Codex Chat 明确展示继承的权限，并保留用户审批与停止控制；它不会借用 Commit Provider 的隔离账号。
- `PREPARE_COMMIT` 只准备文本和打开 IDE UI，不拥有 Git 副作用。
- 插件不持久化聊天、不保存普通 CLI 凭据，也不把 Environment 变量当作秘密存储。
