package mn.murray.pts

import java.io.File
import kotlinx.serialization.json.Json

object JsonStore {
    private val file = File(System.getProperty("pts.data.file", "data/pts-data.json"))
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Volatile
    private var cache: PtsData? = null

    @Volatile
    private var loadedAt: Long = -1

    fun data(): PtsData = synchronized(this) {
        val stamp = if (file.exists()) file.lastModified() else -1L
        val cached = cache
        if (cached != null && stamp == loadedAt) return cached
        var loaded: PtsData? = null
        if (file.exists()) {
            val text = file.readText()
            loaded = runCatching { json.decodeFromString<PtsData>(text) }.getOrNull()
            if (loaded == null) {
                File("$file.bak").writeText(text)
            }
        }
        var effective = loaded ?: DemoData.seed().also { write(it) }
        if (loaded != null) {
            val migrated = migrate(effective)
            if (migrated != effective) {
                write(migrated)
                effective = migrated
            }
        }
        cache = effective
        loadedAt = if (file.exists()) file.lastModified() else -1L
        effective
    }

    private fun migrate(data: PtsData): PtsData {
        var out = data

        // 1. Project types + type-encoded codes (files written before types existed).
        if (out.projects.any { it.type.isEmpty() }) {
            out = out.copy(
                projects = out.projects.map { project ->
                    if (project.type.isNotEmpty()) return@map project
                    val legacy = LEGACY_PROJECTS[project.code]
                    if (legacy != null) {
                        project.copy(code = legacy.first, type = legacy.second, name = legacy.third ?: project.name)
                    } else {
                        project.copy(type = "Site projects")
                    }
                },
            )
        }

        // 2. Project statuses -> Ongoing / On hold / Not started / Blocked / Completed.
        val restatus = out.projects.map { it.copy(status = migrateStatus(it.status)) }
        if (restatus != out.projects) out = out.copy(projects = restatus)

        // 3. Team roster: fill employee number / department / position for legacy members.
        if (out.team.any { it.employeeNumber.isEmpty() }) {
            val known = out.team.mapNotNull { ROSTER_BY_NAME[it.name] }
            val usedNums = known.mapNotNull { it.employeeNumber.removePrefix("EMP").toIntOrNull() }.toMutableSet()
            var next = (usedNums.maxOrNull() ?: 0) + 1
            val rebuilt = out.team.map { m ->
                ROSTER_BY_NAME[m.name] ?: run {
                    while (next in usedNums) next++
                    usedNums.add(next)
                    Member("EMP${next.toString().padStart(4, '0')}", m.name, "General", m.position.ifEmpty { "Team member" })
                }
            }
            // Ensure the super admin account always exists.
            val withAdmin = if (rebuilt.none { it.employeeNumber == "EMP0000" }) {
                listOf(TEAM_ROSTER.first()) + rebuilt
            } else rebuilt
            out = out.copy(team = withAdmin)
        }

        // 4. Access rows: align employee numbers with the roster (by name) and ensure super admin.
        val teamByName = out.team.associateBy { it.name }
        val realigned = out.access.map { row ->
            val member = teamByName[row.name]
            if (member != null && member.employeeNumber != row.employeeNumber) {
                row.copy(employeeNumber = member.employeeNumber, position = member.position.ifEmpty { row.position })
            } else row
        }
        val accessOut = if (realigned.none { it.employeeNumber == "EMP0000" }) {
            listOf(AccessRow("EMP0000", "Super Admin", "Super Admin", "Admin")) + realigned
        } else realigned
        if (accessOut != out.access) out = out.copy(access = accessOut)

        // 5. Seed positions if empty.
        if (out.positions.isEmpty()) {
            out = out.copy(positions = DemoData.seed().positions)
        }

        // 6. Auto-assign legacy assigned tasks to their project's manager.
        if (out.assignedTasks.any { it.assignee.isEmpty() }) {
            val empByName = out.team.associate { it.name to it.employeeNumber }
            val projectByName = out.projects.associateBy { it.name }
            val reassigned = out.assignedTasks.map { task ->
                if (task.assignee.isNotEmpty()) return@map task
                val manager = projectByName[task.project]?.manager ?: return@map task
                task.copy(assignee = empByName[manager] ?: "")
            }
            if (reassigned != out.assignedTasks) out = out.copy(assignedTasks = reassigned)
        }

        // 7. Give assigned tasks stable ids (for status updates).
        if (out.assignedTasks.any { it.id == 0 }) {
            var nextId = (out.assignedTasks.maxOfOrNull { it.id } ?: 0) + 1
            val withIds = out.assignedTasks.map { task ->
                if (task.id != 0) task else task.copy(id = nextId++)
            }
            out = out.copy(assignedTasks = withIds)
        }

        // 8. Seed the other-task bucket list.
        if (out.buckets.isEmpty()) {
            val fromTasks = out.otherTasks.map { it.bucket }.distinct()
            out = out.copy(buckets = fromTasks.ifEmpty { listOf("Management") })
        }

        // 9. To-do lists: ensure a default list exists and give legacy todos ids + a list.
        if (out.todoLists.isEmpty()) {
            out = out.copy(todoLists = listOf(TodoList(1, "General")))
        }
        if (out.todos.any { it.id == 0 }) {
            var nextId = (out.todos.maxOfOrNull { it.id } ?: 0) + 1
            val knownLists = out.todoLists.map { it.id }.toSet()
            val withIds = out.todos.map { todo ->
                var t = todo
                if (t.id == 0) t = t.copy(id = nextId++)
                if (t.list !in knownLists) t = t.copy(list = out.todoLists.first().id)
                t
            }
            out = out.copy(todos = withIds)
        }

        return out
    }

    fun update(mutate: (PtsData) -> PtsData): PtsData = synchronized(this) {
        val next = mutate(data())
        write(next)
        cache = next
        loadedAt = file.lastModified()
        next
    }

    fun filePath(): String = file.absolutePath

    private fun write(data: PtsData) {
        file.absoluteFile.parentFile?.mkdirs()
        file.writeText(json.encodeToString(PtsData.serializer(), data))
    }
}
