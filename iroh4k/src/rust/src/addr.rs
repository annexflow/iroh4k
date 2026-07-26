//! Endpoint addresses (`EndpointAddr`, `TransportAddr`) and connection tickets
//! (`EndpointTicket`).
//!
//! Owned by the addressing domain. Contains the shared logic plus both facades' exports for it:
//! `#[no_mangle] extern "C"` for cinterop and `#[cfg(not(target_os = "ios"))] Java_*` for JNI.
