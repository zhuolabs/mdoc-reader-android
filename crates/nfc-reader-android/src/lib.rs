use mdoc_android_platform::NfcPlatform;
use nfc_reader::{NfcReader, NfcTag};
use std::{sync::Arc, time::Duration};

pub struct AndroidNfcReader(pub Arc<dyn NfcPlatform>);
pub struct AndroidNfcTag(Arc<dyn NfcPlatform>);

impl NfcReader for AndroidNfcReader {
    type NfcTag<'a> = AndroidNfcTag;
    async fn connect(&mut self, timeout: Duration) -> anyhow::Result<Option<AndroidNfcTag>> {
        Ok(self
            .0
            .nfc_connect(timeout.as_millis().try_into()?)?
            .then(|| AndroidNfcTag(self.0.clone())))
    }
}
impl NfcTag for AndroidNfcTag {
    async fn transceive(&mut self, data: &[u8]) -> anyhow::Result<Vec<u8>> {
        self.0.nfc_transceive(data.to_vec())
    }
}
