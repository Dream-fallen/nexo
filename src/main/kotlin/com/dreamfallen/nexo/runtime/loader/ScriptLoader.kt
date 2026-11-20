package com.dreamfallen.nexo.runtime.loader

import com.dreamfallen.nexo.lang.ast.Program
import com.dreamfallen.nexo.lang.ast.ScriptException
import com.dreamfallen.nexo.lang.parser.Parser
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/** Loads and compiles .nx files. Cache entries are invalidated using size and last-modified time. */
class ScriptLoader(
    private val scriptsDirectory: Path,
    private val logger: Logger? = null
) {
    private data class Cached(val modified: Long, val size: Long, val program: Program)
    private val cache = ConcurrentHashMap<Path, Cached>()

    fun loadAll(): List<Program> {
        if (!Files.exists(scriptsDirectory)) Files.createDirectories(scriptsDirectory)
        val programs = ArrayList<Program>()
        Files.walk(scriptsDirectory).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".nx", ignoreCase = true) }
                .sorted()
                .forEach { file ->
                    try {
                        programs += load(file)
                    } catch (e: Exception) {
                        logger?.severe("Failed to load script '${file.fileName}': ${e.message}")
                    }
                }
        }
        return programs
    }

    fun load(file: Path): Program {
        val normalized = file.toAbsolutePath().normalize()
        require(normalized.startsWith(scriptsDirectory.toAbsolutePath().normalize())) { "Script path must be inside the scripts directory" }
        val modified = Files.getLastModifiedTime(normalized).toMillis()
        val size = Files.size(normalized)
        cache[normalized]?.let { if (it.modified == modified && it.size == size) return it.program }
        val source = Files.readString(normalized)
        val program = try {
            Parser(source, normalized.fileName.toString()).parse()
        } catch (e: ScriptException) {
            throw e
        }
        cache[normalized] = Cached(modified, size, program)
        return program
    }

    fun invalidate(file: Path? = null) {
        if (file == null) cache.clear() else cache.remove(file.toAbsolutePath().normalize())
    }
}
