//! Cryptographic identities: `SecretKey`, `EndpointId` and `Signature`.
//!
//! Owned by the keys domain. Contains the shared logic plus both facades' exports for it:
//! `#[no_mangle] extern "C"` for cinterop and `#[cfg(not(target_os = "ios"))] Java_*` for JNI.
//!
//! Unlike iroh-ffi — which makes all three opaque `Arc`-wrapped objects with their own FFI
//! lifetime — iroh4k keeps them as **pure Kotlin value types**. A key is 32 bytes and a
//! signature is 64; they own no resources and have no identity beyond their contents, so a
//! [`crate::handle`] would buy nothing and cost a free routine and a leak to worry about. Kotlin
//! holds the bytes and hands them back in on every call.
//!
//! What is left on this side is only what genuinely needs iroh:
//!
//! - **Key generation, public-key derivation, signing and verification.** The Ed25519 primitives
//!   themselves; there is nothing to reimplement in Kotlin and everything to get wrong.
//! - **Textual coding.** `Display`/`FromStr` (lowercase hex, and base32 also accepted on the way
//!   in) plus the z-base-32 form iroh uses for endpoint ids in DNS names. A Kotlin
//!   reimplementation would have to agree bit-for-bit with iroh, and would silently disagree the
//!   day it did not.
//! - **Validation.** `EndpointId` needs a real curve-point check — not every 32-byte string
//!   decompresses to a point — and routing `SecretKey`/`Signature` through the same door means
//!   Kotlin never has to know which type happens to have extra rules today.
//!
//! Pure formatting stays in Kotlin: a signature's hex string involves no crypto and no iroh type,
//! so it does not earn a boundary crossing. `fmt_short` does cross, because iroh defines what
//! "short" means and should keep defining it.
//!
//! Every failure here is malformed caller input and is reported as [`ERROR_KEY`]. This crate
//! builds with `panic = "abort"`, so a panic on a bad length or a bad string would take the host
//! process down — nothing below may ever panic on what Kotlin passes in.

use std::{
    ffi::{c_char, c_int},
    slice,
};

use iroh::{EndpointId, SecretKey, Signature};
use std::str::FromStr;

use crate::core::{bytes_result, c_str, error_result, i64_result, Iroh4kResult, ERROR_KEY};

/// Raw byte lengths, fixed by Ed25519. Named so the error messages and the array conversions
/// cannot drift apart.
const SECRET_KEY_LEN: usize = 32;
const ENDPOINT_ID_LEN: usize = 32;
const SIGNATURE_LEN: usize = 64;

// ============================================================================
// Shared logic
// ============================================================================

/// Copies a caller buffer into a fixed-size array, reporting a length mismatch instead of
/// panicking — `bytes.try_into().unwrap()` on a short buffer would abort the process.
fn fixed<const N: usize>(what: &str, bytes: &[u8]) -> Result<[u8; N], String> {
    bytes.try_into().map_err(|_| {
        format!(
            "{what} must be exactly {N} bytes, got {len}",
            len = bytes.len()
        )
    })
}

/// Parses a secret key. Any 32 bytes are a valid Ed25519 scalar seed, so only the length can be
/// wrong — but the check lives here rather than in Kotlin so iroh stays the one authority on it.
fn parse_secret(bytes: &[u8]) -> Result<SecretKey, String> {
    Ok(SecretKey::from_bytes(&fixed::<SECRET_KEY_LEN>(
        "a secret key",
        bytes,
    )?))
}

/// Parses an endpoint id, rejecting 32-byte strings that are not a compressed Edwards point.
fn parse_endpoint_id(bytes: &[u8]) -> Result<EndpointId, String> {
    let bytes = fixed::<ENDPOINT_ID_LEN>("an endpoint id", bytes)?;
    EndpointId::from_bytes(&bytes).map_err(|error| format!("invalid endpoint id: {error}"))
}

/// Parses a signature. As with a secret key the only rule today is the length; verification is
/// what decides whether the signature is *correct*, and that is a separate question.
fn parse_signature(bytes: &[u8]) -> Result<Signature, String> {
    Ok(Signature::from_bytes(&fixed::<SIGNATURE_LEN>(
        "a signature",
        bytes,
    )?))
}

/// A freshly generated Ed25519 secret key's 32 raw bytes.
pub fn secret_generate() -> Vec<u8> {
    SecretKey::generate().to_bytes().to_vec()
}

/// Validates `bytes` as a secret key, returning iroh's own byte form of it.
pub fn secret_from_bytes(bytes: &[u8]) -> Result<Vec<u8>, String> {
    Ok(parse_secret(bytes)?.to_bytes().to_vec())
}

