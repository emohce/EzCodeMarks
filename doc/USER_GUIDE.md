# EzCodeMarks 用户指南

> **版本**：1.2.0
> **更新日期**：2026-07-31
> **适用版本**：EzCodeMarks 1.2.0+

---

## 目录

- [快速开始](#快速开始)
- [Git 提交信息助手](#git-提交信息助手)
- [Environment Actions](#environment-actions)
- [基本操作](#基本操作)
- [书签树管理](#书签树管理)
- [编辑器集成](#编辑器集成)
- [流程导航](#流程导航)
- [搜索功能](#搜索功能)
- [快捷键参考](#快捷键参考)
- [高级功能](#高级功能)
- [常见问题](#常见问题)

---

## 快速开始

### 安装插件

1. 下载插件 ZIP 文件
2. 打开 IntelliJ IDEA
3. 进入 `Settings → Plugins → ⚙️ → Install Plugin from Disk...`
4. 选择下载的 ZIP 文件
5. 重启 IDE

### 打开工具窗口

- 通过菜单：`View → Tool Windows → EzCodeMarks`
- 或点击右侧边栏的插件图标

### 创建第一个书签

1. 在编辑器中将光标定位到要标记的代码行
2. 按 `Shift+F2` 或右键选择 `Add CodeMark Here`
3. 输入书签名称和描述
4. 点击 OK

---

## Git 提交信息助手

### 入口与默认快捷键

提交信息助手会出现在非模态 Commit ToolWindow 与传统 Commit Dialog 的提交信息 Action 区域，也可通过 Find Action 或 Keymap 调用。

| Action | 默认显示 | Windows / Linux | macOS | 用途 |
| --- | --- | --- | --- | --- |
| Create Commit Message | 是 | `Ctrl+Alt+Shift+M` | `⌘⌥⇧M` | 打开结构化编辑器 |
| Generate Commit Message | 是 | `Ctrl+Alt+X` | `⌃⌥X` | 根据已包含的变更生成 |
| Generate With Additional Requirements | 是 | 未预设 | 未预设 | 先输入附加要求再生成 |
| Format Commit Message | 否 | 未预设 | 未预设 | 用 AI 格式化当前草稿 |

工具栏显隐只影响 Commit Message 位置，不会删除 Action 或快捷键。运行中的 Action 会切换为取消图标；再次触发可取消，同一提交文档的另外三个助手 Action 会暂时禁用。

### 创建结构化提交信息

1. 在提交面板点击 `Create Commit Message`。
2. 编辑 `type`、`scope`、`subject`、`body`、`breaking changes`、`closes` 与 `skip-ci`。
3. `subject` 始终显示且必须填写。
4. 点击 Apply 后，当前项目选中的 Velocity 模板会渲染并写回提交框。

初始值按以下顺序取得：当前非空提交信息的本地解析结果、项目未完成草稿、默认值。启用 Smart Echo 且已有可用 AI Profile 时，会在后台增强解析结果。取消结构化对话框会保留项目草稿；成功应用后会清除草稿。

### AI 生成与格式化

- `Generate` 使用提交工作流中已包含的变更与未版本化文件；历史提交场景只读取 IDE 提供的公开 revision。
- `Generate With Additional Requirements` 会先打开多行输入框；取消该对话框不会发起网络请求。
- `Format` 只在当前提交信息非空时可用。它先打开一次性优化提示词输入框：留空时使用当前 Commit Style，取消不会读取凭据或发起请求；提示词不会持久化。
- `Format` 仅把当前提交文本、模板、风格和本次提示词交给 AI，不采集或发送 Git status、diff、未版本化文件、revision 或最近提交；结果保留事实含义并按配置语言返回。
- AI 结果通过结构化校验和安全模板渲染后，默认原子写回提交框；可在根设置页开启“写回前预览”，此时左侧显示原文，右侧结果可编辑，并显示 Provider、Endpoint、模板、风格和被过滤文件。
- 预览模式下只有点击 Apply 才会写回，Copy 只复制结果。无论是否启用预览，Cancel、网络失败、输出校验失败、操作过期或运行期间原文被修改时，原文都保持不变。

### 在预览页继续优化

启用“写回前预览”后，结果页会增加“使用 AI 再优化…”和“优化记录”操作：

1. 点击“使用 AI 再优化…”，输入本次修改提示。
2. 可先点击“使用 AI 优化提示词…”。发送前会展示本次提示词优化实际使用的完整 SYSTEM/USER 文本，确认后才调用 Provider；AI 返回的优化提示仍可编辑。
3. 点击“确认并优化提交信息”时，会再次展示实际用于修改当前提交信息的完整 SYSTEM/USER 文本。确认后才生成新结果。
4. 成功结果替换右侧可编辑内容；取消、失败、过期或校验失败不会修改结果，也不会新增记录。
5. “优化记录”显示最开始的提交信息、首次 AI 结果、当前最终结果，以及每次成功操作的修改前后文本、输入提示、AI 优化提示、最终确认提示和两类提示词请求；复制记录会包含全部这些内容。

历史只保存在当前预览对话框内存中，关闭后即释放，不会写入应用设置、workspace、`.codemark`、日志或分析数据。二次优化请求只包含当前结果、用户确认指令、已校验模板和风格；不包含最开始的提交信息，也不会重新采集 Git status、diff、文件、revision 或提交历史。一次明确操作共用最多 3 次 Provider 请求：可选提示词优化、提交信息优化，以及必要时的一次结构化 JSON 修复。

### 提交风格

- 内置 `标准` 保持原助手的均衡行为；`精简` 优先使用 72 字符以内的祈使句 subject，除非关键信息无法容纳，否则省略 body。
- 可在 `Commit Template → Style` 新建自定义风格，填写自然语言描述、模型提示词和可选 Velocity 模板，并实时查看最终渲染预览。
- “使用 AI 生成提示词与模板…”只发送风格描述、当前提示词和已校验模板，不发送 Git diff 或源码；生成结果必须在对话框确认后才更新当前风格。
- 使用 Find Action、Keymap 或 `Tools → EzCodeMarks → Git Commit Message → Select Commit Style` 可快速切换当前项目风格。

### 设置树

进入 `Settings → Tools → EzCodeMarks → Git Commit Message`：

- `Git Commit Message`：配置工具栏显隐、查看当前 Keymap/快捷键冲突、打开 Keymap 设置、控制字段显示与 type 展示方式、skip-ci、是否在 AI 写回前显示预览，以及全局附加指令和同步冲突处理。
- `Commit Template`：按原助手布局提供 Template / Type / Style 页签。Template 管理默认值和安全 Velocity 编辑/预览；Type 管理 conventional commit 类型与顺序；Style 管理标准、精简和自定义风格及 AI 优化。
- `LLM Settings`：按原助手布局提供顶部 Active Model、Temperature、Response Language、Smart Echo、Streaming、Reasoning 与 Test，下方表格用于 Profile 的新增、删除、编辑和复制。表格选中只决定管理对象，不会偷偷切换 Active Model；可点击铅笔按钮编辑，也可左键双击具体 Profile 行进入同一编辑对话框。ChatGPT / Codex 区域只显示当前可执行文件并打开独立 `Codex CLI` 设置页。
- Profile 编辑对话框中的 API Key 使用宽幅掩码输入；新输入密钥在当前设置会话中保留，Apply 后也不会清空。空输入框不代表删除，只有确认 `Clear API key…` 后再 Apply 才会删除凭据。Model 下拉输入会实时进行大小写无关的 exact / prefix / substring / subsequence 模糊筛选；可滚动后鼠标或键盘选择并点击 `OK` 保存，也允许输入并保留自定义模型 ID。模型请求返回前输入的搜索词会继续应用到新列表。
- `Fetch models` 与 `Test` 都会立即显示阶段状态并可再次点击取消；关闭 Profile 对话框也会取消其模型请求并清零临时密钥副本。`Test` 先获取模型列表；选择模型后再发起最多 8 token 的最小推理，未选择模型则停在列表阶段。该操作可能产生 Provider 请求或费用。
- `Project Private`：选择仅当前 workspace 生效的模板/风格并清除未完成草稿。
- `Project Providers`：定义仅当前 workspace 使用的 Provider Profile、活动 Profile 和 PasswordSafe 凭据；选择“使用全局 Profile”时回退到全局活动 Profile。
- `Project Shared`：维护可随项目提交的附加指令、模板、风格和共享默认值。项目指令可选择继承、追加或替换全局附加指令，但不会替换内置结构化输出、事实、隐私与安全约束。

“配置快捷键…”只打开 IntelliJ 公共 Keymap 设置并定位 Action，不会在运行时修改用户键位方案。复制 Profile 不复制 API 密钥；删除 Profile 会同步删除其 PasswordSafe 凭据。

### ChatGPT / Codex Provider

1. 安装 `codex-cli 0.144.5` 或更新版本。
2. 在 `Settings → Tools → EzCodeMarks → Git Commit Message → Codex CLI` 填写 Codex 可执行文件路径；留空时通过平台 PATH 和标准安装位置探测。可执行文件字段遵循 Apply/Reset/Cancel，未 Apply 时检查和登录操作保持禁用。
3. 点击刷新检查安装与账户状态，再选择浏览器登录或设备码登录。这里操作的是隔离的 Commit Provider 账号；登录和退出立即生效，执行前会确认，不能由 Cancel 回滚。令牌仍由 Codex App Server 管理，EzCodeMarks 不读取 OAuth 令牌。
4. 新建 `ChatGPT / Codex` Profile，使用 `Fetch models` 选择文本模型。该 Provider 不显示 Endpoint，也不需要 API Key。
5. `Test` 仍会先读取模型，并在已选择模型时执行最多 8 token 的最小推理；它可能使用 ChatGPT 配额。

EzCodeMarks 在 JetBrains 公共数据目录为 Commit Provider 使用独立 Codex home，因此同一机器上的 EzCodeMarks JetBrains 产品共享一个隔离 ChatGPT 账户，但不会复用普通 Codex CLI 或 VS Code 的账户。退出会影响所有这些 EzCodeMarks 实例，并使既有源码上下文同意失效。Environment Actions 中的 **Codex Chat** 是另一条路径：它使用用户正常 CLI home、账号、配置、Skills、插件和项目指令，不会放宽 Commit Provider 的只读隔离。

每次生成使用新的临时结构化线程：不加载项目指令文件，不持久化对话历史，不读取项目或用户 home，只允许读取空的隔离工作目录，并禁用模型网络、Shell、浏览器、MCP、Hook 和委派工具。隔离结果不匹配、出现未知工具事件、取消时尚未取得线程/Turn ID，都会终止该 App Server 进程并拒绝结果。

### 全局同步与项目数据

- 全局配置同时保存在 JetBrains common-data 原子快照和可漫游的 `TOOLS` 状态中，可覆盖同机跨产品与 Backup and Sync 场景。祖先版本自动收敛；分支冲突会在 `Git Commit Message` 设置页要求明确选择机器版本或同步版本。
- 同步内容不包含 API Key、OAuth 令牌、账户邮箱、授权码、Codex 可执行文件、账户代次或源码同意。
- 项目共享设置写入 `.idea/ezCodeMarkCommitMessage.xml`，可按团队策略纳入版本控制；项目私有 Profile、活动选择、草稿、凭据与源码同意仍留在 workspace/PasswordSafe。

### Provider 与隐私边界

- OpenAI Compatible 使用 `/chat/completions` 与 `/models`，通过 Bearer 认证。
- Anthropic 使用 `/v1/messages` 与 `/v1/models`，通过 `x-api-key` 认证。
- ChatGPT / Codex Commit Provider 使用用户安装的 Codex CLI 稳定 App Server 协议；其账户和令牌由独立 Codex home 管理，不进入插件状态。Codex Chat 使用普通 CLI 身份，插件不持久化其凭据或对话。
- API 密钥只保存在 IntelliJ PasswordSafe，不会写入插件 XML、项目 workspace 或 `.codemark`。
- 每个 Profile 第一次向当前 Provider 类型与 Endpoint 发送源码上下文时，必须确认数据共享；Endpoint、Provider 或隐私策略变化后会再次确认。
- 上下文会过滤二进制、生成文件、`.env*`、凭据、token、私钥、证书、SSH、service-account、Docker/Kubernetes 认证文件等敏感路径或内容。
- 状态、diff、未版本化文本和最近提交均有固定预算；v1 不提供关闭敏感过滤或突破预算的开关。
- 请求遵循 IDE Proxy，支持取消；单次操作最多调用 Provider 三次，只对明确的 streaming/reasoning 参数不兼容或结构化 JSON 修复做受控回退。
- Velocity 仅允许结构化字段、局部变量和 `trim/lower/truncate` 字符串 helper；文件加载、`#parse/#include/#evaluate/#foreach`、任意成员访问、范围与超长模板/输出会被拒绝。

项目私有模板/风格选择、Profile、未完成草稿和源码同意保存在 IDE workspace state，不会进入 CodeMark 数据文件；项目共享模板、风格、附加指令和默认值保存在 `.idea/ezCodeMarkCommitMessage.xml`。

---

## Environment Actions

Environment Actions 的定义在同机 JetBrains IDE 间共享，当前 Environment 则按项目保存在非漫游 workspace state。打开 `Settings → Tools → EzCodeMarks → Environment Actions` 创建或编辑 Environment；同一机器中的 IntelliJ IDEA、Rider、WebStorm 等产品读取相同定义，但各项目可保持自己的当前选择。

每个 Environment 默认包含 10 个固定 Action 槽位。可编辑 Action 名称、类型、定义、参数、工作目录与环境变量，并拖拽调整显示顺序。拖拽不会影响槽位：`Environment Action Slot 1` 始终调用槽位 1，依此类推。可通过设置页的“Configure shortcuts…”为每个槽位打开 IDE Keymap 配置；插件不会覆盖已有快捷键。

| Action 类型 | 配置内容 | 执行方式 |
| --- | --- | --- |
| Shell | 命令 | macOS/Linux 使用 `/bin/sh -lc`，Windows 使用 `cmd.exe /c`。 |
| Script | 脚本路径与参数 | 支持 Python、Node.js、Shell、PowerShell、Ruby、PHP 等常见扩展名，并自动选择解释器。 |
| Codex one-shot job | 提示词 | 通过 stdin 调用 `codex exec --json --ephemeral`；默认要求 Git 目录，只有显式开启“允许非 Git 目录”才跳过检查。需要交互审批时引导到 Codex Chat。 |
| Prepare Commit | 提交说明 | 打开 IDE 原生 Commit 流程并安全填入说明；插件不选择文件、不暂存、不提交。 |

创建或编辑 Action 后可选择一行并点击 `Detect type`：常见脚本扩展名、`skill:`/`agent:`/`codex ` 前缀和历史 `git commit`/`commit:` 前缀会由本地规则分别识别为 Script、Codex one-shot 或 Prepare Commit；其他内容默认是 Shell。

可在 EzCodeMarks 工具窗口的 `Environment Actions` 标签选中 Environment、查看 Action、运行或停止进程。Shell、Script 与 one-shot 由平台进程 API 管理，输出流相互隔离；超时、Stop 或 ToolWindow 释放都会终止进程树。Prepare Commit 只打开原生 Commit UI：空提交框直接填入，非空时选择 Replace / Append / Cancel；若平台未消费短时草稿，说明会复制到剪贴板，绝不会自动执行 `git add` 或 `git commit`。

同一标签中的 **Codex Chat** 是真实临时会话：首次发送建立一个 `ephemeral` App Server thread，后续消息在该 thread 上启动新的 turn，并流式显示消息、工具与审批事件。会话继承用户正常 CLI 的 sandbox、approval、network、Skills、插件和项目指令；无法确认有效权限时会阻止首轮，宽权限配置每个新会话都需确认。审批以内联、脱敏、非阻塞控件展示，必须由用户 Allow/Deny，等待期间 Stop 仍可操作；切换 Environment 后需 New conversation，聊天记录不会持久化。

Environment 变量不是秘密存储。设置页会拒绝疑似密钥变量名，日志和通知也会脱敏，但仍请勿在变量、命令或参数中保存密码、令牌或私钥。

完整的数据接口、执行限制与安全边界见 [Environment Actions 接口说明](environment-actions.md)。

---

## 基本操作

### 创建书签

**方式一：快捷键**
- `Shift+F2` - 在当前行创建/编辑书签
- `Shift+F3` - 创建分组
- `Shift+F4` - 创建描述性笔记

**方式二：右键菜单**
- 在编辑器中右键，选择 `Add CodeMark Here`
- 根据当前行是否有书签，菜单会自动显示「新增」或「编辑」

**方式三：工具窗口**
- 在书签树中右键，选择 `Add CodeMark`

### 编辑书签

- 在有书签的行按 `Shift+F2`，或右键选择 `Edit CodeMark`
- 在书签树中双击节点，或右键选择 `Edit`
- **独立编辑窗口**：在编辑对话框中聚焦 Description 或 Markdown 字段后，按 `F2` 键可弹出独立编辑窗口
  - 独立窗口提供完整的编辑器体验（支持 IdeaVim）
  - 编辑内容自动同步回原对话框
  - 按 `ESC` 键关闭独立窗口
  - Description 原有换行会保留
- **悬浮详情**：在编辑器中光标定位到有书签的行，按 `Shift+F1` 可显示书签悬浮详情
  - 悬浮窗显示书签名称、类型、文件路径和完整描述
  - 支持基础 Markdown 渲染和超链接
  - 按 `ESC` 键关闭悬浮窗，保持编辑器焦点

### 删除书签

- 在有书签的行按 `Shift+Delete`，或右键选择 `Delete CodeMark`
- 在书签树中选中节点，按 `Delete` 键或右键选择 `Delete`
- 删除前会弹出确认对话框

---

## 书签树管理

### 树结构

EzCodeMarks 使用树形结构组织书签，支持以下节点类型：

- **Bookmark（书签）** - 关联代码位置的书签
- **Group（分组）** - 文件夹式容器，可包含其他节点
- **Process（流程）** - 有序步骤容器，支持流程导航
- **DescriptiveBookmark（描述性笔记）** - 纯文本/Markdown 笔记

Bookmark 和 Group 节点会在名称后方以灰色后缀展示 description 的首个非空行，便于在树中快速识别用途。

### 树操作

**导航**
- `↑ / ↓` - 在可见行间移动选中
- `← / →` - 折叠/展开节点，或进入子级
- `Enter` - 导航到选中书签

**拖拽**
- 直接拖拽节点调整位置
- 拖拽到分组上方：插入到该位置
- 拖拽到分组中间：放入该分组内
- 拖拽到分组下方：插入到该位置之后

**右键菜单**
- **Add Group** - 创建分组
- **Add CodeMark** - 创建书签
- **Add Note** - 创建笔记
- **Edit** - 编辑节点
- **Move** - 移动节点
- **Delete** - 删除节点
- **Prev in Process** - 流程内上一项
- **Next in Process** - 流程内下一项
- **Refresh** - 刷新树
- **Jump to current** - 定位到当前节点

### 详情弹窗

- 树获得焦点时按 `F1`，展示当前悬停节点详情；没有悬停节点时展示当前选中节点详情
- 搜索输入期间同样可按 `F1` 打开详情弹窗
- 同一时间只保留一个详情弹窗，新弹窗打开时会自动关闭旧弹窗
- 弹窗展示节点类型、相对路径与行号、完整 description
- description 支持 Markdown 渲染，并保留原有换行

Markdown 链接支持基于项目根目录解析：

| 写法 | 行为 |
|------|------|
| `[文档](USER_GUIDE.md)` | 打开项目内相对文件 |
| `[指定行](USER_GUIDE.md:12)` | 打开文件并跳转到第 12 行 |
| 文件路径后接“行号:列号” | 例如打开文件并跳转到第 7 行第 3 列 |
| `[GitHub 行号](../README.md#L5)` | 打开文件并跳转到第 5 行 |
| `[官网](https://example.com)` | 用浏览器打开外部链接 |

### 工具栏按钮

- **刷新** - 重新加载书签树
- **全部折叠** - 折叠所有节点
- **展开当前** - 展开到当前选中的节点

---

## 编辑器集成

### Gutter 图标

编辑器左侧行号区域会显示书签图标：

**左键点击**
- 在书签树中选中对应节点
- 打开工具窗口
- 高亮显示该节点

**右键菜单**
- **Edit** - 编辑书签
- **Add Bookmark After This Node** - 在此节点后添加新书签
- **Next CodeMark** - 全局下一个书签
- **Prev CodeMark** - 全局上一个书签
- **Delete** - 删除书签

### 行尾提示

书签行末尾会显示书签名称，点击可导航到对应位置。

### 自动跟随

当你在书签行上方插入或删除行时，书签会自动跟随移动，保持与原始代码的关联。

---

## 流程导航

### 创建流程

1. 创建一个 Process 节点（右键 `Add Process Entry Here`）
2. 在流程内添加书签节点
3. 流程内的书签会按顺序排列

### 流程内导航

**方式一：工具窗口**
- 在书签树中选中流程内的书签
- 使用工具栏的 `Prev` / `Next` 按钮
- 或右键菜单选择 `Prev in Process` / `Next in Process`

**方式二：快捷键**
- 在书签树获得焦点时，按 `Alt+↑` / `Alt+↓`（注意：这是兄弟排序，不是流程导航）

### 流程进度

工具窗口会显示当前流程的进度（如 "步骤 2/5"）。

---

## 搜索功能

### 基本搜索

1. 在工具窗口搜索框中输入文字
2. 匹配的节点会高亮显示匹配字符
3. 搜索不会过滤节点，只高亮匹配项

### 搜索导航

- `↑ / ↓` - 在匹配项间移动高亮
- `Enter` - 选中当前高亮的节点并导航
- `Escape` - 退出搜索，保持最后高亮的节点作为当前节点

### 搜索行为

- 首次搜索时，会自动展开匹配项的祖先节点
- 搜索期间的手动展开/折叠会保留
- 退出搜索后，临时展开的节点会回收
- 搜索期间按 `F1` 可查看当前高亮/选中节点详情

---

## 快捷键参考

### 全局快捷键

| 快捷键 | 功能 |
|--------|------|
| `Shift+F2` | 在当前行创建/编辑书签 |
| `Shift+F3` | 创建分组 |
| `Shift+F4` | 创建笔记 |
| `F1` | 显示当前悬停/选中树节点详情 |
| `Shift+F1` | 在编辑器中光标所在行显示书签悬浮详情（ESC 关闭，保持编辑器焦点） |
| `Alt+Shift+↓` | 全局下一个书签 |
| `Alt+Shift+↑` | 全局上一个书签 |
| `Shift+Delete` | 删除当前行书签 |

### 书签树快捷键

| 快捷键 | 功能 |
|--------|------|
| `↑ / ↓` | 在可见行间移动选中 |
| `← / →` | 折叠/展开节点 |
| `Enter` | 导航到选中书签 |
| `Delete` | 删除选中节点 |
| `F1` | 显示节点详情弹窗 |
| `Ctrl+F` | 聚焦搜索框 |
| `Escape` | 退出搜索 |

### 搜索框快捷键

| 快捷键 | 功能 |
|--------|------|
| `Escape` | 清除搜索并返回树 |
| `Ctrl+Enter` | 执行搜索（输入即搜已自动触发） |

---

## 高级功能

### 引用同步

可以在多个位置创建对同一书签的引用：

1. 选中要引用的书签
2. 右键选择创建引用
3. 在目标位置粘贴引用
4. 修改源书签时，可以选择同步更新所有引用

### 循环引用检测

系统会自动检测并防止引用循环，避免无限递归。

### 数据持久化

- 书签数据保存在项目根目录的 `.codemark/codemark.json`
- 支持撤销操作（自动创建备份文件）
- 重启 IDE 后自动恢复书签树状态

### 展开状态记忆

- 书签树的展开状态会保存
- 重启 IDE 后恢复到上次展开状态
- 搜索期间的手动展开/折叠会保留

---

## 常见问题

### Q: 书签图标没有显示？

A: 请检查：
1. 插件是否已正确安装
2. 工具窗口是否已打开
3. 书签是否已成功创建（查看书签树）

### Q: 全局导航跳转顺序是什么？

A: 全局导航按书签树的深度优先顺序遍历所有可导航节点（Bookmark 和 Process 入口）。

### Q: 同一行可以有多个书签吗？

A: 当前实现支持同一行多个书签，但在编辑时会优先编辑第一个。

### Q: 如何批量移动书签？

A: 可以使用拖拽功能，或右键菜单的 `Move` 选项。

### Q: 数据文件在哪里？

A: 数据保存在项目根目录的 `.codemark/codemark.json`，建议将其加入版本控制。

### Q: 如何在不同项目间共享书签？

A: 当前版本不支持跨项目同步，需要手动复制 `.codemark` 目录。

### Q: 搜索时节点消失了？

A: 搜索不会过滤节点，只高亮匹配字符。如果节点消失，可能是折叠状态导致的，请使用 `← / →` 展开。

### Q: 编辑代码后书签位置不对？

A: 插件会自动跟随行号变化。如果仍有问题，请刷新书签树。

---

## 技术支持

如遇到问题或需要功能建议，请通过以下方式联系：

- GitHub Issues: [项目地址]
- 邮件: [支持邮箱]

---

## 更新日志

详见 [change-log.md](./change-log.md)
