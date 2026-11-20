@file:OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)

package dev.surefold.sql.plugin

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageLocation
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.IrAnonymousInitializer
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationContainer
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithName
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrBody
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstKind
import org.jetbrains.kotlin.ir.expressions.IrContainerExpression
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrExpressionBody
import org.jetbrains.kotlin.ir.expressions.IrGetEnumValue
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrStringConcatenation
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.expressions.IrWhen
import org.jetbrains.kotlin.ir.symbols.IrBindableSymbol
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.typeOrNull
import org.jetbrains.kotlin.ir.types.withNullability
import org.jetbrains.kotlin.ir.util.deepCopyWithoutPatchingParents
import org.jetbrains.kotlin.ir.util.getAnnotation
import org.jetbrains.kotlin.ir.util.hasTopLevelEqualFqName
import org.jetbrains.kotlin.ir.util.isNullable
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.ir.util.propertyIfAccessor
import org.jetbrains.kotlin.ir.visitors.IrTransformer
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import java.io.File
import java.util.IdentityHashMap

internal class Walk(val scope: IrSymbol?, val owner: IrSymbol?, val file: IrFile?)

internal class SqlRewriter(
    private val context: IrPluginContext,
    private val configuration: CompilerConfiguration,
    files: List<IrFile>,
) : IrTransformer<Walk>() {
    private val symbols = Symbols(context, files)
    private val namesOption: String? = configuration.get(NAMES)
    private val dialectOption: String? = configuration.get(DIALECT)

    /** Spec 0087 rule 6: what the target database accepts. Postgres unless the module names another. */
    private val dialect: PluginDialect = dialectOption?.let(::pluginDialect) ?: PluginDialect.Postgres
    private val moduleCase: ColumnCase = namesOption?.let(::columnCase) ?: ColumnCase.Snake
    private val objects = ArrayList<IrClass>()
    private val batchElements = IdentityHashMap<IrCall, IrType>()
    private val counts = HashMap<IrSymbol, Int>()
    private val anonymous = HashMap<IrSymbol, String>()
    private val entries = ArrayList<Pair<String, String>>()
    private val problems = ArrayList<String>()

    fun note(module: IrModuleFragment) {
        if (namesOption != null && columnCase(namesOption) == null) {
            report(null, module, "the names option is not snake, camel, pascal or upper-snake")
        }
        if (dialectOption != null && pluginDialect(dialectOption) == null) {
            val known = PluginDialect.entries.joinToString(" or ") { it.optionName }
            report(null, module, "the dialect option is not $known")
        }
        module.files.forEach { file -> collect(file) }
        module.accept(
            object : IrVisitorVoid() {
                override fun visitCall(expression: IrCall) {
                    if (expression.isSurefoldSql("batch") || expression.isSurefoldSql("batchReturning")) {
                        val sqlCall = findSql(expression.extensionReceiver())
                        val element = batchElement(expression)
                        if (sqlCall != null && element != null) batchElements[sqlCall] = element
                    }
                    super.visitCall(expression)
                }
            },
            null,
        )
    }

    fun writeCatalog() {
        val path = configuration.get(CATALOG_PATH) ?: return
        val file = File(path)
        if (problems.isNotEmpty()) {
            file.delete()
            return
        }
        file.parentFile?.mkdirs()
        file.writeText(encodeCatalog(entries))
    }

    override fun visitFile(declaration: IrFile, data: Walk): IrFile =
        super.visitFile(declaration, Walk(data.scope, data.owner, declaration))

    override fun visitFunction(declaration: IrFunction, data: Walk): IrStatement {
        val named = declaration.propertyIfAccessor
        val owner = if (named is IrProperty) named.symbol else declaration.symbol
        return super.visitFunction(declaration, Walk(declaration.symbol, owner, data.file))
    }

    override fun visitField(declaration: IrField, data: Walk): IrStatement {
        val property = declaration.correspondingPropertySymbol?.owner
        val owner = property?.symbol ?: data.owner
        val scope = property?.getter?.symbol ?: declaration.symbol
        return super.visitField(declaration, Walk(scope, owner ?: scope, data.file))
    }

    override fun visitAnonymousInitializer(declaration: IrAnonymousInitializer, data: Walk): IrStatement {
        val parent = declaration.parent as? IrClass
        val owner = parent?.symbol ?: data.owner
        return super.visitAnonymousInitializer(declaration, Walk(parent?.symbol ?: data.scope, owner, data.file))
    }

    override fun visitCall(expression: IrCall, data: Walk): IrElement {
        val visited = super.visitCall(expression, data)
        val call = visited as? IrCall ?: return visited
        val rewritten = when {
            call.isSurefoldSql("sql") -> rewriteSql(call, data)

            call.isSurefoldSql("query") && call.regularArguments().isEmpty() ->
                rewriteRow(call, data, returning = false)

            call.isSurefoldSql("returning") && call.regularArguments().isEmpty() ->
                rewriteRow(call, data, returning = true)

            else -> null
        }
        return rewritten ?: call
    }

    private fun rewriteSql(call: IrCall, data: Walk): IrExpression? {
        val scope = data.scope
        val owner = data.owner
        if (scope == null || owner == null) {
            report(data.file, call, "sql() is outside a declaration")
            return null
        }
        val statement = statementOf(call, owner, data) ?: return null
        val emit = Emit(context, symbols, scope, call.startOffset, call.endOffset)
        val built = emitPrepared(emit, statement)
        if (built == null) report(data.file, call, "${statement.id}: the statement could not be built")
        return built
    }

    private fun statementOf(call: IrCall, owner: IrSymbol, data: Walk): Statement? {
        val id = queryId(call, owner, data) ?: return null
        val scope = scopeOf(call, id, data) ?: return null
        val slots = boundSlots(call, id, data) ?: return null
        val rendered = render(slots)
        if (!singleCommand(rendered, id, data.file, call)) return null
        val sql = finish(scope, rendered, id, data.file, call) ?: return null
        val values = slots.filterIsInstance<Slot.Value>()
        val batch = when (val choice = batchPlan(call, values, id, data.file)) {
            BatchChoice.Broken -> return null
            BatchChoice.None -> null
            is BatchChoice.Ready -> choice.plan
        }
        if (!claim(id, sql, data.file, call)) return null
        return Statement(id, sql, values, batch, if (batch == null) null else valuesTuple(sql))
    }

    private fun queryId(call: IrCall, owner: IrSymbol, data: Walk): String? {
        val index = nextIndex(owner)
        val generated = named(owner).let { base -> if (index == 1) base else "$base#$index" }
        if (!hasParam(call, "id")) return generated
        return explicitId(call.argument("id"), generated, data.file, call)
    }

    private fun scopeOf(call: IrCall, id: String, data: Walk): ScopeChoice? {
        if (!hasParam(call, "scope")) return ScopeChoice.Tenant
        val expr = call.argument("scope") ?: return ScopeChoice.Tenant
        return when (enumEntry(expr, "dev.surefold.sql.Scope")) {
            "Tenant" -> ScopeChoice.Tenant

            "Platform" -> ScopeChoice.Platform

            else -> {
                report(data.file, call, "$id: the scope is not a constant")
                null
            }
        }
    }

    private fun finish(scope: ScopeChoice, rendered: String, id: String, file: IrFile?, call: IrCall): String? {
        if (!dialect.returning && hasReturning(rendered)) {
            report(file, call, "$id: the ${dialect.optionName} dialect has no returning")
            return null
        }
        if (scope == ScopeChoice.Platform) return rendered
        if (!dialect.tenantScope) {
            report(file, call, "$id: the ${dialect.optionName} dialect has no tenant scope; name Scope.Platform")
            return null
        }
        return when (val spliced = spliceTenant(rendered)) {
            is Splice.Ready -> spliced.sql

            is Splice.Broken -> {
                report(file, call, "$id: ${spliced.message}")
                null
            }
        }
    }

    private fun boundSlots(call: IrCall, id: String, data: Walk): List<Slot>? {
        val text = call.argument("text")
        val parsed = text?.let { parseTemplate(it) }
        if (parsed == null) {
            report(data.file, call, "$id: the text is not a template the plugin can decompose")
            return null
        }
        return slots(parsed, id, data.file, call)
    }

    private fun singleCommand(sql: String, id: String, file: IrFile?, call: IrCall): Boolean {
        val keyword = statementKeyword(sql)
        if (keyword != null && oneStatement(sql)) return true
        report(file, call, "$id: the statement is not one select, insert, update, delete or with")
        return false
    }

    private fun rewriteRow(call: IrCall, data: Walk, returning: Boolean): IrExpression? {
        val id = idName(data.owner)
        val receiver = call.extensionReceiver()
        val rewritten = receiver?.let { follow(it, call, data, returning) }
        if (rewritten == null) {
            report(data.file, call, "$id: the statement was not rewritten")
            return null
        }
        val index = call.symbol.owner.parameters.indexOfFirst { it.kind == IrParameterKind.ExtensionReceiver }
        if (index >= 0 && call.arguments.getOrNull(index) === rewritten) call.arguments[index] = null
        return rewritten
    }

    private fun follow(expr: IrExpression, call: IrCall, data: Walk, returning: Boolean): IrExpression? {
        val sql = preparedText(expr)
        if (sql != null) return buildRow(expr, sql, call, data, returning)
        val typed = expr as? IrTypeOperatorCall
        if (typed != null) return follow(typed.argument, call, data, returning)
        val branched = expr as? IrWhen
        if (branched != null) return followWhen(branched, call, data, returning)
        val block = expr as? IrContainerExpression
        if (block != null) return followBlock(block, call, data, returning)
        val read = expr as? IrGetValue ?: return null
        return followRead(read, call, data, returning)
    }

    private fun followWhen(expr: IrWhen, call: IrCall, data: Walk, returning: Boolean): IrExpression? {
        val rewritten = expr.branches.map { branch ->
            if (branch.result.isExhaustiveElse()) {
                branch.result
            } else {
                follow(branch.result, call, data, returning) ?: return null
            }
        }
        expr.branches.forEachIndexed { index, branch -> branch.result = rewritten[index] }
        expr.type = rewritten.first().type
        return expr
    }

    private fun followBlock(expr: IrContainerExpression, call: IrCall, data: Walk, returning: Boolean): IrExpression? {
        val last = expr.statements.lastOrNull() as? IrExpression ?: return null
        val rewritten = follow(last, call, data, returning) ?: return null
        expr.statements[expr.statements.lastIndex] = rewritten
        expr.type = rewritten.type
        return expr
    }

    private fun followRead(expr: IrGetValue, call: IrCall, data: Walk, returning: Boolean): IrExpression? {
        val variable = expr.symbol.owner as? IrVariable ?: return null
        val init = variable.initializer ?: return null
        val rewritten = follow(init, call, data, returning) ?: return null
        variable.initializer = rewritten
        variable.type = rewritten.type
        expr.type = rewritten.type
        return expr
    }

    private fun buildRow(
        receiver: IrExpression,
        sql: String,
        call: IrCall,
        data: Walk,
        returning: Boolean,
    ): IrExpression? {
        val scope = data.scope ?: return null
        val id = idName(data.owner)
        val rowType = call.typeArguments.firstOrNull()
        val kind = if (returning) "returning" else "select"
        val columns = rowColumns(sql, call, data, returning, id, rowType) ?: return null
        val plan = rowPlan(rowType ?: return null, columns, kind, id, data.file, call) ?: return null
        val emit = Emit(context, symbols, scope, call.startOffset, call.endOffset)
        val codec = plan.emit(emit)
        if (codec == null) {
            report(data.file, call, "$id: the row codec could not be built")
            return null
        }
        val target = if (returning) symbols.returning else symbols.query
        return emit.call(target, listOf(codec), type = rowType, receiver = receiver.deepCopyWithoutPatchingParents())
    }

    /** The columns a row is read from, or null once the reason it cannot be read is reported. */
    private fun rowColumns(
        sql: String,
        call: IrCall,
        data: Walk,
        returning: Boolean,
        id: String,
        rowType: IrType?,
    ): List<String>? {
        val kind = if (returning) "returning" else "select"
        val columns = if (returning) returningColumns(sql) else selectColumns(sql)
        val problem = when {
            rowType == null -> "the row type is missing"
            returning && !dialect.returning -> "the ${dialect.optionName} dialect has no returning"
            !returning && outerSelect(sql) == null -> "query reads a select"
            columns == null -> "the $kind list is not a plain column list"
            else -> null
        }
        problem?.let { report(data.file, call, "$id: $it") }
        return columns.takeIf { problem == null }
    }

    private fun emitPrepared(emit: Emit, statement: Statement): IrExpression? {
        val binds = statement.values.map { slot ->
            val codec = slot.plan.emit(emit) ?: return null
            val bound = slot.source.deepCopyWithoutPatchingParents()
            emit.call(symbols.bind, listOf(codec, bound), type = slot.source.type) ?: return null
        }
        val bindType = emit.bindType() ?: return null
        val array = emit.array(bindType, binds) ?: return null
        val batchArg = statement.batch?.let { it.emit(emit) ?: return null } ?: emit.absent()
        val common = listOf(emit.string(statement.id), emit.string(statement.sql), array, batchArg)
        val tuple = statement.tuple ?: return emit.call(symbols.prepared, common)
        return emit.call(symbols.preparedTuple, common + emit.int(tuple.first) + emit.int(tuple.last))
    }

    private fun batchPlan(call: IrCall, values: List<Slot.Value>, id: String, file: IrFile?): BatchChoice {
        val marked = batchElements[call]
        if (marked == null) {
            val single = singlePlan(values)
            return if (single == null) BatchChoice.None else BatchChoice.Ready(single)
        }
        val found = codecFor(notNull(marked))
        if (found is CodecResult.Missing) {
            report(file, call, "$id: ${found.message}")
            return BatchChoice.Broken
        }
        val plan = (found as CodecResult.Ready).plan
        if (!aligns(values, marked, plan)) {
            report(file, call, "$id: the batch row does not line up with the statement")
            return BatchChoice.Broken
        }
        return BatchChoice.Ready(plan)
    }

    private fun explicitId(expr: IrExpression?, generated: String, file: IrFile?, at: IrCall): String? {
        val text = constString(expr)
        if (text == null) {
            report(file, at, "$generated: the explicit id is not a constant")
            return null
        }
        if (!EXPLICIT_ID.matches(text)) {
            report(file, at, "$generated: the explicit id must match [a-z0-9.#]+")
            return null
        }
        return text
    }

    private fun slots(pieces: List<Piece<IrExpression>>, id: String, file: IrFile?, at: IrCall): List<Slot>? =
        pieces.fold(emptyList<Slot>() as List<Slot>?) { acc, piece ->
            if (acc == null) {
                null
            } else {
                when (piece) {
                    is Piece.Text -> textSlot(piece.text, acc, id, file, at)
                    is Piece.Hole -> holeSlot(piece.payload, acc, id, file, at)
                }
            }
        }

    private fun textSlot(text: String, acc: List<Slot>, id: String, file: IrFile?, at: IrCall): List<Slot>? = when {
        '$' in text -> {
            report(file, at, "$id: the template has a \$ the plugin does not bind")
            null
        }

        '?' in text -> {
            report(file, at, "$id: the template has a placeholder the plugin does not bind")
            null
        }

        else -> acc + Slot.Words(text)
    }

    private fun holeSlot(expr: IrExpression, acc: List<Slot>, id: String, file: IrFile?, at: IrCall): List<Slot>? =
        when (val ident = identOf(expr)) {
            is Ident.Constant -> if (IDENTIFIER.matches(ident.text)) {
                acc + Slot.Words(ident.text)
            } else {
                report(file, at, "$id: a spliced identifier is not a constant")
                null
            }

            Ident.Dynamic -> {
                report(file, at, "$id: a spliced identifier is not a constant")
                null
            }

            Ident.NotIdent -> when (val found = codecFor(expr.type)) {
                is CodecResult.Missing -> {
                    report(file, at, "$id: ${found.message}")
                    null
                }

                is CodecResult.Ready -> acc + Slot.Value(expr, found.plan)
            }
        }

    private fun rowPlan(
        type: IrType,
        columns: List<String>,
        kind: String,
        id: String,
        file: IrFile?,
        at: IrCall,
    ): CodecPlan? {
        val found = codecFor(type)
        if (found is CodecResult.Missing) {
            report(file, at, "$id: ${found.message}")
            return null
        }
        val plan = (found as CodecResult.Ready).plan
        val klass = notNull(type).classOrNull?.owner
        if (klass != null && klass.isData) {
            val props = properties(klass)
            val resolved = if (props == null) null else resolveColumns(klass, props)
            if (resolved is Columns.Broken) {
                report(file, at, "$id: ${resolved.message}")
                return null
            }
            if (resolved !is Columns.Ready || !columnsMatch(columns, resolved.names)) {
                report(file, at, "$id: ${klass.name.asString()} does not line up with the $kind list")
                return null
            }
        } else if (columns.size != plan.placeholders) {
            report(file, at, "$id: the $kind list does not match the row")
            return null
        }
        return plan
    }

    private fun codecFor(type: IrType): CodecResult {
        if (type.isNullable()) return nullablePlan(type)
        scalar(type)?.let { return it }
        val custom = objects.filter { implements(it, type) }
        val klass = type.classOrNull?.owner
        return when {
            custom.size > 1 -> CodecResult.Missing("ambiguous codec for ${typeName(type)}")
            custom.size == 1 -> customPlan(custom.single(), type)
            klass == null -> CodecResult.Missing(noCodec(type))
            klass.isValue || klass.valueClassRepresentation != null -> inlinePlan(klass, type)
            klass.isData -> dataPlan(klass, type)
            else -> CodecResult.Missing(noCodec(type))
        }
    }

    private fun nullablePlan(type: IrType): CodecResult {
        val inner = codecFor(notNull(type))
        if (inner is CodecResult.Missing) return inner
        val plan = (inner as CodecResult.Ready).plan
        if (plan.placeholders != 1) return CodecResult.Missing("a null ${typeName(type)} is not one column")
        return CodecResult.Ready(
            CodecPlan(1, type) { emit ->
                val expr = plan.emit(emit) ?: return@CodecPlan null
                emit.extension(symbols.orNull, expr, notNull(type))
            },
        )
    }

    private fun scalar(type: IrType): CodecResult? {
        val name = type.classFqName?.asString()
        val field = dialect.codecs.scalars[name]
        if (field != null) return fieldPlan(field, type)
        if (name != "kotlin.collections.List") return null
        val element = (type as? IrSimpleType)?.arguments?.firstOrNull()?.typeOrNull ?: return null
        if (element.isNullable()) return null
        val listField = dialect.codecs.lists[element.classFqName?.asString()] ?: return null
        return fieldPlan(listField, type)
    }

    /** A dialect other than the default says it is the reason, so a `UUID` under `standard` is not a mystery. */
    private fun noCodec(type: IrType): String {
        val plain = "no codec for ${typeName(type)}"
        return if (dialect == PluginDialect.Postgres) plain else "$plain in the ${dialect.optionName} dialect"
    }

    private fun fieldPlan(field: CodecField, type: IrType): CodecResult = CodecResult.Ready(
        CodecPlan(1, type) { emit -> emit.codecField(field) },
    )

    private fun customPlan(obj: IrClass, type: IrType): CodecResult {
        val count = placeholderCount(obj)
            ?: return CodecResult.Missing("codec ${obj.name.asString()} has no constant placeholders")
        return CodecResult.Ready(CodecPlan(count, type) { emit -> emit.instance(obj) })
    }

    private fun inlinePlan(klass: IrClass, type: IrType): CodecResult {
        val innerType = properties(klass)?.singleOrNull()?.type ?: inlineUnderlying(klass)
            ?: return CodecResult.Missing(noCodec(type))
        val nested = codecFor(innerType)
        if (nested is CodecResult.Missing) return nested
        val plan = (nested as CodecResult.Ready).plan
        return CodecResult.Ready(
            CodecPlan(plan.placeholders, type) { emit ->
                val codec = plan.emit(emit) ?: return@CodecPlan null
                val java = emit.javaClass(klass) ?: return@CodecPlan null
                emit.call(symbols.inlineCodec, listOf(codec, java))
            },
        )
    }

    private fun inlineUnderlying(klass: IrClass): IrType? {
        val representation = klass.valueClassRepresentation ?: return null
        return representation.underlyingPropertyNamesToTypes?.singleOrNull()?.second
    }

    private fun named(owner: IrSymbol): String {
        val base = idName(owner)
        if (!base.endsWith(".<anonymous>")) return base
        return anonymous.getOrPut(owner) {
            val earlier = anonymous.values.count { it == base || it.startsWith("$base.") }
            if (earlier == 0) base else "$base.$earlier"
        }
    }

    private fun dataPlan(klass: IrClass, type: IrType): CodecResult {
        val props = properties(klass)
        if (props.isNullOrEmpty()) return CodecResult.Missing(noCodec(type))
        val named = when (val resolved = resolveColumns(klass, props)) {
            is Columns.Broken -> return CodecResult.Missing(resolved.message)
            is Columns.Ready -> resolved.names
        }
        val parts = props.map { codecFor(it.type) }
        val missing = parts.filterIsInstance<CodecResult.Missing>().firstOrNull()
        if (missing != null) return missing
        val ready = parts.filterIsInstance<CodecResult.Ready>()
        return CodecResult.Ready(
            CodecPlan(ready.sumOf { it.plan.placeholders }, type) { emit ->
                val java = emit.javaClass(klass) ?: return@CodecPlan null
                val propertyNames = emit.strings(props.map { it.name }) ?: return@CodecPlan null
                val columns = emit.strings(named) ?: return@CodecPlan null
                val codecs = ready.map { part ->
                    val expr = part.plan.emit(emit) ?: return@CodecPlan null
                    emit.extension(symbols.boxed, expr, part.plan.type) ?: return@CodecPlan null
                }
                val element = emit.codecAny() ?: return@CodecPlan null
                val array = emit.array(element, codecs) ?: return@CodecPlan null
                emit.call(symbols.dataCodec, listOf(java, propertyNames, columns, array))
            },
        )
    }

    private fun claim(id: String, sql: String, file: IrFile?, at: IrCall): Boolean {
        if (entries.any { it.first == id }) {
            report(file, at, "$id is already declared")
            return false
        }
        if (entries.size >= CATALOG_LIMIT) {
            report(file, at, "$id: the catalog would exceed $CATALOG_LIMIT entries")
            return false
        }
        entries.add(id to sql)
        return true
    }

    private fun nextIndex(owner: IrSymbol): Int {
        val next = (counts[owner] ?: 0) + 1
        counts[owner] = next
        return next
    }

    private fun report(file: IrFile?, element: IrElement, message: String) {
        problems.add(message)
        val location = if (file == null) {
            CompilerMessageLocation.create("sql")
        } else {
            CompilerMessageLocation.create(
                file.fileEntry.name,
                file.fileEntry.getLineNumber(element.startOffset),
                file.fileEntry.getColumnNumber(element.startOffset),
                null,
            )
        }
        context.diagnosticReporter.report(SqlDiagnostics.SQL, message, location)
    }

    private fun resolveColumns(klass: IrClass, props: List<Prop>): Columns {
        val style = caseFor(klass) ?: return Columns.Broken("@ColumnName is not one of the four cases")
        return props.fold(Columns.Ready(emptyList()) as Columns) { acc, prop ->
            val soFar = acc as? Columns.Ready ?: return acc
            when (val literal = literalName(prop)) {
                Literal.Absent -> Columns.Ready(soFar.names + columnName(prop.name, style))

                is Literal.Text ->
                    if (literal.value.isEmpty()) {
                        Columns.Broken("@SqlName is empty")
                    } else {
                        Columns.Ready(soFar.names + literal.value)
                    }

                Literal.NotConstant -> Columns.Broken("@SqlName is not a constant")
            }
        }
    }

    private fun caseFor(klass: IrClass): ColumnCase? {
        val annotation = klass.getAnnotation(FqName(COLUMN_NAME)) ?: return moduleCase
        val entry = enumEntry(annotation.argumentMapping[Name.identifier("value")], "dev.surefold.sql.NameCase")
        return when (entry) {
            "Snake" -> ColumnCase.Snake
            "Camel" -> ColumnCase.Camel
            "Pascal" -> ColumnCase.Pascal
            "UpperSnake" -> ColumnCase.UpperSnake
            else -> null
        }
    }

    private fun literalName(prop: Prop): Literal {
        val annotation = prop.property?.getAnnotation(FqName(SQL_NAME))
            ?: prop.parameter.getAnnotation(FqName(SQL_NAME))
            ?: return Literal.Absent
        val text = constString(annotation.argumentMapping[Name.identifier("value")])
        return if (text == null) Literal.NotConstant else Literal.Text(text)
    }

    private fun collect(container: IrDeclarationContainer) {
        container.declarations.forEach { declaration ->
            if (declaration is IrClass) {
                if (declaration.kind == ClassKind.OBJECT) objects.add(declaration)
                collect(declaration)
            }
        }
    }
}

