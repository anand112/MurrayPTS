package mn.murray.pts.security

/**
 * The three authorization roles used across PTS. They are ordered: a higher rank
 * implies every capability of the lower ones.
 *
 * Both the account-level `access` column and a position's `permissionLevel` use these
 * same three names (see domain ACCESS_LEVELS / PERMISSION_LEVELS), so a single enum
 * models both.
 */
enum class Role(val rank: Int) {
    Viewer(0),
    Manager(1),
    Admin(2);

    fun atLeast(other: Role): Boolean = rank >= other.rank

    companion object {
        /** Parse a role name case-insensitively; unknown/blank falls back to [Viewer]. */
        fun from(value: String?): Role =
            entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) } ?: Viewer
    }
}
