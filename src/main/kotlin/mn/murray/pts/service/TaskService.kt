package mn.murray.pts.service

import mn.murray.pts.domain.TASK_STATUSES
import mn.murray.pts.domain.TaskItem
import mn.murray.pts.repository.BucketRepository
import mn.murray.pts.repository.ProjectRepository
import mn.murray.pts.repository.TaskRepository
import mn.murray.pts.repository.TeamRepository

/**
 * Application-level operations for the tasks + buckets aggregate.
 *
 * A single [TaskItem] shape backs both the assigned-tasks kanban and the other-tasks
 * board; the DB `kind` column ("ASSIGNED" | "OTHER") tells them apart. This service owns
 * the legacy validation rules that used to live inline in the route handlers (priority /
 * status whitelists, roster membership for the assignee, project-name resolution, and the
 * kanban "manager or own task" authorization check) and translates the name-based legacy
 * inputs into the foreign-key ids the repositories persist.
 */
object TaskService {

    private const val KIND_ASSIGNED = "ASSIGNED"
    private const val KIND_OTHER = "OTHER"
    private val PRIORITIES = listOf("High", "Medium", "Low")

    fun assigned(): List<TaskItem> = TaskRepository.findByKind(KIND_ASSIGNED)

    fun other(): List<TaskItem> = TaskRepository.findByKind(KIND_OTHER)

    fun buckets(): List<String> = BucketRepository.names()

    /**
     * Create an assigned task. Returns the new id, or null when the title is blank or the
     * assignee is not on the roster. When [requireValidProject] is true a non-blank project
     * name that cannot be resolved aborts the insert (legacy `/app/team/assign` guard);
     * otherwise an unresolved project is simply stored as "no project" (legacy
     * `/app/assigned-tasks` behaviour, which never validated the project).
     */
    fun createAssigned(
        title: String,
        projectName: String,
        due: String,
        priority: String,
        assigneeEmpNo: String,
        started: String,
        requireValidProject: Boolean = false,
    ): Int? {
        if (title.isBlank()) return null
        if (assigneeEmpNo.isBlank() || TeamRepository.findByEmpNo(assigneeEmpNo) == null) return null
        val projectId = if (projectName.isBlank()) {
            null
        } else {
            val resolved = ProjectRepository.idByName()[projectName]
            if (resolved == null && requireValidProject) return null
            resolved
        }
        val validPriority = if (priority in PRIORITIES) priority else "Medium"
        return TaskRepository.insert(
            kind = KIND_ASSIGNED,
            title = title,
            projectId = projectId,
            due = due,
            priority = validPriority,
            assigneeEmpNo = assigneeEmpNo,
            status = "Not started",
            bucketId = null,
            started = started,
        )
    }

    /**
     * Create an other-board task. Returns the new id, or null when the title or bucket is
     * blank or the bucket is unknown (the board only offers existing buckets).
     */
    fun createOther(
        title: String,
        bucketName: String,
        due: String,
        priority: String,
        started: String,
    ): Int? {
        if (title.isBlank() || bucketName.isBlank()) return null
        val bucketId = BucketRepository.idByName()[bucketName] ?: return null
        val validPriority = if (priority in PRIORITIES) priority else "Medium"
        return TaskRepository.insert(
            kind = KIND_OTHER,
            title = title,
            projectId = null,
            due = due,
            priority = validPriority,
            assigneeEmpNo = null,
            status = "Not started",
            bucketId = bucketId,
            started = started,
        )
    }

    /**
     * Kanban drag-drop status change. Mirrors the legacy guard: a manager may move any task,
     * a non-manager may only move a task assigned to them. Returns false when the task is
     * missing, the status is not whitelisted, or the caller is not permitted.
     */
    fun updateStatus(id: Int, status: String, callerEmpNo: String, canManage: Boolean): Boolean {
        if (status !in TASK_STATUSES) return false
        val task = TaskRepository.findById(id) ?: return false
        if (!canManage && task.assignee != callerEmpNo) return false
        TaskRepository.updateStatus(id, status)
        return true
    }

    /** Add an other-board bucket if it does not already exist. Returns false when skipped. */
    fun addBucket(name: String): Boolean {
        if (name.isBlank()) return false
        if (BucketRepository.idByName().containsKey(name)) return false
        BucketRepository.insert(name)
        return true
    }
}
