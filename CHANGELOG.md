# Changelog

All notable AEOPIN changes are documented here.

## [1.2.1] - 2026-09-05

### Fixed

- The installer no longer trusts a stale `aeopin-portable.zip` found beside the Authority; local packages must match the SHA-256 in the current published metadata.
- Installation and repair now persist the package version returned by release metadata instead of assuming the Authority's compile-time version.
- Failed payload swaps attempt to restore the previous application directory instead of leaving the managed install empty.
- Existing AEOPIN processes are stopped before installation and launch, preventing the older installed version from winning the single-instance lock.
- Legacy installed application directories are migrated before the new payload is extracted.
- The Authority helper functions now compile correctly in release and test builds.
- README and release metadata identify the corrected installer build.

## [1.2.2] - 2026-09-06

### Fixed

- Launch now checks the release manifest first and repairs or updates the managed payload before starting it.
- Managed payloads carry a verified version marker; the Authority refuses to launch a missing or mismatched executable.
- Process shutdown waits for older AEOPIN processes to disappear instead of assuming `taskkill` completed.
- Install, update, and repair launch the verified payload only after the replacement succeeds.
- Authority failures now offer direct support and install-instruction links.
- Database bootstrap validates required tables, FTS objects, and triggers, and preserves a recovery copy before destructive journal repair.

## [1.2.0] - 2026-09-05

### Added

- Managed Windows installation under `%LOCALAPPDATA%\AEOPIN`.
- Desktop shortcut creation for the stable AEOPIN Authority entry point.
- Migration of legacy portable and common installed `bin`/`data` directories.
- Installed payload version tracking and semantic version comparison.

### Changed

- Updates and repairs preserve the local `data` directory while replacing only the application payload.
- Existing AEOPIN processes are stopped before installation, launch, repair, or update to avoid single-instance conflicts and locked files.
- The Authority remains the installer, updater, repair tool, and supported desktop entry point.
- README installation, offline setup, migration, update, and release instructions were updated.

### Fixed

- Older installed versions no longer cause the new Authority launch to fail because an existing AEOPIN process is still running.
- The Authority no longer reports `v0.0.0` after a first installation; successful installs and repairs persist the installed version.
- Re-running installation refreshes the managed desktop shortcut.

## [1.1.0] - 2026-09-05

- Improved global hotkey reliability with debouncing, UI-thread dispatch, and lifecycle cleanup.
- Added structured link metadata and HTML anchor drop support.
- Added searchable file metadata for names, paths, extensions, and sizes.
