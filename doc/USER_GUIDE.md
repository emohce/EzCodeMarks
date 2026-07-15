# EzCodeMarks 用户指南

> **版本**：1.0.1
> **更新日期**：2026-07-15
> **适用版本**：EzCodeMarks 1.0.1+

---

## 目录

- [快速开始](#快速开始)
- [Git 提交信息助手](#git-提交信息助手)
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
| Generate Commit Message | 是 | `Ctrl+Alt+Shift+G` | `⌘⌥⇧G` | 根据已包含的变更生成 |
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
- `Format` 只在当前提交信息非空时可用，保留事实含义并按配置语言返回结果。
- 所有 AI 结果先进入并排预览：左侧为原始内容，右侧结果可编辑，同时显示 Provider、Endpoint、模板和被过滤文件。
- 只有点击 Apply 才会写回提交框。Copy 只复制结果；Cancel、网络失败、输出校验失败、操作过期或运行期间原文被修改时，原文保持不变。

### 设置树

进入 `Settings → Tools → EzCodeMarks → Git Commit Message`：

- `Git Commit Message`：配置工具栏显隐、查看当前 Keymap/快捷键冲突、打开 Keymap 设置、控制字段显示与 type 展示方式，以及 skip-ci、Smart Echo。
- `Templates & Types`：新增、复制、删除模板，选择全局默认，编辑/校验 Velocity 并实时预览，管理提交类型及描述顺序。内置模板不可删除或重命名，可恢复默认内容。
- `AI Providers`：管理多个 Profile，选择 OpenAI Compatible 或 Anthropic，设置 URL、模型、温度、语言、Streaming 与 Reasoning compatibility，并测试连接或获取模型。
- `Project Defaults`：覆盖本项目模板、恢复全局默认、清除未完成草稿。

“配置快捷键…”只打开 IntelliJ 公共 Keymap 设置并定位 Action，不会在运行时修改用户键位方案。复制 Profile 不复制 API 密钥；删除 Profile 会同步删除其 PasswordSafe 凭据。

### Provider 与隐私边界

- OpenAI Compatible 使用 `/chat/completions` 与 `/models`，通过 Bearer 认证。
- Anthropic 使用 `/v1/messages` 与 `/v1/models`，通过 `x-api-key` 认证。
- API 密钥只保存在 IntelliJ PasswordSafe，不会写入插件 XML、项目 workspace 或 `.codemark`。
- 每个 Profile 第一次向当前 Provider 类型与 Endpoint 发送源码上下文时，必须确认数据共享；Endpoint、Provider 或隐私策略变化后会再次确认。
- 上下文会过滤二进制、生成文件、`.env*`、凭据、token、私钥、证书、SSH、service-account、Docker/Kubernetes 认证文件等敏感路径或内容。
- 状态、diff、未版本化文本和最近提交均有固定预算；v1 不提供关闭敏感过滤或突破预算的开关。
- 请求遵循 IDE Proxy，支持取消；单次操作最多调用 Provider 三次，只对明确的 streaming/reasoning 参数不兼容或结构化 JSON 修复做受控回退。

项目模板 ID 与未完成草稿保存在 IDE workspace state，不会进入 CodeMark 数据文件。

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