/** [tuple] bounds the `values` tuple a batch repeats, found here once rather than by the runtime on each batch. */
private class Statement(
    val id: String,
    val sql: String,
    val values: List<Slot.Value>,
    val batch: CodecPlan?,
    val tuple: IntRange?,
)

private class CodecPlan(val placeholders: Int, val type: IrType, val emit: (Emit) -> IrExpression?)

private sealed interface CodecResult {
    class Ready(val plan: CodecPlan) : CodecResult

    class Missing(val message: String) : CodecResult
}

private sealed interface BatchChoice {
    object None : BatchChoice

    class Ready(val plan: CodecPlan) : BatchChoice

    object Broken : BatchChoice
}

private sealed interface Slot {
    class Words(val text: String) : Slot

    class Value(val source: IrExpression, val plan: CodecPlan) : Slot
}

private enum class ScopeChoice { Tenant, Platform }

private sealed interface Columns {
    class Ready(val names: List<String>) : Columns

    class Broken(val message: String) : Columns
}

private sealed interface Literal {
    object Absent : Literal

    object NotConstant : Literal

    class Text(val value: String) : Literal
}

private const val COLUMN_NAME = "dev.surefold.sql.ColumnName"

private const val SQL_NAME = "dev.surefold.sql.SqlName"

private sealed interface Ident {
    class Constant(val text: String) : Ident

