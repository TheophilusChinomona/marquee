//! Platform child surface. Its owner is the Tauri UI thread; mpv is destroyed
//! before the parent window, and never receives frontend-supplied native handles.
use crate::models::AppError;

#[cfg(windows)]
pub fn create(window: &tauri::WebviewWindow) -> Result<u64, AppError> {
    use windows_sys::Win32::{System::LibraryLoader::GetModuleHandleW, UI::WindowsAndMessaging::*};
    let parent = window
        .hwnd()
        .map_err(|_| AppError::player("Unable to create video surface."))?;
    let class: Vec<u16> = "STATIC\0".encode_utf16().collect();
    // SAFETY: called on Tauri's UI thread with a live parent HWND. STATIC is a
    // system class. No pointers to Rust storage are retained by the window.
    let child = unsafe {
        CreateWindowExW(
            0,
            class.as_ptr(),
            std::ptr::null(),
            WS_CHILD | WS_VISIBLE | WS_CLIPSIBLINGS,
            0,
            80,
            1280,
            560,
            parent.0.cast(),
            std::ptr::null_mut(),
            GetModuleHandleW(std::ptr::null()),
            std::ptr::null(),
        )
    };
    if child.is_null() {
        return Err(AppError::player("Unable to create video surface."));
    }
    Ok(child as u64)
}

#[cfg(windows)]
pub fn resize(window: &tauri::WebviewWindow, surface: u64) {
    use windows_sys::Win32::UI::WindowsAndMessaging::*;
    if let (Ok(size), Ok(scale)) = (window.inner_size(), window.scale_factor()) {
        let top = (80.0 * scale).round() as i32;
        let bottom = (180.0 * scale).round() as i32;
        // SAFETY: child HWND was created on this same UI thread and remains live
        // for the parent's lifetime. Position leaves React header/controls visible.
        unsafe {
            SetWindowPos(
                surface as _,
                HWND_TOP,
                0,
                top,
                size.width as i32,
                (size.height as i32 - top - bottom).max(1),
                SWP_NOACTIVATE,
            );
        }
    }
}

#[cfg(not(windows))]
pub fn create(_: &tauri::WebviewWindow) -> Result<u64, AppError> {
    Err(AppError::player(
        "Native video surface is not yet validated on this platform.",
    ))
}
#[cfg(not(windows))]
pub fn resize(_: &tauri::WebviewWindow, _: u64) {}
