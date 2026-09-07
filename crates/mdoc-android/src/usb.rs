use super::*;
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
        nfc: Arc<dyn NfcHardware>,
        ble: Arc<UsbBleHardware>,
        events: Arc<dyn ReaderEventSink>,
    ) -> Arc<Self> {
        Arc::new(Self {
            nfc: Arc::new(NfcAdapter(nfc)),
            ble: ble.0.clone(),
            events: Arc::new(EventAdapter(events)),
            cancelled: CancellationToken::new(),
            started: AtomicBool::new(false),
        })
    }
}
