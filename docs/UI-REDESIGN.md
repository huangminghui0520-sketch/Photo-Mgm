# Photo-Mgm UI 重构方案

> 版本：v4 提案 · 2026-10-02
> 范围：**全部推倒重写，含新的信息架构**
> 状态：**仅方案，未改任何代码**
> 基线：[app/src/main/java/com/photomgm/app](../../app/src/main/java/com/photomgm/app) 现有实现
> 上游资产：[ui-design-runs/ui进行重构优化先提供方-20261002-003837-137a](../ui-design-runs/ui进行重构优化先提供方-20261002-003837-137a/run.json)（已发布飞书文档 `JACgdBNOHoBLCLx9NLQcwkH4ndB`）

---

## 0. 结论摘要

三句话：

1. **现有的视觉底子可以直接继承，但信息架构必须推倒。** [Theme.kt](../../app/src/main/java/com/photomgm/app/theme/Theme.kt) 的排版与形状系统质量不错，真正的病灶是「设置藏进齿轮弹窗 + 单页流水线要靠滚动才能看全 + 新手无法自解释」，这些是结构问题，换皮救不了。
2. **配色需要做一次正式裁决。** 代码里是**深青绿 + 琥珀**，而已经交付给客户看的 HTML 原型是**冷灰绿 + 安全橙 `#F0560A`**。两套并存，客户看到的和 APK 里跑的不是同一个东西。本方案建议以原型为准收敛。
3. **`#F0560A` 不能直接当小字色，而`#C24A09` 也不够。** 实测：`#F0560A` 在画布上 **3.14:1**（够图形线 3.0，不够正文线 4.5）；即便压深到 `#C24A09` 也只有 **4.43:1**，仍不达标。最终需要三个角色：装饰用 `#F0560A`、橙色文字用 **`#B94208`**（4.92:1）、按钮填充用 **`#CE4A09`**（白字 4.56:1）。全部数值已用校准过的脚本实测（见 2.3）。

---

## 1. 现状诊断

### 1.1 代码解剖：2889 行 UI 代码，只有 1 个真实入口

| 文件 | 行数 | 实际角色 |
|---|---|---|
| [MainActivity.kt](../../app/src/main/java/com/photomgm/app/MainActivity.kt) | 58 | 权限 + Scaffold + Snackbar，`AppNav(vm)` 唯一入口 |
| [ui/AppNav.kt](../../app/src/main/java/com/photomgm/app/ui/AppNav.kt) | 152 | 转发到 `UnifiedScreen`（本身只剩 `ProgressStrip`） |
| [ui/UnifiedScreen.kt](../../app/src/main/java/com/photomgm/app/ui/UnifiedScreen.kt) | 288 | 真正的骨架：TopBar + ProgressStrip + 2 项 LazyColumn + 2 个覆盖层 |
| [ui/LogScreen.kt](../../app/src/main/java/com/photomgm/app/ui/LogScreen.kt) | 444 | 提供 `LogSection`、`PasteLogDialog`（**没有 `LogScreen` 函数**） |
| [ui/PreviewScreen.kt](../../app/src/main/java/com/photomgm/app/ui/PreviewScreen.kt) | 1171 | 提供 6 个组件：`EventCard`/`Thumb`/`ThumbCell`/`EventPhotosSheet`/`UnmatchedPhotosSheet`/`ExportSection`（**没有 `PreviewScreen` 函数**） |
| [ui/SettingsScreen.kt](../../app/src/main/java/com/photomgm/app/ui/SettingsScreen.kt) | 776 | 提供 `SettingsContent`/`SettingsOverlay`（**没有 `SettingsScreen` 函数**） |

两个立刻可确认的结论：

