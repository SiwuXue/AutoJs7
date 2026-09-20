package org.autojs.autojs.mcp.tools

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import org.autojs.autojs.mcp.McpArgumentException
import org.autojs.autojs.mcp.McpArgs
import org.autojs.autojs.mcp.McpJson
import org.autojs.autojs.mcp.McpSchema
import org.autojs.autojs.mcp.McpStorage
import org.autojs.autojs.mcp.McpTool
import org.autojs.autojs.mcp.McpToolResult
import org.autojs.autojs.mcp.McpToolRisk
import org.autojs.autojs.util.WorkingDirectoryUtils
import java.io.File

/**
 * Read and write SQLite databases inside the working directory.
 *
 * @Created by fork author on Sep 16, 2026.
 *
 * @Why
 *  ! The script facing `SQLite` API needs a `ScriptRuntime`, and the `Database`
 *  ! class it wraps is built for asynchronous callbacks. Neither fits a
 *  ! synchronous tool call, so this drives `SQLiteDatabase` directly -- the same
 *  ! underlying class, minus the scripting layer.
 *  ! zh-CN: 脚本可见的 `SQLite` API 需要 `ScriptRuntime`, 而它包装的 `Database`
 *  ! 类是为异步回调设计的, 两者都不适合一次同步的工具调用. 因此这里直接驱动
 *  ! `SQLiteDatabase` —— 同一个底层类, 只是去掉了脚本层.
 *
 * @Security
 *  ! Two guards sit in front of the query. The first is the working directory
 *  ! boundary, shared with the `file_*` tools. The second is that the statement
 *  ! is parsed before it runs, so a read-only call cannot smuggle a write past
 *  ! the caller by appending a second statement -- `SELECT 1; DROP TABLE t` is
 *  ! exactly the shape this refuses.
 *  ! zh-CN: 查询前有两道防护. 第一道是与 `file_*` 工具共用的工作目录边界.
 *  ! 第二道是语句在执行前会被解析, 因此只读调用无法通过追加第二条语句把写操作夹带进来
 *  ! —— `SELECT 1; DROP TABLE t` 正是它拒绝的那类形态.
 */
internal object McpDatabaseTools {

    val tools: List<McpTool> = listOf(sqliteQueryTool())

    private const val DEFAULT_LIMIT = 100

    private const val MAX_LIMIT = 1000

    /** Blacklisted as "not a read statement" when they lead a statement. */
    private val READ_KEYWORDS = setOf("select", "with", "pragma", "explain", "values")

    /**
     * Words that only ever appear in a statement which modifies something.
     *
     * @Note
     *  ! Consulted only for `WITH`, because a common table expression can front
     *  ! an INSERT, UPDATE or DELETE: `WITH x AS (...) DELETE FROM t` leads with
     *  ! `with` and would otherwise sail past a leading-keyword check. Scanning
     *  ! everywhere would be a needless source of false positives, since no
     *  ! plain SELECT contains these words outside a string or an identifier.
     *  ! zh-CN: 仅在 `WITH` 时参考, 因为公共表表达式后面可以跟 INSERT, UPDATE 或
     *  ! DELETE: `WITH x AS (...) DELETE FROM t` 以 `with` 开头, 否则就会从
     *  ! 首关键字检查下溜过去. 全面扫描会无谓地引入误报, 因为普通 SELECT 在字符串或
     *  ! 标识符之外根本不会包含这些词.
     */
    private val WRITE_KEYWORDS = setOf(
        "insert", "update", "delete", "replace", "drop", "alter", "create", "truncate",
    )

    private const val SQLITE_MASTER_HINT =
            "Table names come from `SELECT name FROM sqlite_master WHERE type = 'table'`."