/// The endpoint id (public key) derived from `secret`. Deterministic for a given secret.
pub fn secret_public(secret: &[u8]) -> Result<Vec<u8>, String> {
    Ok(parse_secret(secret)?.public().as_bytes().to_vec())
}

/// Signs `message` with `secret`, returning the 64 signature bytes.
pub fn secret_sign(secret: &[u8], message: &[u8]) -> Result<Vec<u8>, String> {
    Ok(parse_secret(secret)?.sign(message).to_bytes().to_vec())
}

/// Validates `bytes` as an endpoint id, returning iroh's canonical byte form of it.
pub fn endpoint_id_from_bytes(bytes: &[u8]) -> Result<Vec<u8>, String> {
    Ok(parse_endpoint_id(bytes)?.as_bytes().to_vec())
}

/// Parses an endpoint id from its textual form, returning its 32 raw bytes.
///
/// Delegates to iroh's own `FromStr`, which accepts **hex or base32** — so anything iroh, iroh-ffi
/// or an iroh log line can produce is accepted here. Deliberately not restricted to one encoding:
/// an id copied out of iroh's output has to parse.
pub fn endpoint_id_from_string(text: &str) -> Result<Vec<u8>, String> {
    let id = EndpointId::from_str(text)
        .map_err(|error| format!("could not parse {text:?} as an endpoint id: {error}"))?;
    Ok(id.as_bytes().to_vec())
}

/// The textual form of `id`, as UTF-8 bytes.
///
/// This is iroh's `Display`, i.e. lowercase hex — *not* z-base-32. It has to be: iroh-ffi exports
/// iroh's `Display` for `EndpointId`, and its own tests pin
/// `523c7996bad77424e96786cf7a7205115337a5b4565cd25506a0f297b191a5ea`. Emitting a different
/// encoding would mean the same endpoint printed by iroh4k and by iroh did not match, and would
/// break ticket and address interop. Use [`endpoint_id_to_z32`] where the DNS form is wanted.
pub fn endpoint_id_to_string(id: &[u8]) -> Result<Vec<u8>, String> {
    Ok(parse_endpoint_id(id)?.to_string().into_bytes())
}

/// The z-base-32 form of `id`, as UTF-8 bytes.
///
/// The encoding iroh (via pkarr) uses for endpoint ids inside DNS names. Kept as an explicitly
/// named operation rather than as `Display`, so choosing it is always deliberate.
pub fn endpoint_id_to_z32(id: &[u8]) -> Result<Vec<u8>, String> {
    Ok(parse_endpoint_id(id)?.to_z32().into_bytes())
}

/// Parses an endpoint id from its z-base-32 form, returning its 32 raw bytes.
pub fn endpoint_id_from_z32(text: &str) -> Result<Vec<u8>, String> {
    let id = EndpointId::from_z32(text)
        .map_err(|error| format!("could not parse {text:?} as a z-base-32 endpoint id: {error}"))?;
    Ok(id.as_bytes().to_vec())
}

/// iroh's short form of `id` — the hex of its first five bytes — as UTF-8 bytes.
pub fn endpoint_id_fmt_short(id: &[u8]) -> Result<Vec<u8>, String> {
    Ok(parse_endpoint_id(id)?.fmt_short().to_string().into_bytes())
}

/// Whether `signature` is a valid signature over `message` by `id`.
///
/// A signature that simply does not verify is `Ok(false)`, not an error: it is the expected
/// answer to a legitimate question. Only input that cannot be parsed at all fails.
pub fn endpoint_id_verify(id: &[u8], message: &[u8], signature: &[u8]) -> Result<bool, String> {
    let id = parse_endpoint_id(id)?;
    let signature = parse_signature(signature)?;
    Ok(id.verify(message, &signature).is_ok())
}

/// Validates `bytes` as a signature, returning iroh's own byte form of it.
pub fn signature_from_bytes(bytes: &[u8]) -> Result<Vec<u8>, String> {
    Ok(parse_signature(bytes)?.to_bytes().to_vec())
}

// ============================================================================
// Result marshalling shared by both facades
// ============================================================================

/// Turns a keys-domain outcome into a result. Every `Err` here is malformed caller input, hence
/// the single [`ERROR_KEY`] code — Kotlin maps it to `IrohError.Code.Key`.
fn bytes_or_error(outcome: Result<Vec<u8>, String>) -> *mut Iroh4kResult {
    match outcome {
        Ok(bytes) => bytes_result(bytes),
        Err(message) => error_result(ERROR_KEY, message),
    }
}

