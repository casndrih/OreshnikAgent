package com.example.oreshik.agent.tools

import com.example.oreshik.agent.core.*
import com.example.oreshik.agent.runtime.RuntimeManager
import com.example.oreshik.logging.AppLogger
import com.google.gson.JsonElement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "DatabaseTools"

/** Maximum characters of query output returned to the model. */
private const val MAX_DB_OUTPUT = 60_000

/** Resolve a database file argument against the agent workspace (canonical, contained). */
private fun resolveDbFile(context: ToolContext, path: String): File? {
    val root = context.workspace.canonicalFile
    val f = File(root, path).canonicalFile
    if (!f.path.startsWith(root.path + File.separator)) return null
    return f.takeIf { it.isFile }
}

/** Simple bounded read of process stdout (same policy as RuntimeTools). */
private fun readBoundedOutput(p: Process, max: Int = MAX_DB_OUTPUT): String {
    val buf = CharArray(8192)
    val out = StringBuilder()
    var total = 0
    var truncated = false
    p.inputStream.bufferedReader().use { reader ->
        while (true) {
            val n = reader.read(buf)
            if (n < 0) break
            val take = minOf(n, (max - total).coerceAtLeast(0))
            if (take > 0) out.appendRange(buf, 0, take)
            total += take
            if (take < n) truncated = true
        }
    }
    if (truncated) out.append("\n[output truncated]")
    return out.toString()
}

/** Run a managed process against the runtime environment with bounded output. */
private suspend fun runDbProcess(
    context: ToolContext,
    runtime: RuntimeManager,
    executable: String,
    args: List<String>,
    timeoutSeconds: Long,
): ToolResult = withContext(Dispatchers.IO) {
    val exe = runtime.resolveExecutable(executable)
        ?: return@withContext ToolResult(
            ok = false,
            error = "$executable is not installed. Install the runtime pack in Agent Tools → Runtime Manager " +
                "(tap 'Provision bundled packs' or install a compatible database runtime pack from Runtime Manager).",
        )
    if (!exe.canExecute()) {
        return@withContext ToolResult(ok = false, error = "Runtime executable is not executable: ${exe.absolutePath}")
    }
    val command = mutableListOf(exe.absolutePath).apply { addAll(args) }
    try {
        val pb = ProcessBuilder(command).directory(context.workspace).redirectErrorStream(true)
        pb.environment().putAll(runtime.environment())
        val p = pb.start()
        val readerExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val future = readerExecutor.submit<String> { readBoundedOutput(p) }
        val finished = p.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
        if (!finished) {
            p.destroy()
            if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly()
            future.cancel(true)
            readerExecutor.shutdownNow()
            return@withContext ToolResult(ok = false, error = "Timed out after ${timeoutSeconds}s")
        }
        val text = runCatching { future[2, java.util.concurrent.TimeUnit.SECONDS] }.getOrDefault("[output unavailable]")
        readerExecutor.shutdownNow()
        val code = p.exitValue()
        ToolResult(
            ok = code == 0,
            output = text,
            error = if (code == 0) null else "$executable exited with code $code",
            exitCode = code,
        )
    } catch (e: Exception) {
        AppLogger.e(TAG, "$executable failed: ${e.message}", e)
        ToolResult(ok = false, error = "$executable: ${e.message}")
    }
}

/** Parse the arg element ("array" of strings or single string). */
private fun stringArgs(arguments: JsonElement): List<String> {
    val el = arguments.asJsonObject["sql"] ?: return emptyList()
    return when {
        el.isJsonArray -> el.asJsonArray.map { it.asString }
        el.isJsonPrimitive -> listOf(el.asString)
        else -> emptyList()
    }
}

private fun stringArg(arguments: JsonElement, key: String): String? =
    arguments.asJsonObject[key]?.takeIf { it.isJsonPrimitive }?.asString

private fun longArg(arguments: JsonElement, key: String, default: Long): Long =
    (arguments.asJsonObject[key]?.takeIf { it.isJsonPrimitive }?.asLong ?: default)

