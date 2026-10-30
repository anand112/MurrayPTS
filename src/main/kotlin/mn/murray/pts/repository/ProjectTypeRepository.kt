package mn.murray.pts.repository

import mn.murray.pts.db.ProjectTypes
import mn.murray.pts.db.Projects
import mn.murray.pts.db.TypeDefaultBuckets
import mn.murray.pts.domain.ProjectType
import mn.murray.pts.domain.installProjectTypes
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/**
 * The project categories: read them, add one, rename one, retire one (V14).
 *
 * Dumb on purpose — no rule about what a letter may be, or whether a category is safe to delete, lives
 * here; [mn.murray.pts.service.ProjectTypeService] decides all of that. What this class does guarantee is
 * that [all] publishes what it read through [installProjectTypes], so the domain's `PROJECT_TYPES` and
 * `TYPE_PREFIX` views cannot be seen to disagree with this table by anything that asked the question
 * properly.
 *
 * The one non-obvious write is [rename], which spans three tables in one transaction. `projects.type` and
 * `type_default_buckets.type` store the category **label**, so a rename that touched only this row would
 * leave every project already filed under the old words sitting in a category that no longer exists: the
 * Projects board would drop it out of its panels and the Forecasts sheet would stop counting its money.
 * Carrying the label across with the row is the difference between renaming a category and orphaning a
 * portfolio.
 */
object ProjectTypeRepository {

    /** Every category in board order. Reading is also what keeps the domain's view of the list current. */
    fun all(): List<ProjectType> = transaction {
        ProjectTypes.selectAll()
            .orderBy(ProjectTypes.position to SortOrder.ASC)
            .orderBy(ProjectTypes.code to SortOrder.ASC)
            .map(::rowOf)
            .also { installProjectTypes(it) }
    }

    fun find(name: String): ProjectType? = transaction {
        ProjectTypes.selectAll().where { ProjectTypes.name eq name }.firstOrNull()?.let(::rowOf)
    }

    fun findByCode(code: String): ProjectType? = transaction {
        ProjectTypes.selectAll().where { ProjectTypes.code eq code }.firstOrNull()?.let(::rowOf)
    }

    /** Append a category after the last one in the list; choosing the letter is the service's job. */
    fun insert(code: String, name: String): ProjectType {
        transaction {
            val next = (ProjectTypes.selectAll().maxOfOrNull { it[ProjectTypes.position] } ?: -1) + 1
            ProjectTypes.insert {
                it[ProjectTypes.code] = code
                it[ProjectTypes.name] = name
                it[ProjectTypes.position] = next
            }
        }
        return requireNotNull(findByCode(code)) { "inserted category $code did not read back" }
    }

    /**
     * Change a category's label and carry the old label across the two tables that store it.
     *
     * Returns how many projects moved, which is the number the answer line tells management — the honest way
     * to say "renaming this changes what forty existing projects read as".
     */
    fun rename(oldName: String, newName: String): Int {
        val moved = transaction {
            ProjectTypes.update({ ProjectTypes.name eq oldName }) { it[ProjectTypes.name] = newName }
            val rows = Projects.update({ Projects.type eq oldName }) { it[Projects.type] = newName }
            TypeDefaultBuckets.update({ TypeDefaultBuckets.type eq oldName }) { it[TypeDefaultBuckets.type] = newName }
            rows
        }
        all()
        return moved
    }

    /**
     * Retire a category, and its lane defaults with it.
     *
     * A default left behind for a category nobody can name any more would be a row no panel can list or
     * edit, so it goes. Existing project boards keep their lanes — those are `project_buckets` rows, and
     * retiring a category has never been a way to delete somebody's board.
     */
    fun delete(code: String): Int {
        val gone = transaction {
            val name = ProjectTypes.selectAll().where { ProjectTypes.code eq code }
                .firstOrNull()?.get(ProjectTypes.name)
            val rows = ProjectTypes.deleteWhere { ProjectTypes.code eq code }
            if (name != null) TypeDefaultBuckets.deleteWhere { TypeDefaultBuckets.type eq name }
            rows
        }
        all()
        return gone
    }

    private fun rowOf(row: ResultRow): ProjectType = ProjectType(
        code = row[ProjectTypes.code],
        name = row[ProjectTypes.name],
    )
}
