# AEOPIN

AEOPIN is a Windows capture tool.

If you have a file, a folder, some text, or a link you need to save fast, drop it in AEOPIN. It moves it to a secure local vault, and makes it searchable. No cloud, no tracking, just local storage.

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

### Option 1: Standard (Internet Required)
- Download **`aeopin-authority.exe`**.
- Run it. The Authority will automatically fetch the core app and install it.

### Option 2: Offline (Air-gapped)
- Download **`aeopin-authority.exe`** AND **`aeopin-portable.zip`**.
- Place them in the same folder.
- Run the `.exe`. It will detect the local zip and install without a network.

> [!NOTE]
> **Development Bug Testing**: This is an internal beta. Please use the "Copy Error Report" button in the Authority if you encounter a crash and visit our [Support Page](https://aeowun.com/support/).

## Data & Privacy

*   **Local Only**: No accounts, no internet required. Everything lives in the `data/` folder (standard location: `Documents/AEOPIN` if not managed by Authority).
*   **Safe Moves**: Uses a Copy → Verify → Delete protocol. We don't delete your source until we're 100% sure the vault copy is perfect.
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

### Build Authority
```powershell
cd authority
cargo build --release
```

### Run Authority
```powershell
.\authority\target\release\aeopin-authority.exe
```
