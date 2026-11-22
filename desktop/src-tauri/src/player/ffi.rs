//! Minimal dynamically loaded mpv client ABI (mpv 0.42, client API 2).
//! The library outlives every function pointer, handle, and event borrow.
use libloading::Library;
use std::ffi::{c_char, c_int, c_void};

#[repr(C)]
pub struct Event {
    pub id: c_int,
    pub error: c_int,
    pub userdata: u64,
    pub data: *mut c_void,
}
#[repr(C)]
pub struct EndFile {
    pub reason: c_int,
    pub error: c_int,
    pub playlist_entry_id: i64,
    pub playlist_insert_id: i64,
    pub playlist_insert_num_entries: c_int,
}

pub struct Api {
    pub create: unsafe extern "C" fn() -> *mut c_void,
    pub initialize: unsafe extern "C" fn(*mut c_void) -> c_int,
    pub terminate_destroy: unsafe extern "C" fn(*mut c_void),
    pub set_option_string: unsafe extern "C" fn(*mut c_void, *const c_char, *const c_char) -> c_int,
    pub command: unsafe extern "C" fn(*mut c_void, *const *const c_char) -> c_int,
    pub get_property_string: unsafe extern "C" fn(*mut c_void, *const c_char) -> *mut c_char,
    pub free: unsafe extern "C" fn(*mut c_void),
    pub wait_event: unsafe extern "C" fn(*mut c_void, f64) -> *const Event,
    _library: Library,
}
impl Api {
    /// Caller supplies a trusted, app-owned absolute library path.
    pub unsafe fn load(path: &std::path::Path) -> Result<Self, libloading::Error> {
        // SAFETY: all symbols use the published mpv client.h ABI; Library remains owned.
        unsafe {
            let library = Library::new(path)?;
            Ok(Self {
                create: *library.get(b"mpv_create\0")?,
                initialize: *library.get(b"mpv_initialize\0")?,
                terminate_destroy: *library.get(b"mpv_terminate_destroy\0")?,
                set_option_string: *library.get(b"mpv_set_option_string\0")?,
                command: *library.get(b"mpv_command\0")?,
                get_property_string: *library.get(b"mpv_get_property_string\0")?,
                free: *library.get(b"mpv_free\0")?,
                wait_event: *library.get(b"mpv_wait_event\0")?,
                _library: library,
            })
        }
    }
}
