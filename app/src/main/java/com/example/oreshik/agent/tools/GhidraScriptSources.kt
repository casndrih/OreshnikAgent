package com.example.oreshik.agent.tools

/**
 * Sources of the four GhidraScripts that the Ghidra tool suite generates into
 * `<workspace>/ghidra_scripts/` and compiles by Ghidra itself at invocation time
 * (`-scriptPath` + `-postScript CdxGhidraXxx.java KEY=value ...`).
 *
 * Conventions (shared by all four scripts):
 *  - argv form: `KEY=value` pairs via `getScriptArgs()` (ProcessBuilder argv — no shell,
 *    therefore no quoting hazards; long values are safe).
 *  - `CDX_OUT=<abs path>` selects the JSON envelope target file (argv first, env fallback).
 *  - Envelope contract: `{"ok":bool,"error":str?,"data":{...}}` (GhidraTools.RESULT_*).
 *  - Every section is bounded by `LIMIT`; all APIs verified with javap against the
 *    Ghidra 12.1.2 jars shipped in the runtime pack (SoftwareModeling/Base/Decompiler/Generic).
 */
internal object GhidraScriptSources {

    /** Shared imports header injected into every generated script. */
    private fun header(): String = """
import com.google.gson.*;
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.mem.*;
import ghidra.program.model.symbol.*;
import ghidra.program.model.data.*;
import ghidra.program.model.reloc.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
""".trimIndent()

    /** Shared helper methods injected into every generated script class. */
    private fun helpers(): String = """
    private String[] currentArgs;

    private static String env(String key, String def) {
        String v = System.getenv(key);
        return (v == null || v.isEmpty()) ? def : v;
    }

    private String argVal(String key, String def) {
        String prefixed = key + "=";
        if (currentArgs != null) {
            for (String a : currentArgs) {
                if (a.startsWith(prefixed)) return a.substring(prefixed.length());
            }
        }
        return env(key, def);
    }

    private boolean hasArg(String key) {
        String prefixed = key + "=";
        if (currentArgs != null) {
            for (String a : currentArgs) {
                if (a.startsWith(prefixed)) return true;
            }
        }
        return env(key, null) != null;
    }

    private static int boundedInt(String raw, int min, int max) {
        int v;
        try { v = Integer.parseInt(raw); } catch (Exception e) { v = min; }
        return Math.max(min, Math.min(v, max));
    }

    private void writeEnvelope(boolean ok, String error, JsonObject data) throws IOException {
        String outPath = argVal("CDX_OUT", null);
        if (outPath == null || outPath.isEmpty()) {
            println("[cdx] CDX_OUT not set; envelope not written");
            return;
        }
        File out = new File(outPath);
        File parent = out.getParentFile();
        if (parent != null) parent.mkdirs();
        JsonObject env = new JsonObject();
        env.addProperty("ok", ok);
        if (error != null) env.addProperty("error", error);
        if (data != null) env.add("data", data);
        try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.UTF_8)) {
            w.write(env.toString());
        }
        println("[cdx] envelope written: " + out.getAbsolutePath());
    }

    private static String js(Object v) { return v == null ? "" : v.toString(); }

    private static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...[truncated]";
    }

    private static String perms(MemoryBlock b) {
        String p = "";
        p += b.isRead() ? "r" : "-";
        p += b.isWrite() ? "w" : "-";
        p += b.isExecute() ? "x" : "-";
        return p;
    }

    private Address parseAddr(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            if (s.startsWith("0x") || s.startsWith("0X")) {
                return currentProgram.getAddressFactory().getDefaultAddressSpace()
                    .getAddress(Long.parseLong(s.substring(2), 16));
            }
            return currentProgram.getAddressFactory().getAddress(s);
        } catch (Exception e) {
            return null;
        }
    }

    private Function findFunction(String nameOrAddr) {
        if (nameOrAddr == null || nameOrAddr.isEmpty()) return null;
        Address a = parseAddr(nameOrAddr);
        if (a != null) {
            Function f = currentProgram.getFunctionManager().getFunctionAt(a);
            if (f != null) return f;
            Function c = currentProgram.getListing().getFunctionContaining(a);
            if (c != null) return c;
        }
        FunctionIterator it = currentProgram.getFunctionManager().getFunctions(true);
        while (it.hasNext()) {
            Function f = it.next();
            if (f.getName().equals(nameOrAddr)) return f;
        }
        SymbolIterator sit = currentProgram.getSymbolTable().getSymbols(nameOrAddr);
        if (sit.hasNext()) {
            Function f = currentProgram.getFunctionManager().getFunctionAt(sit.next().getAddress());
            if (f != null) return f;
        }
        return null;
    }
""".trimIndent()