    private fun sqliteQueryTool(): McpTool = McpTool(
        name = "sqlite_query",
        title = "Query a SQLite database",
        description = buildString {
            append("Runs one SQL statement against a SQLite file and returns the rows as objects. ")
            append("Reads are the default and open the file read-only, so a mistaken statement cannot change it. ")
            append("Only SELECT, WITH, PRAGMA, EXPLAIN and VALUES are accepted in that mode; anything else needs ")
            append("`allowWrite: true`. ")
            append("Bind values with `?` placeholders and pass them in `params` rather than pasting them into the ")
            append("statement -- a literal semicolon inside the SQL is treated as the start of a second statement ")
            append("and rejected, and a bound parameter has no such problem. ")
            append("$SQLITE_MASTER_HINT ")
            append("Only the working directory is reachable unless `outsideWorkingDirectory` is set.")
        },
        risk = McpToolRisk.DANGEROUS,
        inputSchema = McpSchema.objectOf(
            properties = mapOf(
                "path" to McpSchema.string(
                    "Database file, relative to the working directory.",
                ),
                "sql" to McpSchema.string("A single SQL statement, without a trailing semicolon."),
                "params" to McpSchema.arrayOf(
                    "Values bound to the `?` placeholders in order. " +
                            "Numbers, strings and nulls are supported; a boolean becomes 1 or 0.",
                    McpSchema.string("Any scalar value."),
                ),
                "allowWrite" to McpSchema.boolean(
                    "Permit statements that modify the file. The database is opened read-write when set; " +
                            "a missing database file is created (including its parent directory).",
                    false,
                ),
                "limit" to McpSchema.integer(
                    "Maximum rows to return. `truncated` reports whether more were available.",
                    DEFAULT_LIMIT, 1, MAX_LIMIT,
                ),
                "outsideWorkingDirectory" to McpSchema.boolean(
                    "Allow a database path outside the working directory.", false,
                ),
            ),
            required = listOf("path", "sql"),
        ),
    ) { args -> invokeSqliteQuery(args) }

    private fun invokeSqliteQuery(args: McpArgs): McpToolResult {
        val databaseFile = resolve(
            args.requireString("path"),
            args.optBoolean("outsideWorkingDirectory", false),
        )
        val sql = args.requireString("sql")
        val allowWrite = args.optBoolean("allowWrite", false)
        val limit = args.optInt("limit", DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)

        val params = try {
            parseParams(args.optArray("params"))
        } catch (e: McpArgumentException) {
            return McpToolResult.error(e.message ?: "Invalid `params`.")
        }

        if (!databaseFile.exists()) {
            if (!allowWrite) {
                // A database the caller knows exists but that `exists()` denies is
                // the shared storage filtering symptom, so the hint goes here too.
                // zh-CN: 调用方确信存在、而 `exists()` 却否认的数据库, 正是共享存储被过滤的
                // 症状, 因此这里同样附带提示.
                return McpToolResult.error(
                    McpStorage.explain("`${databaseFile.path}` does not exist.", databaseFile)
                )
            }
            // A write call against a missing file is the normal way to bootstrap a
            // new database, so the parent directory is created and the open below
            // is allowed to create the file. A read call keeps the strict error:
            // silently opening an empty database for a typo'd path would hide bugs.
            // zh-CN: 对不存在的文件发起写调用正是新建数据库的正常方式, 因此先补建父目录,
            // 并允许下面的打开操作创建文件. 读调用保持严格报错:
            // 静默为拼错的路径打开一个空库只会掩盖错误.
            databaseFile.parentFile?.mkdirs()
        }
        if (databaseFile.isDirectory) {
            return McpToolResult.error("`${databaseFile.path}` is a directory, not a SQLite file.")
        }

        val shape = analyze(sql)

        if (shape.extraStatements) {
            throw McpArgumentException(
                "`sql` must contain a single statement. " +
                        "A semicolon followed by more SQL is refused so that a read-only call cannot append a write. " +
                        "If the semicolon belongs inside a string, pass that value through `params` instead."
            )
        }
        if (!allowWrite && !shape.isReadOnly) {
            throw McpArgumentException(
                "`${shape.keyword.uppercase()}` is not a read statement, so it requires `allowWrite: true`. " +
                        "Accepted read keywords are ${READ_KEYWORDS.joinToString(", ") { it.uppercase() }}."
            )
        }

        val flags = if (allowWrite) {
            // CREATE_IF_NECESSARY also covers the missing-file case above, so a
            // write call can bootstrap a brand new database. A 0-byte file left
            // behind by an interrupted attempt is equally fine: SQLite initialises
            // it on first write.
            // zh-CN: CREATE_IF_NECESSARY 同时覆盖上方文件缺失的情形, 因此写调用可以
            // 从零新建数据库. 中断尝试留下的 0 字节文件同样没问题: SQLite 会在
            // 首次写入时完成初始化.
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY
        } else {
            SQLiteDatabase.OPEN_READONLY
        }
        val database = try {
            SQLiteDatabase.openDatabase(databaseFile.path, null, flags)
        } catch (e: Throwable) {
            // `SQLiteDatabase` reports a filtered path as "unable to open", which
            // reads as corruption. The storage hint distinguishes the two cases.
            // zh-CN: 对一条被过滤的路径, `SQLiteDatabase` 报的是 "unable to open",
            // 容易被当成文件损坏. 存储提示正是用来区分这两种情况.
            return McpToolResult.error(
                McpStorage.explain(
                    "Opening `${databaseFile.path}` failed: ${e::class.java.simpleName}: " +
                            "${e.message ?: "no message"}. " +
                            "The file may not be a SQLite database, or it may need write access.",
                    databaseFile,
                )
            )
        }

        return database.use { db ->
            if (shape.isReadOnly) {
                runRead(db, sql, params, limit)
            } else {
                runWrite(db, sql, params, shape.keyword)
            }
        }
    }

