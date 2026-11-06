package com.tyranor.next.core.engine.runtime

/**
 * 第三方 APK 运行时模块的 v1 协议常量。
 *
 * 模块通过 exported Activity 声明 [ACTION_LAUNCH] 被主 App 发现；包名前缀只是命名约定，
 * action 才是发现键。扫描描述放在模块自己的 `res/raw/`，由 [META_MANIFEST] 指向。
 */
object RuntimeModuleContract {
    /** 模块启动入口声明的 action（同时用于发现查询）。 */
    const val ACTION_LAUNCH = "com.tyranor.runtime.action.LAUNCH"

    /** 模块包名命名约定；不是安全边界。 */
    const val PACKAGE_PREFIX = "com.tyranor.runtime."

    /** Activity meta-data：指向模块 `res/raw/` 下的扫描描述资源。 */
    const val META_MANIFEST = "com.tyranor.runtime.MANIFEST"

    /** 启动 extra：游戏文件夹绝对路径。 */
    const val EXTRA_FOLDER_PATH = "com.tyranor.runtime.extra.FOLDER_PATH"

    /** 启动 extra：游戏标题（便于模块展示，可不读）。 */
    const val EXTRA_GAME_TITLE = "com.tyranor.runtime.extra.GAME_TITLE"

    /** 启动 extra：主 App 协议版本。 */
    const val EXTRA_PROTOCOL_VERSION = "com.tyranor.runtime.extra.PROTOCOL_VERSION"

    /** 可选 extra：稳定游戏标识，供模块隔离单游戏状态。 */
    const val EXTRA_GAME_ID = "com.tyranor.runtime.extra.GAME_ID"

    /** 可选 extra：已选翻译文件的 content URI；模块获临时读取授权。 */
    const val EXTRA_TRANSLATION_URI = "com.tyranor.runtime.extra.TRANSLATION_URI"

    /** 可选 extra：虚拟按键是否启用。 */
    const val EXTRA_PAD_ENABLED = "com.tyranor.runtime.extra.PAD_ENABLED"

    /** 可选 extra：按键方案的 content URI；模块获临时读写授权。 */
    const val EXTRA_PAD_PROFILE_URI = "com.tyranor.runtime.extra.PAD_PROFILE_URI"

    /** 可选 extra：当前生效的按键方案 id。 */
    const val EXTRA_PAD_PROFILE_ID = "com.tyranor.runtime.extra.PAD_PROFILE_ID"

    /** 可选 extra：TYN 当前主题强调色，用于模块内控件与按键层。 */
    const val EXTRA_THEME_PRIMARY_COLOR = "com.tyranor.runtime.extra.THEME_PRIMARY_COLOR"

    /** 可选 extra：主题强调色上的文字颜色。 */
    const val EXTRA_THEME_ON_PRIMARY_COLOR = "com.tyranor.runtime.extra.THEME_ON_PRIMARY_COLOR"

    /** 当前主 App 支持的协议版本。 */
    const val PROTOCOL_VERSION = 1
}

/** 可选运行能力；模块在 `tyranor_runtime.json` 的 `capabilities` 中声明。 */
object RuntimeModuleCapabilities {
    const val TRANSLATION = "translation"
}
