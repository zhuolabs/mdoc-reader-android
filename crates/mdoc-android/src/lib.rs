use anyhow::{Result, ensure};
use mdoc_android_platform::{BlePlatform, EventSink, NfcPlatform};
use mdoc_core::{CoseKeyPrivate, DeviceRequest, NameSpaces};
use mdoc_ui::MdocResultUi;
use serde::Deserialize;
use std::sync::{
    Arc,
    atomic::{AtomicBool, Ordering},
};

uniffi::setup_scaffolding!();

#[cfg(all(feature = "btstack", target_os = "android"))]
mod usb;

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum ReaderError {
    #[error("{details}")]
    Failure { details: String },
}
impl From<anyhow::Error> for ReaderError {
    fn from(e: anyhow::Error) -> Self {
        Self::Failure {
            details: format!("{e:#}"),
        }
    }
}

/// NFC callbacks execute on a Rust worker, never the Kotlin UI thread.
#[uniffi::export(with_foreign)]
pub trait NfcHardware: Send + Sync {
    fn nfc_connect(&self, timeout_ms: u64) -> Result<bool, ReaderError>;
    fn nfc_transceive(&self, command: Vec<u8>) -> Result<Vec<u8>, ReaderError>;
    fn shutdown(&self);
}

/// BLE callbacks execute on a Rust worker, never the Kotlin UI thread.
#[uniffi::export(with_foreign)]
pub trait BleHardware: Send + Sync {
    fn ble_connect(
        &self,
        uuid: String,
        ident: Vec<u8>,
        timeout_ms: u64,
    ) -> Result<u16, ReaderError>;
    fn ble_send(&self, chunk: Vec<u8>) -> Result<(), ReaderError>;
    fn ble_receive(&self, timeout_ms: u64) -> Result<Vec<u8>, ReaderError>;
    fn shutdown(&self);
}

#[uniffi::export(with_foreign)]
pub trait ReaderEventSink: Send + Sync {
    fn on_event(&self, event: String);
}

struct NfcAdapter(Arc<dyn NfcHardware>);
impl NfcPlatform for NfcAdapter {
    fn nfc_connect(&self, t: u64) -> Result<bool> {
        Ok(self.0.nfc_connect(t)?)
    }
    fn nfc_transceive(&self, c: Vec<u8>) -> Result<Vec<u8>> {
        Ok(self.0.nfc_transceive(c)?)
    }
    fn shutdown(&self) {
        self.0.shutdown();
    }
}
struct BleAdapter(Arc<dyn BleHardware>);
impl BlePlatform for BleAdapter {
    fn ble_connect(&self, u: String, i: Vec<u8>, t: u64) -> Result<u16> {
        Ok(self.0.ble_connect(u, i, t)?)
    }
    fn ble_send(&self, c: Vec<u8>) -> Result<()> {
        Ok(self.0.ble_send(c)?)
    }
    fn ble_receive(&self, t: u64) -> Result<Vec<u8>> {
        Ok(self.0.ble_receive(t)?)
    }
    fn shutdown(&self) {
        self.0.shutdown();
    }
}
struct EventAdapter(Arc<dyn ReaderEventSink>);
impl EventSink for EventAdapter {
    fn on_event(&self, event: String) {
        self.0.on_event(event);
    }
}

#[derive(uniffi::Object)]
pub struct ReaderSession {
    nfc: Arc<dyn NfcPlatform>,
    ble: Arc<dyn BlePlatform>,
    events: Arc<dyn EventSink>,
    cancelled: Arc<AtomicBool>,
    started: AtomicBool,
}
struct Cleanup {
    nfc: Arc<dyn NfcPlatform>,
    ble: Arc<dyn BlePlatform>,
    cancelled: Arc<AtomicBool>,
}
impl Drop for Cleanup {
    fn drop(&mut self) {
        self.cancelled.store(true, Ordering::SeqCst);
        self.nfc.shutdown();
        self.ble.shutdown();
    }
}