    private fun runRead(
        database: SQLiteDatabase,
        sql: String,
        params: List<Any?>?,
        limit: Int,
    ): McpToolResult {
        val startedAt = System.currentTimeMillis()
        val cursor = try {
            queryCursor(database, sql, params)
        } catch (e: Throwable) {
            return McpToolResult.error(
                "The query failed: ${e::class.java.simpleName}: ${e.message ?: "no message"}"
            )
        }

        return cursor.use {
            val names = disambiguate(it.columnNames)
            val rows = JsonArray()
            var count = 0
            var truncated = false

            while (it.moveToNext()) {
                if (count >= limit) {
                    truncated = true
                    break
                }
                rows.add(rowObject(it, names))
                count++
            }

            McpToolResult.json(McpJson.obj().apply {
                addProperty("ok", true)
                addProperty("kind", "read")
                add("columns", JsonArray().apply { names.forEach { add(it) } })
                addProperty("count", count)
                addProperty("truncated", truncated)
                addProperty("elapsedMs", System.currentTimeMillis() - startedAt)
                add("rows", rows)
                if (truncated) {
                    addProperty("hint", "Only the first $limit rows are shown. Raise `limit` or add a WHERE clause.")
                }
                if (count == 0 && !truncated) {
                    addProperty("emptyHint", "No rows matched. $SQLITE_MASTER_HINT")
                }
            })
        }
    }

    private fun runWrite(
        database: SQLiteDatabase,
        sql: String,
        params: List<Any?>?,
        keyword: String,
    ): McpToolResult {
        val startedAt = System.currentTimeMillis()
        val statement = try {
            database.compileStatement(sql)
        } catch (e: Throwable) {
            return McpToolResult.error(
                "The statement could not be prepared: ${e::class.java.simpleName}: ${e.message ?: "no message"}"
            )
        }

        return statement.use {
            try {
                bindParams(it, params)
            } catch (e: McpArgumentException) {
                return McpToolResult.error(e.message ?: "Invalid `params`.")
            }

            val result = runCatching {
                when (keyword) {
                    // REPLACE goes through the insert path because it also
                    // produces a row id.
                    // zh-CN: REPLACE 走插入路径, 因为它同样会产出行号.
                    "insert", "replace" -> "lastInsertRowId" to it.executeInsert().toDouble()
                    "update", "delete" -> "affectedRows" to it.executeUpdateDelete().toDouble()
                    else -> {
                        it.execute()
                        "executed" to 1.0
                    }
                }
            }.getOrElse { error ->
                return McpToolResult.error(
                    "The statement failed: ${error::class.java.simpleName}: ${error.message ?: "no message"}"
                )
            }

            McpToolResult.json(McpJson.obj().apply {
                addProperty("ok", true)
                addProperty("kind", "write")
                addProperty("statement", keyword)
                addProperty(result.first, result.second)
                addProperty("elapsedMs", System.currentTimeMillis() - startedAt)
            })
        }
    }

