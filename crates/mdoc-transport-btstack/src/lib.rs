//! USB BTstack backend for the shared mdoc BLE connector and framing.
use anyhow::{Result, anyhow, bail, ensure};
use btstack_gatt::{
    GattCharacteristic, GattConnection, GattServer, GattService, GattStatus, ServerEvent,
    SubscriptionType,
};
use btstack_nusb::NusbHciTransport;
use mdoc_android_platform::{BlePlatform, EventSink};
use std::{
    collections::VecDeque,
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, Ordering},
    },
    time::{Duration, Instant},
};

const fn characteristic(n: u8) -> [u8; 16] {
    [
        0, 0, 0, n, 0xa1, 0x23, 0x48, 0xce, 0x89, 0x6b, 0x4c, 0x76, 0x97, 0x33, 0x73, 0xe6,
    ]
}
const STATE: [u8; 16] = characteristic(5);
const C2S: [u8; 16] = characteristic(6);
const S2C: [u8; 16] = characteristic(7);
const IDENT: [u8; 16] = characteristic(8);

#[derive(Default)]
struct Peer {
    connection: Option<GattConnection>,
    subscribed: bool,
    started: bool,
    ended: bool,
    packets: VecDeque<Vec<u8>>,
    failure: Option<String>,
}
impl Peer {
    fn accept(&mut self, connection: &GattConnection) -> std::result::Result<(), GattStatus> {
        if let Some(peer) = &self.connection
            && peer.id() != connection.id()
        {
            return Err(GattStatus::RequestNotSupported);
        }
        self.connection = Some(connection.clone());
        Ok(())
    }
    fn write(
        &mut self,
        connection: &GattConnection,
        state: bool,
        value: &[u8],
    ) -> std::result::Result<(), GattStatus> {
        self.accept(connection)?;
        if self.ended {
            return Err(GattStatus::RequestNotSupported);
        }
        if state {
            match value {
                [1] => self.started = true,
                [2] => self.ended = true,
                _ => return Err(GattStatus::InvalidValueLength),
            }
        } else {
            if value.is_empty() || value.len() > 512 || value[0] > 1 || value == [1] {
                self.failure = Some("Invalid BLE frame".into());
                return Err(GattStatus::InvalidValueLength);
            }
            if self.packets.len() >= 2048 {
                self.failure = Some("BLE receive queue overflow".into());
                return Err(GattStatus::UnlikelyError);
            }
            self.packets.push_back(value.to_vec());
        }
        Ok(())
    }
    fn next_packet(&mut self) -> Result<Option<Vec<u8>>> {
        if let Some(packet) = self.packets.pop_front() {
            return Ok(Some(packet));
        }
        ensure!(!self.ended, "Presenting device ended the BLE session");
        Ok(None)
    }
}