#[uniffi::export]
impl ReaderSession {
    #[uniffi::constructor]
    pub fn new(
        nfc: Arc<dyn NfcHardware>,
        ble: Arc<dyn BleHardware>,
        events: Arc<dyn ReaderEventSink>,
    ) -> Arc<Self> {
        Arc::new(Self {
            nfc: Arc::new(NfcAdapter(nfc)),
            ble: Arc::new(BleAdapter(ble)),
            events: Arc::new(EventAdapter(events)),
            cancelled: Arc::new(AtomicBool::new(false)),
            started: AtomicBool::new(false),
        })
    }
    pub fn cancel(&self) {
        self.cancelled.store(true, Ordering::SeqCst);
        self.nfc.shutdown();
        self.ble.shutdown();
    }
    /// UniFFI generates suspend fun read(requestJson: String): String.
    /// Non-Send upstream futures live on a dedicated current-thread runtime.
    pub async fn read(&self, request_json: String) -> Result<String, ReaderError> {
        if self.started.swap(true, Ordering::SeqCst) {
            return Err(ReaderError::Failure {
                details: "Session already used".into(),
            });
        }
        let _cancel_on_drop = Cleanup {
            nfc: self.nfc.clone(),
            ble: self.ble.clone(),
            cancelled: self.cancelled.clone(),
        };
        let nfc = self.nfc.clone();
        let ble = self.ble.clone();
        let events = self.events.clone();
        let cancelled = self.cancelled.clone();
        let (tx, rx) = tokio::sync::oneshot::channel();
        std::thread::Builder::new()
            .name("mdoc-reader".into())
            .spawn(move || {
                let _cleanup = Cleanup {
                    nfc: nfc.clone(),
                    ble: ble.clone(),
                    cancelled: cancelled.clone(),
                };
                let result =
                    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| -> Result<String> {
                        let runtime = tokio::runtime::Builder::new_current_thread()
                            .enable_all()
                            .build()?;
                        runtime.block_on(async {
                            tokio::select! {
                                biased;
                                _ = async { loop {
                                    if cancelled.load(Ordering::SeqCst) { break; }
                                    tokio::time::sleep(std::time::Duration::from_millis(50)).await;
                                }} => anyhow::bail!("Cancelled"),
                                result = tokio::time::timeout(
                                    std::time::Duration::from_secs(420),
                                    run(nfc, ble, events, &request_json),
                                ) => result?,
                            }
                        })
                    }));
                let result =
                    result.unwrap_or_else(|_| Err(anyhow::anyhow!("Reader worker failed")));
                let _ = tx.send(result.map_err(ReaderError::from));
            })
            .map_err(|e| ReaderError::Failure {
                details: e.to_string(),
            })?;
        rx.await.map_err(|_| ReaderError::Failure {
            details: "Reader worker closed".into(),
        })?
    }
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct RequestConfig {
    iaca_cert: String,
    doc_requests: Vec<DocRequest>,
    #[serde(default)]
    version: Option<String>,
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct DocRequest {
    items_request: ItemsRequest,
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct ItemsRequest {
    doc_type: String,
    name_spaces: NameSpaces,
}
fn parse_request(raw: &str) -> Result<(DeviceRequest, url::Url)> {
    let config: RequestConfig = serde_json::from_str(raw)?;
    let url = url::Url::parse(&config.iaca_cert)?;
    ensure!(url.scheme() == "https", "IACA certificate must use HTTPS");
    ensure!(!config.doc_requests.is_empty(), "No document requests");
    let mut builder = DeviceRequest::builder();
    if let Some(v) = config.version {
        ensure!(v == "1.0", "Unsupported request version");
        builder = builder.version(v);
    }
    for doc in config.doc_requests {
        let item = doc.items_request;
        ensure!(
            !item.doc_type.is_empty() && !item.name_spaces.is_empty(),
            "Empty document type or namespaces"
        );
        ensure!(
            item.name_spaces.iter().all(|(ns, fields)| !ns.is_empty()
                && !fields.is_empty()
                && fields.keys().all(|k| !k.is_empty())),
            "Empty namespace or element"
        );
        builder = builder.add_doc_request(item.doc_type, item.name_spaces, None);
    }
    Ok((builder.build(), url))
}
async fn run(
    nfc_platform: Arc<dyn NfcPlatform>,
    ble_platform: Arc<dyn BlePlatform>,
    events: Arc<dyn EventSink>,
    raw: &str,
) -> Result<String> {
    let (request, url) = parse_request(raw)?;
    events.on_event("certificate_loading".into());
    let certificate = tokio::time::timeout(
        std::time::Duration::from_secs(30),
        mdoc_security::download_x509_certificate(&url),
    )
    .await??;
    let mut nfc = nfc_reader_android::AndroidNfcReader(nfc_platform);
    let ble = mdoc_transport_ble_android::AndroidBleConnector {
        platform: ble_platform,
        events: events.clone(),
    };
    let mut ui = mdoc_ui_android::AndroidUi {
        events,
        result: None,
    };
    let response = mdoc_reader_flow::read_mdoc(
        &mut nfc,
        &ble,
        &CoseKeyPrivate::new()?,
        &request,
        false,
        false,
        Some(&ui),
        Some(&certificate),
        None,
    )
    .await?;
    ui.render_result(&response, &())?;
    ui.result
        .ok_or_else(|| anyhow::anyhow!("No rendered result"))
}

#[cfg(test)]
mod tests {
    use super::*;
    const REQUEST: &str = include_str!("../../../android/app/src/main/assets/request.example.json");
    #[test]
    fn example_request_preserves_all_elements() {
        let (request, _) = parse_request(REQUEST).unwrap();
        let item = request.doc_requests[0].items_request.decode().unwrap();
        assert_eq!(item.doc_type, "org.iso.18013.5.1.mDL");
        assert_eq!(
            item.name_spaces.values().map(|v| v.len()).sum::<usize>(),
            11
        );
        assert!(
            item.name_spaces
                .values()
                .flat_map(|v| v.values())
                .all(|retain| !retain)
        );
    }
    #[test]
    fn rejects_bad_request() {
        for raw in [
            "{}",
            "{bad",
            r#"{"iacaCert":"https://example.com/cert","docRequests":[]}"#,
        ] {
            assert!(parse_request(raw).is_err());
        }
        assert!(parse_request(&REQUEST.replace("https://", "http://")).is_err());
        assert!(parse_request(&REQUEST.replace("false", "\"false\"")).is_err());
    }
}
