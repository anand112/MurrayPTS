package mn.murray.pts.domain

/**
 * Position catalog. `permissionLevel` is one of Viewer / Manager / Admin.
 * Per the v2 architecture this is now wired into authorization (Stage 5):
 * a user's effective role derives from their position's permission level,
 * falling back to the account's own access value.
 */

val PERMISSION_LEVELS = listOf("Viewer", "Manager", "Admin")

@kotlinx.serialization.Serializable
data class Position(
    val id: Int,
    val name: String,
    val permissionLevel: String,
    val description: String = "",
)
