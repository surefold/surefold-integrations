package dev.surefold.sql.plugin

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration

@OptIn(ExperimentalCompilerApi::class)
class SqlCompilerPluginRegistrar : CompilerPluginRegistrar() {
    override val pluginId: String = PLUGIN_ID

    override val supportsK2: Boolean = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        IrGenerationExtension.registerExtension(SqlIrGenerationExtension(configuration))
    }
}

internal const val PLUGIN_ID = "dev.surefold.sql"
