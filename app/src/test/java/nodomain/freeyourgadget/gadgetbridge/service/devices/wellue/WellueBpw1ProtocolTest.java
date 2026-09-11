package nodomain.freeyourgadget.gadgetbridge.service.devices.wellue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

public class WellueBpw1ProtocolTest {
    @Test
    public void commandsUseMasWearMtFraming() {
        assertEquals(
                "e0 00 06 e7 00 00 00 00 01 00",
                hex(WellueBpw1Protocol.getDeviceFeatures())
        );
        assertEquals(
                "e0 00 06 ea 01 01 00 00 01 01",
                hex(WellueBpw1Protocol.syncData(1))
        );
        assertEquals(
                "0e 00 06 1a 03 01 01 00 01 00",
                hex(WellueBpw1Protocol.buildAck(3, 1, 0, 1))
        );
    }

    @Test
    public void reassemblesLiveFeatureResponse() {
        final WellueBpw1Protocol.StreamParser parser = new WellueBpw1Protocol.StreamParser();
        final byte[] first = bytes(
                "e0 00 20 a0 00 01 00 00 1b 01 ff 01 04 00 00 da 1e 00 d8 20"
        );
        final byte[] second = bytes(
                "08 10 00 00 5f 50 00 00 00 22 04 82 00 00 00 20"
        );

        assertTrue(parser.feed(first).isEmpty());
        final List<WellueBpw1Protocol.Frame> frames = parser.feed(second);
        assertEquals(1, frames.size());
        assertEquals(0, frames.get(0).commandId);
        assertEquals(1, frames.get(0).deviceVersion);
        assertEquals(27, frames.get(0).payload.length);
    }

    @Test
    public void decodesBloodPressureRecords() {
        final byte[] payload = bytes(
                "35 2a 00 02 "
                        + "00 00 77 88 01 48 78 50 "
                        + "00 00 a9 ec 02 41 82 55"
        );
        final WellueBpw1Protocol.Frame frame = WellueBpw1Protocol.parseFrame(
                WellueBpw1Protocol.buildFrame(3, 1, 1, payload)
        );

        final WellueBpw1Protocol.BloodPressureBatch batch =
                WellueBpw1Protocol.decodeBloodPressure(frame);
        assertEquals(2026, batch.date.year);
        assertEquals(9, batch.date.month);
        assertEquals(10, batch.date.day);
        assertEquals(2, batch.records.size());
        assertEquals(120, batch.records.get(0).systolic);
        assertEquals(80, batch.records.get(0).diastolic);
        assertEquals(130, batch.records.get(1).systolic);
        assertEquals(85, batch.records.get(1).diastolic);
    }

    private static byte[] bytes(final String value) {
        final String[] parts = value.split(" ");
        final byte[] result = new byte[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = (byte) Integer.parseInt(parts[i], 16);
        }
        return result;
    }

    private static String hex(final byte[] value) {
        final StringBuilder result = new StringBuilder(value.length * 3 - 1);
        for (byte b : value) {
            if (result.length() > 0) {
                result.append(' ');
            }
            result.append(String.format("%02x", b & 0xff));
        }
        return result.toString();
    }
}