    /** Wraps a `run()` body in a GhidraScript class with the shared header/helpers. */
    private fun scriptClass(className: String, category: String, body: String, extraMethods: String = ""): String =
        "//@category Oreshik." + category + "\n" + header() + "\n\n" +
            "public class " + className + " extends GhidraScript {\n\n" +
            helpers() + "\n\n" +
            "    @Override\n" +
            "    public void run() throws Exception {\n" +
            "        currentArgs = getScriptArgs();\n" +
            "        try {\n" +
            body.trimIndent() + "\n" +
            "        } catch (Throwable t) {\n" +
            "            println(\"[cdx] failed: \" + t);\n" +
            "            try { writeEnvelope(false, String.valueOf(t), null); } catch (IOException ignored) {}\n" +
            "        }\n" +
            "    }\n" +
            extraMethods.trimIndent() + "\n" +
            "}\n"

    // ------------------------------------------------------------------
    // Shared section emitters used by Export and Pipeline (verified APIs only)
    // ------------------------------------------------------------------

    /** `overview` — counts + executable bytes + external count. */
    private fun overviewSection(): String = """
        |        if (want.contains("overview")) {
        |            JsonObject ov = new JsonObject();
        |            ov.addProperty("function_count", fm.getFunctionCount());
        |            ov.addProperty("symbol_count", st.getNumSymbols());
        |            MemoryBlock[] blocks = currentProgram.getMemory().getBlocks();
        |            ov.addProperty("memory_block_count", blocks.length);
        |            long codeBytes = 0;
        |            for (MemoryBlock b : blocks) { if (b.isExecute()) codeBytes += b.getSize(); }
        |            ov.addProperty("executable_bytes", codeBytes);
        |            int ext = 0;
        |            FunctionIterator eit = fm.getExternalFunctions();
        |            while (eit.hasNext() && !monitor.isCancelled()) { eit.next(); ext++; }
        |            ov.addProperty("external_count", ext);
        |            data.add("overview", ov);
        |        }
    """.trimMargin()

    /** `functions` — signatures, calling convention, params with stack offsets. */
    private fun functionsSection(): String = """
        |        if (want.contains("functions")) {
        |            JsonArray arr = new JsonArray();
        |            FunctionIterator it = fm.getFunctions(true);
        |            int n = 0;
        |            while (it.hasNext() && n < limit && !monitor.isCancelled()) {
        |                Function f = it.next();
        |                if (addr != null && !f.getBody().contains(addr)) continue;
        |                if (symFilter != null && !f.getName().contains(symFilter)) continue;
        |                JsonObject o = new JsonObject();
        |                o.addProperty("name", f.getName());
        |                o.addProperty("entry", js(f.getEntryPoint()));
        |                o.addProperty("size", f.getBody().getNumAddresses());
        |                o.addProperty("return_type", js(f.getReturnType()));
        |                o.addProperty("calling_convention", f.getCallingConventionName());
        |                o.addProperty("external", f.isExternal());
        |                o.addProperty("thunk", f.isThunk());
        |                JsonArray ps = new JsonArray();
        |                for (Parameter p : f.getParameters()) {
        |                    JsonObject po = new JsonObject();
        |                    po.addProperty("name", p.getName());
        |                    po.addProperty("type", js(p.getDataType()));
        |                    po.addProperty("stack_offset", p.getStackOffset());
        |                    ps.add(po);
        |                }
        |                o.add("parameters", ps);
        |                arr.add(o);
        |                n++;
        |            }
        |            data.add("functions", arr);
        |            data.addProperty("functions_returned", n);
        |        }
    """.trimMargin()

