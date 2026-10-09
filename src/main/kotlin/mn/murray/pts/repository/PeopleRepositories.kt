package mn.murray.pts.repository

import mn.murray.pts.db.Positions
import mn.murray.pts.db.TeamMembers
import mn.murray.pts.db.Users
import mn.murray.pts.domain.AccessRow
import mn.murray.pts.domain.Member
import mn.murray.pts.domain.Position
import mn.murray.pts.domain.User
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant

/**
 * Data-access layer for people: positions (catalog), team members (roster) and users
 * (login accounts). Each method opens its own Exposed transaction so callers don't have
 * to; services compose multiple calls when they need atomicity across aggregates.
 */

object PositionRepository {

    private fun map(r: ResultRow) = Position(
        id = r[Positions.id],
        name = r[Positions.name],
        permissionLevel = r[Positions.permissionLevel],
        description = r[Positions.description],
    )

    fun findAll(): List<Position> = transaction {
        Positions.selectAll().orderBy(Positions.id to SortOrder.ASC).map(::map)
    }

    fun findById(id: Int): Position? = transaction {
        Positions.selectAll().where { Positions.id eq id }.firstOrNull()?.let(::map)
    }

    fun findByName(name: String): Position? = transaction {
        Positions.selectAll().where { Positions.name eq name }.firstOrNull()?.let(::map)
    }

    fun insert(positionName: String, level: String, description: String): Int = transaction {
        Positions.insert {
            it[Positions.name] = positionName
            it[permissionLevel] = level
            it[Positions.description] = description
        } get Positions.id
    }

    fun update(id: Int, positionName: String, level: String, description: String) = transaction {
        Positions.update({ Positions.id eq id }) {
            it[Positions.name] = positionName
            it[permissionLevel] = level
            it[Positions.description] = description
        }
    }

    fun delete(id: Int): Int = transaction { Positions.deleteWhere { Positions.id eq id } }
}

object TeamRepository {

    private fun map(r: ResultRow) = Member(
        employeeNumber = r[TeamMembers.employeeNumber],
        name = r[TeamMembers.name],
        department = r[TeamMembers.department],
        position = r[TeamMembers.position],
    )

    fun findAll(): List<Member> = transaction {
        TeamMembers.selectAll().orderBy(TeamMembers.employeeNumber to SortOrder.ASC).map(::map)
    }

    fun findByEmpNo(empNo: String): Member? = transaction {
        TeamMembers.selectAll().where { TeamMembers.employeeNumber eq empNo }.firstOrNull()?.let(::map)
    }

    fun findByName(name: String): Member? = transaction {
        TeamMembers.selectAll().where { TeamMembers.name eq name }.firstOrNull()?.let(::map)
    }

    /** Employee-number → name map, used to resolve manager/assignee references. */
    fun empNoByName(): Map<String, String> = transaction {
        TeamMembers.selectAll().associate { it[TeamMembers.name] to it[TeamMembers.employeeNumber] }
    }

    fun upsert(m: Member) = transaction {
        val exists = TeamMembers.selectAll().where { TeamMembers.employeeNumber eq m.employeeNumber }.firstOrNull()
        if (exists == null) {
            TeamMembers.insert {
                it[employeeNumber] = m.employeeNumber
                it[name] = m.name
                it[department] = m.department
                it[position] = m.position
            }
        } else {
            TeamMembers.update({ TeamMembers.employeeNumber eq m.employeeNumber }) {
                it[name] = m.name
                it[department] = m.department
                it[position] = m.position
            }
        }
    }

    fun delete(empNo: String): Int = transaction { TeamMembers.deleteWhere { TeamMembers.employeeNumber eq empNo } }
}

object UserRepository {

    private fun map(r: ResultRow) = User(
        employeeNumber = r[Users.employeeNumber],
        name = r[Users.name],
        passwordHash = r[Users.passwordHash],
        access = r[Users.access],
        email = r[Users.email],
        positionId = r[Users.positionId],
        isSuperAdmin = r[Users.isSuperAdmin],
        mustChangePassword = r[Users.mustChangePassword],
        createdAt = r[Users.createdAt],
        updatedAt = r[Users.updatedAt],
    )

    fun findAll(): List<User> = transaction {
        Users.selectAll().orderBy(Users.employeeNumber to SortOrder.ASC).map(::map)
    }

    fun findByEmpNo(empNo: String): User? = transaction {
        Users.selectAll().where { Users.employeeNumber eq empNo }.firstOrNull()?.let(::map)
    }

    fun count(): Long = transaction { Users.selectAll().count() }

    fun insert(
        employeeNumber: String,
        name: String,
        passwordHash: String,
        access: String,
        email: String,
        positionId: Int?,
        isSuperAdmin: Boolean,
        mustChangePassword: Boolean,
    ) = transaction {
        val now = Instant.now().toString()
        Users.insert {
            it[Users.employeeNumber] = employeeNumber
            it[Users.name] = name
            it[Users.passwordHash] = passwordHash
            it[Users.access] = access
            it[Users.email] = email
            it[Users.positionId] = positionId
            it[Users.isSuperAdmin] = isSuperAdmin
            it[Users.mustChangePassword] = mustChangePassword
            it[createdAt] = now
            it[updatedAt] = now
        }
    }

    fun updatePasswordHash(empNo: String, hash: String, mustChange: Boolean = false) = transaction {
        Users.update({ Users.employeeNumber eq empNo }) {
            it[passwordHash] = hash
            it[mustChangePassword] = mustChange
            it[updatedAt] = Instant.now().toString()
        }
    }

    fun updateEmail(empNo: String, email: String) = transaction {
        Users.update({ Users.employeeNumber eq empNo }) {
            it[Users.email] = email
            it[updatedAt] = Instant.now().toString()
        }
    }

    fun updateAccess(empNo: String, access: String) = transaction {
        Users.update({ Users.employeeNumber eq empNo }) {
            it[Users.access] = access
            it[updatedAt] = Instant.now().toString()
        }
    }

    fun updatePosition(empNo: String, positionId: Int?) = transaction {
        Users.update({ Users.employeeNumber eq empNo }) {
            it[Users.positionId] = positionId
            it[updatedAt] = Instant.now().toString()
        }
    }

    fun updateName(empNo: String, name: String) = transaction {
        Users.update({ Users.employeeNumber eq empNo }) {
            it[Users.name] = name
            it[updatedAt] = Instant.now().toString()
        }
    }

    fun delete(empNo: String): Int = transaction { Users.deleteWhere { Users.employeeNumber eq empNo } }
}
