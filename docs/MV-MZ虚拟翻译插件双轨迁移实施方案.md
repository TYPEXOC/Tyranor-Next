# RPG Maker MV/MZ 虚拟翻译插件双轨迁移方案

> 状态：代码阶段已完成；真实 MV/MZ 游戏设备验收待执行。
> 范围：只调整 MV/MZ 翻译运行时的共用交接和加载方式；TYN RGSS（XP/VX/VX Ace）不属于本方案。
> 文件边界：不向游戏目录复制、创建或修改文件。

## 1. 目标与选择

MV/MZ 默认由 RPG Maker 的 `PluginManager` 加载 TYN 提供的翻译脚本，同时保留原有宿主注入路径用于手动对照。两种方式使用同一份翻译运行时、同一份规范化译文 map 和同一个热更新会话。

当前选择位于 `EngineLauncher.RPG_TRANSLATION_BOOTSTRAP_MODE`：默认值为 `virtualPlugin`；要手动对照旧路径时将它改成 `directInjection`。一次会话只使用所选模式。虚拟插件预检或资源加载失败时记录原因并停止虚拟插件登记，不自动切换到直接注入，避免掩盖故障。

## 2. 共用翻译层

```text
游戏操作抽屉
  └─ TranslationCandidate（只含 ID、展示名、格式 ID）
       └─ TranslationCatalog（主 App 的发现规则、格式解析器、单游戏选择）
            └─ TranslationMapProvider（只读 map URI、来源文件变化通知）
                 ├─ TranslationSession（内置 Web 宿主的生命周期、推表、热更新）
                 └─ RuntimeModuleLauncher（给声明翻译能力和规则的外置模块同类 URI）
```

- 主 App 的格式注册表当前提供 `mtool-json`，读取 MTool JSON 并归一成字符串键值 map。新增来源格式时，在主 App 注册对应解析器和发现规则。
- RPG Maker MV/MZ 使用内置 `mtool-json` 发现规则。第三方运行时模块必须在描述文件声明 `capabilities: ["translation"]` 和 `translationRule`；模块声明的是规则 ID，不接收主 App 文件路径，也不自行实现同一份译文格式解析。

```json
{
  "capabilities": ["translation"],
  "translationRule": "mtool-json"
}
```

- UI 通过 `TranslationCandidate` DTO 读候选、当前候选 ID 并更新选择，不读 `File`、目录路径或底层 prefs 键；选定后由 `TranslationCatalog` 保存候选 ID。
- `TranslationMapProvider` 为一次启动创建只读 URI。engine 宿主与外置模块读取的都是归一后 JSON map；源文件变化由 Provider 通知，并由 `TranslationSession` 以通知和 revision 轮询刷新。

## 3. MV/MZ 虚拟插件链路

1. App 启动 MV/MZ 游戏时创建已选 map 会话 URI，并传递 `virtualPlugin` 或手动选择的 `directInjection`。
2. v0 游戏由 `TyranoActivity` 承载；TynMV/TynMZ 的 v1/v2 路线由 `RpgMakerActivity` 承载。两个 Web 宿主共用 `TranslationSession`。
3. 虚拟模式检查标准 `js/plugins.js` 是否存在、是否暴露 `$plugins`，并检查同名脚本/插件冲突。ASAR 游戏通过归档读取执行相同检查。
4. 通过预检后，宿主仅对本地服务器响应追加插件登记；`js/plugins/TyranorNextTranslation.js` 的响应内容来自 APK 内 `__translation_runtime.js`。游戏 `PluginManager` 按其正常插件加载路径执行该脚本。
5. 页面完成和译表变化时，宿主把 map 写入页面 pending queue；运行脚本由插件管理器加载后消费最新 map。直接注入模式继续由宿主执行运行时脚本，再使用原 setter 应用译表。

插件登记只改变本次会话的 HTTP 响应；原始 `plugins.js`、游戏插件目录与其它游戏文件保持不变。MZ 已有核心脚本追加逻辑与翻译插件登记分开维护。

## 4. 阶段与完成情况

| 阶段 | 内容 | 状态 |
| --- | --- | --- |
| 1. 翻译共用层 | 注册解析/发现规则、DTO、选择 API、只读 map Provider 和外置模块规则声明 | 已实现 |
| 2. 宿主会话 | 内置 Web 宿主统一读取 map URI；直接注入和虚拟插件共用推表与热更新 | 已实现 |
| 3. 虚拟插件 | v0/v1/v2 宿主预检插件清单、追加内存登记、提供 APK 内脚本 | 已实现 |
| 4. 手动模式与诊断 | App 内单点选择模式；失败只记录并停止注册，不自动回退 | 已实现 |
| 5. 游戏验收 | 测试散文件与 ASAR、MV 与 MZ、v0/v1/v2、翻译热更新、缺失/冲突插件清单，并确认游戏文件哈希未变化 | 待真实设备与样本执行 |

## 5. 失败行为与验证范围

- `plugins.js` 缺失/不可读、`$plugins` 不符合预期、脚本名冲突或 APK 资源缺失：记录具体原因；不追加登记、不注入旧运行时、不改游戏文件。所选虚拟模式保持不变。
- 直接注入模式绕过插件清单预检，使用宿主页面生命周期注入脚本；切换必须显式改动 App 内部模式常量并重新构建。
- 当前已执行 `:engine:compileDebugKotlin :app:compileDebugKotlin`。编译只证明 Kotlin/资源可构建，不能替代真实游戏加载、翻译生效、热更新或文件完整性验收。
