# Tyranor-Next 中文增强版 · 交接工作文档

> 本仓库 fork 自上游 [Weiss-UltimateSavior/Tyranor-Next](https://github.com/Weiss-UltimateSavior/Tyranor-Next)，
> 在上游基线之上维护三个自用功能分支。本文档面向接手维护的人（也包括未来的自己），
> 说明每个分支做了什么、为什么这么做、如何重建 dev 集成线、以及同步上游时的注意事项。

---

## 1. 总览

| 分支 | 链尾提交 | 领先 main | 规模（vs main） | 定位 |
|---|---|---|---|---|
| `main` | `abd91574` | — | — | 上游基线，**只读**，只用于同步上游 |
| `cheat` | `0caaf569` | 12 commits | 3 文件 +239/−13 | RPG Maker 修改器运行时资源 |
| `ui` | `a12e1f01` | 5 commits | 7 文件 +332/−291 | 封面动作整合与搜索体验 |
| `translation` | `a4b80a09` | 28 commits | 17 文件 +1598/−8 | MV/MZ 译表翻译（核心功能） |
| `dev` | `066925f6` | 2 squash commits | 19 文件 +1928/−297 | 集成线 = 翻译 squash + ui squash |

- **明确不向上游提 PR**：fork 自用，功能按自己的审美做。仓库开放，他人自取。
- 上游有更新时的流程：同步 `main` → 三个功能分支逐个 rebase/适配 → 重新 squash 刷 `dev`。

## 2. 分支模型与集成流程

```
main (上游基线, 只读)
 ├── cheat        功能分支（12 提交）
 ├── ui           功能分支（5 提交）
 └── translation  功能分支（28 提交）
      ↓ 各自 squash merge
dev = main → [feat: translation squash] → [feat: ui squash]
```

重建 dev 的标准操作（已多次演练）：

```bash
git checkout dev
git reset --hard <main 基线>
git merge --squash translation && git commit -m "feat: add MTool-format translation support for RPG Maker MV/MZ"
git merge --squash ui && git commit -m "feat: consolidate cover actions into cover settings with aggregated source search"
git push --force-with-lease origin dev
```

**已知冲突点（每次重刷必看）**：`GameScreen.kt` 的封面函数区——
translation 侧保留上游旧的单来源 `CoverSourcePickerDialog`，ui 侧已替换为聚合的
`CoverSettingsDialog`。**一律取 ui 侧**。抽屉 buildList 区当前可自动合并
（封面设置 + 修改名称 + 翻译相关条目按双方声明自然组合）。

破坏性操作前先打备份 tag（先例：`backup/dev-old-20261031`、`backup/translation-drawer-split-20261031`）。

## 3. cheat 分支：RPG Maker 修改器

只动三个运行时资产文件，不碰任何 Kotlin/资源：

- `engine/src/main/assets/__rpgmaker_mod_core.js` —— 作弊核心逻辑
- `engine/src/main/assets/__rpgmaker_mod_ui.js` —— 修改器面板 UI
- `engine/src/main/assets/__rpgmaker_mod.css` —— 面板样式（含地图列表高亮只标注用户选中项等修正）

## 4. ui 分支：封面动作整合

改动集中在 `GameScreen.kt` + `SlidingTabRow.kt` + 三语字符串 + 一个新图标。

- **抽屉整合**：删除独立的「搜索封面 / 修改封面」入口，合并为「封面设置」弹窗
  （两个 AppNavItem：搜索封面带 summary、更换封面本地图片）。配套新图标
  `ic_sheet_cover_settings.xml`（相框）。
- **聚合搜索**：`CoverSearchDialog` 去掉来源参数，改为内部来源切换，一次并发搜全部启用来源
  （`coroutineScope + async + awaitAll`，全部失败才报错）；来源切换行用自研 `SlidingTabRow(scrollable)`。
- **规范修复**：弹窗内 `AppNavItem` 补 `containerColor = DialogItemSurface + indication = null`；
  `CoverSearchDialog` 用裸 Dialog 时必须手动 `CompositionLocalProvider(LocalAppDialogContent provides true)`，
  否则 `SlidingTabRow` 指示器取 NavWhite 画在 NavWhite 上不可见。
- **SlidingTabRow 首帧修复**：滚动态指示器恒渲染，几何缺失/零宽时按视口均分估算，
  `viewportSize` 可观察保证首帧必然重组（首页/引擎页共用受益）。
- **加载提示居中**：「正在搜索封面」从头部小字移入结果区居中。

> 拍板不改项：来源栏滚动形态在放得下时左侧 30dp / 右侧贴边的不对称留白——这是
> scrollable 形态的固有表现，非 bug，**勿再主动提议修改**。

## 5. translation 分支：MV/MZ 译表翻译（核心功能）

### 5.1 架构与分层

```
app 层（UI）                    engine 层（无 UI 依赖）
GameScreen.kt                  TranslationTable.kt（288 行）
「属性」对话框                  ├─ resolveTranslationFile  解析生效译表
  ├─ 名称修改行                 ├─ gameRootTranslationFiles 枚举候选
  └─ 加载翻译行(MV/MZ)          └─ 热更新监听
      ↓ TranslationSelectDialog        ↑ LaunchContract 透传 translation_language
                                __translation_runtime.js（721 行，页面注入）
```

- 持久化：`PerGameSettingsStore` 游戏覆盖分区的 `translation_language`，启动时
  `EngineLauncher` 读取并经 `LaunchContract` 透传给引擎。
- 引擎层遵守分层红线：不引 UI、不引 Compose。

### 5.2 译表格式与识别规则

MTool 导出格式（顶层对象、值为基本类型）。候选识别条件：`.json` 后缀、≤16MB、
UTF-8（容忍 BOM）、顶层 JSON 对象、值全部为基本类型（嵌套对象/数组即淘汰）、
至少 1 条字符串值。`ManualTransFile.json` 等惯例名仅排序优先，**不走免检特例**
（任何叫该名的损坏文件不得混进列表）。

### 5.3 解析优先级

1. App 私有目录 `<externalFilesDir>/translations/<sha256(gameId)>/<选定文件>.json`
   （可写、热更新友好；游戏目录在 SAF 下只读）
2. 游戏目录根同名文件（只读兜底）
3. 未选定或选定文件不存在 → **不翻译、保持原文**（无自动回落）

选定文件被删除时：抽屉打开即用 `EngineLauncher.hasTranslationFile`（与启动解析同路径）
验证，失效则同时清 UI 状态与持久化——**不做「保持显示」的假回落**。

### 5.4 运行时（__translation_runtime.js）

- window API：`__TRS_SET_TRANSLATIONS__` / `__TRS_STATUS__` /
  `__TRS_RUNTIME_TRANSLATOR__` / `__TRS_WRITEBACK__`（F12 可见，已全部中性化，
  无 MTool 字样；代码内表述统一为「MTool 导出格式」）。
- **双路径查找**：整句 `map['$' + block]` + 行索引切片，同时兼容 MTool 逐行键
  与自家整句块键。
- `%1/%2` 占位符模板由引擎 format 后处理，译值必须保留占位符。

### 5.5 UI 交互（终态，用户拍板「不迁就上游作者」）

抽屉「属性」条目（所有游戏）→ 属性对话框：名称修改行 + MV/MZ「加载翻译」行
（`PropertyOptionRow` 带当前值）→ `TranslationSelectDialog`：
两步确认（点行只选中 → 底部确定提交）、列表 `heightIn(max = 320.dp)`、
每次打开重扫译表（授权/新增文件即时生效）、首项「（默认）」恒为合法选项。

> 历史方案备注：曾按上游作者意见改为抽屉独立条目 + 「翻译设置」命名
> （备份 tag `backup/translation-drawer-split-20261031`），后因确定不提 PR
> 整体回退到属性聚合方案。上游作者曾建议把翻译放进妙妙工具侧边栏/引擎设置，
> 已否决——翻译跟单个游戏走，全局容器语义不符。

### 5.6 Tyrano 引擎

宿主管线（TyranoActivity 接入）已通，但 UI 门只对 MV/MZ 开放，Tyrano 翻译**暂缓**。
实现备忘在 `.workbuddy/tyrano-translation-notes.md`（gitignore 内，不入库）。

### 5.7 验证

- `engine/src/test/js/translation-runtime.harness.js`：54 项断言，覆盖查找/占位符/BOM/切片。
- 跑法：`gradle jsHarnessTranslation`（engine/build.gradle 内注册的任务）。
- 提取工具覆盖度实测：数据库术语/能力词/系统消息模板 100%，唯一缺 Animations
  动画名（玩家不可见，不需补）。

## 6. CI

- `android-ci.yml`：push `main` 自动触发 + PR 到 main；**其他分支需手动 dispatch**
  （`workflow_dispatch`，无输入参数）。
- 本机无 gh CLI 时的 dispatch 方法：用 `git credential fill` 取 token，
  POST `/repos/TYPEXOC/Tyranor-Next/actions/workflows/android-ci.yml/dispatches`，body `{"ref":"分支名"}`。
- 产物：app-debug（内含 standard/ludashi/pubg/antutu 四种渠道 APK）+ ethornell 验收产物。
- 推功能分支不会触发构建，别干等。

## 7. 开发约定（踩过坑的都在这）

1. **注释只写功能性/为什么**：非显而易见的行为原因、状态语义、守卫原因、取色陷阱、
   规范依据。禁止叙事型注释（「XX：点击打开YY」「同款ZZ样式」这类复述一律不写）。
   清理基线注释时先对照 main 确认归属——重缩进会让基线注释在 diff 中误报为新增。
2. **三语字符串同步**：`values/`、`values-en/`、`values-ja/` 必须同 key 同步增删。
3. **CHANGELOG/SPEC 等上游文档不动**，保持上游原样。
4. **提交信息**：英文祈使句（早期有中文存量，历史提交不重写）。
5. **`.workbuddy/` 已进 .gitignore**：`git add -A` 前务必检查，本项目误提交过两次。
6. 删除函数用脚本动态定位大括号深度时，参数行无 `{}` 会导致 depth 恒 0 提前停——已踩坑。
7. Compose 陷阱备忘：`remember` 首帧快照不会随异步回填重算（踩过勾选错位 bug）；
   LazyListScope 的内容 lambda 非 `@Composable`，`stringResource` 要提前到组合体取值。

## 8. 未决事项

- [ ] Tyrano 引擎翻译（方案已备忘，待开工）
- [ ] 上游更新后的三分支适配（流程见 §2，注意保住属性对话框方案与 ui 侧封面聚合）
- [ ] （低优先）历史中文提交信息不重写，新增提交保持英文即可