    /** `symbols` — all symbols with namespace/primary/external flags. */
    private fun symbolsSection(): String = """
        |        if (want.contains("symbols")) {
        |            JsonArray arr = new JsonArray();
        |            SymbolIterator it = st.getAllSymbols(true);
        |            int n = 0;
        |            while (it.hasNext() && n < limit && !monitor.isCancelled()) {
        |                Symbol s = it.next();
        |                if (addr != null && !s.getAddress().equals(addr)) continue;
        |                if (symFilter != null && !s.getName().contains(symFilter)) continue;
        |                JsonObject o = new JsonObject();
        |                o.addProperty("name", s.getName());
        |                o.addProperty("address", js(s.getAddress()));
        |                o.addProperty("external", s.isExternal());
        |                o.addProperty("primary", s.isPrimary());
        |                o.addProperty("namespace", s.getParentNamespace().getName());
        |                arr.add(o);
        |                n++;
        |            }
        |            data.add("symbols", arr);
        |            data.addProperty("symbols_returned", n);
        |        }
    """.trimMargin()

    /** `strings` — defined string data (address + clipped value). */
    private fun stringsSection(): String = """
        |        if (want.contains("strings")) {
        |            JsonArray arr = new JsonArray();
        |            DataIterator di = listing.getDefinedData(true);
        |            int n = 0;
        |            while (di.hasNext() && n < limit && !monitor.isCancelled()) {
        |                Data d = di.next();
        |                if (addr != null && !addr.equals(d.getAddress())) continue;
        |                Object v = d.getValue();
        |                if (!(v instanceof String)) continue;
        |                JsonObject o = new JsonObject();
        |                o.addProperty("address", js(d.getAddress()));
        |                o.addProperty("value", clip((String) v, 512));
        |                arr.add(o);
        |                n++;
        |            }
        |            data.add("strings", arr);
        |            data.addProperty("strings_returned", n);
        |        }
    """.trimMargin()

    /** `imports` — external libraries + external symbols grouped by library. */
    private fun importsSection(): String = """
        |        if (want.contains("imports")) {
        |            JsonArray libs = new JsonArray();
        |            int libCount = 0;
        |            for (String lib : currentProgram.getExternalManager().getExternalLibraryNames()) {
        |                if (libCount >= limit || monitor.isCancelled()) break;
        |                libs.add(new JsonPrimitive(lib));
        |                libCount++;
        |            }
        |            data.add("external_libraries", libs);
        |            JsonArray imps = new JsonArray();
        |            SymbolIterator es = st.getExternalSymbols();
        |            int n = 0;
        |            while (es.hasNext() && n < limit && !monitor.isCancelled()) {
        |                Symbol s = es.next();
        |                JsonObject o = new JsonObject();
        |                o.addProperty("name", s.getName());
        |                o.addProperty("library", s.getParentNamespace().getName());
        |                imps.add(o);
        |                n++;
        |            }
        |            data.add("imports", imps);
        |        }
    """.trimMargin()

    /** `exports` / `entry_points` — external entry points + primary symbol names. */
    private fun exportsSection(): String = """
        |        if (want.contains("exports") || want.contains("entry_points")) {
        |            JsonArray arr = new JsonArray();
        |            AddressIterator ei = st.getExternalEntryPointIterator();
        |            while (ei.hasNext() && arr.size() < limit && !monitor.isCancelled()) {
        |                Address a = ei.next();
        |                Symbol s = st.getPrimarySymbol(a);
        |                JsonObject o = new JsonObject();
        |                o.addProperty("address", js(a));
        |                o.addProperty("name", s == null ? "" : s.getName());
        |                arr.add(o);
        |            }
        |            if (want.contains("exports")) data.add("exports", arr);
        |            if (want.contains("entry_points")) data.add("entry_points", arr);
        |        }
    """.trimMargin()

    /** `memory_blocks` — name, range, size, permissions, initialized. */
    private fun memoryBlocksSection(): String = """
        |        if (want.contains("memory_blocks")) {
        |            JsonArray arr = new JsonArray();
        |            for (MemoryBlock b : currentProgram.getMemory().getBlocks()) {
        |                if (arr.size() >= limit || monitor.isCancelled()) break;
        |                JsonObject o = new JsonObject();
        |                o.addProperty("name", b.getName());
        |                o.addProperty("start", js(b.getStart()));
        |                o.addProperty("end", js(b.getEnd()));
        |                o.addProperty("size", b.getSize());
        |                o.addProperty("permissions", perms(b));
        |                o.addProperty("initialized", b.isInitialized());
        |                arr.add(o);
        |            }
        |            data.add("memory_blocks", arr);
        |        }
    """.trimMargin()

