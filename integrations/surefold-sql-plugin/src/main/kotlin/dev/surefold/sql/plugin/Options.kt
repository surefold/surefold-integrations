package dev.surefold.sql.plugin

import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey

internal val CATALOG_PATH = CompilerConfigurationKey<String>("catalog path")

internal val NAMES = CompilerConfigurationKey<String>("column name case")

internal val DIALECT = CompilerConfigurationKey<String>("target database dialect")

@OptIn(ExperimentalCompilerApi::class)
class SqlCommandLineProcessor : CommandLineProcessor {
    override val pluginId: String = PLUGIN_ID

    override val pluginOptions: Collection<AbstractCliOption> = listOf(
        CliOption("catalog", "<path>", "Where to write the query catalog", required = false),
        CliOption("names", "<snake|camel|pascal|upper-snake>", "Column name case", required = false),
        CliOption("dialect", "<postgres|standard>", "The database the statements are for", required = false),
    )

    override fun processOption(option: AbstractCliOption, value: String, configuration: CompilerConfiguration) {
        when (option.optionName) {
            "catalog" -> configuration.put(CATALOG_PATH, value)
            "names" -> configuration.put(NAMES, value)
            "dialect" -> configuration.put(DIALECT, value)
        }
    }
}
