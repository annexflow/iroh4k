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

/// Big-endian decoder, the mirror of [`Writer`].
///
/// Every method returns `Result`. That is not politeness: the buffer arrives from across the FFI
/// boundary, so a length prefix in it can say anything at all, and this crate is built with
/// `panic = "abort"` — an out-of-range index or invalid UTF-8 must be an error value, never an
/// `unwrap` that would take the host process down.
///
/// Four domains decode payloads (addressing, endpoint, services, connection). This is deliberately
/// one implementation rather than one per module: the bounds and negative-length checks below are
/// the crate's guard against a hostile or merely wrong payload, and copies of them would drift.
pub struct Reader<'a> {
    buf: &'a [u8],
    pos: usize,
}

#[allow(dead_code)]
impl<'a> Reader<'a> {
    pub fn new(buf: &'a [u8]) -> Self {
        Self { buf, pos: 0 }
    }

    pub fn remaining(&self) -> usize {
        self.buf.len() - self.pos
    }

    pub fn take(&mut self, n: usize) -> Result<&'a [u8], String> {
        if self.remaining() < n {
            return Err(format!(
                "malformed payload: needed {n} more bytes at offset {}, have {}",
                self.pos,
                self.remaining()
            ));
        }
        let slice = &self.buf[self.pos..self.pos + n];
        self.pos += n;
        Ok(slice)
    }

    pub fn u8(&mut self) -> Result<u8, String> {
        Ok(self.take(1)?[0])
    }

    pub fn bool(&mut self) -> Result<bool, String> {
        Ok(self.u8()? != 0)
    }

    pub fn i32(&mut self) -> Result<i32, String> {
        let bytes: [u8; 4] = self
            .take(4)?
            .try_into()
            .expect("take(4) always yields 4 bytes");
        Ok(i32::from_be_bytes(bytes))
    }

    pub fn i64(&mut self) -> Result<i64, String> {
        let bytes: [u8; 8] = self
            .take(8)?
            .try_into()
            .expect("take(8) always yields 8 bytes");
        Ok(i64::from_be_bytes(bytes))
    }

    pub fn f64(&mut self) -> Result<f64, String> {
        Ok(f64::from_bits(self.i64()? as u64))
    }

    /// `i32 len` followed by `len` bytes. A negative length is malformed here — use
    /// [`opt_bytes`](Self::opt_bytes) where the layout genuinely encodes an absent value.
    pub fn bytes(&mut self) -> Result<&'a [u8], String> {
        let len = self.i32()?;
        if len < 0 {
            return Err(format!(
                "malformed payload: negative length {len} at offset {}",
                self.pos - 4
            ));
        }
        self.take(len as usize)
    }

    /// `i32 -1` for absent, otherwise a length-prefixed value.
    pub fn opt_bytes(&mut self) -> Result<Option<&'a [u8]>, String> {
        let len = self.i32()?;
        if len < 0 {
            return Ok(None);
        }
        self.take(len as usize).map(Some)
    }

    pub fn str(&mut self) -> Result<&'a str, String> {
        Self::utf8(self.bytes()?)
    }

    pub fn opt_str(&mut self) -> Result<Option<&'a str>, String> {
        match self.opt_bytes()? {
            None => Ok(None),
            Some(bytes) => Self::utf8(bytes).map(Some),
        }
    }

    /// An element count, rejecting both a negative value and one larger than the bytes remaining.
    ///
    /// The second check is what stops a hostile `i32::MAX` from being turned into a reservation:
    /// no sequence can have more elements than there are bytes left to hold them.
    pub fn count(&mut self) -> Result<usize, String> {
        let count = self.i32()?;
        if count < 0 {
            return Err(format!(
                "malformed payload: negative count {count} at offset {}",
                self.pos - 4
            ));
        }
        if count as usize > self.remaining() {
            return Err(format!(
                "malformed payload: count {count} exceeds the {} bytes remaining",
                self.remaining()
            ));
        }
        Ok(count as usize)
    }

    /// Rejects trailing bytes, so a payload built with the wrong layout fails loudly here instead
    /// of being half-read and silently accepted.
    pub fn finish(self) -> Result<(), String> {
        if self.remaining() == 0 {
            Ok(())
        } else {
            Err(format!(
                "malformed payload: {} unexpected trailing bytes",
                self.remaining()
            ))
        }
    }

    fn utf8(bytes: &'a [u8]) -> Result<&'a str, String> {
        std::str::from_utf8(bytes)
            .map_err(|error| format!("malformed payload: invalid UTF-8: {error}"))
    }
}