// ---------------------------------------------------------------------------
// Tools
// ---------------------------------------------------------------------------

/** SQLite3 shell access via the bundled `sqlite` pack. */
class DbSqliteQueryTool(private val runtime: RuntimeManager) : AgentTool {
    override val definition = ToolDefinition(
        name = "db.sqlite.query",
        category = ToolCategory.DATABASE,
        description = "Execute SQL against an SQLite database file (or ':memory:') using the bundled sqlite3 CLI. " +
            "Pass a single SQL string or an array of dot-commands/SQL statements.",
        permissions = setOf(ToolPermission.DATABASE, ToolPermission.EXECUTE_PROCESS),
        parameters = mapOf(
            "database" to "string (workspace-relative path or ':memory:')",
            "sql" to "string|array",
            "timeout_seconds" to "integer (default 60)",
        ),
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult {
        val database = stringArg(arguments, "database") ?: ":memory:"
        val sql = stringArgs(arguments)
        if (sql.isEmpty()) return ToolResult(ok = false, error = "Missing sql")
        val timeout = longArg(arguments, "timeout_seconds", 60L).coerceIn(1, 900)
        val args = mutableListOf<String>()
        if (database != ":memory:") {
            val f = resolveDbFile(context, database)
                ?: return ToolResult(ok = false, error = "Database file not found in workspace: $database")
            args += f.absolutePath
        }
        args += sql
        AppLogger.d(TAG, "db.sqlite.query (${sql.size} statement(s))")
        return runDbProcess(context, runtime, "sqlite3", args, timeout)
    }
}

/** Access/Jet (.mdb/.accdb) table listing via mdb-tables. */
class DbMdbTablesTool(private val runtime: RuntimeManager) : AgentTool {
    override val definition = ToolDefinition(
        name = "db.mdbtools.tables",
        category = ToolCategory.DATABASE,
        description = "List the tables of a Microsoft Access / Jet database file (.mdb, .accdb) using mdbtools.",
        permissions = setOf(ToolPermission.DATABASE, ToolPermission.EXECUTE_PROCESS),
        parameters = mapOf("file" to "string", "system_tables" to "boolean (default false)"),
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult {
        val file = stringArg(arguments, "file")
            ?: return ToolResult(ok = false, error = "Missing file")
        val f = resolveDbFile(context, file)
            ?: return ToolResult(ok = false, error = "Database file not found in workspace: $file")
        val showSystem = arguments.asJsonObject["system_tables"]?.takeIf { it.isJsonPrimitive }?.asString == "true"
        val args = mutableListOf<String>()
        if (showSystem) args += "--system"
        args += f.absolutePath
        return runDbProcess(context, runtime, "mdb-tables", args, 60)
    }
}

/** Export an Access table to CSV via mdb-export. */
class DbMdbExportTool(private val runtime: RuntimeManager) : AgentTool {
    override val definition = ToolDefinition(
        name = "db.mdbtools.export",
        category = ToolCategory.DATABASE,
        description = "Export a Microsoft Access / Jet table (or all tables) as CSV via mdbtools. " +
            "With 'out' the CSV is written to a workspace file instead of being returned inline.",
        permissions = setOf(
            ToolPermission.DATABASE,
            ToolPermission.EXECUTE_PROCESS,
            ToolPermission.READ_WORKSPACE,
            ToolPermission.WRITE_WORKSPACE,
        ),
        parameters = mapOf(
            "file" to "string",
            "table" to "string (omit to export all tables)",
            "out" to "string (optional output path, workspace-relative)",
            "timeout_seconds" to "integer (default 120)",
        ),
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult {
        val file = stringArg(arguments, "file")
            ?: return ToolResult(ok = false, error = "Missing file")
        val f = resolveDbFile(context, file)
            ?: return ToolResult(ok = false, error = "Database file not found in workspace: $file")
        val table = stringArg(arguments, "table")
        val timeout = longArg(arguments, "timeout_seconds", 120L).coerceIn(1, 1800)

        val root = context.workspace.canonicalFile
        if (table == null) {
            // Export every table: enumerate then run mdb-export per table.
            val tablesResult = runDbProcess(context, runtime, "mdb-tables", listOf("-1", f.absolutePath), 60)
            if (!tablesResult.ok) return tablesResult
            val tables = tablesResult.output.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (tables.isEmpty()) return ToolResult(ok = false, error = "No tables found in $file")
            val outDirName = stringArg(arguments, "out") ?: "${f.nameWithoutExtension}_csv"
            val outDir = File(root, outDirName)
            if (!outDir.canonicalPath.startsWith(root.path + File.separator)) {
                return ToolResult(ok = false, error = "out must stay inside the workspace")
            }
            outDir.mkdirs()
            var failed = 0
            for (t in tables) {
                val target = File(outDir, "${t.replace(Regex("[^A-Za-z0-9_.-]"), "_")}.csv")
                val export = runDbProcess(
                    context, runtime, "mdb-export",
                    listOf(f.absolutePath, t),
                    timeout,
                )
                if (!export.ok) {
                    failed++
                    continue
                }
                target.writeText(export.output)
            }
            return ToolResult(
                ok = failed == 0,
                output = "Exported ${tables.size - failed}/${tables.size} table(s) to ${outDir.name}/",
                error = if (failed == 0) null else "$failed table(s) failed to export",
            )
        }

        val exportArgs = mutableListOf(f.absolutePath, table)
        val out = stringArg(arguments, "out")
        if (out != null) {
            val target = File(root, out).canonicalFile
            if (!target.path.startsWith(root.path + File.separator)) {
                return ToolResult(ok = false, error = "out must stay inside the workspace")
            }
            target.parentFile?.mkdirs()
            val result = runDbProcess(context, runtime, "mdb-export", exportArgs, timeout)
            if (result.ok) target.writeText(result.output)
            return result
        }
        return runDbProcess(context, runtime, "mdb-export", exportArgs, timeout)
    }
}

/** Schema dump for Access databases via mdb-schema. */
class DbMdbSchemaTool(private val runtime: RuntimeManager) : AgentTool {
    override val definition = ToolDefinition(
        name = "db.mdbtools.schema",
        category = ToolCategory.DATABASE,
        description = "Print the SQL schema (DDL) of a Microsoft Access / Jet database via mdb-schema.",
        permissions = setOf(ToolPermission.DATABASE, ToolPermission.EXECUTE_PROCESS),
        parameters = mapOf(
            "file" to "string",
            "backend" to "string (access|sybase|oracle|postgres|mysql, default access)",
        ),
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult {
        val file = stringArg(arguments, "file")
            ?: return ToolResult(ok = false, error = "Missing file")
        val f = resolveDbFile(context, file)
            ?: return ToolResult(ok = false, error = "Database file not found in workspace: $file")
        val backend = stringArg(arguments, "backend") ?: "access"
        val validBackends = setOf("access", "sybase", "oracle", "postgres", "mysql")
        if (backend !in validBackends) {
            return ToolResult(ok = false, error = "backend must be one of ${validBackends.joinToString(", ")}")
        }
        return runDbProcess(context, runtime, "mdb-schema", listOf("--backend", backend, f.absolutePath), 120)
    }
}

/** Readiness report for all bundled/downloaded database stacks. */
class DbToolsStatusTool(private val runtime: RuntimeManager) : AgentTool {
    override val definition = ToolDefinition(
        name = "db.tools.status",
        category = ToolCategory.DATABASE,
        description = "Report which database toolchains are installed and usable: sqlite3 CLI, mdbtools, " +
            "unixODBC (pypyodbc driver manager), sqlite-jdbc and jackcess jars.",
        permissions = setOf(ToolPermission.DATABASE),
        parameters = emptyMap(),
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult = withContext(Dispatchers.IO) {
        val lines = mutableListOf<String>()

        val sqlite3 = runtime.resolveExecutable("sqlite3")
        lines += if (sqlite3 != null) {
            "sqlite3 CLI: READY (${sqlite3.absolutePath})"
        } else {
            "sqlite3 CLI: MISSING (provision the bundled 'sqlite' pack in Runtime Manager)"
        }

        val mdb = runtime.resolveExecutable("mdb-tables")
        lines += if (mdb != null) {
            "mdbtools: READY (${mdb.parentFile?.name}/)"
        } else {
            "mdbtools: MISSING (install a compatible database runtime pack in Runtime Manager)"
        }

        val unixodbc = runtime.resolveExecutable("odbcinst")
        val libodbc = File(runtime.runtimePath("databases"), "lib/libodbc.so").isFile
        lines += when {
            unixodbc != null -> "unixODBC (pypyodbc driver manager): READY (${unixodbc.absolutePath})"
            libodbc -> "unixODBC (pypyodbc driver manager): READY (libodbc.so present)"
            else -> "unixODBC: MISSING (needed by pypyodbc — install the 'Database Tools' pack)"
        }

        val jars = runtime.installDatabaseJars()
        lines += if (jars.isNotEmpty()) {
            "JDBC/Access jars: READY (${jars.joinToString(", ") { it.name }})"
        } else {
            "JDBC/Access jars: MISSING (no databases-jars in APK assets)"
        }

        // pypyodbc availability inside the Chaquopy runtime.
        val pyCheck = runtime.python.execute(
            "import importlib.util; print('pypyodbc' if importlib.util.find_spec('pypyodbc') else 'missing')",
            context.workspace,
            30,
        )
        val pyStatus = pyCheck.getOrNull()?.stdout?.trim() ?: "unknown"
        lines += "pypyodbc (Python ODBC): $pyStatus"
        lines += "Note: native pyodbc has no Android wheel; the agent shim maps 'import pyodbc' to pypyodbc."
        lines += "Note: sqlite-jdbc's native lib is glibc-linked — on bionic Android use db.sqlite.query instead; " +
            "db.sqlite.jdbc needs a glibc JVM (gpkg) and fails gracefully otherwise."
        lines += "Note: ODBC Driver= entries must point at a driver .so by absolute path (no DSNs without odbcinst.ini)."

        ToolResult(ok = true, output = lines.joinToString("\n"))
    }
}

/**
 * Java helper source for db.sqlite.jdbc / db.access.query: takes a JSON request on stdin,
 * prints a JSON result on stdout. Compiled once per JVM into the pack lib dir.
 */
private const val JdbcHelperSource = """
import com.healthmarketscience.jackcess.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.stream.*;

public class DbHelper {
    private static String esc(Object v) {
        if (v == null) return "null";
        String s = String.valueOf(v);
        StringBuilder b = new StringBuilder(s.length() + 2);
        b.append('\"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        b.append('\"');
        return b.toString();
    }

    private static String rsToJson(ResultSet rs, int maxRows) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        int cols = md.getColumnCount();
        StringBuilder sb = new StringBuilder();
        sb.append("{\"columns\":[");
        for (int i = 1; i <= cols; i++) {
            if (i > 1) sb.append(',');
            sb.append(esc(md.getColumnLabel(i)));
        }
        sb.append("],\"rows\":[");
        int n = 0;
        while (rs.next() && n < maxRows) {
            if (n > 0) sb.append(',');
            sb.append('[');
            for (int i = 1; i <= cols; i++) {
                if (i > 1) sb.append(',');
                Object v;
                try { v = rs.getObject(i); } catch (SQLException e) { v = "<unreadable>"; }
                sb.append(v instanceof Number || v instanceof Boolean ? String.valueOf(v) : esc(v));
            }
            sb.append(']');
            n++;
        }
        boolean truncated = false;
        if (!rs.isAfterLast()) truncated = true;
        sb.append("],\"row_count\":").append(n);
        sb.append(",\"truncated\":").append(truncated);
        sb.append('}');
        return sb.toString();
    }

    private static void writeJson(String json) throws IOException {
        try (Writer w = new OutputStreamWriter(System.out, StandardCharsets.UTF_8)) {
            w.write(json);
        }
    }

    /** SQLite via JDBC. argv: <mode> <dbfile> <sql...> */
    private static void sqlite(String db, String sql, int maxRows) throws Exception {
        Class.forName("org.sqlite.JDBC");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement st = c.createStatement()) {
            boolean hasRs = st.execute(sql);
            if (hasRs) {
                writeJson(rsToJson(st.getResultSet(), maxRows));
            } else {
                writeJson("{\"updated\":" + st.getUpdateCount() + "}");
            }
        }
    }

    /** Schema listing via Jackcess. argv: <mode> <dbfile> */
    private static void schema(String db, int maxRows) throws Exception {
        try (Database d = new DatabaseBuilder(new File(db)).setReadOnly(true).open()) {
            StringBuilder sb = new StringBuilder();
            sb.append("{\"tables\":[");
            List<String> names = new ArrayList<>(d.getTableNames());
            for (int t = 0; t < names.size(); t++) {
                String name = names.get(t);
                if (t > 0) sb.append(',');
                sb.append("{\"name\":").append(esc(name)).append(",\"columns\":[");
                Table table = d.getTable(name);
                List<? extends Column> cols = table.getColumns();
                for (int i = 0; i < cols.size(); i++) {
                    Column col = cols.get(i);
                    if (i > 0) sb.append(',');
                    sb.append("{\"name\":").append(esc(col.getName()))
                      .append(",\"type\":").append(esc(col.getType().toString()))
                      .append('}');
                }
                sb.append("],\"row_count\":").append(table.getRowCount()).append('}');
            }
            sb.append("]}");
            writeJson(sb.toString());
        }
    }

    /** Table scan via Jackcess. argv: <mode> <dbfile> <table> <limit> */
    private static void table(String db, String table, int limit) throws Exception {
        try (Database d = new DatabaseBuilder(new File(db)).setReadOnly(true).open()) {
            Table t = d.getTable(table);
            StringBuilder sb = new StringBuilder();
            List<? extends Column> cols = t.getColumns();
            sb.append("{\"columns\":[");
            for (int i = 0; i < cols.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(esc(cols.get(i).getName()));
            }
            sb.append("],\"rows\":[");
            int n = 0;
            Cursor cursor = CursorBuilder.createCursor(t);
            try {
                while (cursor.moveToNextRow() && n < limit) {
                    if (n > 0) sb.append(',');
                    sb.append('[');
                    Row row = cursor.getCurrentRow();
                    for (int i = 0; i < cols.size(); i++) {
                        if (i > 0) sb.append(',');
                        sb.append(esc(row.get(cols.get(i).getName())));
                    }
                    sb.append(']');
                    n++;
                }
            } catch (java.io.IOException e) {
                throw e;
            }
            // Cursor has no close() in Jackcess 5 — the owning Database handles resources.
            sb.append("],\"row_count\":").append(n).append('}');
            writeJson(sb.toString());
        }
    }

    public static void main(String[] args) {
        try {
            String mode = args[0];
            int maxRows = args.length > 3 ? Integer.parseInt(args[3]) : 200;
            if (maxRows < 1) maxRows = 1;
            if (maxRows > 5000) maxRows = 5000;
            switch (mode) {
                case "sqlite": sqlite(args[1], args[2], maxRows); break;
                case "schema": schema(args[1], maxRows); break;
                case "table": table(args[1], args[2], maxRows); break;
                default: System.err.println("Unknown mode: " + mode); System.exit(2);
            }
        } catch (Throwable t) {
            String msg = String.valueOf(t);
            if (msg.contains("UnsatisfiedLinkError") || msg.contains("no suitable driver")
                || msg.contains("native library") || msg.contains(".so")) {
                System.err.println("NATIVE_LINK_UNAVAILABLE: " + msg);
                System.err.println("This JVM cannot load the sqlite-jdbc native library (glibc-linked). " +
                    "Use the db.sqlite.query tool (bundled sqlite3 CLI) instead, or install a glibc JVM " +
                    "(e.g. via a compatible Android userspace runtime pack) for JDBC support.");
            } else {
                System.err.println("ERROR: " + msg);
            }
            System.exit(1);
        }
    }
}
"""

/** Compile the DbHelper.java source once, return the .java/.class file paths. */
private fun ensureJdbcHelper(runtime: RuntimeManager): Pair<File, File>? {
    val libDir = runtime.installDatabaseJars().takeIf { it.isNotEmpty() }
        ?.firstOrNull()?.parentFile ?: return null
    val java = File(libDir, "DbHelper.java")
    val cls = File(libDir, "DbHelper.class")
    if ((!java.isFile) || (java.readText() != JdbcHelperSource.trimIndent())) {
        java.writeText(JdbcHelperSource.trimIndent())
        cls.delete()
    }
    return java to cls
}

/** Build the -cp argument for the helper from the copied jars. */
private fun classpathFor(runtime: RuntimeManager): String? =
    runtime.installDatabaseJars().takeIf { it.isNotEmpty() }
        ?.joinToString(File.pathSeparator) { it.absolutePath }
        ?.plus(File.pathSeparator + runtime.runtimePath("databases/lib").absolutePath)

class DbSqliteJdbcTool(private val runtime: RuntimeManager) : AgentTool {
    override val definition = ToolDefinition(
        name = "db.sqlite.jdbc",
        category = ToolCategory.DATABASE,
        description = "Execute SQL against an SQLite file through the sqlite-jdbc driver on the bundled JVM. " +
            "On bionic Android the driver's native library cannot load (glibc-linked); the tool reports this " +
            "and points to db.sqlite.query (sqlite3 CLI), which is the reliable on-device path.",
        permissions = setOf(
            ToolPermission.DATABASE,
            ToolPermission.EXECUTE_PROCESS,
            ToolPermission.BUILD,
        ),
        parameters = mapOf(
            "database" to "string",
            "sql" to "string",
            "max_rows" to "integer (default 200, max 5000)",
            "timeout_seconds" to "integer (default 120)",
        ),
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult {
        val database = stringArg(arguments, "database")
            ?: return ToolResult(ok = false, error = "Missing database")
        val sql = stringArg(arguments, "sql")
            ?: return ToolResult(ok = false, error = "Missing sql")
        val f = resolveDbFile(context, database)
            ?: return ToolResult(ok = false, error = "Database file not found in workspace: $database")
        val maxRows = longArg(arguments, "max_rows", 200L).coerceIn(1, 5000)
        val timeout = longArg(arguments, "timeout_seconds", 120L).coerceIn(1, 900)

        val cp = classpathFor(runtime)
            ?: return ToolResult(ok = false, error = "No JDBC jars bundled (missing databases-jars in APK assets)")
        val helper = ensureJdbcHelper(runtime)
            ?: return ToolResult(ok = false, error = "Unable to stage JDBC helper source (no database jars staged)")

        // 1) compile (only when class is missing)
        val helperDir = helper.first.parentFile
        if (!helper.second.isFile) {
            val compile = runDbProcess(
                context, runtime, "javac",
                listOf("-cp", cp, "-d", helperDir?.absolutePath ?: ".", helper.first.absolutePath),
                180,
            )
            if (!compile.ok) {
                return ToolResult(
                    ok = false,
                    error = "javac failed to compile the database helper: ${compile.error ?: compile.output.take(500)}",
                )
            }
        }

        // 2) run
        return runDbProcess(
            context, runtime, "java",
            listOf(
                "-cp", "$cp${File.pathSeparator}$helperDir", "DbHelper",
                "sqlite", f.absolutePath, sql, maxRows.toString(),
            ),
            timeout,
        )
    }
}

/** Row scan of an Access table via Jackcess on the bundled JVM. */
class DbAccessQueryTool(private val runtime: RuntimeManager) : AgentTool {
    override val definition = ToolDefinition(
        name = "db.access.query",
        category = ToolCategory.DATABASE,
        description = "Read rows from a Microsoft Access / Jet table (.mdb, .accdb) via Jackcess on the bundled JVM.",
        permissions = setOf(
            ToolPermission.DATABASE,
            ToolPermission.EXECUTE_PROCESS,
            ToolPermission.BUILD,
        ),
        parameters = mapOf(
            "file" to "string",
            "table" to "string",
            "max_rows" to "integer (default 200, max 5000)",
            "timeout_seconds" to "integer (default 120)",
        ),
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult {
        val file = stringArg(arguments, "file")
            ?: return ToolResult(ok = false, error = "Missing file")
        val table = stringArg(arguments, "table")
            ?: return ToolResult(ok = false, error = "Missing table")
        val f = resolveDbFile(context, file)
            ?: return ToolResult(ok = false, error = "Database file not found in workspace: $file")
        val maxRows = longArg(arguments, "max_rows", 200L).coerceIn(1, 5000)
        val timeout = longArg(arguments, "timeout_seconds", 120L).coerceIn(1, 900)

        val cp = classpathFor(runtime)
            ?: return ToolResult(ok = false, error = "No JDBC jars bundled (missing databases-jars in APK assets)")
        val helper = ensureJdbcHelper(runtime)
            ?: return ToolResult(ok = false, error = "Unable to stage JDBC helper source (no database jars staged)")

        if (!helper.second.isFile) {
            val compile = runDbProcess(
                context, runtime, "javac",
                listOf("-cp", cp, "-d", helper.first.parentFile?.absolutePath ?: ".", helper.first.absolutePath),
                180,
            )
            if (!compile.ok) {
                return ToolResult(
                    ok = false,
                    error = "javac failed to compile the database helper: ${compile.error ?: compile.output.take(500)}",
                )
            }
        }

        return runDbProcess(
            context, runtime, "java",
            listOf("-cp", "$cp${File.pathSeparator}${helper.second.parentFile}", "DbHelper",
                "table", f.absolutePath, table, maxRows.toString()),
            timeout,
        )
    }
}

/** Table/column listing of an Access DB via Jackcess on the bundled JVM. */
class DbAccessSchemaTool(private val runtime: RuntimeManager) : AgentTool {
    override val definition = ToolDefinition(
        name = "db.access.schema",
        category = ToolCategory.DATABASE,
        description = "List tables, columns (name/type) and row counts of a Microsoft Access / Jet database " +
            "(.mdb, .accdb) via Jackcess on the bundled JVM.",
        permissions = setOf(
            ToolPermission.DATABASE,
            ToolPermission.EXECUTE_PROCESS,
            ToolPermission.BUILD,
        ),
        parameters = mapOf(
            "file" to "string",
            "timeout_seconds" to "integer (default 120)",
        ),
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult {
        val file = stringArg(arguments, "file")
            ?: return ToolResult(ok = false, error = "Missing file")
        val f = resolveDbFile(context, file)
            ?: return ToolResult(ok = false, error = "Database file not found in workspace: $file")
        val timeout = longArg(arguments, "timeout_seconds", 120L).coerceIn(1, 900)

        val cp = classpathFor(runtime)
            ?: return ToolResult(ok = false, error = "No JDBC jars bundled (missing databases-jars in APK assets)")
        val helper = ensureJdbcHelper(runtime)
            ?: return ToolResult(ok = false, error = "Unable to stage JDBC helper source (no database jars staged)")

        if (!helper.second.isFile) {
            val compile = runDbProcess(
                context, runtime, "javac",
                listOf("-cp", cp, "-d", helper.first.parentFile?.absolutePath ?: ".", helper.first.absolutePath),
                180,
            )
            if (!compile.ok) {
                return ToolResult(
                    ok = false,
                    error = "javac failed to compile the database helper: ${compile.error ?: compile.output.take(500)}",
                )
            }
        }

        return runDbProcess(
            context, runtime, "java",
            listOf("-cp", "$cp${File.pathSeparator}${helper.second.parentFile}", "DbHelper",
                "schema", f.absolutePath),
            timeout,
        )
    }
}

/**
 * ODBC through pypyodbc inside the Chaquopy Python runtime.
 * The agent passes a Python snippet; the bridge prepends a shim module that maps
 * `import pyodbc` onto pypyodbc and preloads the bundled unixODBC library.
 */
class DbOdbcPythonTool(private val runtime: RuntimeManager) : AgentTool {
    override val definition = ToolDefinition(
        name = "db.odbc.python",
        category = ToolCategory.DATABASE,
        description = "Run Python code that connects to a database over ODBC (pyodbc API, pypyodbc implementation) " +
            "using the Chaquopy runtime. unixODBC comes from the 'databases' runtime pack; reference ODBC drivers " +
            "by absolute path in the connection string (DSNs are unavailable without odbcinst.ini). " +
            "Use db.sqlite.query for SQLite and db.mdbtools.* for Access files instead.",
        permissions = setOf(ToolPermission.DATABASE, ToolPermission.EXECUTE_PYTHON, ToolPermission.NETWORK),
        parameters = mapOf(
            "code" to "string",
            "timeout_seconds" to "integer (default 120)",
        ),
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult {
        val code = stringArg(arguments, "code")
            ?: return ToolResult(ok = false, error = "Missing code")
        val timeout = longArg(arguments, "timeout_seconds", 120L).coerceIn(1, 900)

        // Bridge: (1) add databases/lib to the dlopen search path for libodbc.so, (2) register a
        // 'pyodbc' module alias pointing at pypyodbc, then exec the agent's code in that namespace.
        val bridge = """
import ctypes, importlib.util, os, sys, types

def _load_unixodbc():
    candidates = []
    env = os.environ.get("LD_LIBRARY_PATH", "")
    for d in env.split(":"):
        if d:
            candidates.append(os.path.join(d, "libodbc.so"))
            candidates.append(os.path.join(d, "libodbc.so.2"))
    for path in candidates:
        try:
            ctypes.CDLL(path, mode=ctypes.RTLD_GLOBAL)
            return path
        except OSError:
            continue
    return None

_loaded = _load_unixodbc()
_spec = importlib.util.find_spec("pypyodbc")
if _spec is None:
    raise ImportError(
        "pypyodbc is not installed in this Python runtime; ODBC support unavailable"
    )
_pyo = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_pyo)
sys.modules["pyodbc"] = _pyo

_odbc_driver_loaded = bool(_loaded)
"""
        val combined = """$bridge
# ---- agent code ----
$code"""
        AppLogger.d(TAG, "db.odbc.python (${code.length} chars)")
        val result = runtime.python.execute(combined, context.workspace, timeout.toInt())
        val runtimeResult = result.getOrNull()
        if (runtimeResult != null) {
            val output = buildString {
                append(runtimeResult.stdout)
                val hint = loadedHint(runtimeResult.stderr)
                if (hint.isNotEmpty()) {
                    append("\n")
                    append(hint)
                }
                if (runtimeResult.stderr.isNotBlank()) {
                    append("\nSTDERR:\n")
                    append(runtimeResult.stderr)
                }
            }
            return ToolResult(runtimeResult.ok, output, runtimeResult.error, runtimeResult.exitCode)
        }
        return ToolResult(ok = false, error = "Python runtime failed: ${result.exceptionOrNull()?.message}")
    }

    private fun loadedHint(stderr: String): String =
        if (stderr.contains("libodbc") || stderr.contains("Can't open lib")) {
            "Hint: the unixODBC driver manager could not be preloaded. Install the 'Database Tools' runtime pack " +
                "(Runtime Manager → Download Optional Runtime Tools) and use absolute driver paths."
        } else {
            ""
        }
}
