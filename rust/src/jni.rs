//! JNI entry points for `app.moonmobile.core.Native`.
//!
//! Strings in, strings out: every method takes the JSON the crate's own functions take and
//! returns their JSON. A panic inside the bridge is caught here and turned into an error reply
//! — unwinding into the JVM would abort the whole app.

use std::panic::{catch_unwind, AssertUnwindSafe};

use jni::objects::{JByteBuffer, JClass, JString};
use jni::sys::{jboolean, jlong, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;

use crate::api::ApiError;

/// Run `work` on the Java string `input` and hand its reply back as a Java string.
fn bridge(mut env: JNIEnv, input: JString, work: impl FnOnce(&str) -> String) -> jstring {
    let reply = match env.get_string(&input) {
        Ok(text) => {
            let text: String = text.into();
            guarded(|| work(&text))
        }
        Err(e) => error_reply("bad_request", format!("argument is not a string: {e}")),
    };
    to_java(&mut env, &reply)
}

/// Run `work`, turning a panic into an error reply.
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

/// A Java string of `text`, or null when the JVM cannot allocate it (an exception is then
/// already pending on the Java side).
fn to_java(env: &mut JNIEnv, text: &str) -> jstring {
    match env.new_string(text) {
        Ok(string) => string.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_app_moonmobile_core_Native_open<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    request: JString<'local>,
) -> jstring {
    bridge(env, request, crate::open)
}

#[no_mangle]
pub extern "system" fn Java_app_moonmobile_core_Native_call<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    request: JString<'local>,
) -> jstring {
    bridge(env, request, crate::call)
}

#[no_mangle]
pub extern "system" fn Java_app_moonmobile_core_Native_tool<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    request: JString<'local>,
) -> jstring {
    bridge(env, request, crate::tool)
}

/// Draws one chart frame into `buffer`, a DIRECT `ByteBuffer` of `width * height * 4` bytes that
/// the app copies into a bitmap. The buffer must stay untouched by the app until this returns.
#[no_mangle]
pub extern "system" fn Java_app_moonmobile_core_Native_chart<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    request: JString<'local>,
    buffer: JByteBuffer<'local>,
) -> jstring {
    let reply = match env.get_string(&request) {
        Err(e) => error_reply("bad_request", format!("argument is not a string: {e}")),
        Ok(text) => {
            let text: String = text.into();
            let memory = env
                .get_direct_buffer_address(&buffer)
                .and_then(|address| Ok((address, env.get_direct_buffer_capacity(&buffer)?)));
            match memory {
                Ok((address, capacity)) if !address.is_null() && capacity > 0 => {
                    // SAFETY: a direct buffer's memory lives as long as the Java object, which
                    // the caller holds for the whole call; `capacity` is its full length and
                    // nothing else reads or writes it while the frame is drawn.
                    let pixels = unsafe { std::slice::from_raw_parts_mut(address, capacity) };
                    guarded(|| crate::chart(&text, pixels))
                }
                _ => error_reply("bad_request", "the buffer is not a direct ByteBuffer".to_string()),
            }
        }
    };
    to_java(&mut env, &reply)
}

#[no_mangle]
pub extern "system" fn Java_app_moonmobile_core_Native_snapshot<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    since: jlong,
) -> jstring {
    let reply = guarded(|| crate::snapshot(since));
    to_java(&mut env, &reply)
}

#[no_mangle]
pub extern "system" fn Java_app_moonmobile_core_Native_close<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
) {
    let _ = catch_unwind(crate::close);
}

#[no_mangle]
pub extern "system" fn Java_app_moonmobile_core_Native_isOpen<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jboolean {
    if catch_unwind(crate::is_open).unwrap_or(false) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[no_mangle]
pub extern "system" fn Java_app_moonmobile_core_Native_version<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jstring {
    let reply = guarded(|| crate::version().to_string());
    to_java(&mut env, &reply)
}
