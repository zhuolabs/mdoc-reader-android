use anyhow::Result;

/// Blocking BLE operations, called on Tokio blocking workers.
/// Implementations must unblock outstanding calls when shutdown() is invoked.
pub trait BlePlatform: Send + Sync {
    fn ble_connect(&self, uuid: String, ident: Vec<u8>, timeout_ms: u64) -> Result<u16>;
    fn ble_send(&self, chunk: Vec<u8>) -> Result<()>;
    fn ble_receive(&self, timeout_ms: u64) -> Result<Vec<u8>>;
    fn shutdown(&self);
}

pub trait EventSink: Send + Sync {
    fn on_event(&self, event: String);
}
