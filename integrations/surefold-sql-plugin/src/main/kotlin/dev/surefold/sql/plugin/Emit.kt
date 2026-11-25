@file:OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)

package dev.surefold.sql.plugin

import org.jetbrains.kotlin.backend.common.extensions.DeclarationFinder
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.backend.jvm.ir.kClassReference
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGetObjectValue
import org.jetbrains.kotlin.ir.builders.irInt
import org.jetbrains.kotlin.ir.builders.irNull
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.builders.irVararg
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionAccessExpression
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.hasTopLevelEqualFqName
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

internal class Symbols(private val context: IrPluginContext, private val files: List<IrFile>) {
    private val builtins: DeclarationFinder = context.finderForBuiltins()
    val prepared: IrSimpleFunctionSymbol? = function("prepared", 4)
    val preparedTuple: IrSimpleFunctionSymbol? = function("prepared", 6)
    val bind: IrSimpleFunctionSymbol? = function("bind", 2)
    val dataCodec: IrSimpleFunctionSymbol? = function("dataCodec", 4)
    val inlineCodec: IrSimpleFunctionSymbol? = function("inlineCodec", 2)
    val query: IrSimpleFunctionSymbol? = function("query", 1)
    val returning: IrSimpleFunctionSymbol? = function("returning", 1)
    val boxed: IrSimpleFunctionSymbol? = function("boxed", 0)
    val orNull: IrSimpleFunctionSymbol? = function("orNull", 0)
    val codecs: IrClassSymbol? = klass("Codecs")
    val postgresCodecs: IrClassSymbol? = klass("PostgresCodecs")
    val dbCodec: IrClassSymbol? = klass("DbCodec")
    val bindClass: IrClassSymbol? = klass("Bind")
    val arrayOf: IrSimpleFunctionSymbol? = builtins
        .findFunctions(CallableId(FqName("kotlin"), Name.identifier("arrayOf")))
        .firstOrNull { symbol -> symbol.owner.parameters.any { it.varargElementType != null } }
    val javaClass: IrSimpleFunctionSymbol? = builtins.findProperties(
        CallableId(FqName("kotlin.jvm"), Name.identifier("java")),
    ).firstOrNull { symbol ->
        symbol.owner.getter?.parameters.orEmpty().any { parameter ->
            parameter.kind == IrParameterKind.ExtensionReceiver &&
                parameter.type.classFqName?.asString()?.contains("KClass") == true
        }
    }?.owner?.getter?.symbol

    fun codecField(codec: CodecField): IrSimpleFunction? =
        properties(CallableId(ClassId(SQL, Name.identifier(codec.holder)), Name.identifier(codec.field)))
            .firstOrNull()?.owner?.getter

    /** The runtime object a built-in codec lives on, `Codecs` or a dialect's own. */
    fun codecHolder(name: String): IrClassSymbol? = when (name) {
        "Codecs" -> codecs
        "PostgresCodecs" -> postgresCodecs
        else -> null
    }

    /** A class compiled in an earlier round, which only the class path still has. */
    fun compiled(id: ClassId): IrClassSymbol? = files.firstOrNull()?.let { context.finderForSource(it).findClass(id) }

    private fun function(name: String, regular: Int): IrSimpleFunctionSymbol? =
        functions(CallableId(SQL, Name.identifier(name))).firstOrNull { symbol ->
            symbol.owner.parameters.count { it.kind == IrParameterKind.Regular } == regular
        }

    private fun klass(name: String): IrClassSymbol? {
        val id = ClassId(SQL, Name.identifier(name))
        return runtime(id.packageFqName, builtins.findClass(id)?.let(::listOf).orEmpty()) { finder ->
            finder.findClass(id)?.let(::listOf).orEmpty()
        }.firstOrNull()
    }

    private fun functions(id: CallableId): Collection<IrSimpleFunctionSymbol> =
        runtime(id.packageName, builtins.findFunctions(id)) { it.findFunctions(id) }

    private fun properties(id: CallableId): Collection<org.jetbrains.kotlin.ir.symbols.IrPropertySymbol> =
        runtime(id.packageName, builtins.findProperties(id)) { it.findProperties(id) }

    private fun <T> runtime(
        packageName: FqName,
        fromBuiltins: Collection<T>,
        fromFile: (DeclarationFinder) -> Collection<T>,
    ): Collection<T> {
        if (fromBuiltins.isNotEmpty() || !packageName.asString().startsWith("dev.surefold.sql")) return fromBuiltins
        return files.firstNotNullOfOrNull { file ->
            fromFile(context.finderForSource(file)).takeIf { it.isNotEmpty() }
        }.orEmpty()
    }