- **命名与结构已经严重脱节。** 文件名承诺「三个页面」，实际是「一个大文件拆成三块」。`PreviewScreen.kt` 里塞了 1171 行、5 个互不相关的组件，任何一次改动都要在这个文件里翻找。
- **存在确认无疑的死代码**（已 grep 全量 `app/src/main/java` 验证，无任何调用点）：
  - `SettingsSection()` — 定义于 [SettingsScreen.kt:78](../../app/src/main/java/com/photomgm/app/ui/SettingsScreen.kt#L78)，**零调用**。它背后拖着一整套折叠头 UI。
  - `MoveTargetDialog()` — 定义于 [PreviewScreen.kt:122](../../app/src/main/java/com/photomgm/app/ui/PreviewScreen.kt#L122)，**零调用**。它已被 [PreviewScreen.kt:763](../../app/src/main/java/com/photomgm/app/ui/PreviewScreen.kt#L763) 的 `MoveTargetDialogWithSearch` 取代（两个调用点 [L698](../../app/src/main/java/com/photomgm/app/ui/PreviewScreen.kt#L698)、[L1081](../../app/src/main/java/com/photomgm/app/ui/PreviewScreen.kt#L1081) 都指向后者），但旧版本没删。**这一条是 1.2 里"三套手搓遮罩"之外的第四份重复实现**——同一个"选目标事件"的交互，代码里有两代版本并存。
  - `PhotoMgmDimens` / `LocalDimens` — 定义于 [Theme.kt:134](../../app/src/main/java/com/photomgm/app/theme/Theme.kt#L134)，通过 `CompositionLocalProvider` 注入于 [Theme.kt:180](../../app/src/main/java/com/photomgm/app/theme/Theme.kt#L180)，**但全项目没有一处 `LocalDimens.current`**。
  - 后果是设计令牌形同虚设：卡片实际用 `MaterialTheme.shapes.large`（20dp），而 Dimens 里写的是 `cardCorner = 16.dp`；缩略图边框在 [PreviewScreen.kt:486](../../app/src/main/java/com/photomgm/app/ui/PreviewScreen.kt#L486) 硬编码 `2.5.dp`，而不是读 `thumbBorderWidth`。

### 1.2 信息架构诊断（核心病灶）

按严重度排序，每条都指向具体代码。

#### P0 — 进度条「2 步」是硬编码产物，且语义错位

[AppNav.kt:1](../../app/src/main/java/com/photomgm/app/ui/AppNav.kt#L1) 的注释写「**保留 4 步进度条**」，[AppNav.kt:51](../../app/src/main/java/com/photomgm/app/ui/AppNav.kt#L51) 的注释写「**2 步可视化进度条**」，而 [UnifiedScreen.kt:99](../../app/src/main/java/com/photomgm/app/ui/UnifiedScreen.kt#L99) 的注释写「**粘性 2 步进度条**」。三处注释互相矛盾，说明这个组件在最近几轮重构里被反复砍削但没统一。

实际只有两步，且第二步的语义是错的：

- 第 1 步标签 `"日志${logN}·照片${photoM}"` — 把**日志条数**和**照片张数**塞进同一个 label，两个不同量纲的数字并排，用户无法判断"这一步到底完成没有"（[AppNav.kt:73](../../app/src/main/java/com/photomgm/app/ui/AppNav.kt#L73)）。
- 第 2 步标签 `"导出"`，但完成条件是 `state.ledger != null`（[AppNav.kt:60](../../app/src/main/java/com/photomgm/app/ui/AppNav.kt#L60)）——**台账生成 ≠ 导出完成**。用户看到"导出"打勾，会以为压缩包已经在输出目录里了。
- `StepChip` 的 `enabled` 参数直接等于 `done`（[AppNav.kt:73-77](../../app/src/main/java/com/photomgm/app/ui/AppNav.kt#L73-L77) 两次传 `step1Done`），意味着**第一步没完成时第二步不可点、也就不可跳转**。而"导出"恰恰是用户最需要提前看到"为什么还不能导出"的地方。`active` 参数恒为 `true`，是纯粹的死参数。
- 步骤数字 `0`/`1` 硬编码，与 `IDX_LOGS=0`/`IDX_EXPORT=1`（[UnifiedScreen.kt:57](../../app/src/main/java/com/photomgm/app/ui/UnifiedScreen.kt#L57-L58)）靠人工保持一致。

#### P0 — 关键配置的入口层级错了

当前唯一入口是右上角齿轮 → `SettingsOverlay`（[UnifiedScreen.kt:96](../../app/src/main/java/com/photomgm/app/ui/UnifiedScreen.kt#L96)、[SettingsScreen.kt:708](../../app/src/main/java/com/photomgm/app/ui/SettingsScreen.kt#L708)）。而设置里装的是**这条流水线跑不跑得起来的前提**：巡查日期、照片源目录、输出目录。

三个直接后果：

1. **新手死路。** 首次打开只看到齿轮、日期按钮、进度条。没有源目录 = 一张照片都读不到 = 事件卡全是 0 张。用户没有任何提示指向齿轮。
2. **日期被重复安置且职责不清。** 顶栏有日期按钮（[UnifiedScreen.kt:205](../../app/src/main/java/com/photomgm/app/ui/UnifiedScreen.kt#L205)），设置弹窗里又有「巡查日期」卡片（[SettingsScreen.kt:517](../../app/src/main/java/com/photomgm/app/ui/SettingsScreen.kt#L517)）。同一状态两个入口，两处都能改，交互路径不收敛。
3. **导出被灰掉但不说清怎么解。** [PreviewScreen.kt:1101](../../app/src/main/java/com/photomgm/app/ui/PreviewScreen.kt#L1101) 的 `ready = classify != null && parsed != null`，未就绪时按钮 disabled，只留一句「未完成分类前不可导出：请先在上方…」（[PreviewScreen.kt:1116](../../app/src/main/java/com/photomgm/app/ui/PreviewScreen.kt#L1116)）。**disabled 按钮 + 文字解释**是已知的可用性反模式：用户必须读那段话才知道缺什么，而且缺的可能是三样里的任意一样。

#### P1 — 主操作路径与实际操作顺序相反

真实流程是：**选日期 → 选源目录 → 粘贴日志 → 解析 → 分类 → 处理未匹配 → 导出**。

当前页面的排布是：**顶栏日期 → ①日志（含粘贴+解析+事件列表） → ②导出**。

也就是说，**「日志」被排在「设置」之前**，但日志解析依赖设置里的源目录才有照片可配。用户很自然地从上往下：先粘贴日志 → 点解析 → 发现 0 张照片 → 才回头找齿轮加目录 → **日期/目录变更又清空已有分类重算**（[SettingsScreen.kt:175](../../app/src/main/java/com/photomgm/app/ui/SettingsScreen.kt#L175)、[SettingsScreen.kt:546](../../app/src/main/java/com/photomgm/app/ui/SettingsScreen.kt#L546) 两处都写着「已有分类会清空重算」）——用户的第一次操作被完整浪费掉。

#### P1 — 「未匹配照片」被埋在事件列表末尾

未匹配池的入口是一张卡片，位置在**全部正常事件之后**（[LogScreen.kt:326](../../app/src/main/java/com/photomgm/app/ui/LogScreen.kt#L326)）。日志解析出 20 条事件时，用户必须滚过 20 张事件卡才能看到"还有 7 张照片没人要"。

而这恰恰是需要人工干预才能推进流程的地方——**最需要被看见的异常，排在最后**。唯一的替代入口是筛选条上的「未匹配 N」chip（[LogScreen.kt:260](../../app/src/main/java/com/photomgm/app/ui/LogScreen.kt#L260)），但筛选后事件列表会被过滤掉，用户失去了"哪些事件缺照片"的上下文，无法做归属判断。

#### P1 — 三套手搓遮罩，各写各的

同一个"模态"概念在代码里有**三种不同实现**：

| 位置 | 实现方式 | 特点 |
|---|---|---|
| [UnifiedScreen.kt:243](../../app/src/main/java/com/photomgm/app/ui/UnifiedScreen.kt#L243) `DatePickerOverlay` | `Box` + 黑 0.5 遮罩 + `clickable` 取消 + `BackHandler` | 居中、`widthIn(max=460dp)` |
| [SettingsScreen.kt:708](../../app/src/main/java/com/photomgm/app/ui/SettingsScreen.kt#L708) `SettingsOverlay` | 同上，注释明说「绕开 Dialog 窗口宽度不可靠问题」 | 居中、`widthIn(max=460dp)`、内部再套 `verticalScroll` |
| [PreviewScreen.kt:565](../../app/src/main/java/com/photomgm/app/ui/PreviewScreen.kt#L565) `EventPhotosSheet` | **改用真正的 `Dialog`** + `DialogProperties` | 底部/居中、高度自适应 ≤80% |

[UnifiedScreen.kt:189](../../app/src/main/java/com/photomgm/app/ui/UnifiedScreen.kt#L189) 还留着一句注释：**「去掉 Surface(tonalElevation) 包裹——iQOO/OriginOS 上该容器可能拦截子元素触摸」**。这说明当前项目已经在 OEM 触摸拦截上踩过坑。三套各写各的遮罩意味着这个坑要踩三次，而且 `Dialog` 与手搓 `Box` 在返回键、IME、状态栏层的表现并不一致。

#### P2 — 列表里看不见"分配对不对"

事件卡只渲染**前 6 张**缩略图（[PreviewScreen.kt:417](../../app/src/main/java/com/photomgm/app/ui/PreviewScreen.kt#L417)），第 7 张起折叠成 `+N`。用户的核对任务是"这个事件的照片对不对"，而列表态只能看到 6 张，必须逐个打开弹窗才能完成核对——**核对成本与事件数成正比，且无法批量扫视**。

事件列表本身也**没有搜索**。搜索框只存在于"移动目标"弹窗里（`Icons.Filled.Search` 仅在此处使用）。日志文本编辑框则固定 `height(200.dp)`（[LogScreen.kt:143](../../app/src/main/java/com/photomgm/app/ui/LogScreen.kt#L143)），长日志只能在一个小窗口里滚。

#### P2 — 自适应是"半吊子"状态

全项目确有 5 处 `widthIn(max = …)`，但它们**都是模态的宽度上限，不是布局的自适应分支**：

| 位置 | 值 | 用途 |
|---|---|---|
| [UnifiedScreen.kt:264](../../app/src/main/java/com/photomgm/app/ui/UnifiedScreen.kt#L264) | 460dp | `DatePickerOverlay` 内层 Surface |
| [SettingsScreen.kt:728](../../app/src/main/java/com/photomgm/app/ui/SettingsScreen.kt#L728) | 460dp | `SettingsOverlay` 内层 Surface |
| [PreviewScreen.kt:592](../../app/src/main/java/com/photomgm/app/ui/PreviewScreen.kt#L592)、[:796](../../app/src/main/java/com/photomgm/app/ui/PreviewScreen.kt#L796)、[:998](../../app/src/main/java/com/photomgm/app/ui/PreviewScreen.kt#L998) | 600dp | 三个 `Dialog` 内容宽度 |

**没有任何 `WindowSizeClass`，主滚动区没有任何宽度约束。** 平板/横屏下，一个约 800dp 宽的屏幕上会拉伸出一条通栏单列流水线：卡片宽度 800dp、缩略图仍是 56dp、正文行长远超中文舒适阅读区。模态内容被限制到 460/600dp，而底层内容全宽——**宽屏下模态反而比背景窄，视觉层级是反的**。

---

## 2. 设计语言（Definitive Design Language）

### 2.1 裁决：以 HTML 原型为准，废弃代码里的青绿+琥珀

**必须二选一**，因为客户已经在飞书上看过原型了。

| 维度 | 代码现状（[Theme.kt](../../app/src/main/java/com/photomgm/app/theme/Theme.kt)） | 原型（已交付客户） | 本方案采纳 |
|---|---|---|---|
| 主色 | 深青绿 `#0E5C6B` | 路政安全橙 `#F0560A` | **原型** |
| 中性底 | 暖米白 `#FAFAF7`（暖） | 冷灰绿 `#F1F4F0`（冷） | **原型** |
| 次色 | 冷灰 `#5D6167` | 沥青深灰 `#222B29` | **原型** |
| 强调操作 | 琥珀 `#C96A00` | 安全橙 `#F0560A` | **原型** |

理由不只是"客户看过"：原型那套的中性色相**取自巡查场景本身**（护栏灰、沥青灰），主色取**路政安全橙**。语义与场景同源，不需要额外解释；而青绿+琥珀是一套通用的"工业精致风"，放之四海皆可，也就放之四海皆不准。

> **待你确认（决策 D1）**：若你更想保留代码里的青绿，方案 3 章之后的所有色值需整体重算，我可以在你确认后重出一版。

### 2.2 六维风格签名

沿用上次重构确立的签名框架，明确下来：

| 维度 | 取值 | 依据 |
|---|---|---|
| `ground` | light | 路政办公/户外效率工具，强光下可读优先 |
| `typeVoice` | sans-cool | 工具型界面，无衬线、无衬线装饰 |
| `imageWorld` | photo | 巡查实拍照片作为事件卡缩略图，是主要视觉资产 |
| `layoutLogic` | rail + list-detail | 步骤轨常驻 + 列表/详情两段式（替代单列瀑布） |
| `colorStrategy` | tri-role | canvas/surface/world 三级底 + 橙色主操作 + 状态三色 |
| `motionVoice` | still | 动效克制：仅展开、Sheet 进出、双击缩放 |

### 2.3 色彩系统

#### 浅色（主场景）

> **本节数据已于 2026-10-02 用脚本实测替换手算值。** 校准方法：函数先复算四个已公布标准值（`white/black` 21.00、`#767676/white` 4.54、`#595959/white` 7.00、`#0000FF/white` 8.59），四项全部命中后才用于下方所有结论。
>
> **这次复核推翻了初稿的三个数字，并发现两处原型的实际缺陷。** 见本节末尾「初稿错在哪」。

| 令牌 | 值 | 角色 | 实测对比度 |
|---|---|---|---|
| `canvas` | `#F1F4F0` | 页面底（冷灰绿相） | — |
| `surface` | `#FBFCFA` | 卡片/Sheet 底 | — |
| `surface2` | `#E7ECE6` | 次级底、按压态 | — |
| `world` | `#222B29` | 深色段（步骤轨、底栏） | — |
| `ink` | `#17201D` | 主文字 | **15.02:1** canvas · 13.91:1 surface2 ✅ |
| `dim` | `#5C6663` | 次级文字（原型原值 56% 透明，**改为实色**） | **5.36:1** canvas · **4.96:1** surface2 ✅ |
| `line` | `rgba(23,32,29,.10)` | 分隔线 | — |
| `line2` | `rgba(23,32,29,.16)` | 强分隔线 | — |
| **`accentBrand`** | `#F0560A` | **品牌橙——仅限装饰性色块、轨线、≥18.66px 大字** | **3.14:1** canvas（过图形线 3.0，**不过正文线 4.5**）⚠️ |
| **`accentText`** | `#B94208` | **强调色的文字形态——凡 `color=` 是橙色的地方用它** | **4.92:1** canvas · **4.56:1** surface2 ✅ |
| **`accentFill`** | `#CE4A09` | **主操作填充（按钮底）** | 白字在其上 **4.56:1** ✅ |
| `onAccent` | `#FFFFFF` | 主操作按钮上的文字 | **4.56:1** on `accentFill` ✅ |
| `ok` | `#2B7349` | 已分类/正常 | **5.19:1** canvas · 4.80:1 surface2 · 4.54:1 自底 ✅ |
| `warn` | `#8D5C17` | 待复查 | **5.15:1** canvas · 4.77:1 surface2 · 4.52:1 自底 ✅ |
| `bad` | `#B6392C` | 未识别/人工干预 | **5.25:1** canvas · 4.86:1 surface2 · 4.52:1 自底 ✅ |

**这套色值的四条硬规则（必须写进代码注释，否则一定被后人破坏）：**

1. **橙色拆成三个角色，不是一个。** `#F0560A` 在画布上实测只有 **3.14:1**——够图形线（3.0），**不够正文线（4.5）**。所以：装饰性色块/轨线用 `accentBrand`；**任何橙色文字**用 `accentText #B94208`；**主按钮填充**用 `accentFill #CE4A09`（因为白字要落在它上面）。
2. **状态色角标的底色透明度必须是 6%，不是原型写的 12%。** 这是本次新发现的缺陷：状态角标是画在**自己颜色的浅底**上的，实测 12% 时三色都只有 **4.2:1**（不达标）。降到 6% 后全部达标（4.54～4.58:1）。**这一条不写进注释，后人一定会照抄原型的 12% 而踩坑。**
3. **次级文字改为实色，不用透明度。** 原型用 `rgba(23,32,29,.56)`，在 `canvas` 上勉强达标但把余量耗尽；落到 `surface2` 上就跌破 4.5:1。改成实色 `#5C6663` 后，两种底色上分别是 5.36 / 4.96。
4. **状态三色不再使用原型原值。** `#2F7D4F`/`#A86E1B`/`#BC3B2D` 在 `surface2` 上分别是 4.21 / 3.57 / 4.61——其中两个不达标。上表的值是反解出来的最小改动解。

#### 深色

原型**只做了浅色**。深色是本次必须补齐的新增项，因为它现在是"能跑但没设计"的状态。

| 令牌 | 值 | 实测对比度 |
|---|---|---|
| `canvas` | `#0F1513` | — |
| `surface` | `#19211E` | — |
| `ink` | `#E8EDEA` | **15.60:1** canvas · 13.88:1 surface ✅ |
| `dim` | `#8E9A94` | **6.33:1** canvas · 5.64:1 surface ✅ |
| **`accent`** | `#FF9A5C` | **8.82:1** canvas · 7.85:1 surface ✅ |
| `onAccent` | `#3A1A00` | **7.55:1** on accent ✅ |

**深色模式的要点是"橙色必须换一个人"。** 浅色下要**压深**（`#B94208`/`#CE4A09`）才达标，深色下要**提亮**（`#FF9A5C`）才达标——同一个 `#F0560A` 在浅底上 3.14:1、在深底上 5.31:1，两边表现完全相反。**不要试图用一个橙色打通两种模式。**

> 顺带一个好消息：`#F0560A` 在深底上实测 **5.31:1**，其实达标。所以深色模式**不是必须**换色——换 `#FF9A5C` 是为了更好看（8.82:1，余量充足），而不是为了合规。

#### 初稿错在哪（留档，避免重复）

| 初稿写的 | 实测 | 性质 |
|---|---|---|
| `ink` 15.0:1 | 15.02:1 | ✅ 准确 |
| `accent #F0560A` 3.35:1 | 3.14:1 | ⚠️ 偏高，但结论（只够图形）不变 |
| `accentInk #C24A09` **5.14:1** | **4.43:1** | ❌ **错**。初稿据此断言它"达标"，实际不达标 |
| `warn #A86E1B` **4.04:1** | **3.86:1** | ❌ **错**，且本来就没达标 |
| `onAccent #FFF4EC` 3.42:1 | 3.21:1 | ⚠️ 偏高 |
| 深色 `dim` 5.72:1 | 6.33:1 | ⚠️ 偏低（偏保守，无害） |
| 深色 `accent` 8.09:1 | 8.82:1 | ⚠️ 偏低（偏保守，无害） |
| 深色 `#F0560A` "只有 3.35:1，不够" | **5.31:1，达标** | ❌ **错**，结论方向反了 |

**错因**：初稿的 `L = 0.2126R + 0.7152G + 0.0722B` 公式写法里漏了 sRGB 线性化的除法与偏移，导致亮度系统性偏低、对比度系统性偏高。两处关键错误（`accentInk`、深色 `#F0560A`）都是被这个偏差掩盖过去的。

**教训**：这类数值不能手算后直接写进文档当结论。凡是要作为**设计约束**写进方案的派生数值，必须先用已公布的标准值校准计算工具，再产出结论。本节的校准四个基准就是为此保留的。

### 2.4 排版

现有 `AppTypography`（[Theme.kt:156](../../app/src/main/java/com/photomgm/app/theme/Theme.kt#L156)）**质量是够的**，中文字号阶梯合理、行高约 1.55 符合中文排版要求。**保留，只改两处：**

1. `bodySmall` 当前 12sp/19sp，是大量副信息的载体。中文 12sp 在 1080p 手机上偏吃力，**提到 13sp/20sp**。
2. 新增 `numeric` 样式：`FontFeatureSettings("tnum")`，等宽数字。用于照片张数、桩号 `K123+000`、日期、进度百分比——**这些数字在列表里必须纵向对齐**，否则扫视时会有明显的参差感。当前完全缺失。

| 层级 | 用途 | 变化 |
|---|---|---|
| `displaySmall` 26sp/600 | 空状态大标题 | 不变 |
| `headlineSmall` 19sp/600 | 分区标题 | 不变 |
| `titleLarge` 17sp/500 | Sheet 标题 | 不变 |
| `titleMedium` 15sp/500 | 事件卡标题、列表主行 | 不变 |
| `bodyLarge` 15sp/400 | 正文 | 不变 |
| `bodyMedium` 14sp/400 | 卡片正文 | 不变 |
| `bodySmall` 12sp → **13sp** | 副信息、桩号、时间 | **放大** |
| `labelSmall` 11sp/500 | 徽章、chip | 不变 |
| **`numeric`** | **新增** | 等宽数字，`tnum` |

### 2.5 度量与形状

把死掉的 `PhotoMgmDimens` **真正用起来**，并补齐缺的令牌。所有组件只从 `LocalDimens.current` / `LocalSpacing.current` 取值，**禁止在组件内写裸 dp 数字**（当前 `2.5.dp`、`14.dp`、`56.dp` 等散落在 [PreviewScreen.kt](../../app/src/main/java/com/photomgm/app/ui/PreviewScreen.kt) 各处）。

| 令牌 | 值 | 说明 |
|---|---|---|
| `space.xs/s/m/l/xl` | 4 / 8 / 14 / 20 / 32 dp | 统一间距阶梯，替代散落的 6/8/10/14 |
| `radius.control` | 8dp | chip、输入框、小按钮 |
| `radius.card` | 12dp | 事件卡、设置卡（**对齐原型 `--r-card`**，替代当前 `shapes.large=20dp`） |
| `radius.shell` | 20dp | Sheet、覆盖层（对齐原型 `--r-shell`） |
| `radius.pill` | 999dp | 状态徽章 |
| `touch.min` | 48dp | **硬约束，不可协商** |
| `thumb.border` | 2.5dp | 选中边框（已有，需改用令牌） |
| `thumb.gap` | 4dp | 缩略图网格间距 |
| `rail.height` | 52dp | 步骤轨高度 |

内部状态枚举：`InteractionState { Default, Pressed, Selected, Disabled }`，在令牌层一次性定义**按压/选中/禁用**三档派生色，避免每个组件各写一份 `if (selected)` 分支（当前 [PreviewScreen.kt:485](../../app/src/main/java/com/photomgm/app/ui/PreviewScreen.kt#L485-L492) 就在组件内联算边框+圆角）。

### 2.6 动效

`motionVoice = still`，动效配额只给三处，其余全部瞬时：

| 场景 | 时长 | 曲线 |
|---|---|---|
| 分区/卡片展开收起 | 200ms | `FastOutSlowInEasing` |
| Sheet 进出（底部滑入 + 遮罩淡入） | 220ms / 140ms | `LinearOutSlowInEasing` |
| 照片双击缩放 | 240ms | `FastOutSlowInEasing` |
| 其余所有状态变化 | **0ms** | 直接切换 |

**明确禁止**：卡片入场动画、列表项依次淡入、任何 `animateContentSize` 连锁、进度条缓动。理由是这个 App 的使用场景是户外、单手、可能戴手套，动效只会延迟反馈。

---

## 3. 新信息架构

### 3.1 结构：三级 + 两条轨

```text
┌─────────────────────────────────────────────────────────┐
│  AppBar（56dp）                                          │
│  [照片管理]  ·  9月8日 · 24张 · 6事件      [⚙ 设置]      │
├─────────────────────────────────────────────────────────┤
│  StepRail（52dp · world 深色段）                          │
│  ① 准备 ✓ ──── ② 核对 ●(3) ──── ③ 交付 ○                │
│  日期·目录·日志已解析    6事件11张待办   台账+压缩包       │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  ── 无配置：全屏引导（StepRail 仅亮 ①）──                │
│                                                         │
│     ┌───────────────────────────────────┐               │
│     │  第 1 步 · 选一个巡查日期           │               │
│     │  [ 9月8日 ▾ ]              ✓       │               │
│     ├───────────────────────────────────┤               │
│     │  第 2 步 · 添加照片目录             │               │
│     │  [ + 选择文件夹 ]          ○       │               │
│     │  常用：DCIM/Camera · DCIM          │               │
│     ├───────────────────────────────────┤               │
│     │  第 3 步 · 粘贴巡查日志             │               │
│     │  [ 粘贴文本 ]              ○       │               │
│     │                    已识别 0 条      │               │
│     └───────────────────────────────────┘               │
│                                                         │
│  ── 配置完成：切换为主工作区 ──                          │
│                                                         │
│  待办条：⚠ 3 张照片未匹配  [处理 ›]                      │
│                                                         │
│  事件列表（可搜索 / 可筛选）                              │
│  ┌───────────────────────────────────┐                 │
│  │ 抛洒物清理          09:12～09:26   │                 │
│  │ K123+000 北行                       │                 │
│  │ ▣▣▣▣▣▣ +2      4张                 │                 │
│  └───────────────────────────────────┘                 │
│  ...                                                    │
├─────────────────────────────────────────────────────────┤
│  BottomActionBar（72dp · 渐隐浮起）                       │
│  已核对 11/14 张              [ 导出交付包 ]             │
└─────────────────────────────────────────────────────────┘
```

### 3.2 StepRail：三步，且步骤状态由「可用性」驱动

替换当前的 2 步 `ProgressStrip`，修掉 1.2 里所有语义问题。

| 步 | 标题 | 进入条件 | 完成条件 | 计数徽章 |
|---|---|---|---|---|
| ① | 准备 | 恒可进 | `date != null && sourceDirs.isNotEmpty() && parsed != null` | 无 |
| ② | 核对 | ① 完成 | `classify != null && pendingEventIds.isEmpty() && effectiveUnmatched.isEmpty()` | **待办数**（待复查事件 + 未匹配照片） |
| ③ | 交付 | ① 完成 | `exportMessage != null` | 无 |

关键修正：

- **② 的徽章是"待办数"，不是"完成/未完成"。** 这是本方案与当前设计最大的分歧：核对是一个**可能永远清不空**的过程（有的照片就是不属于任何事件）。用"未打勾"惩罚用户会让人以为流程坏了。改成显示"还有 3 件待处理"，用户可以随时决定"就这 3 张不管了，进入交付"。当前代码里 `step1Done = parsed?.events?.isNotEmpty()`（[AppNav.kt:59](../../app/src/main/java/com/photomgm/app/ui/AppNav.kt#L59)）这种二值判断，正是要废掉的东西。
- **③ 的完成条件是 `exportMessage`，不是 `ledger != null`。** 直接修掉 1.2 里"打勾了但压缩包不存在"的误导。
- **所有步骤恒可点击**，未就绪时点击 = 跳到该段并高亮缺失项，**不是 disabled**（当前 `enabled = done` 的写法要删掉）。
- 步数常量与 LazyColumn 索引由**单一枚举**驱动，消除 `IDX_LOGS`/`IDX_EXPORT` 那种人工对齐。

### 3.3 修复 P1：主操作路径倒置

**把「准备工作」提到第一位，并且让它自己解释自己。**

原来靠"用户自己发现齿轮"的三件事（日期、源目录、输出目录）拆开安置：

| 项目 | 新位置 | 理由 |
|---|---|---|
| 巡查日期 | StepRail 右侧常驻 + ① 段内可改 | 高频切换，必须一步可达 |
| 照片源目录 | **① 段内，作为引导第 2 步** | 不配就等于没有数据，必须挡在流程前面 |
| 输出目录 | **③ 交付段内** | 只在导出时才需要，不该干扰前面的流程 |
| 命名模板 / 重置人工移动 | 设置 Sheet（齿轮） | 低频、全局性 |
| 版本号 / 反馈 | 设置 Sheet 底部 | 不变 |

这样处理的收益：**输出目录从"前置必填"降级为"交付时才要求"**，消除了"用户必须先把所有设置填完才能看到任何东西"的压迫感；同时源目录升格为流程第一步，新手不可能再错过。

### 3.4 状态机：每个动作都能回答「为什么不能点」

全局定义一组 `ActionGate`，**每个**主操作按钮都通过它渲染：

```kotlin
sealed interface ActionGate {
    data object Ready : ActionGate
    /** 为什么不能点 —— 一句话 */
    data class Blocked(val reason: String, val fix: Fix) : ActionGate

    sealed interface Fix {
        /** 点击后直接跳到能解决问题的地方 */
        data class Goto(val step: PipelineStep) : Fix
        /** 点击后直接执行修复动作本身 */
        data class Invoke(val label: String, val run: () -> Unit) : Fix
    }
}

@Composable
fun GateButton(gate: ActionGate, label: String, onClick: () -> Unit)
```

`Blocked` 时**按钮不 disabled**，而是降为 tonal 样式并展示 `reason`，点击直接执行 `fix`。举例：

| 场景 | 当前表现 | 新版表现 |
|---|---|---|
| 未选源目录 | 灰色按钮 + 一行小字解释 | 按钮写「先添加照片目录 ›」，点击直接拉起 SAF |
| 未解析日志 | 同上 | 按钮写「先粘贴并解析日志 ›」，点击跳 ① 段并聚焦输入框 |
| 有未匹配照片 | 可导出，但不提示 | 按钮写「导出交付包（3 张未匹配将不入台账）」，点击弹二次确认 |

**最后一条尤其重要**：当前 `ready` 只看 `classify != null && parsed != null`（[PreviewScreen.kt:1101](../../app/src/main/java/com/photomgm/app/ui/PreviewScreen.kt#L1101)），**未匹配照片会被静默排除在台账之外**。用户拿到交付包才发现少照片，这是数据正确性问题，不是体验问题。

### 3.5 修复 P1：未匹配照片提到「待办条」

未匹配池从"事件列表末尾的卡片"改为**工作区顶部的待办条**，常驻在事件列表之上：

```text
⚠ 3 张照片未匹配 · 点击处理 ›          [全部忽略]
```

三点设计考虑：

1. **位置**：紧贴 StepRail 下方，进入工作区第一眼就看到。
2. **可折叠**：全部处理完后自动消失，不占空间。
3. **有出口**：提供「全部忽略」——明确允许用户放弃这批照片，并在导出时计入台账备注。当前代码没有这个出口，用户要么处理完要么一直看着它。

事件卡的缩略图从"前 6 张 + N"改为**最多 4 张，且第 4 张位改为「进入核对」按钮**并直接显示 `共 7 张`。理由：列表态的职责是"扫视判断哪个事件需要进去看"，不是"预览照片"。**少给 2 张缩略图，换来一个明确的进入动作。**

### 3.6 修复 P1：统一模态为唯一 `AppSheet`

三种手搓遮罩（P1-1.2）统一为一个组件：

```kotlin
enum class SheetMode { Bottom, Center, Full }

@Composable
fun AppSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    mode: SheetMode = SheetMode.Bottom,
    title: String? = null,
    onBack: (() -> Unit)? = null,
    primaryAction: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
)
```

**统一后的硬性约定（对抗 OEM 触摸拦截，已踩过的坑）：**

- 内部一律使用 **M3 标准 `Button` / `IconButton` / `Switch`**，不自绘可点击区域（沿用 [UnifiedScreen.kt:189](../../app/src/main/java/com/photomgm/app/ui/UnifiedScreen.kt#L189) 的既有结论）。
- 遮罩点击关闭 + `BackHandler` 只在 `AppSheet` 内部实现一次，**全项目禁止再出现第二个 `Box(Black.copy(alpha=.5f))`**。
- 底部 Sheet 预留 `navigationBars` inset；内容区 `imePadding()` 由 `AppSheet` 统一加，组件不再各自处理。
- 提供 `Full` 模式承接当前的 `SettingsOverlay`（大内容、可滚动）。

配套：`DatePickerOverlay` 退役，改为 `AppSheet(mode = Center)`；`EventPhotosSheet` 与 `UnmatchedPhotosSheet` 改为 `AppSheet(mode = Bottom)`。

### 3.7 修正 P2：全宽工作区的密度

引入 `WindowSizeClass`，三个断点：

| 宽度 | 布局 |
|---|---|
| `< 600dp` | 单列（如上图），Sheet 为底部 |
| `600–840dp` | 列表 + 详情**双栏**；左侧事件列表，右侧选中事件的照片网格常驻，取消 Sheet 跳转 |
| `>= 840dp` | 三栏：待办/筛选 ｜ 事件列表 ｜ 照片网格 |

平板/横屏下**取消"点卡片 → 弹窗"这一跳**是这个断点方案的主要价值：核对工作从"反复开关弹窗"变成"左右扫视"。

---

## 4. 组件清单与文件结构

### 4.1 目标文件树

```text
ui/
├── AppNav.kt                    改：唯一入口，接 WindowSizeClass
├── shell/
│   ├── AppScaffold.kt           新：AppBar + StepRail + BottomActionBar 三段壳
│   ├── AppBar.kt                新：状态胶囊 + 设置入口
│   ├── StepRail.kt              新：三步轨（替代 ProgressStrip）
│   └── BottomActionBar.kt       新：渐隐浮起主操作条
├── prepare/
│   ├── PrepareSection.kt        新：引导式 3 步（替代 LogSection 前半）
│   ├── OnboardingGuide.kt       新：无配置时的全屏引导
│   └── LogEditor.kt             新：日志输入（抽为独立组件，高度自适应）
├── events/
│   ├── EventList.kt             新：可搜索 / 可筛选事件列表
│   ├── EventCard.kt             改：4 缩略图 + 明确进入动作
│   ├── PendingBar.kt            新：待办条（未匹配 + 待复查）
│   ├── EventDetailPane.kt       新：核对工作区（窄屏 Sheet / 宽屏右栏共用）
│   └── ThumbGrid.kt             新：多选网格 + 台账槽位角标（从 PreviewScreen 抽出）
├── deliver/
│   ├── DeliverSection.kt        新：预检清单 + 输出目录 + 导出
│   └── PreflightList.kt         新：导出台账核对项
├── settings/
│   └── SettingsSheet.kt         改：仅留命名模板 / 重置移动 / 版本 / 反馈
└── common/
    ├── AppSheet.kt              新：唯一模态（替代 3 套手搓遮罩）
    ├── GateButton.kt            新：ActionGate 渲染
    ├── StateChip.kt             新：ok/warn/bad 状态徽章
    ├── EmptyState.kt            新：空状态
    └── SectionHeader.kt         改：从 SettingsScreen 抽出，由折叠头改为纯标题
```

### 4.2 旧 → 新 映射表（迁移时直接照此执行）

| 旧标识 | 旧位置 | 去向 |
|---|---|---|
| `PhotoMgmTheme` | Theme.kt:175 | 保留，内部换为 2.3/2.4/2.5 新令牌 |
| `PhotoMgmDimens` / `LocalDimens` | Theme.kt:134 | **复活并扩展**为 `LocalSpacing`/`LocalDimens`/`LocalColors` |
| `AppTypography` | Theme.kt:156 | 保留，`bodySmall` 放大 + 新增 `numeric` |
| `AppNav` | AppNav.kt:44 | 保留（几乎空），加 `WindowSizeClass` |
| `ProgressStrip` | AppNav.kt:55 | **删除** → `shell/StepRail.kt` |
| `StepChip` | AppNav.kt:93 | **删除** → `StepRail` 内部 |
| `Connector` | AppNav.kt:141 | **删除** → `StepRail` 内部 |
| `UnifiedScreen` | UnifiedScreen.kt:61 | **删除** → `shell/AppScaffold.kt` |
| `TopBar` | UnifiedScreen.kt:192 | **删除** → `shell/AppBar.kt` |
| `DatePickerOverlay` | UnifiedScreen.kt:243 | **删除** → `AppSheet(mode=Center)` + `AdaptiveDatePicker` |
| `LogSection` | LogScreen.kt:72 | **拆除** → `PrepareSection` + `LogEditor` + `EventList` |
| `PasteLogDialog` | LogScreen.kt:382 | **删除** → `AppSheet` + `LogEditor` |
| `EventCard` | PreviewScreen.kt:344 | **重写**（缩略图 6→4 + 进入动作）→ `events/EventCard.kt` |
| `Thumb` | PreviewScreen.kt:468 | **删除** → `ThumbGrid` 内部 |
| `ThumbCell` | PreviewScreen.kt:312 | **删除** → `ThumbGrid` 内部（与 `Thumb` 重复） |
| `MoveTargetDialog` | PreviewScreen.kt:122 | **删除**（死代码，零调用） |
| `MoveTargetDialogWithSearch` | PreviewScreen.kt:763 | **重写** → `EventDetailPane` 的"移动到"模式 |
| `MarkLedgerDialog` | PreviewScreen.kt:237 | **重写** → `EventDetailPane` 的台账标记模式 |
| `PhotoTile` | PreviewScreen.kt:896 | **重写** → `ThumbGrid` 内部 |
| `EventPhotosSheet` | PreviewScreen.kt:565 | **重写**为 `EventDetailPane` |
| `UnmatchedPhotosSheet` | PreviewScreen.kt:981 | **重写**为 `EventDetailPane` 的 unmatched 模式 |
| `ExportSection` | PreviewScreen.kt:1099 | **重写** → `DeliverSection` + `PreflightList` |
| `SettingsSection` | SettingsScreen.kt:78 | **删除**（死代码，零调用） |
| `SectionHeader` | SettingsScreen.kt:347 | 改造后移入 `common/` |
| `SettingCard` | SettingsScreen.kt:401 | 保留，移入 `common/` |
| `NamingRadio` | SettingsScreen.kt:438 | 保留，移入 `settings/` |
| `SettingsContent` | SettingsScreen.kt:476 | **拆除**：日期/源目录 → `PrepareSection`；输出目录 → `DeliverSection`；余下留 `SettingsSheet` |
| `SettingsOverlay` | SettingsScreen.kt:708 | **删除** → `AppSheet(mode=Full)` |
| `safeDirLabel` | SettingsScreen.kt:464 | 保留移入 `common/`，**并修复**（见 6.1 风险 R4） |
| `FilterMode` | PreviewScreen.kt:105 | 保留移入 `events/` |
| `LongSetSaver` | PreviewScreen.kt:110 | 保留移入 `events/` |

预期结果：`PreviewScreen.kt`（1171 行）彻底消失，最大的单文件降到 300 行以内。

---

## 5. 迁移路径

五阶段，每阶段结束**都能编译出可用的 APK**，不出现"重构到一半整个 App 打不开"。

### 阶段 0 · 设计令牌落地（先行，零行为变更）
**改动**：重写 [Theme.kt](../../app/src/main/java/com/photomgm/app/theme/Theme.kt)，落地 2.3/2.4/2.5；复活 `LocalDimens` 并新增 `LocalSpacing`/`LocalColors`/`InteractionState`；新增 `theme/Preview.kt` 放各令牌的 `@Preview`。
**产出**：视觉变了，交互一点没动。
**验收**：`gradle :app:assembleDebug` 通过；`@Preview` 里浅/深两套色板可见；对比度复核通过。
**风险**：低。唯一的坑是第 2.3 节的 `accent`/`accentInk` 双角色必须一次到位，否则后面每个组件都要返工。

### 阶段 1 · 公共组件层
**改动**：新建 `common/AppSheet.kt`、`common/GateButton.kt`、`common/StateChip.kt`、`common/EmptyState.kt`，`common/SectionHeader.kt`。
**产出**：新组件可用但**尚未接入**，旧遮罩仍在跑。
**验收**：`@Preview` 覆盖 `AppSheet` 三种 mode + 暗色；`GateButton` 的 Ready/Blocked 两态。
**风险**：中。`AppSheet` 必须在这一步就把 OEM 触摸拦截、IME、返回键、inset 四件事一次性处理正确——**它是后面所有 UI 的地基，这里省的事后面要还三倍**。

### 阶段 2 · 骨架与导航
**改动**：新建 `shell/AppScaffold.kt`、`AppBar.kt`、`StepRail.kt`、`BottomActionBar.kt`；`AppNav` 切到新骨架；**删除** `ProgressStrip`/`StepChip`/`Connector`/`TopBar`/`UnifiedScreen`。
**产出**：骨架换成新的（三步轨、状态胶囊、底部条），**内容区暂时仍塞旧的 `LogSection` + `ExportSection`**。
**验收**：三步轨可见、可点、状态正确；旧内容在新骨架里不崩。
**风险**：中。这一段会有一次"新旧混装"的丑陋期，是正常的。

### 阶段 3 · 业务分区迁移（工作量最大，可按段拆 PR）
**改动**：
1. `PrepareSection` + `OnboardingGuide` + `LogEditor` 替换 `LogSection`
2. `EventList` + `EventCard`(重写) + `PendingBar` 替换事件卡片区
3. `EventDetailPane` + `ThumbGrid` 替换两个 Sheet
4. `DeliverSection` + `PreflightList` 替换 `ExportSection`
5. `SettingsSheet` 收敛 `SettingsContent`
**验收**：**同时**在 390×844 与 800×1280（或横屏）两档跑通完整流程；未匹配照片在待办条可见；导出前有预检与二次确认。

### 阶段 4 · 清理与打磨
**改动**：删除 `PreviewScreen.kt`/`LogScreen.kt`/`SettingsScreen.kt` 三个旧文件与全部死代码（`SettingsSection`、旧 `SectionHeader` 折叠逻辑）；接入 `WindowSizeClass` 双栏/三栏；补深色模式全流程走查；全局扫一遍裸 dp 与裸 hex。
**验收**：`grep -rn "0x[0-9A-F]\{8\}" ui/` 除 `theme/` 外**无命中**；`grep -rn "\.dp" ui/` 仅出现在 `theme/` 与 `AppSheet` 的 inset 处理中；旧文件不再被引用。

---

## 6. 风险与待决策

### 6.1 风险

| ID | 风险 | 应对 |
|---|---|---|
| **R1** | OEM 触摸拦截（**已经发生过**，见 [UnifiedScreen.kt:189](../../app/src/main/java/com/photomgm/app/ui/UnifiedScreen.kt#L189) 的 iQOO/OriginOS 记录） | 继承既有结论：新组件一律用 M3 标准 `Button`/`IconButton`，不自绘点击区；`AppSheet` 在有真机的 OEM 上至少验一遍 |
| **R2** | 导出**不可中断**。当前 `exportZip()` 在 `Dispatchers.IO` 里跑（[AppViewModel.kt:413](../../app/src/main/java/com/photomgm/app/AppViewModel.kt#L413)），只有 `exportProgress` 单向进度，无取消信号 | 本次 UI 重构**顺带补**：`exportJob?.cancel()` + 后确认。照片量大时这是必须的，否则用户只能杀进程 |
| **R3** | 「全部忽略未匹配」是**新增语义**，涉及台账内容 | 需你确认台账里是否要留痕（建议留一行备注"另有 N 张照片未匹配"），否则交付包会"看起来完整但实际不完整" |
| **R4** | `safeDirLabel`（[SettingsScreen.kt:464](../../app/src/main/java/com/photomgm/app/ui/SettingsScreen.kt#L464)）把 SAF tree URI 转成目录名，`substringAfter(':')` 对部分国产 ROM 的 URI 形态取不到有效名，会**整串 URI 显示给用户** | 阶段 3 一并修：取不到时显示「已选文件夹（点击更换）」而非裸 URI |
| **R5** | 深色模式**从未被设计过**，2.3 的深色表是推导值 | 阶段 0 先用推导值跑通，阶段 4 做一次完整走查再定稿。若你不需要深色，可以**直接砍掉**（`PhotoMgmTheme` 固定 `darkTheme = false`），省一整轮工作量 |

### 6.2 验收与度量

- **视觉回归**：阶段 2/3/4 各截一轮 390×844 与 800×1280 全流程截图，与本节目标态图对照。截图脚本可直接复用上游 `ui-design-runs` 里 `shoot.py` 的做法（注意：那是另一套环境的工具，本机不可用，需重新搭）。
- **对比度**：2.3 的全部数值**已于 2026-10-02 用校准脚本实测**（四项标准值基准全部命中），初稿的手算错误已更正并留档。落地时仍建议在 Android Studio 里对关键组合抽查一次，特别是 `warn` 系列。
- **触控**：所有可点击元素 ≥ 48dp，用 Layout Inspector 抽查。
- **死代码**：阶段 4 后 `grep` 确认 `SettingsSection` / `LocalDimens` 的旧用法 / `PhotoMgmDimens` 默认值引用全部清零。

### 6.3 待你决策

| ID | 问题 | 我的建议 |
|---|---|---|
| **D1** | 配色：采纳原型的「冷灰绿 + 安全橙」，还是保留代码的「青绿 + 琥珀」？ | **采纳原型**。客户已看过，且色彩语义与巡查场景同源 |
| **D2** | 是否保留深色模式？（R5） | **保留但降级**：本次只做推导值，不做精细走查。若不常用可直接砍 |
| **D3** | 「全部忽略未匹配」是否在台账里留痕？（R3） | **留痕**。宁可台账多一行，不要交付包静默少照片 |
| **D4** | 平板/横屏双栏（3.7）本次做还是推后？ | **推后到独立一轮**。先保证手机端 IA 正确，双栏是增量收益，不应拖慢主线 |
| **D5** | 阶段 1 的 `AppSheet` 是否要一次做全三种 mode？ | **要**。三种 mode 的 inset/返回键差异是同一批坑，拆开做等于踩三遍 |

---

## 7. 附：与上游原型的关系

本次方案**继承**上游 HTML 原型的：色彩语义与具体色值（`#F1F4F0`/`#F0560A`/`#222B29`）、圆角刻度（20/12/8/999）、三色状态体系（`ok`/`warn`/`bad`）、`motionVoice = still` 的克制动效、以及单页流水线的基本主张。

本次方案**修正/推翻**上游原型的：2 步进度条（原型也是 2 步，语义同样错位）→ 3 步 `StepRail`；纯单列 → rail + list-detail；未匹配池置于列表末尾 → 提到待办条；设置藏在齿轮后 → 拆为「准备前置」+「交付后置」。

上游 [observed-app.json](../ui-design-runs/ui进行重构优化先提供方-20261002-003837-137a/observed-app.json) 里 7 条已闭环的视觉缺陷（steps 被 topbar 遮挡、末卡片被 bottombar 遮盖、标题 ellipsis 等）属于**原型层面的实现细节**，其教训已吸收进 2.5（`rail.height` 明确值）与 3.7（BottomActionBar 的 `contentPadding` 预留），无需逐条搬迁。
