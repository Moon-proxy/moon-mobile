//! C entry points: what the iOS app calls (from Swift today, from Kotlin/Native later).
//!
//! The same eight functions as `jni.rs`, with C strings: every function takes the JSON the
//! crate's own functions take and returns their JSON. A reply is a string the bridge allocated;
//! the caller reads it and hands it back to [`mm_free`]. A panic inside the bridge is caught here
//! and turned into an error reply — unwinding into the host would abort the whole app.
//!
//! The declarations the host compiles against are in `rust/include/moonmobile.h`.

use std::ffi::{c_char, CStr, CString};
use std::panic::{catch_unwind, AssertUnwindSafe};

use crate::api::ApiError;

/// Run `work`, turning a panic into an error reply. The twin of the helper in `jni.rs`, which
/// stays as it is so the Android library does not change.
fn guarded(work: impl FnOnce() -> String) -> String {
    match catch_unwind(AssertUnwindSafe(work)) {
        Ok(reply) => reply,
        Err(payload) => {
            let detail = payload
                .downcast_ref::<&str>()
                .map(|s| (*s).to_string())
                .or_else(|| payload.downcast_ref::<String>().cloned())
                .unwrap_or_else(|| "panic".to_string());
            log::error!("bridge panic: {detail}");
            error_reply("internal", detail)
        }
    }
}

fn error_reply(code: &'static str, message: String) -> String {
    ApiError::new(code, message).to_json().to_string()
}

/// `reply` as a C string the caller frees with [`mm_free`]. JSON text never holds a NUL byte
/// (serde writes it as `\u0000`); should one appear anyway it is dropped rather than cutting
/// the reply short.
fn to_c(reply: String) -> *mut c_char {
    let text = CString::new(reply).unwrap_or_else(|e| {
        let mut bytes = e.into_vec();
        bytes.retain(|&b| b != 0);
        CString::new(bytes).unwrap_or_default()
    });
    text.into_raw()
}

/// The UTF-8 text behind `input`.
///
/// # Safety
/// `input` is null or points to a NUL-terminated string that stays alive for the whole call.
unsafe fn text<'a>(input: *const c_char) -> Result<&'a str, String> {
    if input.is_null() {
        return Err("argument is null".to_string());
    }
    CStr::from_ptr(input)
        .to_str()
        .map_err(|e| format!("argument is not UTF-8: {e}"))
}

/// Run `work` on the C string `input` and hand its reply back as a C string.
///
/// # Safety
/// As for [`text`].
unsafe fn bridge(input: *const c_char, work: impl FnOnce(&str) -> String) -> *mut c_char {
    let reply = match text(input) {
        Ok(request) => guarded(|| work(request)),
        Err(message) => error_reply("bad_request", message),
    };
    to_c(reply)
}

/// [`crate::open`]. Blocks for about a second (key derivation): call it off the UI thread.
///
/// # Safety
/// `request` is a NUL-terminated UTF-8 string alive for the whole call.
#[no_mangle]
pub unsafe extern "C" fn mm_open(request: *const c_char) -> *mut c_char {
    bridge(request, crate::open)
}

/// [`crate::call`].
///
/// # Safety
/// As for [`mm_open`].
#[no_mangle]
pub unsafe extern "C" fn mm_call(request: *const c_char) -> *mut c_char {
    bridge(request, crate::call)
}

/// [`crate::tool`].
///
/// # Safety
/// As for [`mm_open`].
#[no_mangle]
pub unsafe extern "C" fn mm_tool(request: *const c_char) -> *mut c_char {
    bridge(request, crate::tool)
}

/// [`crate::chart`]: draws one frame into `pixels`, `len` bytes of premultiplied RGBA
/// (`width * height * 4`). Asking for frames is also what keeps the chart's market subscribed.
///
/// # Safety
/// `request` as for [`mm_open`]; `pixels` points to `len` writable bytes that nothing else
/// reads or writes until this returns.
#[no_mangle]
pub unsafe extern "C" fn mm_chart(request: *const c_char, pixels: *mut u8, len: usize) -> *mut c_char {
    let reply = match text(request) {
        Err(message) => error_reply("bad_request", message),
        Ok(_) if pixels.is_null() || len == 0 => {
            error_reply("bad_request", "the pixel buffer is empty".to_string())
        }
        Ok(request) => {
            // SAFETY: the caller owns `len` bytes at `pixels` for the whole call (see above).
            let pixels = std::slice::from_raw_parts_mut(pixels, len);
            guarded(|| crate::chart(request, pixels))
        }
    };
    to_c(reply)
}

/// [`crate::snapshot`].
#[no_mangle]
pub extern "C" fn mm_snapshot(since: i64) -> *mut c_char {
    to_c(guarded(|| crate::snapshot(since)))
}

/// [`crate::close`]: stops the engine and every core connection.
#[no_mangle]
pub extern "C" fn mm_close() {
    let _ = catch_unwind(crate::close);
}

/// [`crate::is_open`].
#[no_mangle]
pub extern "C" fn mm_is_open() -> bool {
    catch_unwind(crate::is_open).unwrap_or(false)
}

/// [`crate::version`].
#[no_mangle]
pub extern "C" fn mm_version() -> *mut c_char {
    to_c(guarded(|| crate::version().to_string()))
}

/// Free a reply of any function above. Null is ignored.
///
/// # Safety
/// `text` is null or a reply from this bridge, not freed before.
#[no_mangle]
pub unsafe extern "C" fn mm_free(text: *mut c_char) {
    if !text.is_null() {
        drop(CString::from_raw(text));
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    unsafe fn read(reply: *mut c_char) -> String {
        let text = CStr::from_ptr(reply).to_str().unwrap().to_string();
        mm_free(reply);
        text
    }

    #[test]
    fn version_round_trips() {
        unsafe { assert_eq!(read(mm_version()), crate::version()) };
    }

    #[test]
    fn null_and_bad_input_are_error_replies() {
        unsafe {
            assert!(read(mm_open(std::ptr::null())).contains("bad_request"));
            let bad = CString::new("not json").unwrap();
            assert!(read(mm_call(bad.as_ptr())).contains("bad_request"));
            let request = CString::new("{}").unwrap();
            assert!(read(mm_chart(request.as_ptr(), std::ptr::null_mut(), 0)).contains("bad_request"));
        }
    }

    #[test]
    fn closed_engine_says_so() {
        assert!(!mm_is_open());
        unsafe { assert!(read(mm_snapshot(-1)).contains("closed")) };
        mm_close();
    }

    #[test]
    fn nul_in_reply_is_dropped() {
        unsafe { assert_eq!(read(to_c("a\0b".to_string())), "ab") };
        unsafe { mm_free(std::ptr::null_mut()) };
    }
}