    /** Common export prologue: parse args, resolve filters, grab managers. */
    private fun exportPrologue(defaultSections: String): String = """
        |        JsonObject data = new JsonObject();
        |        data.addProperty("program", js(currentProgram.getName()));
        |        data.addProperty("image_base", js(currentProgram.getImageBase()));
        |        data.addProperty("language", js(currentProgram.getLanguageID()));
        |        data.addProperty("creation_date", js(currentProgram.getCreationDate()));
        |
        |        String sections = argVal("SECTIONS", "$defaultSections");
        |        Set<String> want = new LinkedHashSet<>();
        |        for (String s : sections.split(",")) {
        |            String t = s.trim().toLowerCase(Locale.ROOT);
        |            if (!t.isEmpty()) want.add(t);
        |        }
        |        Set<String> validSections = new HashSet<>(Arrays.asList(
        |            "overview", "functions", "symbols", "strings", "imports", "exports",
        |            "memory_blocks", "entry_points", "comments", "relocations", "equates",
        |            "bookmarks", "names", "data_types"));
        |        for (String section : want) {
        |            if (!validSections.contains(section)) throw new IllegalArgumentException("unknown export section: " + section);
        |        }
        |        int limit = boundedInt(argVal("LIMIT", "200"), 1, 20000);
        |        String addrFilter = argVal("ADDR", null);
        |        String symFilter = argVal("SYMBOL", null);
        |        Address addr = parseAddr(addrFilter);
        |        FunctionManager fm = currentProgram.getFunctionManager();
        |        SymbolTable st = currentProgram.getSymbolTable();
        |        Listing listing = currentProgram.getListing();
    """.trimMargin()

    // ------------------------------------------------------------------
    // CdxGhidraExport
    // ------------------------------------------------------------------

    fun cdxExport(): String = scriptClass(
        "CdxGhidraExport", "Export",
        exportPrologue("overview,functions,symbols,strings,imports,exports,memory_blocks,entry_points") +
            overviewSection() + functionsSection() + symbolsSection() + stringsSection() +
            importsSection() + exportsSection() + memoryBlocksSection() + """

        |        if (want.contains("comments")) {
        |            JsonArray arr = new JsonArray();
        |            FunctionIterator it = fm.getFunctions(true);
        |            int n = 0;
        |            while (it.hasNext() && n < limit && !monitor.isCancelled()) {
        |                Function f = it.next();
        |                String plate = f.getComment();
        |                String rep = f.getRepeatableComment();
        |                if ((plate == null || plate.isEmpty()) && (rep == null || rep.isEmpty())) continue;
        |                JsonObject o = new JsonObject();
        |                o.addProperty("function", f.getName());
        |                o.addProperty("entry", js(f.getEntryPoint()));
        |                if (plate != null && !plate.isEmpty()) o.addProperty("plate", clip(plate, 512));
        |                if (rep != null && !rep.isEmpty()) o.addProperty("repeatable", clip(rep, 512));
        |                arr.add(o);
        |                n++;
        |            }
        |            data.add("comments", arr);
        |        }
        |
        |        if (want.contains("relocations")) {
        |            JsonArray arr = new JsonArray();
        |            Iterator<Relocation> ri = currentProgram.getRelocationTable().getRelocations();
        |            int n = 0;
        |            while (ri.hasNext() && n < limit && !monitor.isCancelled()) {
        |                Relocation r = ri.next();
        |                JsonObject o = new JsonObject();
        |                o.addProperty("address", js(r.getAddress()));
        |                o.addProperty("type", r.getType());
        |                o.addProperty("status", js(r.getStatus()));
        |                arr.add(o);
        |                n++;
        |            }
        |            data.add("relocations", arr);
        |        }
        |
        |        if (want.contains("equates")) {
        |            JsonArray arr = new JsonArray();
        |            EquateTable et = currentProgram.getEquateTable();
        |            AddressIterator ai = et.getEquateAddresses();
        |            int n = 0;
        |            outer:
        |            while (ai.hasNext() && n < limit && !monitor.isCancelled()) {
        |                Address a = ai.next();
        |                for (Equate e : et.getEquates(a)) {
        |                    JsonObject o = new JsonObject();
        |                    o.addProperty("address", js(a));
        |                    o.addProperty("name", e.getName());
        |                    o.addProperty("value", e.getValue());
        |                    arr.add(o);
        |                    n++;
        |                    if (n >= limit) break outer;
        |                }
        |            }
        |            data.add("equates", arr);
        |        }
        |
        |        if (want.contains("bookmarks")) {
        |            JsonArray arr = new JsonArray();
        |            Iterator<Bookmark> bi = currentProgram.getBookmarkManager().getBookmarksIterator();
        |            int n = 0;
        |            while (bi.hasNext() && n < limit && !monitor.isCancelled()) {
        |                Bookmark b = bi.next();
        |                JsonObject o = new JsonObject();
        |                o.addProperty("address", js(b.getAddress()));
        |                o.addProperty("type", b.getTypeString());
        |                o.addProperty("category", b.getCategory());
        |                o.addProperty("comment", clip(b.getComment(), 256));
        |                arr.add(o);
        |                n++;
        |            }
        |            data.add("bookmarks", arr);
        |        }
        |
        |        if (want.contains("names")) {
        |            JsonArray arr = new JsonArray();
        |            DataIterator di = listing.getDefinedData(true);
        |            int n = 0;
        |            while (di.hasNext() && n < limit && !monitor.isCancelled()) {
        |                Data d = di.next();
        |                Symbol s = st.getPrimarySymbol(d.getAddress());
        |                if (s == null) continue;
        |                JsonObject o = new JsonObject();
        |                o.addProperty("address", js(d.getAddress()));
        |                o.addProperty("name", s.getName());
        |                arr.add(o);
        |                n++;
        |            }
        |            data.add("names", arr);
        |        }
        |
        |        if (want.contains("data_types")) {
        |            JsonArray arr = new JsonArray();
        |            Iterator<DataType> dti = currentProgram.getDataTypeManager().getAllDataTypes();
        |            int n = 0;
        |            while (dti.hasNext() && n < limit && !monitor.isCancelled()) {
        |                arr.add(new JsonPrimitive(dti.next().getName()));
        |                n++;
        |            }
        |            data.add("data_types", arr);
        |        }
        |
        |        writeEnvelope(true, null, data);
    """.trimMargin(),
    )

