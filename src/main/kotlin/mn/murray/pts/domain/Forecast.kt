package mn.murray.pts.domain

/** Read-only planning content: resource forecasts and lessons learned. */

@kotlinx.serialization.Serializable
data class Forecast(val project: String, val period: String, val hours: String, val note: String)

@kotlinx.serialization.Serializable
data class Lesson(val title: String, val project: String, val summary: String)
