# MURRAY Project Tracking System (PTS)

A web-based project tracking and task management system built with Kotlin and Ktor, designed for mining and construction operations management.

## Features

- **Project Management**: Track multiple projects with status, progress, and team assignments
- **Task Assignment**: Assign tasks to team members with priority levels and due dates
- **Team Management**: View and manage staff roster with positions and departments
- **Access Control**: Role-based access control with customizable positions and permission levels
- **Forecasts**: Resource and hour forecasting for project planning
- **Lessons Learned**: Document and share lessons from completed projects
- **Kanban Board**: Visual task management with drag-and-drop functionality
- **Dark Mode**: Built-in theme switching for user comfort
- **Responsive Design**: Clean, modern UI that works across devices

## Tech Stack

- **Language**: Kotlin 2.2.10
- **Framework**: Ktor 3.1.1 (Netty server)
- **Serialization**: kotlinx.serialization
- **Build Tool**: Gradle with Kotlin DSL
- **Java Version**: 21
- **Logging**: Logback

## Prerequisites

- Java 21 or higher
- Gradle (or use the included Gradle wrapper)

## Installation

1. Clone the repository:
```bash
git clone <repository-url>
cd f3690155
```

2. Build the project:
```bash
gradlew.bat build
```

3. Run the application:
```bash
gradlew.bat run
```

The server will start on `http://127.0.0.1:8080`

## Usage

### First Login

The system comes with demo accounts. For the demo system, every account's starting password is its employee number.

Demo accounts:
- **EMP0000** (Super Admin) - Full system access
- **EMP0001** (CEO) - Executive access
- **EMP0002** (Project Manager) - Can manage projects and tasks
- Other team members have Viewer access by default

### Access Control

Admins can:
- Add, edit, and delete user accounts
- Manage positions with permission levels (Viewer, Manager, Admin)
- Assign access levels to users

Permission levels:
- **Viewer**: Read-only access to projects and tasks
- **Manager**: Can create and manage projects, assign tasks
- **Admin**: Full system access including position management

### Project Types

The system supports different project types:
- Site projects
- Manufacturing
- Repair/Maintenance/Other Services
- Labour supply

## Project Structure

```
f3690155/
├── src/
│   └── main/
│       ├── kotlin/mn/murray/pts/
│       │   ├── Application.kt       # Main application and routes
│       │   ├── AccessControl.kt     # Access control UI
│       │   ├── AssignedTasks.kt     # Task management
│       │   ├── DashboardCommon.kt    # Shared UI components
│       │   ├── Data.kt              # Data models and seeding
│       │   ├── Forecasts.kt         # Forecast management
│       │   ├── Lessons.kt           # Lessons learned
│       │   ├── OtherTasks.kt        # Unassigned task backlog
│       │   ├── Projects.kt          # Project management
│       │   ├── Settings.kt          # User settings
│       │   ├── Store.kt             # JSON data persistence
│       │   ├── Team.kt              # Team roster
│       │   ├── Todo.kt              # To-do list
│       │   └── Views.kt             # Login page
│       └── resources/
│           ├── styles.css           # Application styles
│           ├── theme.js             # Theme switching
│           ├── kanban.js            # Kanban drag-and-drop
│           └── logback.xml          # Logging configuration
├── data/
│   └── pts-data.json               # Persistent data storage
├── build.gradle.kts                # Gradle build configuration
└── settings.gradle.kts             # Gradle settings
```

## Data Storage

The system uses JSON file storage (`data/pts-data.json`) for persistence. Data includes:
- Projects
- Assigned tasks
- Team roster
- Forecasts
- Lessons learned
- Access control settings
- Positions with permission levels

## Development

### Adding New Features

1. Add data models in `Data.kt`
2. Create routes in `Application.kt`
3. Build UI components in relevant view files
4. Update migration logic in `Store.kt` if needed

### Running Tests

```bash
gradlew.bat test
```

### Building for Production

```bash
gradlew.bat installDist
```

The executable will be in `build/install/f3690155/bin/`

## Configuration

### Server Configuration

Default configuration:
- Host: `127.0.0.1`
- Port: `8080`

To change the data file location, set the system property:
```
-Dpts.data.file=/path/to/your/data.json
```

## Security Considerations

This is a demo system with the following limitations:
- Passwords are stored in plain text (employee number as password)
- Session management uses in-memory storage
- No HTTPS/TLS encryption
- Not suitable for production use without security enhancements

For production deployment, consider:
- Implementing proper password hashing (bcrypt, Argon2)
- Using database storage instead of JSON files
- Adding HTTPS/TLS support
- Implementing proper session management with secure cookies
- Adding CSRF protection
- Implementing rate limiting

## License

[Specify your license here]

## Contributing

Contributions are welcome! Please follow these steps:
1. Fork the repository
2. Create a feature branch
3. Commit your changes
4. Push to the branch
5. Open a Pull Request

## Support

For issues and questions, please use the GitHub issue tracker.

## Roadmap

Potential future enhancements:
- Database integration (PostgreSQL, MySQL)
- Enhanced security features
- Mobile application
- Advanced reporting and analytics
- Email notifications
- File attachments for tasks and projects
- Calendar integration