    // ------------------------------------------------------------------
    // CdxGhidraAnnotate — comm / rename / bookmark / types / clear
    // ------------------------------------------------------------------

    fun cdxAnnotate(): String = scriptClass(
        "CdxGhidraAnnotate", "Annotate",
        """
        |        JsonObject data = new JsonObject();
        |        data.addProperty("program", js(currentProgram.getName()));
        |        int applied = 0;
        |        int failed = 0;
        |        JsonArray results = new JsonArray();
        |
        |        String section = argVal("SECTION", "comm").toLowerCase(Locale.ROOT);
        |        Address at = parseAddr(argVal("AT", null));
        |        Listing listing = currentProgram.getListing();
        |
        |        if (section.equals("comm")) {
        |            if (at == null) throw new IllegalArgumentException("AT=<addr> is required for SECTION=comm");
        |            String text = argVal("TEXT", "");
        |            if (text.isEmpty()) throw new IllegalArgumentException("TEXT=<comment> is required for SECTION=comm");
        |            String type = argVal("TYPE", "plate").toLowerCase(Locale.ROOT);
        |            CommentType ct;
        |            if (type.equals("plate")) ct = CommentType.PLATE;
        |            else if (type.equals("eol")) ct = CommentType.EOL;
        |            else if (type.equals("pre")) ct = CommentType.PRE;
        |            else if (type.equals("post")) ct = CommentType.POST;
        |            else if (type.equals("repeatable")) ct = CommentType.REPEATABLE;
        |            else throw new IllegalArgumentException("unknown comment type: " + type);
        |            Function fn = listing.getFunctionContaining(at);
        |            if (fn != null && ct == CommentType.PLATE) {
        |                String existing = fn.getComment();
        |                String merged = (existing == null || existing.isEmpty()) ? text : existing + "\n" + text;
        |                fn.setComment(merged);
        |                results.add(new JsonPrimitive("function_comment@" + fn.getName()));
        |                applied++;
        |            } else if (fn != null && ct == CommentType.REPEATABLE) {
        |                String existing = fn.getRepeatableComment();
        |                String merged = (existing == null || existing.isEmpty()) ? text : existing + "\n" + text;
        |                fn.setRepeatableComment(merged);
        |                results.add(new JsonPrimitive("function_comment@" + fn.getName() + ":repeatable"));
        |                applied++;
        |            } else {
        |                CodeUnit cu = listing.getCodeUnitAt(at);
        |                if (cu == null) throw new IllegalArgumentException("no code unit at " + at);
        |                cu.setComment(ct, text);
        |                results.add(new JsonPrimitive("comment@" + at + ":" + type));
        |                applied++;
        |            }
        |        } else if (section.equals("rename")) {
        |            String from = argVal("FROM", null);
        |            String to = argVal("TO", argVal("TEXT", null));
        |            Function f = findFunction(from != null ? from : argVal("AT", null));
        |            if (f == null) throw new IllegalArgumentException("target function not found (FROM or AT)");
        |            if (to == null || to.isEmpty()) throw new IllegalArgumentException("TO=<new name> is required");
        |            f.setName(to, SourceType.USER_DEFINED);
        |            results.add(new JsonPrimitive("rename@" + f.getEntryPoint() + " -> " + to));
        |            applied++;
        |        } else if (section.equals("bookmark")) {
        |            if (at == null) throw new IllegalArgumentException("AT=<addr> is required for SECTION=bookmark");
        |            String cat = argVal("CATEGORY", "cdx");
        |            String comment = argVal("COMMENT", argVal("TEXT", ""));
        |            currentProgram.getBookmarkManager().setBookmark(at, "Analysis", cat, comment);
        |            results.add(new JsonPrimitive("bookmark@" + at + ":" + cat));
        |            applied++;
        |        } else if (section.equals("types")) {
        |            String header = argVal("HEADER", null);
        |            if (header == null || header.isEmpty() || header.contains("/") || header.contains("\\"))
        |                throw new IllegalArgumentException("HEADER must be a file name inside ghidra_scripts");
        |            String sd = argVal("CDX_SCRIPT_DIR", env("CDX_SCRIPT_DIR", System.getProperty("user.dir")));
        |            File base = new File(sd).getCanonicalFile();
        |            File hf = new File(base, header).getCanonicalFile();
        |            if (!hf.toPath().startsWith(base.toPath()) || !hf.isFile())
        |                throw new FileNotFoundException("header not found inside ghidra_scripts: " + header);
        |            DataTypeManager dtm = currentProgram.getDataTypeManager();
        |            CategoryPath cat = new CategoryPath("/cdx_types");
        |            try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(hf), StandardCharsets.UTF_8))) {
        |            String line;
        |            while ((line = r.readLine()) != null && !monitor.isCancelled()) {
        |                line = line.trim();
        |                if (line.isEmpty() || line.startsWith("//") || line.startsWith("#")) continue;
        |                String[] t = line.split("\\s+");
        |                try {
        |                    if (t[0].equals("TYPE") && t.length == 3) {
        |                        int size = parseIntRequired(t[2], "TYPE size");
        |                        if (size <= 0) throw new IllegalArgumentException("TYPE size must be positive");
        |                        DataType base = primitiveType(t[1], size);
        |                        dtm.addDataType(new TypedefDataType(cat, t[1], base), DataTypeConflictHandler.DEFAULT_HANDLER);
        |                        applied++;
        |                    } else if (t[0].equals("STRUCT") && t.length == 3) {
        |                        int size = parseIntRequired(t[2], "STRUCT size");
        |                        if (size < 0) throw new IllegalArgumentException("STRUCT size cannot be negative");
        |                        StructureDataType s = new StructureDataType(cat, t[1], size);
        |                        dtm.addDataType(s, DataTypeConflictHandler.DEFAULT_HANDLER);
        |                        applied++;
        |                    } else if (t[0].equals("FIELD") && t.length == 5) {
        |                        DataType existing = findDataType(dtm, t[1]);
        |                        if (existing instanceof Composite) {
        |                            DataType ft = findDataType(dtm, t[4]);
        |                            if (ft == null) ft = primitiveType(t[4], 0);
        |                            int offset = parseIntRequired(t[3], "FIELD offset");
        |                            if (offset < 0) throw new IllegalArgumentException("FIELD offset cannot be negative");
        |                            ((Composite) existing).add(ft, offset, t[2], null);
        |                            applied++;
        |                        } else { failed++; println("[cdx] struct not found for FIELD: " + t[1]); }
        |                    } else { failed++; println("[cdx] bad header line: " + line); }
        |                } catch (Exception ex) {
        |                    failed++;
        |                    println("[cdx] type line failed: " + line + " -> " + ex);
        |                }
        |            }
        |            }
        |        } else if (section.equals("clear")) {
        |            if (at == null) throw new IllegalArgumentException("AT=<addr> is required for SECTION=clear");
        |            Function fn = listing.getFunctionContaining(at);
        |            if (fn != null) {
        |                fn.setComment("");
        |                fn.setRepeatableComment("");
        |            }
        |            CodeUnit cu = listing.getCodeUnitAt(at);
        |            if (cu != null) {
        |                for (CommentType ct : new CommentType[]{CommentType.EOL, CommentType.PRE, CommentType.POST, CommentType.PLATE, CommentType.REPEATABLE}) {
        |                    cu.setComment(ct, "");
        |                }
        |            }
        |            if (fn == null && cu == null) throw new IllegalArgumentException("no function or code unit at " + at);
        |            results.add(new JsonPrimitive("cleared@" + at));
        |            applied++;
        |        } else {
        |            throw new IllegalArgumentException("unknown SECTION: " + section);
        |        }
        |
        |        data.addProperty("section", section);
        |        data.addProperty("applied", applied);
        |        data.addProperty("failed", failed);
        |        data.add("results", results);
        |        writeEnvelope(failed == 0, failed == 0 ? null : "some type declarations could not be applied", data);
    """.trimMargin(),
        extraMethods = """
    private static int parseIntRequired(String s, String label) {
        try { return Integer.parseInt(s); }
        catch (Exception e) { throw new IllegalArgumentException("invalid " + label + ": " + s); }
    }

    private DataType primitiveType(String name, int size) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.equals("int") || n.equals("int32")) return new IntegerDataType();
        if (n.equals("uint") || n.equals("unsigned int") || n.equals("uint32")) return new UnsignedIntegerDataType();
        if (n.equals("char")) return new CharDataType();
        if (n.equals("float")) return new FloatDataType();
        if (n.equals("double")) return new DoubleDataType();
        if (n.equals("bool") || n.equals("boolean")) return new BooleanDataType();
        if (n.equals("ptr") || n.equals("pointer") || n.endsWith("*")) return new PointerDataType();
        if (n.equals("string")) return new TerminatedStringDataType();
        if (size == 1) return new Undefined1DataType();
        if (size == 2) return new Undefined2DataType();
        if (size == 4) return new Undefined4DataType();
        if (size == 8) return new Undefined8DataType();
        throw new IllegalArgumentException("unsupported type or size: " + name + " (" + size + ")");
    }

    private DataType findDataType(DataTypeManager dtm, String name) {
        Iterator<DataType> it = dtm.getAllDataTypes();
        while (it.hasNext()) {
            DataType dt = it.next();
            if (dt.getName().equals(name)) return dt;
        }
        return null;
    }
""",
    )

