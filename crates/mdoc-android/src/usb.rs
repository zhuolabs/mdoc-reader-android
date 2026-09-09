use super::*;
use mdoc_android_platform::BlePlatform;
use mdoc_transport_ble::{
    BleBackendError, BleBackendParams, BleConnectionInfo, BleReceiveOrdering,
};
use mdoc_transport_btstack::BtstackBle;
use std::os::fd::BorrowedFd;

#[derive(uniffi::Object)]
pub struct UsbBleHardware(Arc<BtstackBle>);

#[uniffi::export]
impl UsbBleHardware {
    /// Kotlin retains its UsbDeviceConnection until close_transport returns.
    /// Duplicate synchronously so Rust never closes Android's descriptor.
    #[uniffi::constructor]
    pub fn new(fd: i32, events: Arc<dyn ReaderEventSink>) -> Result<Arc<Self>, ReaderError> {
        if fd < 0 {
            return Err(anyhow::anyhow!("Invalid USB file descriptor").into());
        }
        // SAFETY: caller keeps the permission-granted connection open for this call.
        let owned = unsafe { BorrowedFd::borrow_raw(fd) }
            .try_clone_to_owned()
            .map_err(anyhow::Error::from)?;
        Ok(Arc::new(Self(Arc::new(BtstackBle::from_fd(
            owned,
            Arc::new(EventAdapter(events)),
        )?))))
    }
    pub fn ble_connect(
        &self,
        uuid: String,
        ident: Vec<u8>,
        timeout_ms: u64,
    ) -> Result<u16, ReaderError> {
        Ok(self.0.ble_connect(uuid, ident, timeout_ms)?)
    }
    pub fn ble_send(&self, chunk: Vec<u8>) -> Result<(), ReaderError> {
        Ok(self.0.ble_send(chunk)?)
    }
    pub fn ble_receive(&self, timeout_ms: u64) -> Result<Vec<u8>, ReaderError> {
        Ok(self.0.ble_receive(timeout_ms)?)
    }
    pub fn shutdown(&self) {
        self.0.shutdown();
    }
    /// May wait for controller shutdown. Call off the Android Main thread.
    pub fn close_transport(&self) {
        self.0.close();
    }
}

#[uniffi::export]
impl ReaderSession {
    /// The USB backend stays entirely in Rust during document transfer.
    #[uniffi::constructor]
    pub fn with_usb(
        nfc: Arc<dyn NfcBackend>,
        ble: Arc<UsbBleHardware>,
        events: Arc<dyn ReaderEventSink>,
    ) -> Arc<Self> {
        Arc::new(Self {
            nfc,
            ble: Arc::new(UsbBackend(ble.0.clone())),
            events: Arc::new(EventAdapter(events)),
            cancelled: CancellationToken::new(),
            started: AtomicBool::new(false),
        })
    }
}

struct UsbBackend(Arc<BtstackBle>);
#[async_trait::async_trait]
impl BleBackend for UsbBackend {
    async fn connect(
        &self,
        params: BleBackendParams,
    ) -> Result<BleConnectionInfo, BleBackendError> {
        let backend = self.0.clone();
        let mtu = tokio::task::spawn_blocking(move || {
            backend.ble_connect(params.service_uuid, params.ident, 120_000)
        })
        .await
        .map_err(usb_error)?
        .map_err(usb_error)?;
        if mtu < 23 {
            return Err(usb_error("Invalid BLE MTU"));
        }
        Ok(BleConnectionInfo {
            max_characteristic_value_size: (u32::from(mtu) - 3).min(512),
            receive_ordering: BleReceiveOrdering::Ordered,
        })
    }
    async fn send(&self, value: Vec<u8>) -> Result<(), BleBackendError> {
        let backend = self.0.clone();
        tokio::task::spawn_blocking(move || backend.ble_send(value))
            .await
            .map_err(usb_error)?
            .map_err(usb_error)
    }
    async fn receive(&self) -> Result<Vec<u8>, BleBackendError> {
        let backend = self.0.clone();
        tokio::task::spawn_blocking(move || backend.ble_receive(120_000))
            .await
            .map_err(usb_error)?
            .map_err(usb_error)
    }
    fn shutdown(&self) {
        self.0.shutdown();
    }
}
fn usb_error(error: impl std::fmt::Display) -> BleBackendError {
    BleBackendError::Failure {
        details: error.to_string(),
    }
}
