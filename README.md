# AEOPIN v1.1.0

AEOPIN is a Windows capture tool.

If you have a file, a folder, some text, or a link you need to save fast, drop it in AEOPIN. It moves it to a secure local vault and makes it searchable. No cloud, no tracking, just local storage.

The core workflow is **one-key capture from anywhere plus instant searchable recall**:

- Press `Alt+Shift+V` to show or hide AEOPIN.
- Drop files, folders, links, or text into the capture window.
- Search saved items by filename, path, extension, URL, domain, title, or text.
- Drag saved files and folders back out when you need them.

Version 1.1.0 improves global hotkey reliability, structured link metadata, HTML link drops, and searchable file metadata.

## Architecture

AEOPIN uses a two-layer native architecture:

1.  **AEOPIN Authority (Rust)**: The native Windows lifecycle manager. Responsible for installation, updates, launching, monitoring, and repair.
2.  **AEOPIN Application (Kotlin/Compose)**: The actual application experience.

### Folder Structure

```text
AEOPIN/
├── bin/                 # Managed AEOPIN application (binaries & runtime)
├── data/                # User projects and database (preserved during updates)
├── logs/                # Diagnostic logs
└── aeopin-authority.exe # Lifecycle manager and primary entry point
```

## Testing & Installation

### (Air-gapped)
- Download **`aeopin-authority.exe`** AND **`aeopin-portable.zip`**.
- Place them in the same folder.
- UNZIP and OPEN **`aeopin-portable.zip`**
- Run the `AEOPIN.exe` . It will detect the local files and install without a network.


## Data & Privacy

*   **Local Only**: No accounts, no internet required. Everything lives in the `data/` folder (standard location: `Documents/AEOPIN` if not managed by Authority).
*   **Safe Moves**: Uses a Copy → Verify → Delete protocol. AEOPIN does not delete the source until the vault copy is verified and committed.
*   **Structured Links**: URL captures retain the URL, domain, title when supplied by the source, and searchable metadata.
*   **Durable Ingestion**: File and folder captures use a local SQLite journal so interrupted work can be recovered on the next launch.

### Capture behavior

Files and folders are copied into the local vault and then removed from their original location after verification. Use **Restore to original folder** or drag an item out of AEOPIN to recover it. Text and links are stored as local database records and never require network access.

AEOPIN does not fetch web pages during capture. Link titles are taken from available dragged HTML metadata; basic URL captures remain immediate and offline-friendly.
*   **Original Names**: Files are de-duplicated by hash but keep their original names when you drag them back out.

---

## For Developers

### Prerequisites
*   JDK 17+
*   Rust (Cargo)

### Build Portable Distribution
```powershell
.\gradlew.bat zipDistributable
```

The portable archive is written to `build/distributions/aeopin-portable.zip`.

### Build Authority
```powershell
cd authority
cargo build --release
```

### Run Authority
```powershell
.\authority\target\release\aeopin-authority.exe
```

### Release checklist

1. Update the application version in `build.gradle.kts`, `authority/src/main.rs`, and `authority/ui/authority.slint`.
2. Build the portable archive with `.\gradlew.bat zipDistributable`.
3. Calculate the archive SHA-256 and update `versions.json`.
4. Push the commit and tag the release as `v<version>`.

Core capture and retrieval remain local-first. Network access is only used by the Authority updater when checking for or downloading an explicitly requested update.
