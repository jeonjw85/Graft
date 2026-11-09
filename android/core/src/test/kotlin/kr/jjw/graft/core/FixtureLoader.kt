package kr.jjw.graft.core

import java.io.File

object FixtureLoader {
    private val directory: File = File(
        System.getProperty("graft.fixturesDir") ?: "../../protocol/fixtures"
    )

    fun names(): List<String> {
        val files = directory.listFiles { file -> file.extension == "json" }
            ?: error("fixtures directory not found: ${directory.absolutePath}")
        return files.map { it.nameWithoutExtension }.sorted()
    }

    fun bytes(name: String): ByteArray = File(directory, "$name.json").readBytes()
}
