//! Binary codec for structured values.
//!
//! Every value too complex for a scalar field on [`crate::core::Iroh4kResult`] —
//! `ConnectionStats`, `PathSnapshot`, `EndpointAddr`, metric maps, watcher events — is
//! encoded here into a flat big-endian, length-prefixed buffer and decoded by shared
//! Kotlin `commonMain` code. That keeps the FFI and JNI facades on one decoder and avoids
//! hand-writing a `#[repr(C)]` struct (and its cbindgen forcing export, and its free
//! routine) per shape.
//!
//! Layout primitives, all big-endian:
//! - `u8`  — discriminators and booleans
//! - `i32` — counts and lengths
//! - `i64` / `f64` — scalars
//! - byte string — `i32 len` followed by `len` bytes; `len == -1` encodes `None`
//!
//! Kotlin's decoder mirrors this exactly; the two must be changed together.

/// Big-endian encoder. Append-only; the buffer is handed to Kotlin via
/// [`crate::core::bytes_result`].
#[derive(Default)]
pub struct Writer {
    buf: Vec<u8>,
}

#[allow(dead_code)]
impl Writer {
    pub fn new() -> Self {
        Self { buf: Vec::new() }
    }

    pub fn u8(&mut self, v: u8) -> &mut Self {
        self.buf.push(v);
        self
    }

    pub fn bool(&mut self, v: bool) -> &mut Self {
        self.u8(u8::from(v))
    }

    pub fn i32(&mut self, v: i32) -> &mut Self {
        self.buf.extend_from_slice(&v.to_be_bytes());
        self
    }

    pub fn i64(&mut self, v: i64) -> &mut Self {
        self.buf.extend_from_slice(&v.to_be_bytes());
        self
    }

    pub fn f64(&mut self, v: f64) -> &mut Self {
        self.buf.extend_from_slice(&v.to_be_bytes());
        self
    }

    /// `i32 len` followed by the bytes.
    pub fn bytes(&mut self, v: &[u8]) -> &mut Self {
        self.i32(v.len() as i32);
        self.buf.extend_from_slice(v);
        self
    }

    pub fn str(&mut self, v: &str) -> &mut Self {
        self.bytes(v.as_bytes())
    }

    /// `i32 -1` for `None`, otherwise a normal length-prefixed string.
    pub fn opt_str(&mut self, v: Option<&str>) -> &mut Self {
        match v {
            None => self.i32(-1),
            Some(s) => self.str(s),
        }
    }

    /// Length-prefixed sequence: `i32 count`, then `f` per element.
    pub fn seq<T>(&mut self, items: &[T], mut f: impl FnMut(&mut Self, &T)) -> &mut Self {
        self.i32(items.len() as i32);
        for item in items {
            f(self, item);
        }
        self
    }

    pub fn finish(self) -> Vec<u8> {
        self.buf
    }
}
