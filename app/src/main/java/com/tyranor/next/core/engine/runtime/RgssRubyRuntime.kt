package com.tyranor.next.core.engine.runtime

/** Ruby VM shipped by the TYN RGSS module. The value is also sent across the module launch contract. */
enum class RgssRubyRuntime(val wireValue: String) {
    AUTO("auto"),
    RUBY_187("187"),
    RUBY_193("193"),
    RUBY_313("313");

    companion object {
        val DEFAULT = AUTO

        fun fromWireValue(value: String?): RgssRubyRuntime =
            entries.firstOrNull { it.wireValue == value } ?: DEFAULT

        /** Resolve automatic selection from the RGSS generation alias recorded by the scanner. */
        fun resolveForGame(runtime: RgssRubyRuntime, engineAlias: String?): RgssRubyRuntime {
            if (runtime != AUTO) return runtime
            return when (engineAlias) {
                "internal.rpgmxp", "internal.rpgmvx" -> RUBY_187
                "internal.rpgmvxace" -> RUBY_193
                else -> RUBY_313
            }
        }
    }
}
