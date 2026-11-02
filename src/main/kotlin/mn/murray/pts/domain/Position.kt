package mn.murray.pts.domain

/**
 * Position catalog — the job **titles** a member of staff can be filed under.
 *
 * Until Stage 110 a position's `permissionLevel` also decided access (a linked position's level won over the
 * account's own `access` word). The user's choice for the roles picture — *"Role wins; positions become
 * titles"* — retired that: since V21 access is decided by the role an account is assigned, and a position is
 * only the word printed beside their name. `permissionLevel` is kept in the table and the form so the value an
 * operator once set is not thrown away, and so a position's rank is still readable in the catalogue, but no
 * route reads it for authorization any more.
 */
val PERMISSION_LEVELS = listOf("Viewer", "Manager", "Admin")

@kotlinx.serialization.Serializable
data class Position(
    val id: Int,
    val name: String,
    /** Legacy label, printed in the catalogue only. It no longer grants anything. */
    val permissionLevel: String,
    val description: String = "",
)
