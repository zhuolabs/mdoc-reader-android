use super::*;
use std::sync::{Condvar, Mutex};
use std::time::Duration;
use tokio::sync::Notify;

#[derive(Default, Clone)]
struct Hardware {
    closed: Arc<Mutex<bool>>,
    released: Arc<Condvar>,
    entered: Arc<Notify>,
    exited: Arc<Notify>,
}
impl Hardware {
    fn blocking_connect(&self) -> Result<bool, nfc_reader::NfcBackendError> {
        self.entered.notify_one();
        let (closed, _) = self
            .released
            .wait_timeout_while(
                self.closed.lock().unwrap(),
                Duration::from_secs(5),
                |closed| !*closed,
            )
            .unwrap();
        let was_closed = *closed;
        self.exited.notify_one();
        assert!(was_closed, "Shutdown must release blocking hardware");
        Err(nfc_reader::NfcBackendError::Failure {
            details: "Cancelled".into(),
        })
    }
}
#[async_trait::async_trait]
impl NfcBackend for Hardware {
    async fn connect(&self, _: u64) -> Result<bool, nfc_reader::NfcBackendError> {
        let hardware = self.clone();
        tokio::task::spawn_blocking(move || hardware.blocking_connect())
            .await
            .unwrap()
    }
    async fn transceive(&self, _: Vec<u8>) -> Result<Vec<u8>, nfc_reader::NfcBackendError> {
        unreachable!()
    }
    fn shutdown(&self) {
        *self.closed.lock().unwrap() = true;
        self.released.notify_all();
    }
}
#[async_trait::async_trait]
impl BleBackend for Hardware {
    async fn connect(
        &self,
        _: mdoc_transport_ble::BleBackendParams,
    ) -> Result<mdoc_transport_ble::BleConnectionInfo, mdoc_transport_ble::BleBackendError> {
        Ok(mdoc_transport_ble::BleConnectionInfo {
            max_characteristic_value_size: 20,
            receive_ordering: mdoc_transport_ble::BleReceiveOrdering::Ordered,
        })
    }
    async fn send(&self, _: Vec<u8>) -> Result<(), mdoc_transport_ble::BleBackendError> {
        Ok(())
    }
    async fn receive(&self) -> Result<Vec<u8>, mdoc_transport_ble::BleBackendError> {
        self.entered.notify_one();
        while !*self.closed.lock().unwrap() {
            tokio::task::yield_now().await;
        }
        self.exited.notify_one();
        Err(mdoc_transport_ble::BleBackendError::Disconnected)
    }
    fn shutdown(&self) {
        NfcBackend::shutdown(self);
        self.exited.notify_one();
    }
}
impl ReaderEventSink for Hardware {
    fn on_event(&self, _: String) {}
}

fn session() -> (Arc<ReaderSession>, Arc<Hardware>, Arc<Hardware>) {
    let nfc = Arc::new(Hardware::default());
    let ble = Arc::new(Hardware::default());
    (
        ReaderSession::new(nfc.clone(), ble.clone(), ble.clone()),
        nfc,
        ble,
    )
}

#[tokio::test]
async fn success_and_failure_close_hardware_and_sessions_are_single_use() {
    for success in [false, true] {
        let (reader, nfc, ble) = session();
        let result = reader
            .read_with(async move {
                if success {
                    Ok("result".into())
                } else {
                    anyhow::bail!("test failure")
                }
            })
            .await;
        assert_eq!(result.is_ok(), success);
        assert!(*nfc.closed.lock().unwrap());
        assert!(*ble.closed.lock().unwrap());
        assert!(
            reader
                .read("{}".into())
                .await
                .unwrap_err()
                .to_string()
                .contains("already used")
        );
    }
}

#[tokio::test]
async fn cancellation_before_read_never_polls_flow() {
    let (reader, _, _) = session();
    reader.cancel();
    let result = reader
        .read_with(async { panic!("Cancelled flow must not start") })
        .await;
    assert!(result.unwrap_err().to_string().contains("Cancelled"));
}

#[tokio::test]
async fn cancellation_and_future_drop_release_blocking_hardware() {
    use mdoc_transport::{BleTransportParams, MdocTransport, MdocTransportConnector};
    use nfc_reader::NfcReader;
    // A single-thread executor proves the blocking callback does not prevent
    // the caller from resuming and cancelling the session.
    for (drop_future, use_ble) in [(false, false), (true, false), (false, true), (true, true)] {
        let (reader, nfc, ble) = session();
        let waiting = if use_ble { ble.clone() } else { nfc.clone() };
        let connector = BleMdocTransportConnector {
            backend: reader.ble.clone(),
            operation_timeout: Duration::from_secs(120),
        };
        let owner = reader.clone();
        let mut adapter = GenericNfcReader(reader.nfc.clone());
        let task = tokio::spawn(async move {
            owner
                .read_with(async move {
                    if use_ble {
                        let mut transport = connector
                            .connect(BleTransportParams {
                                service_uuid: "00000000-0000-0000-0000-000000000001"
                                    .parse()
                                    .unwrap(),
                                ident: [0; 16],
                            })
                            .await?;
                        transport.send(&[1, 2, 3]).await?;
                        transport.receive_packets().await?;
                    } else {
                        adapter.connect(Duration::from_secs(5)).await?;
                    }
                    Ok(String::new())
                })
                .await
        });
        tokio::time::timeout(Duration::from_secs(2), waiting.entered.notified())
            .await
            .unwrap();
        if drop_future {
            task.abort();
        } else {
            reader.cancel();
        }
        let result = tokio::time::timeout(Duration::from_secs(2), task)
            .await
            .unwrap();
        if drop_future {
            assert!(result.unwrap_err().is_cancelled());
        } else {
            assert!(
                result
                    .unwrap()
                    .unwrap_err()
                    .to_string()
                    .contains("Cancelled")
            );
        }
        tokio::time::timeout(Duration::from_secs(2), waiting.exited.notified())
            .await
            .unwrap();
        assert!(*nfc.closed.lock().unwrap());
        assert!(*ble.closed.lock().unwrap());
    }
}

struct Dropped(Arc<AtomicBool>);
impl Drop for Dropped {
    fn drop(&mut self) {
        self.0.store(true, Ordering::SeqCst);
    }
}

#[tokio::test(start_paused = true)]
async fn overall_timeout_aborts_async_flow_and_closes_hardware() {
    let (reader, nfc, ble) = session();
    let dropped = Arc::new(AtomicBool::new(false));
    let guard = Dropped(dropped.clone());
    let result = reader
        .read_with(async move {
            let _guard = guard;
            std::future::pending().await
        })
        .await;
    assert!(
        result
            .unwrap_err()
            .to_string()
            .contains("deadline has elapsed")
    );
    tokio::task::yield_now().await;
    assert!(dropped.load(Ordering::SeqCst));
    assert!(*nfc.closed.lock().unwrap());
    assert!(*ble.closed.lock().unwrap());
}