/// As [`bytes_or_error`], for the one operation that answers with a boolean. `1` is true and `0`
/// is false, carried in `i64_val`.
fn bool_or_error(outcome: Result<bool, String>) -> *mut Iroh4kResult {
    match outcome {
        Ok(value) => i64_result(i64::from(value)),
        Err(message) => error_result(ERROR_KEY, message),
    }
}

// ============================================================================
// C ABI — Kotlin/Native via cinterop
// ============================================================================

/// Borrows a caller-owned buffer as a slice for the duration of one call.
///
/// Unlike [`crate::core::owned_bytes`] this does not copy. It can afford not to because every
/// export in this module is synchronous and finishes before returning, so the Kotlin `ByteArray`
/// pinned around the call outlives the borrow, and nothing here retains the slice past the call —
/// each operation copies whatever it keeps into the returned result.
///
/// # Safety
/// `ptr` must be null, or point to at least `len` initialised bytes that stay valid and unmutated
/// for the lifetime `'a`. Callers must not let `'a` outlive the call they borrowed for; every use
/// below passes the slice straight into an operation that returns before the export does.
unsafe fn borrowed<'a>(ptr: *const u8, len: c_int) -> &'a [u8] {
    if ptr.is_null() || len <= 0 {
        return &[];
    }
    slice::from_raw_parts(ptr, len as usize)
}

/// A freshly generated secret key's 32 raw bytes.
#[no_mangle]
pub extern "C" fn iroh4k_keys_secret_generate() -> *mut Iroh4kResult {
    bytes_result(secret_generate())
}

/// Validates a secret key, returning its 32 raw bytes. `ERROR_KEY` if the length is wrong.
///
/// # Safety
/// `secret`/`secret_len` must satisfy [`borrowed`]'s contract.
#[no_mangle]
pub unsafe extern "C" fn iroh4k_keys_secret_from_bytes(
    secret: *const u8,
    secret_len: c_int,
) -> *mut Iroh4kResult {
    bytes_or_error(secret_from_bytes(borrowed(secret, secret_len)))
}

/// The 32 raw bytes of the endpoint id derived from a secret key.
///
/// # Safety
/// `secret`/`secret_len` must satisfy [`borrowed`]'s contract.
#[no_mangle]
pub unsafe extern "C" fn iroh4k_keys_secret_public(
    secret: *const u8,
    secret_len: c_int,
) -> *mut Iroh4kResult {
    bytes_or_error(secret_public(borrowed(secret, secret_len)))
}

/// The 64 signature bytes for `message` under `secret`.
///
/// # Safety
/// Both buffers must satisfy [`borrowed`]'s contract. An empty message is passed as a null
/// pointer with length 0, which is explicitly allowed.
#[no_mangle]
pub unsafe extern "C" fn iroh4k_keys_secret_sign(
    secret: *const u8,
    secret_len: c_int,
    message: *const u8,
    message_len: c_int,
) -> *mut Iroh4kResult {
    bytes_or_error(secret_sign(
        borrowed(secret, secret_len),
        borrowed(message, message_len),
    ))
}

/// Validates an endpoint id, returning its canonical 32 raw bytes. `ERROR_KEY` if the length is
/// wrong or the bytes are not a point on the curve.
///
/// # Safety
/// `id`/`id_len` must satisfy [`borrowed`]'s contract.
#[no_mangle]
pub unsafe extern "C" fn iroh4k_keys_endpoint_id_from_bytes(
    id: *const u8,
    id_len: c_int,
) -> *mut Iroh4kResult {
    bytes_or_error(endpoint_id_from_bytes(borrowed(id, id_len)))
}

/// The 32 raw bytes of the endpoint id written as `text` (hex or base32, per iroh's `FromStr`).
///
/// # Safety
/// `text` must be null or point to a valid nul-terminated UTF-8 string, read only for the
/// duration of the call.
#[no_mangle]
pub unsafe extern "C" fn iroh4k_keys_endpoint_id_from_string(
    text: *const c_char,
) -> *mut Iroh4kResult {
    // `c_str` yields "" for a null or non-UTF-8 pointer, which then fails to parse as a key —
    // an `ERROR_KEY` rather than undefined behaviour or a panic.
    bytes_or_error(endpoint_id_from_string(c_str(text)))
}

/// The textual (hex) form of an endpoint id, as UTF-8 bytes — iroh's `Display`.
///
/// # Safety
/// `id`/`id_len` must satisfy [`borrowed`]'s contract.
#[no_mangle]
pub unsafe extern "C" fn iroh4k_keys_endpoint_id_to_string(
    id: *const u8,
    id_len: c_int,
) -> *mut Iroh4kResult {
    bytes_or_error(endpoint_id_to_string(borrowed(id, id_len)))
}

