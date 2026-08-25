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

const METADATA_URL: &str = "https://raw.githubusercontent.com/Aeowun/Aeopin_v0.1.5/main/versions.json";

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
        let app_dir = env::current_exe()
            .expect("Failed to get current executable path")
            .parent()
            .expect("Failed to get parent directory")
            .to_path_buf();

        let settings_file = app_dir.join("authority_settings.json");
        let settings = if settings_file.exists() {
            let data = fs::read_to_string(&settings_file).unwrap_or_default();
            serde_json::from_str(&data).unwrap_or(AuthoritySettings { hotkey: "Alt+Shift+V".to_string() })
        } else {
            AuthoritySettings { hotkey: "Alt+Shift+V".to_string() }
        };

        Self {
            bin_dir: app_dir.join("bin"),
            data_dir: app_dir.join("data"),
            logs_dir: app_dir.join("logs"),
            staging_dir: app_dir.join("staging"),
            settings_file,
            child_process: None,
            current_version: "1.6.0".to_string(),
            settings,
            last_error: None,
        }
    }

    fn save_settings(&self) -> io::Result<()> {
        let data = serde_json::to_string_pretty(&self.settings)?;
        fs::write(&self.settings_file, data)?;
        Ok(())
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

                s.install_from_zip_bytes(&bytes)?;
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

                if meta.version == current {
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
                s.install_from_zip_bytes(&bytes)?;
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
                s.install_from_zip_bytes(&bytes)?;
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
