package mn.murray.pts
import mn.murray.pts.domain.*

// This file now serves as a routing hub. All dashboard functions have been moved to separate files:
// - Projects.kt: projectsContent(), projectTable()
// - AssignedTasks.kt: assignedTasksContent()
// - Team.kt: teamContent()
// - Forecasts.kt: forecastsContent()
// - Lessons.kt: lessonsContent()
// - OtherTasks.kt: otherTasksContent(), the backlog board's own columns, popup and toggle
// - Todo.kt: todoContent()
// - AccessControl.kt: accessContent()
// - Settings.kt: settingsContent()
// - DashboardCommon.kt: appPage(), navItems(), ICONS, themeScript()

// CSS and JavaScript have been extracted to resources:
// - src/main/resources/styles.css
// - src/main/resources/theme.js
// - src/main/resources/kanban.js
// - src/main/resources/logo.json