    object Dynamic : Ident

    object NotIdent : Ident
}

private fun parseTemplate(expr: IrExpression): List<Piece<IrExpression>>? =
    when (val parsed = flatten(expr, top = true)) {
        is Parsed.Ready -> parsed.pieces
        Parsed.Broken -> null
    }

private sealed interface Parsed {
    class Ready(val pieces: List<Piece<IrExpression>>) : Parsed

    object Broken : Parsed
}

private fun flatten(expr: IrExpression, top: Boolean): Parsed {
    val node = unwrap(expr)
    constString(node)?.let { return Parsed.Ready(listOf(Piece.Text(it))) }
    val operands = (node as? IrCall)?.let(::plusOperands)
    return when {
        node is IrStringConcatenation -> flattenAll(node.arguments, top = false)
        node is IrCall && node.symbol.owner.hasTopLevelEqualFqName("kotlin.text", "trimIndent") -> flattenTrimmed(node)
        operands != null -> flattenPlus(operands)
        top -> Parsed.Broken
        else -> Parsed.Ready(listOf(Piece.Hole(node)))
    }
}

private fun flattenTrimmed(call: IrCall): Parsed {
    val inner = call.extensionReceiver() ?: call.dispatchReceiver ?: return Parsed.Broken
    val pieces = (flatten(inner, top = false) as? Parsed.Ready)?.pieces ?: return Parsed.Broken
    return trimPieces(pieces)?.let { Parsed.Ready(it) } ?: Parsed.Broken
}

