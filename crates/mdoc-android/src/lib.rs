use anyhow::{Result, ensure};
use mdoc_android_platform::{EventSink, NfcPlatform};
use mdoc_core::{CoseKeyPrivate, DeviceRequest, NameSpaces};
use mdoc_reader_flow::{IssuerTrust, ReaderOptions, TrustPolicy, VerificationPolicy};
use mdoc_transport_ble::{BleBackend, BleMdocTransportConnector};
use mdoc_ui::MdocResultUi;
use serde::Deserialize;
use std::sync::{
    Arc,
    atomic::{AtomicBool, Ordering},
};

use tokio_util::{sync::CancellationToken, task::AbortOnDropHandle};

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

/// NFC operations execute on Tokio blocking workers, never the Kotlin UI thread.
/// Shutdown may be called from the caller thread to interrupt pending operations.
#[uniffi::export(with_foreign)]
pub trait NfcHardware: Send + Sync {
    fn nfc_connect(&self, timeout_ms: u64) -> Result<bool, ReaderError>;
    fn nfc_transceive(&self, command: Vec<u8>) -> Result<Vec<u8>, ReaderError>;
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
struct EventAdapter(Arc<dyn ReaderEventSink>);
impl EventSink for EventAdapter {
    fn on_event(&self, event: String) {
        self.0.on_event(event);
    }
}

#[derive(uniffi::Object)]
pub struct ReaderSession {
    nfc: Arc<dyn NfcPlatform>,
    ble: Arc<dyn BleBackend>,
    events: Arc<dyn EventSink>,
    cancelled: CancellationToken,
    started: AtomicBool,
}
struct Cleanup<'a>(&'a ReaderSession);
impl Drop for Cleanup<'_> {
    fn drop(&mut self) {
        self.0.cancel();
    }
}

#[uniffi::export]
impl ReaderSession {
    #[uniffi::constructor]
    pub fn new(
        nfc: Arc<dyn NfcHardware>,
        ble: Arc<dyn BleBackend>,
        events: Arc<dyn ReaderEventSink>,
    ) -> Arc<Self> {
        Arc::new(Self {
            nfc: Arc::new(NfcAdapter(nfc)),
            ble,
            events: Arc::new(EventAdapter(events)),
            cancelled: CancellationToken::new(),
            started: AtomicBool::new(false),
        })
    }
    pub fn cancel(&self) {
        self.cancelled.cancel();
        self.nfc.shutdown();
        self.ble.shutdown();
    }
    /// UniFFI generates suspend fun read(requestJson: String): String.
    /// Send futures run on Tokio; blocking hardware work uses its blocking pool.
    #[uniffi::method(async_runtime = "tokio")]
    pub async fn read(&self, request_json: String) -> Result<String, ReaderError> {
        self.read_with(run(
            self.nfc.clone(),
            self.ble.clone(),
            self.events.clone(),
            request_json,
        ))
        .await
    }
}

impl ReaderSession {
    async fn read_with(
        &self,
        flow: impl std::future::Future<Output = Result<String>> + Send + 'static,
    ) -> Result<String, ReaderError> {
        if self.started.swap(true, Ordering::SeqCst) {
            return Err(ReaderError::Failure {
                details: "Session already used".into(),
            });
        }
        let _cleanup = Cleanup(self);
        if self.cancelled.is_cancelled() {
            return Err(anyhow::anyhow!("Cancelled").into());
        }
        // Aborting the UniFFI future also aborts the spawned flow. Hardware
        // shutdown releases blocking operations, which cannot be task-aborted.
        let task = AbortOnDropHandle::new(tokio::spawn(flow));
        let result: Result<String> = async {
            tokio::select! {
                biased;
                _ = self.cancelled.cancelled() => anyhow::bail!("Cancelled"),
                result = tokio::time::timeout(std::time::Duration::from_secs(420), task) => result??,
            }
        }.await;
        result.map_err(ReaderError::from)
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
    ble_platform: Arc<dyn BleBackend>,
    events: Arc<dyn EventSink>,
    raw: String,
) -> Result<String> {
    let (request, url) = parse_request(&raw)?;
    events.on_event("certificate_loading".into());
    let certificate = tokio::time::timeout(
        std::time::Duration::from_secs(30),
        mdoc_security::download_x509_certificate(&url),
    )
    .await??;
    let mut nfc = nfc_reader_android::AndroidNfcReader(nfc_platform);
    events.on_event("ble_connecting".into());
    let ble = BleMdocTransportConnector {
        backend: ble_platform,
        operation_timeout: std::time::Duration::from_secs(120),
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
        &ReaderOptions {
            service_uuid: None,
            verification: VerificationPolicy::new(TrustPolicy::RequireTrustedIssuer {
                iaca: &certificate,
            }),
        },
        Some(&ui),
    )
    .await?;
    ensure!(
        response.issuer_trust() == IssuerTrust::Trusted,
        "No trusted documents returned"
    );
    ui.render_result(response.response(), &())?;
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

#[cfg(test)]
mod session_tests;
