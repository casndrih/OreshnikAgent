package com.example.oreshik.agent.core

import android.content.Context
import com.example.oreshik.agent.runtime.RuntimeManager
import com.example.oreshik.agent.tools.*
import com.example.oreshik.agent.security.RootManager
import com.example.oreshik.logging.AppLogger

class ToolRegistry(context: Context, runtime: RuntimeManager) {

    private companion object {
        const val TAG = "ToolRegistry"
    }

    private val tools: LinkedHashMap<String, AgentTool> = linkedMapOf(
        "file.list" to FileListTool(),
        "file.read" to FileReadTool(),
        "file.write" to FileWriteTool(),
        "file.search" to FileSearchTool(),
        "file.patch" to FilePatchTool(),
        "file.delete" to FileDeleteTool(),
        "gguf.tensor.edit" to GgufTensorTool(),
        "python.exec" to PythonExecTool(runtime),
        "python.run_file" to PythonRunFileTool(runtime),
        "python.pip" to PythonPipTool(runtime),
        "runtime.info" to RuntimeInfoTool(runtime),
        "gguf.quantize" to GgufQuantizeTool(),
        "runtime.sqlmap.status" to SqlmapStatusTool(runtime),
        "runtime.sqlmap.install" to SqlmapInstallTool(runtime),
        "node.exec" to NodeExecTool(runtime),
        "npm.exec" to NpmExecTool(runtime),
        "java.exec" to JavaExecTool(runtime),
        "javac.exec" to JavacExecTool(runtime),
        "gradle.exec" to GradleExecTool(runtime),
        "gradle9.exec" to Gradle9ExecTool(runtime),
        "kotlinc.exec" to KotlinExecTool(runtime),
        "php.exec" to PhpExecTool(runtime),
        "composer.exec" to ComposerExecTool(runtime),
        "ruby.exec" to RubyExecTool(runtime),
        "clang.exec" to ClangExecTool(runtime),
        "make.exec" to MakeExecTool(runtime),
        // Database toolchains: sqlite3 CLI, mdbtools (Access/Jet), unixODBC/pypyodbc,
        // sqlite-jdbc + jackcess (bundled JVM)
        "db.sqlite.query" to DbSqliteQueryTool(runtime),
        "db.sqlite.jdbc" to DbSqliteJdbcTool(runtime),
        "db.access.query" to DbAccessQueryTool(runtime),
        "db.access.schema" to DbAccessSchemaTool(runtime),
        "db.mdbtools.tables" to DbMdbTablesTool(runtime),
        "db.mdbtools.export" to DbMdbExportTool(runtime),
        "db.mdbtools.schema" to DbMdbSchemaTool(runtime),
        "db.odbc.python" to DbOdbcPythonTool(runtime),
        "db.tools.status" to DbToolsStatusTool(runtime),
        "process.exec" to ProcessExecTool(),
        "terminal.session" to TerminalSessionTool(),
        "system.process_list" to ProcessListTool(),
        "system.app_list" to AppListTool(),
        "system.process_kill" to ProcessKillTool(),
        "root.exec" to RootExecTool(RootManager(context)),
        "root.info" to RootInfoTool(RootManager(context)),
        "git.status" to GitStatusTool(),
        "git.diff" to GitDiffTool(),
        "git.log" to GitLogTool(),
        "git.remote" to GitRemoteTool(),
        "git.branch" to GitBranchTool(),
        "git.stage" to GitStageTool(),
        "git.commit" to GitCommitTool(),
        "git.patch.export" to GitPatchExportTool(),
        "workflow.manage" to WorkflowBuilderTool(),
        "workflow.schedule" to WorkflowScheduleTool(),
        "provider.metrics" to ProviderMetricsTool(),
        "workspace.snapshot" to WorkspaceSnapshotCreateTool(),
        "workspace.snapshots" to WorkspaceSnapshotListTool(),
        "workspace.rollback" to WorkspaceSnapshotRestoreTool(),
        "project.template" to ProjectTemplateTool(),
        "android.build" to AndroidBuildTool(),
        "accessibility.screen" to AccessibilityScreenTool(),
        "accessibility.click" to AccessibilityClickTool(),
        "accessibility.scroll" to AccessibilityScrollTool(),
        "accessibility.focus" to AccessibilityFocusTool(),
        "accessibility.global" to AccessibilityGlobalTool(),
        "accessibility.sequence" to AccessibilitySequenceTool(),
        "accessibility.snapshot" to AccessibilitySnapshotTool(),
        "memory.search" to MemorySearchTool(context),
        "memory.save" to MemorySaveTool(context),
        "memory.delete" to MemoryDeleteTool(context),
        "memory.stats" to MemoryStatsTool(context),
        "skills.list" to SkillsListTool(context),
        "skills.use" to SkillActivateTool(context),
        "analysis.collaborate" to CollaborativeAnalysisTool(),
        "analysis.context" to AnalysisContextTool(),
        "web.search" to WebSearchTool(),
        "web.crawl" to WebCrawlTool(),
        "web.browser" to WebBrowserTool(),
        // Hermes-style Android computer use
        "androidreadscreen" to AndroidReadScreenTool(),
        "android_screenshot" to AndroidScreenshotTool(),
        "androidanalyze" to AndroidAnalyzeScreenTool(),
        "android_tap" to AndroidTapTool(),
        "androidtaptext" to AndroidTapTextTool(),
        "androidclickelement" to AndroidClickElementTool(),
        "androidtypetext" to AndroidTypeTextTool(),
        "android_swipe" to AndroidSwipeTool(),
        "android_scroll" to AndroidScrollTool(),
        "androidlongpress" to AndroidLongPressTool(),
        "androidpressback" to AndroidPressBackTool(),
        "androidpresshome" to AndroidPressHomeTool(),
        "androidopenapp" to AndroidOpenAppTool(),
        // Direct device operations for spoken commands (send a text, uninstall an app)
        "android.sms.send" to SendSmsTool(),
        "android.app_settings" to OpenAppSettingsTool(),
        // RE runtime tools (radare2, apktool, native Ghidra headless suite)
        "re.r2.analyze" to R2AnalyzeTool(runtime),
        "re.rabin2" to R2BinInfoTool(runtime),
        "re.apktool.decode" to ApktoolDecodeTool(runtime),
        "re.apktool.build" to ApktoolBuildTool(runtime),
        "re.apk.manifest" to ApkManifestTool(runtime),
        // APK patch pipeline for strongly protected apps:
        // decode → smali patch (agent) → build → debug-sign → root install
        "re.apk.sign" to ApkSignTool(runtime),
        "re.apk.install" to ApkInstallTool(runtime),
        "re.apk.patch" to ApkPatchPipelineTool(runtime),
        // Native Ghidra headless suite (real analyzeHeadless on the bundled JDK) —
        // replaces the previous javap/r2 approximation (GhidraStyleAnalysisTool).
        "re.ghidra.capabilities" to GhidraToolFactory(runtime).capabilitiesTool(),
        "re.ghidra.analyze" to GhidraToolFactory(runtime).analyzeTool(),
        "re.ghidra.decompile" to GhidraToolFactory(runtime).decompileTool(),
        "re.ghidra.xref" to GhidraToolFactory(runtime).xrefTool(),
        "re.ghidra.annotate" to GhidraToolFactory(runtime).annotateTool(),
        "re.ghidra.project" to GhidraToolFactory(runtime).projectTool(),
        "re.ghidra.run_script" to GhidraToolFactory(runtime).runScriptTool(),
        // IDA Pro & IL2CPP tools
        "re.ida.capabilities" to IDAProCapabilitiesTool(),
        "re.ida.analyze" to IDAProAnalyzeTool(runtime),
        "re.il2cpp.metadata" to Il2cppMetadataTool(runtime),
        "re.il2cpp.resolve" to Il2cppResolveTool(),
        "re.il2cpp.vtable" to Il2cppVtableTool(runtime),
        "re.hex.identify" to HexIdentifyTool(runtime),
        "re.ida.offset" to IdaOffsetCalcTool(),
        "re.ida.xref" to IdaXrefTool(runtime),
        "re.mem.ida_correlate" to MemoryIdaCorrelateTool(runtime),
        "re.unity.runtime_dump" to UnityRuntimeDumpTool(),
        // Web vulnerability scanning
        "web.sqlmap" to SqlmapScanTool(runtime),
        "web.sqli.passive" to PassiveSqlInjectionProbeTool(),
        "web.xssscan" to XssScanTool(),
        "web.dirbrute" to DirBruteTool(),
        "web.headervuln" to HeaderVulnScanTool(),
        "web.exploit.search" to ExploitResearchSearchTool(),
        "web.exploit.review_source" to ExploitSourceReviewTool(),
        "web.exploit.stage_source" to ExploitSourceStageTool(),
        "net.portscan" to PortScanTool(),
        // Traffic capture, inspection and modification
        "http.capture" to HttpCaptureTool(),
        "http.capture.modify" to HttpCaptureModifyTool(),
        "http.replay" to HttpReplayTool(),
        "http.capture.repeat" to HttpCaptureRepeaterTool(),
        "httplog.capture" to HttpLogCaptureTool(),
        "http.history" to HttpHistoryTool(),
        "http.history.clear" to HttpHistoryClearTool(),
        "tcpdump.capture" to TcpdumpCaptureTool(),
        "packet.capture.stop" to PacketCaptureStopTool(),
        "traffic.capture.info" to TrafficCaptureInfoTool(),
        // Memory manipulation (root)
        "mem.process.list" to MemProcessListTool(RootManager(context)),
        "mem.regions" to MemRegionsTool(),
        "mem.scan" to MemScanTool(),
        "mem.read" to MemReadTool(),
        "mem.write" to MemWriteTool(),
        "mem.inject" to MemInjectTool(),
        "mem.visual.adjust" to VisualAdjustTool(),
        "mem.visual.verify" to VisualVerifyTool(),
        "mem.inject.verify" to InjectVerifyTool(),
        "mem.stealth" to MemStealthTool(),
        "mem.stealth.verify" to StealthVerifyTool(),
        "mem.antidetect" to MemAntiDetectTool(),
        // Automatic visual game-playing agent (vision loop: see → think → act)
        "mem.visual.autoplay" to VisualAutoPlayTool(),
        // Zygisk spawn-time injection (bypasses anti-tamper init checks)
        "mem.zygisk" to ZygiskInjectTool(),
        // Toolchain / shell environment diagnostics for memory work
        "mem.env.diagnose" to MemEnvDiagnoseTool(runtime),
    )

    init {
        AppLogger.i(TAG, "Tool registry built with ${tools.size} tools")
    }

    /** NVIDIA/OpenAI function names may only contain letters, digits, underscores and dashes. */
    fun externalName(tool: AgentTool): String = sanitize(tool.definition.name)
    fun externalName(internalName: String): String = sanitize(internalName)
    fun get(name: String): AgentTool? {
        val found = tools[name] ?: tools.values.firstOrNull { externalName(it) == name }
        if (found == null) AppLogger.w(TAG, "Tool lookup failed: '$name'")
        return found
    }

    fun all(): List<AgentTool> = tools.values.toList()

    /** Public API for enumerating tools with their external (sanitized) names. */
    @Suppress("unused")
    fun allExternal(): List<Pair<String, AgentTool>> = tools.values.map { externalName(it) to it }
    private fun sanitize(name: String): String = name.replace(Regex("[^A-Za-z0-9_-]"), "_")
}
