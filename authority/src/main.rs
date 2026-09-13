/*
 * AEOPIN — Local Capture & Search
 * Copyright (C) 2026 Aeowun
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

slint::include_modules!();

use std::path::{Path, PathBuf};
use std::env;
use std::fs;
use std::process::{Command, Child};
use std::sync::{Arc, Mutex};
use std::thread;
use zip::read::ZipArchive;
use std::io::{self, Cursor, Read};
use std::time::Duration;
use serde::{Deserialize, Serialize};
use sha2::{Sha256, Digest};

use windows::Win32::System::Threading::{CreateMutexW, ReleaseMutex};
use windows::Win32::Foundation::{CloseHandle, ERROR_ALREADY_EXISTS};
use windows::core::PCWSTR;

const METADATA_URL: &str = "https://raw.githubusercontent.com/Aeowun/Aeopin/main/versions.json";
const APP_NAME: &str = "AEOPIN";
const AUTHORITY_EXE: &str = "aeopin-authority.exe";
const PACKAGE_FILE: &str = "aeopin-portable.zip";
const LEGACY_PACKAGE_FILE: &str = "Aeopin-win-Portable.zip";
const CURRENT_VERSION: &str = "1.2.3";
const SUPPORT_URL: &str = "https://Aeowun.com";
const INSTALL_URL: &str = "https://github.com/Aeowun/Aeopin/releases/latest";

#[derive(Deserialize, Serialize, Clone, Debug)]
struct VersionMetadata {
    version: String,
    url: String,
    sha256: String,
}

#[derive(Deserialize, Serialize, Clone)]
struct AuthoritySettings {
    hotkey: String,
}

struct AuthorityState {
    bin_dir: PathBuf,
    data_dir: PathBuf,
    logs_dir: PathBuf,
    staging_dir: PathBuf,
    settings_file: PathBuf,
    child_process: Option<Arc<Mutex<Child>>>,
    current_version: String,
    settings: AuthoritySettings,
    last_error: Option<String>,
}

impl AuthorityState {
    fn new() -> Self {
        let authority_dir = env::current_exe()
            .expect("Failed to get current executable path")
            .parent()
            .expect("Failed to get parent directory")
            .to_path_buf();

        let local_app_data = env::var_os("LOCALAPPDATA")
            .map(PathBuf::from)
            .unwrap_or_else(|| authority_dir.clone());
        let managed_dir = local_app_data.join(APP_NAME);
        let managed_authority = managed_dir.join(AUTHORITY_EXE);
        let install_dir = if env::current_exe().ok().as_ref() == Some(&managed_authority) {
            managed_dir
        } else {
            // Portable and legacy launches are migrated into the managed location.
            managed_dir
        };

        let settings_file = install_dir.join("authority_settings.json");
        let installed_version_file = install_dir.join("installed.version");
        let installed_version = fs::read_to_string(&installed_version_file)
            .map(|version| version.trim().to_string())
            .ok()
            .filter(|version| !version.is_empty())
            .unwrap_or_else(|| "0.0.0".to_string());
        let settings = if settings_file.exists() {
            let data = fs::read_to_string(&settings_file).unwrap_or_default();
            serde_json::from_str(&data).unwrap_or(AuthoritySettings { hotkey: "Alt+Shift+V".to_string() })
        } else {
            AuthoritySettings { hotkey: "Alt+Shift+V".to_string() }
        };

        Self {
            bin_dir: install_dir.join("bin"),
            data_dir: install_dir.join("data"),
            logs_dir: install_dir.join("logs"),
            staging_dir: install_dir.join("staging"),
            settings_file,
            child_process: None,
            current_version: installed_version,
            settings,
            last_error: None,
        }
    }

    fn save_settings(&self) -> io::Result<()> {
        let data = serde_json::to_string_pretty(&self.settings)?;
        fs::write(&self.settings_file, data)?;
        Ok(())
    }

    fn save_installed_version(&self, version: &str) -> io::Result<()> {
        let version_file = self.bin_dir.parent().unwrap().join("installed.version");
        fs::write(version_file, version)?;
        fs::write(self.bin_dir.join("AEOPIN.version"), version)
    }

    fn is_installed(&self) -> bool {
        self.validate_managed_payload().is_ok()
    }

    fn validate_managed_payload(&self) -> io::Result<()> {
        let executable = self.bin_dir.join("AEOPIN.exe");
        if !executable.is_file() {
            return Err(io::Error::new(io::ErrorKind::NotFound, "Managed AEOPIN.exe is missing"));
        }
        let payload_version = fs::read_to_string(self.bin_dir.join("AEOPIN.version"))
            .map(|version| version.trim().to_string())
            .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "Managed payload version is missing"))?;
        if payload_version != self.current_version || payload_version == "0.0.0" {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                format!(
                    "Managed payload version {} does not match expected {}",
                    payload_version, self.current_version
                ),
            ));
        }
        Ok(())
    }

    fn ensure_dirs(&self) -> io::Result<()> {
        fs::create_dir_all(&self.bin_dir)?;
        fs::create_dir_all(&self.data_dir)?;
        fs::create_dir_all(&self.logs_dir)?;
        fs::create_dir_all(&self.staging_dir)?;
        Ok(())
    }

    fn migrate_legacy_install(&self) -> io::Result<()> {
        let current_exe = env::current_exe()?;
        let managed_root = self.bin_dir.parent().unwrap();
        let mut roots = vec![current_exe.parent().unwrap_or(Path::new(".")).to_path_buf()];
        if let Some(local_app_data) = env::var_os("LOCALAPPDATA").map(PathBuf::from) {
            roots.push(local_app_data.join("Programs").join(APP_NAME));
        }
        if let Some(program_files) = env::var_os("ProgramFiles").map(PathBuf::from) {
            roots.push(program_files.join(APP_NAME));
        }
        if let Some(program_files_x86) = env::var_os("ProgramFiles(x86)").map(PathBuf::from) {
            roots.push(program_files_x86.join(APP_NAME));
        }

        for legacy_root in roots {
            if legacy_root == managed_root || !legacy_root.exists() {
                continue;
            }
            let legacy_bin = legacy_root.join("bin");
            let legacy_data = legacy_root.join("data");
            if legacy_bin.exists() && !self.bin_dir.exists() {
                self.ensure_dirs()?;
                move_directory(&legacy_bin, &self.bin_dir)?;
            }
            if legacy_data.exists() && !self.data_dir.exists() {
                self.ensure_dirs()?;
                move_directory(&legacy_data, &self.data_dir)?;
            }
            // Best-effort cleanup removes old application payloads but never user data.
            let _ = fs::remove_dir_all(&legacy_bin);
            let _ = fs::remove_file(legacy_root.join(AUTHORITY_EXE));
        }
        Ok(())
    }

    fn stop_existing_app(&self) -> io::Result<()> {
        let _ = Command::new("taskkill")
            .args(["/IM", "AEOPIN.exe", "/T", "/F"])
            .output();
        for _ in 0..20 {
            let running = Command::new("tasklist")
                .args(["/FI", "IMAGENAME eq AEOPIN.exe", "/FO", "CSV", "/NH"])
                .output()?
                .stdout;
            if !String::from_utf8_lossy(&running).contains("AEOPIN.exe") {
                return Ok(());
            }
            thread::sleep(Duration::from_millis(100));
        }
        Err(io::Error::new(
            io::ErrorKind::Other,
            "An older AEOPIN process could not be stopped",
        ))
    }

    fn install_shortcut(&self) -> io::Result<()> {
        let desktop = env::var_os("USERPROFILE")
            .map(PathBuf::from)
            .map(|p| p.join("Desktop"))
            .ok_or_else(|| io::Error::new(io::ErrorKind::NotFound, "USERPROFILE is not set"))?;
        fs::create_dir_all(&desktop)?;
        let shortcut = desktop.join("AEOPIN.lnk");
        let authority = self.bin_dir.parent().unwrap().join(AUTHORITY_EXE);
        let script = "$ws = New-Object -ComObject WScript.Shell; \
            $sc = $ws.CreateShortcut($env:AEOPIN_SHORTCUT); \
            $sc.TargetPath = $env:AEOPIN_TARGET; \
            $sc.WorkingDirectory = $env:AEOPIN_WORKDIR; \
            $sc.IconLocation = $env:AEOPIN_ICON; \
            $sc.Description = 'AEOPIN local capture and search'; \
            $sc.Save()";
        let status = Command::new("powershell.exe")
            .args(["-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", script])
            .env("AEOPIN_SHORTCUT", &shortcut)
            .env("AEOPIN_TARGET", &authority)
            .env("AEOPIN_WORKDIR", authority.parent().unwrap())
            .env("AEOPIN_ICON", self.bin_dir.join("AEOPIN.exe"))
            .status()?;
        if !status.success() {
            return Err(io::Error::new(io::ErrorKind::Other, "Could not create desktop shortcut"));
        }
        Ok(())
    }

    fn install_authority_entry_point(&self) -> io::Result<()> {
        self.ensure_dirs()?;
        let current = env::current_exe()?;
        let target = self.bin_dir.parent().unwrap().join(AUTHORITY_EXE);
        if current != target {
            let temp = target.with_extension("new");
            fs::copy(&current, &temp)?;
            fs::rename(temp, target)?;
        }
        Ok(())
    }

    fn is_newer_version(remote: &str, current: &str) -> bool {
        fn parts(version: &str) -> Vec<u32> {
            version.trim_start_matches('v').split('.')
                .map(|part| part.parse::<u32>().unwrap_or(0)).collect()
        }
        let mut left = parts(remote);
        let mut right = parts(current);
        left.resize(3, 0);
        right.resize(3, 0);
        left > right
    }

    fn verify_sha256(bytes: &[u8], expected_hex: &str) -> bool {
        let mut hasher = Sha256::new();
        hasher.update(bytes);
        let result = hasher.finalize();
        let hex = format!("{:x}", result);
        hex == expected_hex
    }

    fn install_from_zip_bytes(&self, bytes: &[u8]) -> io::Result<()> {
        self.ensure_dirs()?;

        if self.staging_dir.exists() {
            fs::remove_dir_all(&self.staging_dir)?;
        }
        fs::create_dir_all(&self.staging_dir)?;

        let cursor = Cursor::new(bytes);
        let mut archive = ZipArchive::new(cursor)?;

        for i in 0..archive.len() {
            let mut file = archive.by_index(i)?;
            let outpath = self.staging_dir.join(file.mangled_name());

            if file.name().ends_with('/') {
                fs::create_dir_all(&outpath)?;
            } else {
                if let Some(p) = outpath.parent() {
                    if !p.exists() {
                        fs::create_dir_all(p)?;
                    }

                }
                let mut outfile = fs::File::create(&outpath)?;
                io::copy(&mut file, &mut outfile)?;
            }
        }

        if !self.staging_dir.join("AEOPIN.exe").is_file() {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "Package does not contain the required AEOPIN.exe",
            ));
        }

        let bin_old = self.bin_dir.with_extension("old");
        if self.bin_dir.exists() {
            if bin_old.exists() {
                fs::remove_dir_all(&bin_old)?;
            }
            fs::rename(&self.bin_dir, &bin_old)?;
        }

        if let Err(error) = fs::rename(&self.staging_dir, &self.bin_dir) {
            if bin_old.exists() && !self.bin_dir.exists() {
                let _ = fs::rename(&bin_old, &self.bin_dir);
            }
            return Err(error);
        }

        if bin_old.exists() {
            let _ = fs::remove_dir_all(&bin_old);
        }

        Ok(())
    }

    fn launch(&mut self) -> io::Result<Child> {
        self.validate_managed_payload()?;
        self.stop_existing_app()?;
        let exe_path = self.bin_dir.join("AEOPIN.exe");

        self.ensure_dirs()?;
        let log_file = fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(self.logs_dir.join("aeopin_app.log"))?;

        let child = Command::new(exe_path)
            .env("AEOPIN_DATA_DIR", &self.data_dir)
            .stdout(log_file.try_clone()?)
            .stderr(log_file)
            .spawn()?;
        Ok(child)
    }

    fn stop_app(&mut self) {
        if let Some(child_arc) = self.child_process.take() {
            let mut child = child_arc.lock().unwrap();
            let _ = child.kill();
            let _ = child.wait();
        }
    }

    fn generate_error_report(&self) -> String {
        let mut report = String::new();
        report.push_str("AEOPIN Authority Error Report\n");
        report.push_str("============================\n");
        report.push_str(&format!("Authority Version: {}\n", env!("CARGO_PKG_VERSION")));
        report.push_str(&format!("AEOPIN Managed Version: {}\n", self.current_version));
        report.push_str(&format!("OS: {}\n", env::consts::OS));
        report.push_str(&format!("Installation State: {}\n", if self.is_installed() { "Installed" } else { "Not Installed" }));
        report.push_str(&format!("Last Error: {}\n", self.last_error.as_deref().unwrap_or("None")));

        report.push_str("\nPaths:\n");
        report.push_str(&format!("Bin: {:?}\n", self.bin_dir));
        report.push_str(&format!("Data: {:?}\n", self.data_dir));
        report.push_str(&format!("Logs: {:?}\n", self.logs_dir));

        report
    }
}

fn download_with_progress(url: &str, ui_handle: slint::Weak<AuthorityWindow>) -> io::Result<Vec<u8>> {
    let client = reqwest::blocking::Client::builder()
        .timeout(Duration::from_secs(300))
        .build()
        .map_err(|e| io::Error::new(io::ErrorKind::Other, e))?;

    let mut response = client.get(url).send().map_err(|e| io::Error::new(io::ErrorKind::Other, e))?;

    if !response.status().is_success() {
        return Err(io::Error::new(io::ErrorKind::Other, format!("Download failed: {}", response.status())));
    }

    let total_size = response.content_length().unwrap_or(0);
    let mut buffer = Vec::new();
    let mut downloaded = 0;
    let mut chunk = [0u8; 8192];

    loop {
        let n = response.read(&mut chunk)?;
        if n == 0 { break; }
        buffer.extend_from_slice(&chunk[..n]);
        downloaded += n as u64;

        if total_size > 0 {
            let progress = downloaded as f32 / total_size as f32;
            let ui_weak = ui_handle.clone();
            slint::invoke_from_event_loop(move || {
                if let Some(ui) = ui_weak.upgrade() {
                    ui.set_progress(progress);
                }
            }).unwrap();
        }
    }

    Ok(buffer)
}

fn move_directory(source: &Path, target: &Path) -> io::Result<()> {
    if let Some(parent) = target.parent() {
        fs::create_dir_all(parent)?;
    }
    match fs::rename(source, target) {
        Ok(()) => Ok(()),
        Err(_) => {
            copy_directory(source, target)?;
            fs::remove_dir_all(source)
        }
    }
}

fn copy_directory(source: &Path, target: &Path) -> io::Result<()> {
    fs::create_dir_all(target)?;
    for entry in fs::read_dir(source)? {
        let entry = entry?;
        let destination = target.join(entry.file_name());
        if entry.file_type()?.is_dir() {
            copy_directory(&entry.path(), &destination)?;
        } else {
            fs::copy(entry.path(), destination)?;
        }
    }
    Ok(())
}

fn fetch_metadata() -> io::Result<VersionMetadata> {
    let client = reqwest::blocking::Client::builder()
        .timeout(Duration::from_secs(30))
        .build()
        .map_err(|e| io::Error::new(io::ErrorKind::Other, e))?;
    let response = client.get(METADATA_URL).send().map_err(|e| io::Error::new(io::ErrorKind::Other, e))?;
    if !response.status().is_success() {
        return Err(io::Error::new(io::ErrorKind::Other, format!("Failed to fetch metadata: {}", response.status())));
    }

    response.json().map_err(|e| io::Error::new(io::ErrorKind::Other, e))
}

fn open_url(url: &str) -> io::Result<()> {
    let status = Command::new("cmd")
        .args(["/C", "start", "", url])
        .status()?;
    if status.success() {
        Ok(())
    } else {
        Err(io::Error::new(io::ErrorKind::Other, "Could not open support link"))
    }
}

fn load_verified_package(
    metadata: &VersionMetadata,
    ui_handle: slint::Weak<AuthorityWindow>,
) -> io::Result<Vec<u8>> {
    let release_dir = env::current_exe()?
        .parent()
        .ok_or_else(|| io::Error::new(io::ErrorKind::NotFound, "Authority has no parent directory"))?
        .to_path_buf();
    for name in [PACKAGE_FILE, LEGACY_PACKAGE_FILE] {
        let local_package = release_dir.join(name);
        if local_package.is_file() {
            let local_bytes = fs::read(&local_package)?;
            if AuthorityState::verify_sha256(&local_bytes, &metadata.sha256) {
                let version = metadata.version.clone();
                slint::invoke_from_event_loop({
                    let ui_handle = ui_handle.clone();
                    move || {
                        if let Some(ui) = ui_handle.upgrade() {
                            ui.set_status_text(slint::format!(
                                "Using verified AEOPIN v{} package...",
                                version
                            ));
                        }
                    }
                }).map_err(|e| io::Error::new(io::ErrorKind::Other, e))?;
                return Ok(local_bytes);
            }
        }
    }

    let version = metadata.version.clone();
    slint::invoke_from_event_loop({
        let ui_handle = ui_handle.clone();
        move || {
            if let Some(ui) = ui_handle.upgrade() {
                ui.set_status_text(slint::format!(
                    "Downloading AEOPIN v{}...",
                    version
                ));
            }
        }
    }).map_err(|e| io::Error::new(io::ErrorKind::Other, e))?;

    let bytes = download_with_progress(&metadata.url, ui_handle)?;
    if !AuthorityState::verify_sha256(&bytes, &metadata.sha256) {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "SHA-256 verification failed",
        ));
    }
    Ok(bytes)
}

fn install_verified_metadata(
    state: &mut AuthorityState,
    metadata: &VersionMetadata,
    ui_handle: slint::Weak<AuthorityWindow>,
) -> io::Result<()> {
    let bytes = load_verified_package(metadata, ui_handle)?;
    state.stop_app();
    state.stop_existing_app()?;
    state.migrate_legacy_install()?;
    state.install_from_zip_bytes(&bytes)?;
    state.install_authority_entry_point()?;
    state.install_shortcut()?;
    state.save_installed_version(&metadata.version)?;
    state.current_version = metadata.version.clone();
    Ok(())
}

fn monitor_child(
    state: Arc<Mutex<AuthorityState>>,
    child: Child,
    ui_handle: slint::Weak<AuthorityWindow>,
) {
    let child_arc = Arc::new(Mutex::new(child));
    if let Ok(mut state_guard) = state.lock() {
        state_guard.child_process = Some(child_arc.clone());
    }
    thread::spawn(move || {
        let started = std::time::Instant::now();
        let result = child_arc.lock().map_err(|_| {
            io::Error::new(io::ErrorKind::Other, "Application process lock failed")
        }).and_then(|mut child| child.wait().map_err(io::Error::from));
        let short_lived = started.elapsed() < Duration::from_secs(5);
        let failure = match &result {
            Ok(status) if status.success() && !short_lived => None,
            Ok(status) if short_lived => Some("AEOPIN stopped during startup".to_string()),
            Ok(status) => Some(format!("AEOPIN exited with status {}", status)),
            Err(error) => Some(format!("AEOPIN process error: {}", error)),
        };
        if let Ok(mut state_guard) = state.lock() {
            state_guard.child_process = None;
            state_guard.last_error = failure.clone();
        }
        let _ = slint::invoke_from_event_loop(move || {
            if let Some(ui) = ui_handle.upgrade() {
                if let Some(error) = failure {
                    ui.set_state(slint::format!("error"));
                    ui.set_status_text(slint::format!(
                        "{}. Use Support or Install Instructions below.",
                        error
                    ));
                } else {
                    ui.set_state(slint::format!("installed"));
                    ui.set_status_text(slint::format!("AEOPIN exited normally."));
                }
            }
        });
    });
}

fn main() -> Result<(), slint::PlatformError> {
    let mutex_name: Vec<u16> = "Global\\AEOPIN_Authority_Mutex\0".encode_utf16().collect();
    let handle = unsafe {
        let h = CreateMutexW(None, true, PCWSTR::from_raw(mutex_name.as_ptr())).unwrap();
        if io::Error::last_os_error().raw_os_error() == Some(ERROR_ALREADY_EXISTS.0 as i32) {
            println!("Another instance is already running.");
            return Ok(());
        }
        h
    };

    env_logger::init();
    let ui = AuthorityWindow::new()?;
    let state = Arc::new(Mutex::new(AuthorityState::new()));

    {
        let s = state.lock().unwrap();
        ui.set_app_version(slint::format!("{}", s.current_version));
        ui.set_hotkey(slint::format!("{}", s.settings.hotkey));
        if s.is_installed() {
            ui.set_state(slint::format!("installed"));
            ui.set_status_text(slint::format!("AEOPIN is installed and ready."));
        } else {
            ui.set_state(slint::format!("not_installed"));
            ui.set_status_text(slint::format!("AEOPIN is ready to install."));
        }
    }

    let ui_handle = ui.as_weak();
    let state_clone = state.clone();
    ui.on_install_clicked(move || {
        let ui = ui_handle.upgrade().unwrap();
        let state = state_clone.clone();

        ui.set_is_working(true);
        ui.set_status_text(slint::format!("Preparing to install..."));

        let ui_weak = ui_handle.clone();
        thread::spawn(move || {
            let res = (|| -> io::Result<()> {
                let mut s = state.lock().unwrap();
                s.stop_existing_app()?;
                let meta = fetch_metadata()?;
                let bytes = load_verified_package(&meta, ui_weak.clone())?;

                slint::invoke_from_event_loop({
                    let ui_weak = ui_weak.clone();
                    move || { if let Some(ui) = ui_weak.upgrade() { ui.set_status_text(slint::format!("Extracting...")); ui.set_progress(0.99); } }
                }).unwrap();

                s.migrate_legacy_install()?;
                s.install_from_zip_bytes(&bytes)?;
                s.install_authority_entry_point()?;
                s.install_shortcut()?;
                s.save_installed_version(&meta.version)?;
                s.current_version = meta.version.clone();
                let child = s.launch()?;
                drop(s);
                monitor_child(state.clone(), child, ui_weak.clone());
                Ok(())
            })();

            slint::invoke_from_event_loop(move || {
                if let Some(ui) = ui_weak.upgrade() {
                    ui.set_is_working(false);
                    match res {
                        Ok(_) => {
                            let installed_version = state.lock()
                                .map(|state| state.current_version.clone())
                                .unwrap_or_else(|_| CURRENT_VERSION.to_string());
                            ui.set_app_version(slint::format!("{}", installed_version));
                            ui.set_state(slint::format!("installed"));
                            ui.set_status_text(slint::format!("Installation complete."));
                        }
                        Err(e) => {
                            if let Ok(mut s) = state.lock() {
                                s.last_error = Some(e.to_string());
                            }
                            ui.set_state(slint::format!("error"));
                            ui.set_status_text(slint::format!("Installation failed: {}", e));
                        }
                    }
                }
            }).unwrap();
        });
    });

    let ui_handle = ui.as_weak();
    let state_clone = state.clone();
    ui.on_launch_clicked(move || {
        let ui = ui_handle.upgrade().unwrap();
        ui.set_is_working(true);
        ui.set_status_text(slint::format!("Checking for updates before launch..."));

        let ui_weak = ui_handle.clone();
        let state = state_clone.clone();
        let monitor_state = state_clone.clone();
        thread::spawn(move || {
            let result = (|| -> io::Result<()> {
                let metadata = fetch_metadata()?;
                let mut state = state.lock().unwrap();
                let needs_update = state.validate_managed_payload().is_err()
                    || AuthorityState::is_newer_version(&metadata.version, &state.current_version);
                if needs_update {
                    install_verified_metadata(&mut state, &metadata, ui_weak.clone())?;
                }
                let child = state.launch()?;
                drop(state);
                monitor_child(monitor_state, child, ui_weak.clone());
                Ok(())
            })();

            slint::invoke_from_event_loop(move || {
                if let Some(ui) = ui_weak.upgrade() {
                    ui.set_is_working(false);
                    match result {
                        Ok(_) => {
                            ui.set_state(slint::format!("running"));
                            ui.set_status_text(slint::format!("AEOPIN is running."));
                        }
                        Err(error) => {
                            ui.set_state(slint::format!("error"));
                            ui.set_status_text(slint::format!(
                                "AEOPIN could not start: {}. Use Support or Install Instructions below.",
                                error
                            ));
                        }
                    }
                }
            }).unwrap();
        });
    });

    let ui_handle = ui.as_weak();
    let state_clone = state.clone();
    ui.on_update_clicked(move || {
        let ui = ui_handle.upgrade().unwrap();
        ui.set_is_working(true);
        ui.set_status_text(slint::format!("Checking for updates..."));

        let ui_weak = ui_handle.clone();
        let state = state_clone.clone();
        thread::spawn(move || {
            let res = (|| -> io::Result<()> {
                let meta = fetch_metadata()?;

                let current = {
                    let s = state.lock().unwrap();
                    s.current_version.clone()
                };

                if !AuthorityState::is_newer_version(&meta.version, &current) {
                    slint::invoke_from_event_loop({
                        let ui_weak = ui_weak.clone();
                        move || {
                            if let Some(ui) = ui_weak.upgrade() {
                                ui.set_status_text(slint::format!("AEOPIN is up to date (v{}).", current));
                            }
                        }
                    }).unwrap();
                    return Ok(());
                }

                slint::invoke_from_event_loop({
                    let ui_weak = ui_weak.clone();
                    let ver = meta.version.clone();
                    move || { if let Some(ui) = ui_weak.upgrade() { ui.set_status_text(slint::format!("Downloading v{}...", ver)); } }
                }).unwrap();

                let bytes = load_verified_package(&meta, ui_weak.clone())?;

                let mut s = state.lock().unwrap();
                s.stop_app();
                s.stop_existing_app()?;
                s.migrate_legacy_install()?;
                s.install_from_zip_bytes(&bytes)?;
                s.install_authority_entry_point()?;
                s.install_shortcut()?;
                s.save_installed_version(&meta.version)?;
                s.current_version = meta.version.clone();
                let child = s.launch()?;
                drop(s);
                monitor_child(state.clone(), child, ui_weak.clone());
                Ok(())
            })();

            slint::invoke_from_event_loop(move || {
                if let Some(ui) = ui_weak.upgrade() {
                    ui.set_is_working(false);
                    match res {
                        Ok(_) => {
                            let installed_version = state.lock()
                                .map(|state| state.current_version.clone())
                                .unwrap_or_else(|_| CURRENT_VERSION.to_string());
                            ui.set_app_version(slint::format!("{}", installed_version));
                            ui.set_state(slint::format!("installed"));
                            ui.set_status_text(slint::format!("Update complete."));
                        }
                        Err(e) => {
                            ui.set_state(slint::format!("error"));
                            ui.set_status_text(slint::format!("Update failed: {}", e));
                        }
                    }
                }
            }).unwrap();
        });
    });

    let ui_handle = ui.as_weak();
    let state_clone = state.clone();
    ui.on_repair_clicked(move || {
        let ui = ui_handle.upgrade().unwrap();
        ui.set_is_working(true);
        ui.set_status_text(slint::format!("Repairing AEOPIN..."));

        let ui_weak = ui_handle.clone();
        let state = state_clone.clone();
        thread::spawn(move || {
            let res = (|| -> io::Result<()> {
                let meta = fetch_metadata()?;
                let bytes = load_verified_package(&meta, ui_weak.clone())?;

                let mut s = state.lock().unwrap();
                s.stop_app();
                s.stop_existing_app()?;
                s.migrate_legacy_install()?;
                s.install_from_zip_bytes(&bytes)?;
                s.install_authority_entry_point()?;
                s.install_shortcut()?;
                s.save_installed_version(&meta.version)?;
                s.current_version = meta.version.clone();
                let _child = s.launch()?;
                Ok(())
            })();

            slint::invoke_from_event_loop(move || {
                if let Some(ui) = ui_weak.upgrade() {
                    ui.set_is_working(false);
                    match res {
                        Ok(_) => {
                            let installed_version = state.lock()
                                .map(|state| state.current_version.clone())
                                .unwrap_or_else(|_| CURRENT_VERSION.to_string());
                            ui.set_app_version(slint::format!("{}", installed_version));
                            ui.set_state(slint::format!("installed"));
                            ui.set_status_text(slint::format!("Repair complete."));
                        }
                        Err(e) => {
                            if let Ok(mut s) = state.lock() {
                                s.last_error = Some(e.to_string());
                            }
                            ui.set_state(slint::format!("error"));
                            ui.set_status_text(slint::format!("Repair failed: {}", e));
                        }
                    }
                }
            }).unwrap();
        });
    });

    let ui_handle = ui.as_weak();
    ui.on_settings_clicked(move || {
        let ui = ui_handle.upgrade().unwrap();
        ui.set_state(slint::format!("settings"));
    });

    let ui_handle = ui.as_weak();
    let state_clone = state.clone();
    ui.on_back_clicked(move || {
        let ui = ui_handle.upgrade().unwrap();
        let s = state_clone.lock().unwrap();
        if s.is_installed() {
            ui.set_state(slint::format!("installed"));
        } else {
            ui.set_state(slint::format!("not_installed"));
        }
    });

    let ui_handle = ui.as_weak();
    let state_clone = state.clone();
    ui.on_save_settings_clicked(move |hotkey| {
        let ui = ui_handle.upgrade().unwrap();
        let mut s = state_clone.lock().unwrap();
        s.settings.hotkey = hotkey.to_string();
        let _ = s.save_settings();
        ui.set_hotkey(hotkey);
        if s.is_installed() {
            ui.set_state(slint::format!("installed"));
        } else {
            ui.set_state(slint::format!("not_installed"));
        }
    });

    let state_clone = state.clone();
    ui.on_copy_error_report_clicked(move || {
        let s = state_clone.lock().unwrap();
        let report = s.generate_error_report();
        println!("Error Report:\n{}", report);
    });

    let ui_handle = ui.as_weak();
    ui.on_support_clicked(move || {
        let _ = open_url(SUPPORT_URL);
        if let Some(ui) = ui_handle.upgrade() {
            ui.set_status_text(slint::format!("Support opened: {}", SUPPORT_URL));
        }
    });

    let ui_handle = ui.as_weak();
    ui.on_install_instructions_clicked(move || {
        let _ = open_url(INSTALL_URL);
        if let Some(ui) = ui_handle.upgrade() {
            ui.set_status_text(slint::format!("Install instructions opened."));
        }
    });

    let run_res = ui.run();

    unsafe {
        ReleaseMutex(handle).unwrap();
        CloseHandle(handle).unwrap();
    }

    run_res
}

#[cfg(test)]
mod tests {
    use super::AuthorityState;

    #[test]
    fn compares_semantic_versions_without_downgrading() {
        assert!(AuthorityState::is_newer_version("1.2.0", "1.1.0"));
        assert!(AuthorityState::is_newer_version("v1.10.0", "1.2.0"));
        assert!(!AuthorityState::is_newer_version("1.2.0", "1.2.0"));
        assert!(!AuthorityState::is_newer_version("1.1.9", "1.2.0"));
    }
}
