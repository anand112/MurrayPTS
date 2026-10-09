package mn.murray.pts.domain

/**
 * Team member domain model and the canonical 23-person roster.
 * EMP0000 is the super admin, EMP0001 the CEO.
 */

@kotlinx.serialization.Serializable
data class Member(
    val employeeNumber: String = "",
    val name: String,
    val department: String = "",
    val position: String = "",
)

val TEAM_ROSTER: List<Member> = listOf(
    Member("EMP0000", "Super Admin", "IT / Systems", "Super Admin"),
    Member("EMP0001", "Baatar Uuganbayar", "Executive", "CEO"),
    Member("EMP0002", "Sarangerel Bold", "Projects", "Project Manager"),
    Member("EMP0003", "Ganbold Erdene", "Operations", "Site Supervisor"),
    Member("EMP0004", "Oyunchimeg Dorj", "Human Resources", "HR Lead"),
    Member("EMP0005", "Munkhbat Tsogt", "HSE", "Safety Officer"),
    Member("EMP0006", "Altantsetseg Khan", "HSE", "Environmental Advisor"),
    Member("EMP0007", "Erdenechuluun Bat", "Engineering", "Mining Engineer"),
    Member("EMP0008", "Solongo Ganbaatar", "Geology", "Geologist"),
    Member("EMP0009", "Temuulen Amgalan", "Engineering", "Drill & Blast Engineer"),
    Member("EMP0010", "Narantsetseg Purev", "Procurement", "Procurement Lead"),
    Member("EMP0011", "Batbayar Nyam", "Engineering", "Mechanical Engineer"),
    Member("EMP0012", "Uyanga Lkham", "Engineering", "Electrical Engineer"),
    Member("EMP0013", "Chuluun Munkh", "Survey", "Surveyor"),
    Member("EMP0014", "Anujin Tseren", "Quality", "QA/QC Engineer"),
    Member("EMP0015", "Ganbaatar Khos", "Logistics", "Logistics Coordinator"),
    Member("EMP0016", "Munkhzaya Dorj", "Finance", "Finance Analyst"),
    Member("EMP0017", "Erdenesukh Baasandorj", "HSE", "HSE Advisor"),
    Member("EMP0018", "Tsetsegmaa Ulziibayar", "Community Relations", "Community Liaison"),
    Member("EMP0019", "Boldbaatar Gantulga", "Operations", "Heavy Equipment Operator"),
    Member("EMP0020", "Khulan Serjee", "Administration", "Camp Administrator"),
    Member("EMP0021", "Jargalsaikhan Otgonbayar", "Logistics", "Fleet Manager"),
    Member("EMP0022", "Myagmarjav Enkhjin", "Human Resources", "Training Coordinator"),
)

val ROSTER_BY_NAME: Map<String, Member> = TEAM_ROSTER.associateBy { it.name }