private fun flattenPlus(operands: Pair<IrExpression, IrExpression>): Parsed {
    val left = flatten(operands.first, top = false)
    val right = flatten(operands.second, top = false)
    return if (left is Parsed.Ready &&
        right is Parsed.Ready
    ) Parsed.Ready(left.pieces + right.pieces) else Parsed.Broken
}

private fun flattenAll(arguments: List<IrExpression>, top: Boolean): Parsed =
    arguments.fold(Parsed.Ready(emptyList()) as Parsed) { acc, argument ->
        val soFar = acc as? Parsed.Ready ?: return@fold Parsed.Broken
        when (val one = flatten(argument, top)) {
            is Parsed.Ready -> Parsed.Ready(soFar.pieces + one.pieces)
            Parsed.Broken -> Parsed.Broken
        }
    }

private fun plusOperands(call: IrCall): Pair<IrExpression, IrExpression>? {
    if (call.symbol.owner.name.asString() != "plus") return null
    val left = call.dispatchReceiver ?: call.extensionReceiver() ?: return null
    val right = call.regularArguments().singleOrNull() ?: return null
    val type = left.type.classFqName?.asString()
    if (type != "kotlin.String" && type != "kotlin.CharSequence") return null
    return left to right
}

private fun unwrap(expr: IrExpression): IrExpression = when (expr) {
    is IrContainerExpression -> {
        val only = expr.statements.singleOrNull() as? IrExpression
        if (only == null) expr else unwrap(only)
    }

    else -> expr
}

