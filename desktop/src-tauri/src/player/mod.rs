mod ffi;
pub mod service;
pub mod session;
pub mod surface;

use crate::models::AppError;
use std::{
    ffi::{CStr, CString, c_void},
    path::{Path, PathBuf},
    ptr::NonNull,
};

pub struct NativePlayer {
    api: ffi::Api,
    handle: Option<NonNull<c_void>>,
}

// SAFETY: mpv client functions are thread-safe. This adapter is always accessed
// through one owner/mutex; wait_event data is copied before the next call.
unsafe impl Send for NativePlayer {}

fn source_string(value: &str) -> Result<CString, AppError> {
    CString::new(value).map_err(|_| AppError::player("Invalid media source."))
}

/// Resolves the bundled pinned libmpv runtime library independently of the current
/// working directory.
pub fn resolve_runtime_path(resource_dir: Option<&Path>) -> PathBuf {
    #[cfg(windows)]
    let lib_name = "libmpv-2.dll";
    #[cfg(target_os = "linux")]
    let lib_name = "libmpv.so.2";
    #[cfg(all(not(windows), not(target_os = "linux")))]
    let lib_name = "libmpv.dylib";

    let mut candidates: Vec<PathBuf> = Vec::new();
    if let Some(res) = resource_dir {
        candidates.push(res.join("resources").join("mpv").join(lib_name));
        candidates.push(res.join("mpv").join(lib_name));
        candidates.push(res.join(lib_name));
    }
    if let Ok(exe) = std::env::current_exe() {
        if let Some(dir) = exe.parent() {
            candidates.push(dir.join("resources").join("mpv").join(lib_name));
            candidates.push(dir.join("mpv").join(lib_name));
            candidates.push(dir.join(lib_name));
        }
    }
    candidates.push(
        Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("resources")
            .join("mpv")
            .join(lib_name),
    );

    for path in &candidates {
        if path.exists() {
            return path.clone();
        }
    }
    candidates
        .into_iter()
        .next()
        .unwrap_or_else(|| PathBuf::from(lib_name))
}

impl NativePlayer {
    pub fn open(path: &Path, window_id: u64) -> Result<Self, AppError> {
        // SAFETY: only explicit app runtime paths are accepted, never provider data.
        let api = unsafe { ffi::Api::load(path) }.map_err(|_| {
            AppError::player(
                "Player runtime unavailable. Run the mpv setup script or reinstall Marquee.",
            )
        })?;
        let handle = NonNull::new(unsafe { (api.create)() })
            .ok_or_else(|| AppError::player("Unable to allocate player runtime."))?;
        let player = Self {
            api,
            handle: Some(handle),
        };
        // No user mpv config, scripts, keyboard handling, or credential-bearing logs.
        for (key, value) in [
            ("config", "no"),
            ("load-scripts", "no"),
            ("terminal", "no"),
            ("msg-level", "all=no"),
            ("osc", "no"),
            ("input-default-bindings", "no"),
            ("input-vo-keyboard", "no"),
            ("idle", "yes"),
            ("keep-open", "no"),
        ] {
            player.option(key, value)?;
        }
        if window_id != 0 {
            player.option("wid", &window_id.to_string())?;
        }
        if unsafe { (player.api.initialize)(handle.as_ptr()) } < 0 {
            return Err(AppError::player(
                "Player initialization failed. Check display and audio drivers.",
            ));
        }
        Ok(player)
    }

    fn ptr(&self) -> Result<*mut c_void, AppError> {
        self.handle
            .map(NonNull::as_ptr)
            .ok_or_else(|| AppError::player("Player is stopped."))
    }

    fn option(&self, key: &str, value: &str) -> Result<(), AppError> {
        let key = source_string(key)?;
        let value = source_string(value)?;
        let result =
            unsafe { (self.api.set_option_string)(self.ptr()?, key.as_ptr(), value.as_ptr()) };
        if result < 0 {
            Err(AppError::player(
                "Player runtime does not support the required options.",
            ))
        } else {
            Ok(())
        }
    }

    pub fn command(&self, args: &[&str]) -> Result<(), AppError> {
        let args: Vec<CString> = args
            .iter()
            .map(|s| source_string(s))
            .collect::<Result<_, _>>()?;
        let mut pointers: Vec<_> = args.iter().map(|s| s.as_ptr()).collect();
        pointers.push(std::ptr::null());
        if unsafe { (self.api.command)(self.ptr()?, pointers.as_ptr()) } < 0 {
            Err(AppError::player(
                "The player could not perform that action.",
            ))
        } else {
            Ok(())
        }
    }

    pub fn load(&self, source: &str, start_ms: u64) -> Result<(), AppError> {
        if start_ms > 0 {
            self.command(&[
                "loadfile",
                source,
                "replace",
                "-1",
                &format!("start={:.3}", start_ms as f64 / 1000.0),
            ])
        } else {
            self.command(&["loadfile", source, "replace"])
        }
    }

    pub fn property(&self, key: &str) -> Option<String> {
        let key = source_string(key).ok()?;
        let handle = self.ptr().ok()?;
        let ptr = unsafe { (self.api.get_property_string)(handle, key.as_ptr()) };
        if ptr.is_null() {
            return None;
        }
        let value = unsafe { CStr::from_ptr(ptr) }
            .to_string_lossy()
            .into_owned();
        unsafe { (self.api.free)(ptr.cast()) };
        Some(value)
    }

    /// End reason 0 = natural EOF, 2 = stop, 3 = quit, 4 = error, 5 = redirect.
    pub fn poll_end_reason(&self) -> Option<i32> {
        let handle = self.ptr().ok()?;
        loop {
            let event = unsafe { (self.api.wait_event)(handle, 0.0).as_ref() }?;
            if event.id == 0 {
                return None;
            }
            if event.id == 7 && !event.data.is_null() {
                return Some(unsafe { (*(event.data.cast::<ffi::EndFile>())).reason });
            }
        }
    }

    pub fn shutdown(&mut self) {
        if let Some(handle) = self.handle.take() {
            unsafe { (self.api.terminate_destroy)(handle.as_ptr()) };
        }
    }
}

impl Drop for NativePlayer {
    fn drop(&mut self) {
        self.shutdown();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn missing_runtime_has_actionable_sanitized_error() {
        let result = NativePlayer::open(std::path::Path::new("Z:/missing/mpv.dll"), 0);
        let error = result.err().expect("missing runtime must fail");
        assert_eq!(error.code, "player");
        assert!(error.message.contains("runtime"));
        assert!(!error.message.contains("Z:/"));
    }

    #[test]
    fn invalid_source_does_not_enter_native_code() {
        assert!(source_string("file\0secret").is_err());
    }

    #[test]
    #[ignore = "requires pinned native mpv runtime; run explicitly on a native host"]
    fn pinned_runtime_initializes_and_shutdown_is_idempotent() {
        let path = Path::new(env!("CARGO_MANIFEST_DIR")).join("resources/mpv/libmpv-2.dll");
        let mut player = NativePlayer::open(&path, 0).unwrap();
        assert!(player.property("mpv-version").unwrap().contains("0.42.0"));
        player.command(&["stop"]).unwrap();
        player.command(&["stop"]).unwrap();
        assert!(player.poll_end_reason().is_none());
        player.shutdown();
        player.shutdown();
        assert!(player.command(&["stop"]).is_err());
    }
}
