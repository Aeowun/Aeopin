# AEOPIN Release Guide

Follow these steps to package and release a new version of AEOPIN.

## 1. Build the Application Payload
Run the Gradle task to produce the portable distribution:
```powershell
.\gradlew.bat zipDistributable
```
This produces `build/distributions/aeopin-portable.zip`.

## 2. Calculate SHA-256
Calculate the hash of the zip file:
```powershell
(Get-FileHash .\build\distributions\aeopin-portable.zip -Algorithm SHA256).Hash.ToLower()
```

## 3. Update `versions.json`
Update the `versions.json` file in the repository root with the new version, download URL, and hash:
```json
{
  "version": "1.6.0",
  "url": "https://github.com/Aeowun/Aeopin_v0.1.5/releases/download/v1.6.0/aeopin-portable.zip",
  "sha256": "YOUR_HASH_HERE"
}
```

## 4. Build the Authority (Lifecycle Manager)
```powershell
cd authority
cargo build --release
```
The resulting `authority\target\release\aeopin-authority.exe` is what you distribute to users.

## 5. Create GitHub Release
1. Go to GitHub and create a new release (e.g., `v1.6.0`).
2. Upload `aeopin-portable.zip`.
3. Upload `aeopin-authority.exe`.
4. Publish.

## 6. Microsoft Store Submission
1. Use the "EXE/MSI" submission path in the Microsoft Partner Center.
2. For the **Package URL**, provide the direct download link to `aeopin-authority.exe` (from the GitHub release).
3. The Store will download the Authority, which will then handle installing the inner AEOPIN payload.

### Store Mode Note
If you want the Store package to be 100% self-contained (no download at install time), bundle `aeopin-portable.zip` in the same folder as `aeopin-authority.exe` before zipping/submitting if the Store accepts a ZIP containing an EXE + assets. However, for a single EXE submission, the Authority will automatically fall back to the GitHub download.