private fun constString(expr: IrExpression?): String? {
    val node = expr?.let(::unwrap) ?: return null
    return if (node is IrConst && node.kind == IrConstKind.String) node.value as? String else null
}

private fun render(slots: List<Slot>): String = slots.joinToString("") { slot ->
    when (slot) {
        is Slot.Words -> slot.text
        is Slot.Value -> List(slot.plan.placeholders) { "?" }.joinToString(", ")
    }
}

private fun preparedText(expr: IrExpression): String? {
    val call = expr as? IrCall ?: return null
    if (!call.isSurefoldSql("prepared")) return null
    return constString(call.regularArguments().getOrNull(1))
}

private fun singlePlan(values: List<Slot.Value>): CodecPlan? {
    val only = values.singleOrNull() ?: return null
    return only.plan.takeIf { it.placeholders == values.sumOf { slot -> slot.plan.placeholders } }
}

private fun aligns(values: List<Slot.Value>, element: IrType, plan: CodecPlan): Boolean {
    if (plan.placeholders != values.sumOf { it.plan.placeholders }) return false
    if (values.size == 1 && sameType(notNull(values[0].source.type), notNull(element))) return true
    val klass = notNull(element).classOrNull?.owner
    if (klass != null && klass.isData) {
        val props = properties(klass) ?: return false
        return props.size == values.size &&
            props.zip(values).all { (prop, slot) -> sameType(prop.type, slot.source.type) }
    }
    return values.size == 1
}

