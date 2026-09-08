use mdoc_android_platform::NfcPlatform;
use nfc_reader::{NfcReader, NfcTag};
use std::{sync::Arc, time::Duration};

pub struct AndroidNfcReader(pub Arc<dyn NfcPlatform>);
pub struct AndroidNfcTag(Arc<dyn NfcPlatform>);

impl NfcReader for AndroidNfcReader {
    type Tag = AndroidNfcTag;
    async fn connect(&mut self, timeout: Duration) -> anyhow::Result<Option<AndroidNfcTag>> {
        let platform = self.0.clone();
        let timeout_ms = timeout.as_millis().try_into()?;
        let connected =
            tokio::task::spawn_blocking(move || platform.nfc_connect(timeout_ms)).await??;
        Ok(connected.then(|| AndroidNfcTag(self.0.clone())))
    }
}
impl NfcTag for AndroidNfcTag {
    async fn transceive(&mut self, data: &[u8]) -> anyhow::Result<Vec<u8>> {
        let platform = self.0.clone();
        let command = data.to_vec();
        tokio::task::spawn_blocking(move || platform.nfc_transceive(command)).await?
    }
}
