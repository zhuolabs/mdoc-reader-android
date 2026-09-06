use anyhow::{Result, ensure};
use mdoc_android_platform::{BlePlatform, EventSink};
use mdoc_transport::{BleTransportParams, MdocTransport, MdocTransportConnector};
use std::{
    sync::Arc,
    time::{Duration, Instant},
};

const MAX_MESSAGE: usize = 16 * 1024 * 1024;
pub struct AndroidBleConnector {
    pub platform: Arc<dyn BlePlatform>,
    pub events: Arc<dyn EventSink>,
}
pub struct AndroidBleTransport {
    platform: Arc<dyn BlePlatform>,
    mtu: u16,
}

impl MdocTransportConnector for AndroidBleConnector {
    type Transport = AndroidBleTransport;
    type Params = BleTransportParams;
    async fn connect(&self, params: BleTransportParams) -> Result<Self::Transport> {
        self.events.on_event("ble_connecting".into());
        let mtu = self.platform.ble_connect(
            params.service_uuid.to_string(),
            params.ident.to_vec(),
            120_000,
        )?;
        ensure!(mtu >= 23, "Invalid BLE MTU");
        Ok(AndroidBleTransport {
            platform: self.platform.clone(),
            mtu,
        })
    }
}

pub fn encode_chunks(message: &[u8], mtu: u16) -> Result<Vec<Vec<u8>>> {
    ensure!(mtu >= 23, "Invalid BLE MTU");
    ensure!(message.len() <= MAX_MESSAGE, "BLE message too large");
    // ATT overhead: 3 bytes, mdoc framing: 1 byte. Attribute value max: 512.
    let payload = (usize::from(mtu) - 3).min(512) - 1;
    if message.is_empty() {
        return Ok(vec![vec![0]]);
    }
    let count = message.len().div_ceil(payload);
    Ok(message
        .chunks(payload)
        .enumerate()
        .map(|(i, part)| {
            let mut frame = vec![u8::from(i + 1 != count)];
            frame.extend_from_slice(part);
            frame
        })
        .collect())
}

#[derive(Default)]
pub struct PacketAssembler {
    packets: Vec<Vec<u8>>,
    size: usize,
}
impl PacketAssembler {
    pub fn push(&mut self, frame: &[u8]) -> Result<Option<Vec<Vec<u8>>>> {
        ensure!(!frame.is_empty(), "Empty BLE frame");
        ensure!(frame[0] <= 1, "Invalid BLE continuation flag");
        ensure!(frame.len() <= 512, "BLE frame too large");
        ensure!(frame.len() > 1 || frame[0] == 0, "Empty continuation frame");
        self.size += frame.len() - 1;
        ensure!(
            self.size <= MAX_MESSAGE && self.packets.len() < 100_000,
            "BLE response too large"
        );
        self.packets.push(frame[1..].to_vec());
        if frame[0] == 0 {
            self.size = 0;
            Ok(Some(std::mem::take(&mut self.packets)))
        } else {
            Ok(None)
        }
    }
}
impl MdocTransport for AndroidBleTransport {
    async fn send(&mut self, message: &[u8]) -> Result<()> {
        for chunk in encode_chunks(message, self.mtu)? {
            self.platform.ble_send(chunk)?;
        }
        Ok(())
    }
    async fn receive_packets(&mut self) -> Result<Vec<Vec<u8>>> {
        let deadline = Instant::now() + Duration::from_secs(120);
        let mut assembler = PacketAssembler::default();
        loop {
            let remaining = deadline
                .saturating_duration_since(Instant::now())
                .as_millis() as u64;
            ensure!(remaining > 0, "BLE receive timeout");
            let chunk = self.platform.ble_receive(remaining)?;
            if let Some(packets) = assembler.push(&chunk)? {
                return Ok(packets);
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn roundtrip_boundaries() {
        for mtu in [23, 185, 247, 517] {
            for len in [0, 1, 19, 20, 180, 1024, 65536] {
                let original: Vec<u8> = (0..len).map(|v| v as u8).collect();
                let mut assembler = PacketAssembler::default();
                let frames = encode_chunks(&original, mtu).unwrap();
                for (i, frame) in frames.iter().enumerate() {
                    assert!(frame.len() <= (usize::from(mtu) - 3).min(512));
                    let result = assembler.push(frame).unwrap();
                    if i + 1 == frames.len() {
                        assert_eq!(result.unwrap().concat(), original);
                    } else {
                        assert!(result.is_none());
                    }
                }
            }
        }
    }
    #[test]
    fn rejects_bad_frames() {
        for frame in [vec![], vec![2, 0], vec![1], vec![0; 513]] {
            assert!(PacketAssembler::default().push(&frame).is_err());
        }
        assert!(encode_chunks(&[], 22).is_err());
        let mut a = PacketAssembler {
            size: MAX_MESSAGE,
            packets: vec![],
        };
        assert!(a.push(&[0, 1]).is_err());
    }
}