private fun identOf(expr: IrExpression): Ident {
    val call = unwrap(expr) as? IrCall ?: return Ident.NotIdent
    if (!call.isSurefoldSql("ident")) return Ident.NotIdent
    val text = constString(call.regularArguments().singleOrNull()) ?: return Ident.Dynamic
    return Ident.Constant(text)
}

private fun findSql(expr: IrExpression?): IrCall? {
    val call = expr as? IrCall ?: return null
    return when {
        call.isSurefoldSql("sql") -> call
        call.isSurefoldSql("query") || call.isSurefoldSql("returning") -> findSql(call.extensionReceiver())
        else -> null
    }
}

private fun batchElement(call: IrCall): IrType? {
    val rows = call.regularArguments().getOrNull(1) ?: return null
    val simple = rows.type as? IrSimpleType ?: return null
    val index = when (simple.classFqName?.asString()) {
        "kotlin.collections.List" -> 0
        "dev.surefold.stream.Stream" -> 1
        else -> return null
    }
    return simple.arguments.getOrNull(index)?.typeOrNull
}

private class Prop(val name: String, val type: IrType, val property: IrProperty?, val parameter: IrValueParameter)

private fun properties(klass: IrClass): List<Prop>? {
    val constructor = klass.primaryConstructor ?: return null
    val props = klass.declarations.filterIsInstance<IrProperty>().associateBy { it.name.asString() }
    return constructor.parameters.filter { it.kind == IrParameterKind.Regular }.map { parameter ->
        Prop(parameter.name.asString(), parameter.type, props[parameter.name.asString()], parameter)
    }
}

