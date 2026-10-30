package mn.murray.pts.db.migration

import mn.murray.pts.DemoData
import mn.murray.pts.PtsData
import mn.murray.pts.domain.*

/**
 * Reproduces the legacy `JsonStore.migrate()` normalization steps so the SQLite
 * importer sees exactly the same cleaned-up data the JSON store would have produced.
 *
 * These 11 steps are a faithful copy of the original logic (Store.kt) operating on the
 * in-memory [PtsData] graph BEFORE it is written to relational tables. Keeping them here
 * (rather than calling the legacy store) means the importer is self-contained and the
 * legacy JsonStore can be deleted in Stage 6 without losing the normalization behavior.
 */
object LegacyNormalizer {

    fun normalize(data: PtsData): PtsData {
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

        // 1b. Four categories -> the nine letter-coded ones (V3). Anything already using a
        // current label or a one-letter code is untouched; unknown labels survive as written
        // so a hand-edited type shows up on the board instead of being silently rewritten.
        val recategorised = out.projects.map { p ->
            p.copy(type = migrateType(p.type), code = migrateCode(p.code))
        }
        if (recategorised != out.projects) out = out.copy(projects = recategorised)

        // 2. Project statuses -> Not started / In progress / On hold / Blocked / Completed.
        // Stage 96 folded the legacy *On track* and the app's own former *Ongoing* into *In progress*,
        // so imported data lands on the same five words the picker shows and the board sorts by.
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

        // 10. Emails: fill auto-generated addresses for accounts created before the field existed.
        if (out.access.any { it.email.isBlank() }) {
            out = out.copy(
                access = out.access.map { row ->
                    if (row.email.isNotBlank()) row else row.copy(email = autoEmail(row.name))
                },
            )
        }

        // 11. To-do boards are personal. Legacy lists/tasks without an owner belong to the
        //     super admin; a task always follows its list's owner.
        if (out.todoLists.any { it.owner.isEmpty() }) {
            out = out.copy(
                todoLists = out.todoLists.map { if (it.owner.isEmpty()) it.copy(owner = "EMP0000") else it },
            )
        }
        if (out.todos.any { it.owner.isEmpty() }) {
            val listOwner = out.todoLists.associate { it.id to it.owner }
            out = out.copy(
                todos = out.todos.map { todo ->
                    if (todo.owner.isNotEmpty()) todo else todo.copy(owner = listOwner[todo.list] ?: "EMP0000")
                },
            )
        }

        return out
    }
}
