package dev.audioeditor.platform.storage

import android.content.Context
import dev.audioeditor.core.model.Project
import dev.audioeditor.core.model.newId
import kotlinx.serialization.json.Json
import java.io.File

/** Persists project metadata and its WAV/peak files in app-private storage. */
class ProjectStore(context: Context) {

    private val root = File(context.filesDir, "projects")
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    init {
        root.mkdirs()
    }

    private fun projectDir(id: String): File = File(root, id).also { it.mkdirs() }

    private fun metadataFile(id: String): File = File(projectDir(id), "project.json")

    fun sourceFile(projectId: String, sourceId: String): File =
        File(projectDir(projectId), "sources/$sourceId.wav").also { it.parentFile?.mkdirs() }

    fun peaksFile(projectId: String, sourceId: String): File =
        File(projectDir(projectId), "peaks/$sourceId.pkpy").also { it.parentFile?.mkdirs() }

    fun list(): List<Project> = root.listFiles { file -> file.isDirectory }
        .orEmpty()
        .mapNotNull { load(it.name) }
        .sortedBy { it.name }

    fun load(id: String): Project? {
        val file = metadataFile(id)
        return if (file.exists()) {
            runCatching { json.decodeFromString<Project>(file.readText()) }.getOrNull()
        } else {
            null
        }
    }

    fun loadOrCreate(): Project = list().firstOrNull() ?: create("Untitled")

    fun save(project: Project): Project {
        metadataFile(project.id).writeText(json.encodeToString(project))
        return project
    }

    fun create(name: String = "Untitled"): Project = save(Project(name = name))

    fun delete(id: String) {
        projectDir(id).deleteRecursively()
    }

    companion object {
        fun newSourceName(): String = newId()
    }
}