    // ------------------------------------------------------------------
    // CdxGhidraXref — references to/from + optional decompile
    // ------------------------------------------------------------------

    fun cdxXref(): String = scriptClass(
        "CdxGhidraXref", "Xref",
        """
        |        JsonObject data = new JsonObject();
        |        String q = argVal("XREF", null);
        |        if (q == null || q.isEmpty()) throw new IllegalArgumentException("XREF=<addr|symbol> is required");
        |        int limit = boundedInt(argVal("LIMIT", "200"), 1, 20000);
        |        String dir = argVal("DIRECTION", "to").toLowerCase(Locale.ROOT);
        |        if (!dir.equals("to") && !dir.equals("from") && !dir.equals("both"))
        |            throw new IllegalArgumentException("unknown DIRECTION: " + dir + " (to | from | both)");
        |        Address target = parseAddr(q);
        |        if (target == null) {
        |            SymbolIterator sit = currentProgram.getSymbolTable().getSymbols(q);
        |            if (sit.hasNext()) target = sit.next().getAddress();
        |        }
        |        if (target == null) throw new IllegalArgumentException("address or symbol not found: " + q);
        |        data.addProperty("target", js(target));
        |        data.addProperty("direction", dir);
        |        ReferenceManager rm = currentProgram.getReferenceManager();
        |
        |        if (dir.equals("to") || dir.equals("both")) {
        |            JsonArray arr = new JsonArray();
        |            ReferenceIterator ri = rm.getReferencesTo(target);
        |            int n = 0;
        |            while (ri.hasNext() && n < limit && !monitor.isCancelled()) {
        |                Reference r = ri.next();
        |                JsonObject o = new JsonObject();
        |                o.addProperty("from", js(r.getFromAddress()));
        |                o.addProperty("type", js(r.getReferenceType()));
        |                o.addProperty("primary", r.isPrimary());
        |                Function f = currentProgram.getListing().getFunctionContaining(r.getFromAddress());
        |                o.addProperty("function", f == null ? "" : f.getName());
        |                arr.add(o);
        |                n++;
        |            }
        |            data.add("references_to", arr);
        |        }
        |        if (dir.equals("from") || dir.equals("both")) {
        |            JsonArray arr = new JsonArray();
        |            Reference[] refs = rm.getReferencesFrom(target);
        |            for (int i = 0; i < refs.length && i < limit && !monitor.isCancelled(); i++) {
        |                Reference r = refs[i];
        |                JsonObject o = new JsonObject();
        |                o.addProperty("to", js(r.getToAddress()));
        |                o.addProperty("type", js(r.getReferenceType()));
        |                arr.add(o);
        |            }
        |            data.add("references_from", arr);
        |        }
        |
        |        if (hasArg("DECOMPILE")) {
        |            Function f = currentProgram.getFunctionManager().getFunctionAt(target);
        |            if (f == null) f = currentProgram.getListing().getFunctionContaining(target);
        |            if (f == null) f = findFunction(q);
        |            if (f == null) {
        |                data.addProperty("decompile_error", "no containing function for " + target);
        |            } else {
        |                try {
        |                    DecompInterface di = new DecompInterface();
        |                    di.openProgram(currentProgram);
        |                    DecompileResults res = di.decompileFunction(f, 60, new ConsoleTaskMonitor());
        |                    if (res.decompileCompleted() && res.getDecompiledFunction() != null) {
        |                        int len = boundedInt(argVal("LEN", "20000"), 100, 60000);
        |                        data.addProperty("decompiled_function", f.getName());
        |                        data.addProperty("decompiled", clip(res.getDecompiledFunction().getC(), len));
        |                    } else {
        |                        data.addProperty("decompile_error", res.getErrorMessage() == null
        |                            ? "decompilation failed" : res.getErrorMessage());
        |                    }
        |                    di.dispose();
        |                } catch (Throwable dt) {
        |                    data.addProperty("decompile_error", "decompiler unavailable: " + dt);
        |                }
        |            }
        |        }
        |
        |        writeEnvelope(true, null, data);
    """.trimMargin(),
    )