/// The z-base-32 (DNS) form of an endpoint id, as UTF-8 bytes.
///
/// # Safety
/// `id`/`id_len` must satisfy [`borrowed`]'s contract.
#[no_mangle]
pub unsafe extern "C" fn iroh4k_keys_endpoint_id_to_z32(
    id: *const u8,
    id_len: c_int,
) -> *mut Iroh4kResult {
    bytes_or_error(endpoint_id_to_z32(borrowed(id, id_len)))
}

/// The 32 raw bytes of the endpoint id written in z-base-32 as `text`.
///
/// # Safety
/// `text` must be null or point to a valid nul-terminated UTF-8 string, read only for the
/// duration of the call.
#[no_mangle]
pub unsafe extern "C" fn iroh4k_keys_endpoint_id_from_z32(
    text: *const c_char,
) -> *mut Iroh4kResult {
    bytes_or_error(endpoint_id_from_z32(c_str(text)))
}

/// iroh's short form of an endpoint id, as UTF-8 bytes.
///
/// # Safety
/// `id`/`id_len` must satisfy [`borrowed`]'s contract.
#[no_mangle]
pub unsafe extern "C" fn iroh4k_keys_endpoint_id_fmt_short(
    id: *const u8,
    id_len: c_int,
) -> *mut Iroh4kResult {
    bytes_or_error(endpoint_id_fmt_short(borrowed(id, id_len)))
}

/// `1` in `i64_val` if the signature verifies, `0` if it does not. `ERROR_KEY` only for input
/// that cannot be parsed.
///
/// # Safety
/// All three buffers must satisfy [`borrowed`]'s contract.
#[no_mangle]
pub unsafe extern "C" fn iroh4k_keys_endpoint_id_verify(
    id: *const u8,
    id_len: c_int,
    message: *const u8,
    message_len: c_int,
    signature: *const u8,
    signature_len: c_int,
) -> *mut Iroh4kResult {
    bool_or_error(endpoint_id_verify(
        borrowed(id, id_len),
        borrowed(message, message_len),
        borrowed(signature, signature_len),
    ))
}

/// Validates a signature, returning its 64 raw bytes. `ERROR_KEY` if the length is wrong.
///
/// # Safety
/// `signature`/`signature_len` must satisfy [`borrowed`]'s contract.
#[no_mangle]
pub unsafe extern "C" fn iroh4k_keys_signature_from_bytes(
    signature: *const u8,
    signature_len: c_int,
) -> *mut Iroh4kResult {
    bytes_or_error(signature_from_bytes(borrowed(signature, signature_len)))
}

// ============================================================================
// JNI — JVM and Android via the shared library
//
// Unavailable on iOS, which uses the FFI/cinterop path exclusively. Symbol names match the
// Kotlin object `tech.annexflow.iroh4k.KeysJni`.
// ============================================================================

#[cfg(not(target_os = "ios"))]
#[allow(non_snake_case)]
mod jni_facade {
    use super::*;
    use jni::objects::{JByteArray, JClass, JString};
    use jni::sys::jbyteArray;
    use jni::JNIEnv;

    // One shared envelope writer — see `crate::jni::finish`.
    use crate::jni::finish;

    /// Copies a Java `byte[]` into an owned `Vec`.
    ///
    /// The copy is what JNI offers without a critical region, and it is irrelevant here: the
    /// buffers are 32 or 64 bytes, and the one that is not — a message being signed or verified —
    /// is copied once per call rather than held.
    ///
    /// An unreadable or absent array becomes empty, which the length checks then reject as an
    /// `ERROR_KEY`. That is deliberate: the FFI boundary must never unwind, so a missing argument
    /// is reported like any other malformed one.
    fn arg(env: &mut JNIEnv, array: &JByteArray) -> Vec<u8> {
        env.convert_byte_array(array).unwrap_or_default()
    }

    #[no_mangle]
    pub extern "system" fn Java_tech_annexflow_iroh4k_KeysJni_secretGenerate(
        mut env: JNIEnv,
        _class: JClass,
    ) -> jbyteArray {
        finish(&mut env, bytes_result(secret_generate()))
    }

    #[no_mangle]
    pub extern "system" fn Java_tech_annexflow_iroh4k_KeysJni_secretFromBytes(
        mut env: JNIEnv,
        _class: JClass,
        secret: JByteArray,
    ) -> jbyteArray {
        let secret = arg(&mut env, &secret);
        let result = bytes_or_error(secret_from_bytes(&secret));
        finish(&mut env, result)
    }