    /**
     * Runs [sql], binding [params] to its `?` placeholders.
     *
     * @Note
     *  ! Both `Cursor` and `SQLiteStatement` are `Closeable`, and every caller
     *  ! wraps them in `use`. Without that, each failing query would leak a
     *  ! native cursor handle, and a client that retries in a loop would exhaust
     *  ! them in a way that looks like the database has gone bad.
     *  ! zh-CN: `Cursor` 与 `SQLiteStatement` 都是 `Closeable`, 且每个调用方都用
     *  ! `use` 包裹. 否则每次查询失败都会泄漏一个本地游标句柄, 循环重试的客户端会耗尽
     *  ! 它们, 表现出来就像是数据库坏掉了.
     *
     * @Note
     *  ! `rawQuery` accepts string arguments only, on every Android version --
     *  ! there is no typed overload, unlike `execSQL`. Values are therefore bound
     *  ! as text and SQLite's column affinity converts them back, so
     *  ! `WHERE id = ?` against an INTEGER column still matches. The difference
     *  ! only shows when a value is compared against an expression that carries
     *  ! no affinity, such as `SELECT ? + 1`, where a cast is needed.
     *  ! zh-CN: 在所有 Android 版本上 `rawQuery` 都只接受字符串参数 —— 与 `execSQL`
     *  ! 不同, 它没有带类型的重载. 因此值以文本形式绑定, 再由 SQLite 的列亲和性转换回来,
     *  ! 于是 `WHERE id = ?` 在 INTEGER 列上依然能匹配. 只有当值与不带亲和性的表达式
     *  ! 比较时才会出现差异, 例如 `SELECT ? + 1`, 那种情况需要显式转换.
     */
    private fun queryCursor(database: SQLiteDatabase, sql: String, params: List<Any?>?): Cursor {
        if (params.isNullOrEmpty()) return database.rawQuery(sql, null)
        return database.rawQuery(sql, params.map { it?.toString() }.toTypedArray())
    }

    private fun bindParams(statement: SQLiteStatement, params: List<Any?>?) {
        statement.clearBindings()
        params?.forEachIndexed { index, value ->
            val position = index + 1
            when (value) {
                null -> statement.bindNull(position)
                is Long -> statement.bindLong(position, value)
                is Double -> statement.bindDouble(position, value)
                is String -> statement.bindString(position, value)
                else -> throw McpArgumentException(
                    "`params[$index]` is a ${value::class.java.simpleName}, which cannot be bound."
                )
            }
        }
    }

    /**
     * @throws McpArgumentException when a value is not a JSON scalar.
     * zh-CN: 当某个值不是 JSON 标量时抛出.
     */
    private fun parseParams(array: JsonArray?): List<Any?>? {
        if (array == null) return null
        return array.mapIndexed { index, element ->
            when {
                element.isJsonNull -> null
                element.isJsonObject || element.isJsonArray -> throw McpArgumentException(
                    "`params[$index]` is a ${if (element.isJsonObject) "object" else "array"}. " +
                            "Only strings, numbers, booleans and nulls can be bound."
                )
                !element.isJsonPrimitive -> throw McpArgumentException("`params[$index]` is not a scalar value.")
                else -> toBindable(element.asJsonPrimitive, index)
            }
        }
    }

    /**
     * Maps a JSON scalar to the Kotlin type `bindParams` expects.
     *
     * @Note
     *  ! A whole number is deliberately bound as a `Long` rather than a `Double`.
     *  ! Gson reports every JSON number as a double, and `SQLiteStatement` is
     *  ! typed, so binding `1` as `1.0` would store a REAL where the column
     *  ! expects an INTEGER -- a change that is invisible until something reads
     *  ! the value back with a strict comparison. The check reads the literal text
     *  ! instead of testing `value % 1.0`, because `1e3` is a double that happens
     *  ! to be whole while `3` is not a double at all.
     *  ! zh-CN: 整数值刻意绑定为 `Long` 而不是 `Double`. Gson 把所有 JSON 数字都报告为
     *  ! double, 而 `SQLiteStatement` 是带类型的, 因此把 `1` 绑定成 `1.0` 会在本该是
     *  ! INTEGER 的列里存入 REAL —— 这种改变在有人用严格比较读回该值之前完全不可见.
     *  ! 判断依据是字面文本而非 `value % 1.0`, 因为 `1e3` 是恰好为整数的 double,
     *  ! 而 `3` 根本不是 double.
     */
    private fun toBindable(primitive: JsonPrimitive, index: Int): Any? = when {
        primitive.isBoolean -> if (primitive.asBoolean) 1L else 0L
        primitive.isNumber -> {
            val literal = primitive.asString
            val looksIntegral = !literal.contains('.') && !literal.contains('e') && !literal.contains('E')
            if (looksIntegral) {
                runCatching { literal.toLong() }.getOrElse { primitive.asDouble }
            } else {
                primitive.asDouble
            }
        }
        primitive.isString -> primitive.asString
        else -> throw McpArgumentException("`params[$index]` is not a supported scalar value.")
    }