private fun hasParam(call: IrCall, name: String): Boolean =
    call.symbol.owner.parameters.any { it.kind == IrParameterKind.Regular && it.name.asString() == name }

private fun IrCall.argument(name: String): IrExpression? {
    val parameters = symbol.owner.parameters
    val index = parameters.indexOfFirst { it.kind == IrParameterKind.Regular && it.name.asString() == name }
    if (index < 0) return null
    return arguments.getOrNull(index)
}

private fun enumEntry(expr: IrExpression?, fqName: String): String? {
    val node = expr?.let(::unwrap) as? IrGetEnumValue ?: return null
    val parent = node.symbol.owner.parent as? IrClass ?: return null
    if (parent.kotlinFqName.asString() != fqName) return null
    return node.symbol.owner.name.asString()
}

private fun implements(obj: IrClass, target: IrType): Boolean =
    obj.superTypes.any { implementsType(it, target, emptySet()) }

private fun implementsType(type: IrType, target: IrType, seen: Set<IrClass>): Boolean {
    if (type.classFqName?.asString() == "dev.surefold.sql.DbCodec") {
        val argument = (type as? IrSimpleType)?.arguments?.firstOrNull()?.typeOrNull ?: return false
        return sameType(notNull(argument), notNull(target))
    }
    val klass = type.classOrNull?.owner ?: return false
    if (klass in seen) return false
    return klass.superTypes.any { implementsType(it, target, seen + klass) }
}