    #[no_mangle]
    pub extern "system" fn Java_tech_annexflow_iroh4k_KeysJni_secretPublic(
        mut env: JNIEnv,
        _class: JClass,
        secret: JByteArray,
    ) -> jbyteArray {
        let secret = arg(&mut env, &secret);
        let result = bytes_or_error(secret_public(&secret));
        finish(&mut env, result)
    }

    #[no_mangle]
    pub extern "system" fn Java_tech_annexflow_iroh4k_KeysJni_secretSign(
        mut env: JNIEnv,
        _class: JClass,
        secret: JByteArray,
        message: JByteArray,
    ) -> jbyteArray {
        let secret = arg(&mut env, &secret);
        let message = arg(&mut env, &message);
        let result = bytes_or_error(secret_sign(&secret, &message));
        finish(&mut env, result)
    }

    #[no_mangle]
    pub extern "system" fn Java_tech_annexflow_iroh4k_KeysJni_endpointIdFromBytes(
        mut env: JNIEnv,
        _class: JClass,
        id: JByteArray,
    ) -> jbyteArray {
        let id = arg(&mut env, &id);
        let result = bytes_or_error(endpoint_id_from_bytes(&id));
        finish(&mut env, result)
    }

    #[no_mangle]
    pub extern "system" fn Java_tech_annexflow_iroh4k_KeysJni_endpointIdFromString(
        mut env: JNIEnv,
        _class: JClass,
        text: JString,
    ) -> jbyteArray {
        // `String::from(JavaStr)` decodes Java's modified UTF-8 and is infallible (it falls back
        // to lossy decoding), so the only failure left is not being handed a string at all —
        // which is reported rather than panicked on, since the FFI boundary must never unwind.
        let result = match env.get_string(&text) {
            Ok(java_str) => bytes_or_error(endpoint_id_from_string(&String::from(java_str))),
            Err(error) => error_result(
                ERROR_KEY,
                format!("could not read the endpoint id argument: {error}"),
            ),
        };
        finish(&mut env, result)
    }

    #[no_mangle]
    pub extern "system" fn Java_tech_annexflow_iroh4k_KeysJni_endpointIdToString(
        mut env: JNIEnv,
        _class: JClass,
        id: JByteArray,
    ) -> jbyteArray {
        let id = arg(&mut env, &id);
        let result = bytes_or_error(endpoint_id_to_string(&id));
        finish(&mut env, result)
    }

    #[no_mangle]
    pub extern "system" fn Java_tech_annexflow_iroh4k_KeysJni_endpointIdToZ32(
        mut env: JNIEnv,
        _class: JClass,
        id: JByteArray,
    ) -> jbyteArray {
        let id = arg(&mut env, &id);
        let result = bytes_or_error(endpoint_id_to_z32(&id));
        finish(&mut env, result)
    }

    #[no_mangle]
    pub extern "system" fn Java_tech_annexflow_iroh4k_KeysJni_endpointIdFromZ32(
        mut env: JNIEnv,
        _class: JClass,
        text: JString,
    ) -> jbyteArray {
        let result = match env.get_string(&text) {
            Err(_) => error_result(ERROR_KEY, "endpoint id string was not valid UTF-16"),
            Ok(java_str) => bytes_or_error(endpoint_id_from_z32(&String::from(java_str))),
        };
        finish(&mut env, result)
    }

    #[no_mangle]
    pub extern "system" fn Java_tech_annexflow_iroh4k_KeysJni_endpointIdFmtShort(
        mut env: JNIEnv,
        _class: JClass,
        id: JByteArray,
    ) -> jbyteArray {
        let id = arg(&mut env, &id);
        let result = bytes_or_error(endpoint_id_fmt_short(&id));
        finish(&mut env, result)
    }

    #[no_mangle]
    pub extern "system" fn Java_tech_annexflow_iroh4k_KeysJni_endpointIdVerify(
        mut env: JNIEnv,
        _class: JClass,
        id: JByteArray,
        message: JByteArray,
        signature: JByteArray,
    ) -> jbyteArray {
        let id = arg(&mut env, &id);
        let message = arg(&mut env, &message);
        let signature = arg(&mut env, &signature);
        let result = bool_or_error(endpoint_id_verify(&id, &message, &signature));
        finish(&mut env, result)
    }

    #[no_mangle]
    pub extern "system" fn Java_tech_annexflow_iroh4k_KeysJni_signatureFromBytes(
        mut env: JNIEnv,
        _class: JClass,
        signature: JByteArray,
    ) -> jbyteArray {
        let signature = arg(&mut env, &signature);
        let result = bytes_or_error(signature_from_bytes(&signature));
        finish(&mut env, result)
    }
}
