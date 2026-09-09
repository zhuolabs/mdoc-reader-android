//! Android shared-library packaging and the optional USB descriptor bridge.
pub use mdoc_reader_ffi::{ReaderError, ReaderEventSink, ReaderSession};
uniffi::setup_scaffolding!();

#[cfg(all(feature = "btstack", target_os = "android"))]
mod usb;
