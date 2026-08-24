# AEOPIN

AEOPIN is a Windows capture tool.**

If you have a file, a folder, some text, or a link you need to save fast, drop it in AEOPIN. It moves it to a secure local vault, and makes it searchable. No cloud, no tracking, just local storage.

## Current Status

> [!CAUTION]
> **Internal Beta.**
>
> AEOPIN is being prepped for the **Microsoft Store**. 
>
> The standalone installer exists in the `build` artifacts, but it isn't "public" yet. Official support begins with the Store release.

### What's left to do?
*   **Store Review**: Finishing the submission and review process.
*   **Final Audit**: Making sure the bundled runtime works on clean machines without JVM errors.

## How to use
1.  **Summon**: Press **Alt + Shift + V** to bring it up.
2.  **Drop**: Drag anything onto the top zone. AEOPIN moves the physical file to the vault.
3.  **Confirm**: You'll see a checkmark once it's safe.
4.  **Find**: Search bar filters results as you type.
5.  **Get it back**: Right-click to **Restore** or just **Drag out** to move it back to your Desktop.

## Data & Privacy

*   **Local Only**: No accounts, no internet required. Everything lives in `Documents/AEOPIN`.
*   **Safe Moves**: Uses a Copy → Verify → Delete protocol. We don't delete your source until we're 100% sure the vault copy is perfect.
*   **Original Names**: Files are de-duplicated by hash but keep their original names when you drag them back out.

---

## For Developers

If you have JDK 17+ and the repo cloned:

### Run it
```powershell
.\gradlew.bat run
```

### Verifying Logic
Run the integrity tests:
```powershell
.\gradlew.bat desktopTest
```

### Build Installer
```powershell
.\gradlew.bat packageMsi
```

Detailed technical logs are in [PROJECT.md](PROJECT.md). Work history is tracked in [CHANGELOG.md](CHANGELOG.md).

---
*Built by Aeowun*