pub struct BtstackBle {
    transport: Mutex<Option<NusbHciTransport>>,
    server: Mutex<Option<GattServer>>,
    peer: Arc<Mutex<Peer>>,
    stopped: AtomicBool,
    events: Arc<dyn EventSink>,
}
impl BtstackBle {
    #[cfg(any(target_os = "android", target_os = "linux"))]
    pub fn from_fd(fd: std::os::fd::OwnedFd, events: Arc<dyn EventSink>) -> Result<Self> {
        let transport =
            btstack_nusb::NusbHciTransport::from_fd(fd).map_err(|e| anyhow!(e.to_string()))?;
        Ok(Self {
            transport: Mutex::new(Some(transport)),
            server: Mutex::new(None),
            peer: Arc::new(Mutex::new(Peer::default())),
            stopped: AtomicBool::new(false),
            events,
        })
    }
    fn check(&self) -> Result<()> {
        ensure!(!self.stopped.load(Ordering::Acquire), "Reading cancelled");
        if let Some(error) = &self.peer.lock().unwrap().failure {
            bail!("{error}");
        }
        Ok(())
    }
    fn poll(&self, server: &GattServer, timeout: Duration) -> Result<()> {
        self.check()?;
        let event = match server.recv_timeout(timeout) {
            Ok(event) => event,
            Err(std::sync::mpsc::RecvTimeoutError::Timeout) => return Ok(()),
            Err(_) => bail!("BTstack runtime stopped"),
        };
        let mut peer = self.peer.lock().unwrap();
        match event {
            ServerEvent::Connected(c) => {
                let _ = peer.accept(&c);
            }
            ServerEvent::Disconnected(c)
                if peer.connection.as_ref().is_some_and(|p| p.id() == c.id()) =>
            {
                peer.failure = Some("BLE disconnected".into())
            }
            ServerEvent::SubscriptionChanged {
                connection,
                characteristic,
                subscription,
            } if characteristic == S2C && peer.accept(&connection).is_ok() => {
                peer.subscribed = subscription == SubscriptionType::Notify;
            }
            ServerEvent::NotificationFailed { status, .. } => {
                peer.failure = Some(format!("BLE notification failed: {status}"))
            }
            ServerEvent::Error(error) => peer.failure = Some(error),
            _ => {}
        }
        drop(peer);
        self.check()
    }
    /// Called off Main after shutdown() has interrupted any blocking operation.
    pub fn close(&self) {
        self.shutdown();
        self.server.lock().unwrap().take();
        self.transport.lock().unwrap().take();
        self.peer.lock().unwrap().packets.clear();
    }
}
impl BlePlatform for BtstackBle {
    fn ble_connect(&self, uuid: String, ident: Vec<u8>, timeout_ms: u64) -> Result<u16> {
        self.check()?;
        ensure!(ident.len() == 16, "Invalid BLE ident");
        let uuid = *uuid::Uuid::parse_str(&uuid)?.as_bytes();
        let deadline = Instant::now() + Duration::from_millis(timeout_ms.min(120_000));
        let mut slot = self.server.lock().unwrap();
        let transport = self
            .transport
            .lock()
            .unwrap()
            .take()
            .ok_or_else(|| anyhow!("BLE session already used"))?;
        let state = self.peer.clone();
        let packets = self.peer.clone();
        let reads = self.peer.clone();
        let server = GattServer::builder(transport)
            .advertise_service_uuid(uuid)
            .name("mdoc USB")
            .service(
                GattService::new(uuid)
                    .characteristic(
                        GattCharacteristic::new(STATE)
                            .write_without_response()
                            .notify()
                            .on_write(move |r| {
                                state.lock().unwrap().write(r.connection, true, r.value())
                            }),
                    )
                    .characteristic(
                        GattCharacteristic::new(C2S)
                            .write_without_response()
                            .on_write(move |r| {
                                packets
                                    .lock()
                                    .unwrap()
                                    .write(r.connection, false, r.value())
                            }),
                    )
                    .characteristic(GattCharacteristic::new(S2C).notify())
                    .characteristic(GattCharacteristic::new(IDENT).read().on_read(move |r| {
                        reads.lock().unwrap().accept(r.connection)?;
                        Ok(ident.clone())
                    })),
            )
            .start()
            .map_err(|e| anyhow!(e.to_string()))?;
        let result = (|| {
            self.check()?;
            self.events.on_event("ble_advertising".into());
            loop {
                ensure!(Instant::now() < deadline, "BLE connection timed out");
                self.poll(&server, Duration::from_millis(20))?;
                let peer = self.peer.lock().unwrap();
                ensure!(!peer.ended, "Presenting device ended the BLE session");
                if peer.started
                    && peer.subscribed
                    && let Some(c) = &peer.connection
                {
                    return Ok(c.mtu().max(23));
                }
            }
        })();
        if result.is_ok() {
            *slot = Some(server);
        }
        result
    }
    fn ble_send(&self, chunk: Vec<u8>) -> Result<()> {
        self.check()?;
        let slot = self.server.lock().unwrap();
        let server = slot.as_ref().ok_or_else(|| anyhow!("BLE not connected"))?;
        let deadline = Instant::now() + Duration::from_secs(10);
        loop {
            self.poll(server, Duration::ZERO)?;
            let peer = self.peer.lock().unwrap();
            ensure!(!peer.ended, "Presenting device ended the BLE session");
            ensure!(peer.subscribed, "BLE notifications are not subscribed");
            let connection = peer
                .connection
                .clone()
                .ok_or_else(|| anyhow!("BLE not connected"))?;
            drop(peer);
            match server.notify(&connection, S2C, &chunk) {
                Ok(()) => return self.poll(server, Duration::ZERO),
                Err(e)
                    if e.to_string() == "Notification queue full" && Instant::now() < deadline =>
                {
                    self.poll(server, Duration::from_millis(10))?;
                }
                Err(e) => bail!("{e}"),
            }
        }
    }
    fn ble_receive(&self, timeout_ms: u64) -> Result<Vec<u8>> {
        let deadline = Instant::now() + Duration::from_millis(timeout_ms.min(120_000));
        let slot = self.server.lock().unwrap();
        let server = slot.as_ref().ok_or_else(|| anyhow!("BLE not connected"))?;
        loop {
            self.poll(server, Duration::ZERO)?;
            {
                let mut peer = self.peer.lock().unwrap();
                // State=2 can follow the final response immediately. Deliver
                // already accepted frames before reporting orderly termination.
                if let Some(packet) = peer.next_packet()? {
                    return Ok(packet);
                }
            }
            ensure!(Instant::now() < deadline, "BLE receive timed out");
            self.poll(server, Duration::from_millis(20))?;
        }
    }
    fn shutdown(&self) {
        self.stopped.store(true, Ordering::Release);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn orderly_termination_preserves_already_received_frames() {
        let mut peer = Peer {
            ended: true,
            packets: VecDeque::from([vec![1, 10], vec![0, 20]]),
            ..Peer::default()
        };
        assert_eq!(peer.next_packet().unwrap(), Some(vec![1, 10]));
        assert_eq!(peer.next_packet().unwrap(), Some(vec![0, 20]));
        assert!(
            peer.next_packet()
                .unwrap_err()
                .to_string()
                .contains("ended")
        );
        assert_eq!(Peer::default().next_packet().unwrap(), None);
    }
}
