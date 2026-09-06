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
const CURRENT_VERSION: &str = "1.2.0";

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
        fs::write(version_file, version)
    }

    fn is_installed(&self) -> bool {
        self.bin_dir.exists() && self.bin_dir.join("AEOPIN.exe").exists()
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
        let legacy_root = current_exe.parent().unwrap_or(Path::new("."));
        if legacy_root == self.bin_dir.parent().unwrap_or(Path::new(".")) {
            return Ok(());
        }
        let legacy_bin = legacy_root.join("bin");
        let legacy_data = legacy_root.join("data");
        if legacy_bin.exists() && !self.bin_dir.exists() {
            self.ensure_dirs()?;
            fs::rename(&legacy_bin, &self.bin_dir)?;
        }
        if legacy_data.exists() && !self.data_dir.exists() {
            self.ensure_dirs()?;
            fs::rename(&legacy_data, &self.data_dir)?;
        }
        Ok(())
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

        let bin_old = self.bin_dir.with_extension("old");
        if self.bin_dir.exists() {
            if bin_old.exists() {
                fs::remove_dir_all(&bin_old)?;
            }
            fs::rename(&self.bin_dir, &bin_old)?;
        }

        fs::rename(&self.staging_dir, &self.bin_dir)?;

        if bin_old.exists() {
            let _ = fs::remove_dir_all(&bin_old);
        }

        Ok(())
    }

    fn launch(&mut self) -> io::Result<Child> {
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

fn fetch_metadata() -> io::Result<VersionMetadata> {
    let client = reqwest::blocking::Client::new();
    let response = client.get(METADATA_URL).send().map_err(|e| io::Error::new(io::ErrorKind::Other, e))?;
    if !response.status().is_success() {
        return Err(io::Error::new(io::ErrorKind::Other, format!("Failed to fetch metadata: {}", response.status())));
    }
    response.json().map_err(|e| io::Error::new(io::ErrorKind::Other, e))
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
                let s = state.lock().unwrap();
                let zip_path = Path::new("aeopin-portable.zip");

                let bytes = if zip_path.exists() {
                    slint::invoke_from_event_loop({
                        let ui_weak = ui_weak.clone();
                        move || { if let Some(ui) = ui_weak.upgrade() { ui.set_status_text(slint::format!("Using local package...")); } }
                    }).unwrap();
                    fs::read(zip_path)?
                } else {
                    slint::invoke_from_event_loop({
                        let ui_weak = ui_weak.clone();
                        move || { if let Some(ui) = ui_weak.upgrade() { ui.set_status_text(slint::format!("Fetching metadata...")); } }
                    }).unwrap();

                    let meta = fetch_metadata()?;

                    slint::invoke_from_event_loop({
                        let ui_weak = ui_weak.clone();
                        move || { if let Some(ui) = ui_weak.upgrade() { ui.set_status_text(slint::format!("Downloading...")); } }
                    }).unwrap();

                    let data = download_with_progress(&meta.url, ui_weak.clone())?;

                    slint::invoke_from_event_loop({
                        let ui_weak = ui_weak.clone();
                        move || { if let Some(ui) = ui_weak.upgrade() { ui.set_status_text(slint::format!("Verifying...")); } }
                    }).unwrap();

                    if !AuthorityState::verify_sha256(&data, &meta.sha256) {
                        return Err(io::Error::new(io::ErrorKind::InvalidData, "SHA-256 verification failed"));
                    }
                    data
                };

                slint::invoke_from_event_loop({
                    let ui_weak = ui_weak.clone();
                    move || { if let Some(ui) = ui_weak.upgrade() { ui.set_status_text(slint::format!("Extracting...")); ui.set_progress(0.99); } }
                }).unwrap();

                s.migrate_legacy_install()?;
                s.install_from_zip_bytes(&bytes)?;
                s.install_authority_entry_point()?;
                s.install_shortcut()?;
                s.save_installed_version(CURRENT_VERSION)?;
                Ok(())
            })();

            slint::invoke_from_event_loop(move || {
                if let Some(ui) = ui_weak.upgrade() {
                    ui.set_is_working(false);
                    match res {
                        Ok(_) => {
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
        let mut s = state_clone.lock().unwrap();

        if s.child_process.is_some() {
            return;
        }

        match s.launch() {
            Ok(child) => {
                let child_arc = Arc::new(Mutex::new(child));
                s.child_process = Some(child_arc.clone());
                ui.set_state(slint::format!("running"));
                ui.set_status_text(slint::format!("AEOPIN is running."));

                let ui_weak = ui_handle.clone();
                let state_mon = state_clone.clone();
                thread::spawn(move || {
                    let start_time = std::time::Instant::now();
                    let wait_res = {
                        let mut child = child_arc.lock().unwrap();
                        child.wait()
                    };
                    let duration = start_time.elapsed();

                    let mut s = state_mon.lock().unwrap();
                    s.child_process = None;

                    let is_short_lived = duration < std::time::Duration::from_secs(5);
                    let last_err = match &wait_res {
                        Ok(status) if !status.success() || is_short_lived => {
                            if is_short_lived {
                                Some(format!("Application crashed on startup ({}s). Check logs.", duration.as_secs()))
                            } else {
                                Some(format!("Exit status: {}", status))
                            }
                        },
                        Err(e) => Some(e.to_string()),
                        _ if is_short_lived => Some(format!("Application closed unexpectedly after {}s.", duration.as_secs())),
                        _ => None,
                    };
                    if let Some(err) = last_err.clone() {
                        s.last_error = Some(err);
                    }

                    slint::invoke_from_event_loop(move || {
                        if let Some(ui) = ui_weak.upgrade() {
                            match wait_res {
                                Ok(status) if status.success() && !is_short_lived => {
                                    ui.set_state(slint::format!("installed"));
                                    ui.set_status_text(slint::format!("AEOPIN exited normally."));
                                }
                                _ => {
                                    ui.set_state(slint::format!("error"));
                                    if is_short_lived {
                                        ui.set_status_text(slint::format!("AEOPIN crashed on startup."));
                                    } else {
                                        ui.set_status_text(slint::format!("AEOPIN stopped unexpectedly."));
                                    }
                                }
                            }
                        }
                    }).unwrap();
                });
            }
            Err(e) => {
                s.last_error = Some(e.to_string());
                ui.set_state(slint::format!("error"));
                ui.set_status_text(slint::format!("Failed to launch: {}", e));
            }
        }
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

                let bytes = download_with_progress(&meta.url, ui_weak.clone())?;

                slint::invoke_from_event_loop({
                    let ui_weak = ui_weak.clone();
                    move || { if let Some(ui) = ui_weak.upgrade() { ui.set_status_text(slint::format!("Verifying...")); } }
                }).unwrap();

                if !AuthorityState::verify_sha256(&bytes, &meta.sha256) {
                    return Err(io::Error::new(io::ErrorKind::InvalidData, "SHA-256 verification failed"));
                }

                let mut s = state.lock().unwrap();
                s.stop_app();
                s.migrate_legacy_install()?;
                s.install_from_zip_bytes(&bytes)?;
                s.install_authority_entry_point()?;
                s.install_shortcut()?;
                s.save_installed_version(&meta.version)?;
                s.current_version = meta.version.clone();
                Ok(())
            })();

            slint::invoke_from_event_loop(move || {
                if let Some(ui) = ui_weak.upgrade() {
                    ui.set_is_working(false);
                    match res {
                        Ok(_) => {
                            ui.set_state(slint::format!("installed"));
                            ui.set_status_text(slint::format!("Update complete."));
                        }
                        Err(e) => {
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
                let zip_path = Path::new("aeopin-portable.zip");
                let bytes = if zip_path.exists() {
                    fs::read(zip_path)?
                } else {
                    let meta = fetch_metadata()?;
                    let data = download_with_progress(&meta.url, ui_weak.clone())?;
                    if !AuthorityState::verify_sha256(&data, &meta.sha256) {
                        return Err(io::Error::new(io::ErrorKind::InvalidData, "SHA-256 verification failed"));
                    }
                    data
                };

                let mut s = state.lock().unwrap();
                s.stop_app();
                s.migrate_legacy_install()?;
                s.install_from_zip_bytes(&bytes)?;
                s.install_authority_entry_point()?;
                s.install_shortcut()?;
                s.save_installed_version(CURRENT_VERSION)?;
                Ok(())
            })();

            slint::invoke_from_event_loop(move || {
                if let Some(ui) = ui_weak.upgrade() {
                    ui.set_is_working(false);
                    match res {
                        Ok(_) => {
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