    private fun rowObject(cursor: Cursor, names: List<String>): JsonObject = McpJson.obj().apply {
        names.forEachIndexed { index, name -> add(name, valueAt(cursor, index)) }
    }

    private fun valueAt(cursor: Cursor, index: Int): JsonElement = when (cursor.getType(index)) {
        Cursor.FIELD_TYPE_NULL -> JsonNull.INSTANCE
        Cursor.FIELD_TYPE_INTEGER -> JsonPrimitive(cursor.getLong(index))
        Cursor.FIELD_TYPE_FLOAT -> JsonPrimitive(cursor.getDouble(index))
        Cursor.FIELD_TYPE_STRING -> JsonPrimitive(cursor.getString(index))
        // Blobs are summarised rather than inlined: a single embedded image would
        // dominate the response, and the caller almost never wants the bytes.
        // zh-CN: BLOB 只做摘要而不内联: 单个内嵌图片就会占满整个响应,
        // 而调用方几乎从不需要那些字节.
        Cursor.FIELD_TYPE_BLOB -> JsonPrimitive("<blob: ${cursor.getBlob(index)?.size ?: 0} bytes>")
        else -> JsonNull.INSTANCE
    }

    /**
     * Makes column names unique.
     *
     * @Note
     *  ! `SELECT a.id, b.id` yields two columns called `id`, and a JSON object
     *  ! cannot hold both -- the second would silently overwrite the first. That
     *  ! kind of quiet data loss is much worse than an ugly suffix.
     *  ! zh-CN: `SELECT a.id, b.id` 会产生两个名为 `id` 的列, 而 JSON 对象无法同时容纳
     *  ! 两者 —— 后者会静默覆盖前者. 这类悄无声息的数据丢失远比一个不好看后缀糟糕.
     */
    private fun disambiguate(columnNames: Array<String>): List<String> {
        val occurrences = HashMap<String, Int>()
        return columnNames.map { name ->
            val seen = occurrences.getOrDefault(name, 0) + 1
            occurrences[name] = seen
            if (seen == 1) name else "${name}_$seen"
        }
    }

    private class SqlShape(
        val keyword: String,
        val extraStatements: Boolean,
        val containsWriteKeyword: Boolean,
    ) {

        /**
         * @Note
         *  ! This is a convenience check, not the security boundary. The real
         *  ! guarantee is that a read-only call opens the file with
         *  ! `OPEN_READONLY`, so SQLite itself refuses any write no matter what
         *  ! this returns. The parsing exists to turn "attempt to write a
         *  ! readonly database", which is cryptic, into a sentence that says what
         *  ! to change.
         *  ! zh-CN: 这里只是便利性检查, 而非安全边界. 真正的保证是只读调用使用
         *  ! `OPEN_READONLY` 打开文件, 因此无论这里返回什么, SQLite 自身都会拒绝写入.
         *  ! 做语法解析是为了把"attempt to write a readonly database"这种晦涩报错
         *  ! 变成一句说明该改什么的话.
         */
        val isReadOnly: Boolean
            get() = keyword in READ_KEYWORDS && !(keyword == "with" && containsWriteKeyword)

    }