    // ------------------------------------------------------------------
    // CdxGhidraPipeline — call-target function creation + core export
    // (non-interactive replacement for the bundled MakeFunctionsScript,
    //  which requires askBytes prompts and cannot run headless)
    // ------------------------------------------------------------------

    fun cdxPipeline(): String = scriptClass(
        "CdxGhidraPipeline", "Pipeline",
        exportPrologue("overview,functions,symbols,strings,imports,exports,memory_blocks,entry_points") + """

        |        // Stage 1: create functions at code-flow targets lacking one.
        |        int made = 0;
        |        int makeCap = boundedInt(argVal("MAKE_LIMIT", "500"), 0, 5000);
        |        if (makeCap > 0) {
        |            InstructionIterator ii = currentProgram.getListing().getInstructions(true);
        |            Set<Address> targets = new LinkedHashSet<>();
        |            while (ii.hasNext() && targets.size() < makeCap * 4 && !monitor.isCancelled()) {
        |                Instruction in = ii.next();
        |                for (Address t : in.getFlows()) {
        |                    if (currentProgram.getFunctionManager().getFunctionAt(t) == null) targets.add(t);
        |                }
        |            }
        |            for (Address t : targets) {
        |                if (made >= makeCap || monitor.isCancelled()) break;
        |                if (disassemble(t)) {
        |                    if (createFunction(t, null) != null) made++;
        |                }
        |            }
        |        }
        |        data.addProperty("functions_created", made);
        |
    """.trimMargin() + overviewSection() + functionsSection() + symbolsSection() +
            stringsSection() + importsSection() + exportsSection() + memoryBlocksSection() + """

        |        writeEnvelope(true, null, data);
    """.trimMargin(),
    )
}