    companion object {
        val SQL: FqName = FqName("dev.surefold.sql")
    }
}

internal class Emit(
    private val context: IrPluginContext,
    private val symbols: Symbols,
    scope: IrSymbol,
    start: Int,
    end: Int,
) {
    private val builder = DeclarationIrBuilder(context, scope, start, end)

    fun string(value: String): IrExpression = builder.irString(value)

    fun absent(): IrExpression = builder.irNull()

    fun int(value: Int): IrExpression = builder.irInt(value)

    fun codecField(codec: CodecField): IrExpression? {
        val getter = symbols.codecField(codec) ?: return null
        val codecs = symbols.codecHolder(codec.holder) ?: return null
        val call = builder.irCall(getter.symbol)
        val dispatch = getter.parameters.firstOrNull { it.kind == IrParameterKind.DispatchReceiver } ?: return null
        call.arguments.set(dispatch, builder.irGetObjectValue(codecs.owner.defaultType, codecs))
        return call
    }

    fun extension(symbol: IrSimpleFunctionSymbol?, receiver: IrExpression, type: IrType?): IrExpression? {
        if (symbol == null) return null
        val call = builder.irCall(symbol)
        val parameter =
            symbol.owner.parameters.firstOrNull { it.kind == IrParameterKind.ExtensionReceiver } ?: return null
        call.arguments.set(parameter, receiver)
        if (type != null && call.typeArguments.isNotEmpty()) call.typeArguments[0] = type
        return call
    }

    fun javaClass(klass: IrClass): IrExpression? {
        val getter = symbols.javaClass ?: return null
        val call = builder.irCall(getter)
        val parameter =
            getter.owner.parameters.firstOrNull { it.kind == IrParameterKind.ExtensionReceiver } ?: return null
        call.arguments.set(parameter, builder.kClassReference(klass.defaultType))
        if (call.typeArguments.isNotEmpty()) call.typeArguments[0] = klass.defaultType
        return call
    }

    fun call(
        symbol: IrSimpleFunctionSymbol?,
        args: List<IrExpression>,
        type: IrType? = null,
        receiver: IrExpression? = null,
    ): IrExpression? {
        if (symbol == null) return null
        val call = builder.irCall(symbol)
        if (type != null && call.typeArguments.isNotEmpty()) call.typeArguments[0] = type
        if (receiver != null) {
            val parameter = symbol.owner.parameters.firstOrNull { it.kind == IrParameterKind.ExtensionReceiver }
                ?: return null
            call.arguments.set(parameter, receiver)
        }
        val regular = symbol.owner.parameters.filter { it.kind == IrParameterKind.Regular }
        if (regular.size != args.size) return null
        regular.zip(args).forEach { (parameter, argument) -> call.arguments.set(parameter, argument) }
        return call
    }

    fun array(element: IrType, values: List<IrExpression>): IrExpression? {
        val symbol = symbols.arrayOf ?: return null
        val call = builder.irCall(symbol)
        if (call.typeArguments.isNotEmpty()) call.typeArguments[0] = element
        val parameter = symbol.owner.parameters.firstOrNull { it.varargElementType != null } ?: return null
        call.arguments.set(parameter, builder.irVararg(element, values))
        return call
    }

    fun strings(values: List<String>): IrExpression? = array(context.irBuiltIns.stringType, values.map(::string))

    fun instance(klass: IrClass): IrExpression = builder.irGetObjectValue(klass.defaultType, klass.symbol)

    fun codecAny(): IrType? {
        val symbol = symbols.dbCodec ?: return null
        return symbol.typeWith(context.irBuiltIns.anyNType)
    }

    fun bindType(): IrType? = symbols.bindClass?.owner?.defaultType
}

internal fun IrFunctionAccessExpression.regularArguments(): List<IrExpression?> {
    val parameters = symbol.owner.parameters
    return parameters.mapIndexedNotNull { index, parameter ->
        if (parameter.kind == IrParameterKind.Regular) arguments.getOrNull(index) else null
    }
}

internal fun IrFunctionAccessExpression.extensionReceiver(): IrExpression? {
    val parameter = symbol.owner.parameters.firstOrNull { it.kind == IrParameterKind.ExtensionReceiver } ?: return null
    return arguments.getOrNull(symbol.owner.parameters.indexOf(parameter))
}

internal fun IrFunctionAccessExpression.isSurefoldSql(name: String): Boolean =
    symbol.owner.hasTopLevelEqualFqName("dev.surefold.sql", name)