private fun sameType(left: IrType, right: IrType): Boolean {
    if (left.classFqName != right.classFqName || left.isNullable() != right.isNullable()) return false
    val leftArgs = (left as? IrSimpleType)?.arguments.orEmpty()
    val rightArgs = (right as? IrSimpleType)?.arguments.orEmpty()
    if (leftArgs.size != rightArgs.size) return false
    return leftArgs.zip(rightArgs).all { (one, other) ->
        val leftType = one.typeOrNull
        val rightType = other.typeOrNull
        if (leftType == null || rightType == null) one == other else sameType(leftType, rightType)
    }
}

private fun placeholderCount(obj: IrClass): Int? {
    val property = obj.declarations.filterIsInstance<IrProperty>().firstOrNull { it.name.asString() == "placeholders" }
        ?: return null
    val field = property.backingField?.initializer?.expression
    return constantInt(field) ?: constantInt(property.getter?.body)
}

private fun constantInt(body: IrBody?): Int? = when (body) {
    is IrExpressionBody -> constantInt(body.expression)

    is IrBlockBody -> body.statements.firstNotNullOfOrNull { statement ->
        when (statement) {
            is IrReturn -> constantInt(statement.value)
            is IrExpression -> constantInt(statement)
            else -> null
        }
    }

    else -> null
}

private fun IrExpression.isExhaustiveElse(): Boolean {
    val call = this as? IrCall ?: return false
    val name = (call.symbol.owner as? IrDeclarationWithName)?.name?.asString()
    return name == "noWhenBranchMatchedException"
}

private fun constantInt(expr: IrExpression?): Int? = when (expr) {
    is IrConst -> (expr.value as? Number)?.toInt()
    is IrReturn -> constantInt(expr.value)
    else -> null
}

private fun notNull(type: IrType): IrType {
    val simple = type as? IrSimpleType ?: return type
    return simple.withNullability(false)
}

private fun typeName(type: IrType): String = type.classFqName?.asString() ?: type.toString()

private fun idName(symbol: IrSymbol?): String {
    val declaration = (symbol as? IrBindableSymbol<*, *>)?.owner as? IrDeclarationWithName ?: return "sql"
    val base = qualify(declaration)
    return if (declaration is IrClass) "$base.init" else base
}

private fun qualify(declaration: IrDeclarationWithName): String {
    val prefix = prefixOf(declaration.parent, declaration)
    val name = declaration.name.asString()
    return if (prefix.isEmpty()) name else "$prefix.$name"
}

private fun prefixOf(parent: IrDeclarationParent, child: IrDeclarationWithName): String = when (parent) {
    is IrClass -> if (parent.kind == ClassKind.OBJECT || parent.kind == ClassKind.CLASS) qualify(parent) else ""

    is IrFunction -> {
        val named = parent.propertyIfAccessor
        if (named is IrProperty) qualify(named) else qualify(parent)
    }

    is IrFile -> if (child is IrClass) "" else fileStem(parent)

    else -> ""
}

private fun fileStem(file: IrFile): String =
    file.fileEntry.name.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.')
