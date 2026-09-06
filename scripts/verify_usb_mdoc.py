# /// script
# requires-python = ">=3.11"
# dependencies = ["bleak>=1.0,<3"]
# ///
"""Pair with UsbGattWireTest; only connects to its dedicated test service UUID."""
import asyncio
from bleak import BleakClient, BleakScanner

SERVICE = "c6f0a001-481e-4fd8-9efd-53736e198a60"
STATE = "00000005-a123-48ce-896b-4c76973373e6"
C2S = "00000006-a123-48ce-896b-4c76973373e6"
S2C = "00000007-a123-48ce-896b-4c76973373e6"
IDENT = "00000008-a123-48ce-896b-4c76973373e6"


async def main():
    for round_index in range(2):
        device = await BleakScanner.find_device_by_filter(
            lambda _, ad: SERVICE in ad.service_uuids, timeout=120
        )
        assert device, "mdoc test service must be present in advertising"
        async with BleakClient(device, timeout=30) as client:
            service = client.services.get_service(SERVICE)
            assert service, "Negotiated GATT service missing"
            assert bytes(await client.read_gatt_char(IDENT)) == bytes(range(16))
            packets = asyncio.Queue()
            await client.start_notify(S2C, lambda _, value: packets.put_nowait(bytes(value)))
            await client.start_notify(STATE, lambda *_: None)
            await client.write_gatt_char(STATE, b"\x01", response=False)
            for index in range(200):
                expected = bytes([0 if index == 199 else 1, index])
                assert await asyncio.wait_for(packets.get(), 30) == expected
            for index in range(200):
                await client.write_gatt_char(C2S, bytes([0 if index == 199 else 1, index]), response=False)
                await asyncio.sleep(0.01)
            await client.write_gatt_char(STATE, b"\x02", response=False)
            await asyncio.sleep(0.2)
        print(f"Round {round_index + 1}: UUID advertisement, discovery, Ident, State, 200 frames each way passed", flush=True)
        await asyncio.sleep(2)


if __name__ == "__main__":
    asyncio.run(main())
