# AEOPIN — Project Record

## Project Structure

### Lifecycle & Native Layer (Rust)
*   `authority/src/main.rs`: **AEOPIN Authority**. Native Windows lifecycle manager (Slint UI). Handles installation, updates, process monitoring, and repair.
*   `authority/ui/authority.slint`: Authority UI definition.

### Core Logic (Kotlin)
*   `src/commonMain/kotlin/.../aeopin/domain/VaultService.kt`: The main engine. Handles the two-stage move protocol and folder zipping.
*   `src/commonMain/kotlin/.../aeopin/data/storage/VaultManager.kt`: Manages the physical files. Implements CAS deduplication and temporary export linking.
*   `src/commonMain/sqldelight/.../aeopin/data/Database.sq`: SQLite schema with FTS5 search.

### UI & UX (Kotlin/Compose)
*   `src/desktopMain/kotlin/.../aeopin/Main.kt`: Window management and the "Wink & Peek" state machine.
*   **Window State Machine**: `ACTIVE` (center) <-> `WINKING` <-> `OFFSCREEN` <-> `PEEKING` (bottom-right).
*   **Hotkey**: `Alt+Shift+V` (Windows Global Hotkey).

### Tooling
*   `src/desktopMain/kotlin/.../aeopin/tools/ReleaseTool.kt`: Automated release orchestrator. Handles builds, Velopack packaging, and GitHub deployment.

## Reliability Architecture

### Single Instance Enforcement
- **Rust Authority**: Uses `Global\AEOPIN_Authority_Mutex`.
- **Kotlin App**: Uses `Global\AEOPIN_Main_App_Mutex`.
Ensures only one instance of the lifecycle manager and the capture tool run at any time.

## Architecture

AEOPIN uses a two-layer architecture:
1.  **Authority (Rust)**: Primary entry point. Owns the app lifecycle.
2.  **Application (Kotlin)**: Secondary process launched by Authority. Owns the capture experience.

### Folder Structure
```text
AEOPIN/
├── bin/                 # Managed application binaries
├── data/                # User data (vault, database, settings)
├── logs/                # Diagnostic logs
└── aeopin-authority.exe # Lifecycle manager
```

## Rules for the App
1.  Never lose a file. Copy first, then delete.
2.  Drops are moves. Dragging out is also a move.
3.  No duplicates. Hash matches mean we store one copy but keep both records.
4.  Data Separation. Application binaries live in `bin/`, user data in `data/`.
5.  Managed Lifecycle. AEOPIN should always be launched through the Authority for proper monitoring and updates.
