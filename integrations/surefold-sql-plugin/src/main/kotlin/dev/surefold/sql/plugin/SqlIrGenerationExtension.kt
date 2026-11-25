package dev.surefold.sql.plugin

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment

internal class SqlIrGenerationExtension(private val configuration: CompilerConfiguration) : IrGenerationExtension {
    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        val rewriter = SqlRewriter(pluginContext, configuration, moduleFragment.files)
        rewriter.note(moduleFragment)
        moduleFragment.transform(rewriter, Walk(null, null, null))
        rewriter.writeCatalog()
        rewriter.writeIndex()
    }
}
