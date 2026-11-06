package com.tyranor.next.core.engine.runtime

/** Ruby VM shipped by the TYN RGSS module. The value is also sent across the module launch contract. */
enum class RgssRubyRuntime(val wireValue: String) {
    RUBY_187("187"),
    RUBY_193("193"),
    RUBY_313("313");

    companion object {
        val DEFAULT = RUBY_313

        fun fromWireValue(value: String?): RgssRubyRuntime =
            entries.firstOrNull { it.wireValue == value } ?: DEFAULT
    }
}
