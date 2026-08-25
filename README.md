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

## How to use
1.  **Launch**: Run `aeopin-authority.exe`.
2.  **Summon**: Press **Alt + Shift + V** to bring it up.
3.  **Drop**: Drag anything onto the top zone. AEOPIN moves the physical file to the vault.
4.  **Confirm**: You'll see a checkmark once it's safe.
5.  **Find**: Search bar filters results as you type.
6.  **Get it back**: Right-click to **Restore** or just **Drag out** to move it back to your Desktop.

## Data & Privacy

*   **Local Only**: No accounts, no internet required. Everything lives in the `data/` folder (standard location: `Documents/AEOPIN` if not managed by Authority).
*   **Safe Moves**: Uses a Copy → Verify → Delete protocol. We don't delete your source until we're 100% sure the vault copy is perfect.
*   **Original Names**: Files are de-duplicated by hash but keep their original names when you drag them back out.

---

## For Developers

"A mechanic gets pissed at a machine and can at least physically attack the problem:

'WHY THE FUCK ARE YOU DOING THAT?' [wrench]

Software gives you:

NullPointerException

and your only available wrench is a $150 keyboard."

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