    /**
     * Extracts the leading keyword and inspects the rest of the statement.
     *
     * @ImplementationNote
     *  ! One pass over the text, skipping string literals, quoted identifiers and
     *  ! comments, because a naive `split(";")` would both mis-handle `'a;b'` and
     *  ! accept `SELECT 1; DROP TABLE t`.
     *  ! zh-CN: 对文本做单次扫描, 跳过字符串字面量, 带引号的标识符与注释, 因为简单地
     *  ! `split(";")` 既会误处理 `'a;b'`, 又会放过 `SELECT 1; DROP TABLE t`.
     */
    private fun analyze(sql: String): SqlShape {
        var index = 0
        var semicolons = 0
        var contentAfterSemicolon = false
        var collectingKeyword = true
        var currentWord = StringBuilder()
        val keyword = StringBuilder()
        var containsWriteKeyword = false

        fun flushWord() {
            if (currentWord.isEmpty()) return
            if (currentWord.toString().lowercase() in WRITE_KEYWORDS) {
                containsWriteKeyword = true
            }
            currentWord = StringBuilder()
            // The keyword is only ever the first word, and this is the single
            // place that ends word collection. Keeping the flag flip here means
            // a leading comment cannot terminate the keyword before it starts.
            // zh-CN: 关键字只可能是第一个词, 而这里是唯一结束词汇收集的地方.
            // 把标志位翻转放在这里, 前导注释因此不会在关键字尚未开始时就把它结束掉.
            collectingKeyword = false
        }

        while (index < sql.length) {
            val char = sql[index]
            when {
                char == '-' && index + 1 < sql.length && sql[index + 1] == '-' -> {
                    flushWord()
                    index = skipToLineEnd(sql, index)
                }
                char == '/' && index + 1 < sql.length && sql[index + 1] == '*' -> {
                    flushWord()
                    index = skipBlockComment(sql, index)
                }
                char == '\'' || char == '"' || char == '`' -> {
                    flushWord()
                    if (semicolons > 0) contentAfterSemicolon = true
                    index = skipQuoted(sql, index)
                }
                char == '[' -> {
                    flushWord()
                    if (semicolons > 0) contentAfterSemicolon = true
                    index = skipBracketed(sql, index)
                }
                char == ';' -> {
                    flushWord()
                    semicolons++
                    index++
                }
                char.isWhitespace() -> {
                    flushWord()
                    index++
                }
                // Words are letter-and-underscore runs, so `delete_log` stays one
                // word and cannot be mistaken for the `delete` keyword.
                // zh-CN: 单词由字母与下划线组成, 因此 `delete_log` 保持为一个词,
                // 不会被误认为 `delete` 关键字.
                char.isLetter() || char == '_' -> {
                    if (semicolons > 0) contentAfterSemicolon = true
                    currentWord.append(char)
                    if (collectingKeyword) keyword.append(char)
                    index++
                }
                else -> {
                    flushWord()
                    if (semicolons > 0) contentAfterSemicolon = true
                    index++
                }
            }
        }
        flushWord()

        return SqlShape(keyword.toString().lowercase(), contentAfterSemicolon, containsWriteKeyword)
    }

    /** @return the index just past the closing quote, or the end of the input. */
    private fun skipQuoted(sql: String, start: Int): Int {
        val quote = sql[start]
        var index = start + 1
        while (index < sql.length) {
            if (sql[index] == quote) {
                // A doubled quote is an escaped one, not the end of the literal.
                // zh-CN: 两个连续的引号是转义字符, 而非字面量的结束.
                if (index + 1 < sql.length && sql[index + 1] == quote) {
                    index += 2
                    continue
                }
                return index + 1
            }
            index++
        }
        return sql.length
    }

    private fun skipBracketed(sql: String, start: Int): Int {
        val end = sql.indexOf(']', start + 1)
        return if (end < 0) sql.length else end + 1
    }

    private fun skipToLineEnd(sql: String, start: Int): Int {
        val end = sql.indexOf('\n', start)
        return if (end < 0) sql.length else end + 1
    }

    private fun skipBlockComment(sql: String, start: Int): Int {
        val end = sql.indexOf("*/", start + 2)
        return if (end < 0) sql.length else end + 2
    }

    /**
     * @throws McpArgumentException when the path escapes the working directory.
     * zh-CN: 当路径越出工作目录时抛出.
     */
    private fun resolve(requested: String, outsideWorkingDirectory: Boolean): File {
        val root = File(WorkingDirectoryUtils.path).canonicalFile
        val candidate = File(requested)
            .let { if (it.isAbsolute) it else File(root, requested) }
            .canonicalFile

        val insideRoot = candidate == root || candidate.path.startsWith(root.path + File.separator)
        if (!insideRoot && !outsideWorkingDirectory) {
            throw McpArgumentException(
                "`$requested` resolves to `$candidate`, which is outside the AutoJs6 working directory " +
                        "`$root`. Pass `outsideWorkingDirectory: true` if that is really intended."
            )
        }
        return candidate
    }

}
