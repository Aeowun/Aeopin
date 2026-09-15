# AEOPIN — Project Record

## Project Structure

### Core Logic (Kotlin)
*   `src/commonMain/kotlin/.../aeopin/domain/VaultService.kt`: The main engine. Handles the two-stage move protocol and folder zipping.
*   `src/commonMain/kotlin/.../aeopin/data/storage/VaultManager.kt`: Manages the physical files. Implements CAS deduplication and temporary export linking.
*   `src/commonMain/sqldelight/.../aeopin/data/Database.sq`: SQLite schema with FTS5 search.

### UI & UX (Kotlin/Compose)
*   `src/desktopMain/kotlin/.../aeopin/Main.kt`: Window management and the "Wink & Peek" state machine.
*   **Window State Machine**: `ACTIVE` (center) <-> `WINKING` <-> `OFFSCREEN` <-> `PEEKING` (bottom-right).
*   **Hotkey**: `Alt+Shift+V` (Windows Global Hotkey).

## Reliability Architecture

### Single Instance Enforcement
- **Aeopin App**: Uses `Global\\AEOPIN_Main_App_Mutex`.

## Folder Structure
```text
Aeopin/
├── app/                 # Application binaries and runtime
├── data/                # User data (vault, database, settings)
├── logs/                # Diagnostic logs
└── Aeopin.exe           # Main application
```

## Rules for the App
1.  Never lose a file. Copy first, then delete.
2.  Drops are moves. Dragging out is also a move.
3.  No duplicates. Hash matches mean we store one copy but keep both records.
4.  Data Separation. Application binaries live in `app/`, user data in `data/`.
