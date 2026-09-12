package nodomain.freeyourgadget.gadgetbridge.devices.colmi;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ColmiR0xHeartRatePacketTest {
    @Test
    public void decodesHeaderAndFirstPacket() {
        byte[] header = new byte[16];
        header[1] = 0;
        header[2] = 24;
        header[3] = 5;

        assertTrue(ColmiR0xHeartRatePacket.isHeader(header));
        assertEquals(24, ColmiR0xHeartRatePacket.getTotalPackets(header));

        byte[] packet = new byte[16];
        packet[1] = 1;
        packet[2] = (byte) 0xe9;
        packet[3] = 0x25;
        packet[4] = (byte) 0xa5;
        packet[5] = 0x6a;
        packet[6] = 81;
        packet[14] = 72;

        List<ColmiR0xHeartRatePacket.HeartRateSample> samples = ColmiR0xHeartRatePacket.decodeSamples(packet);

        assertEquals(0x6aa525e9, ColmiR0xHeartRatePacket.getSyncTimestamp(packet));
        assertEquals(2, samples.size());
        assertEquals(0, samples.get(0).getMinuteOfDay());
        assertEquals(81, samples.get(0).getHeartRate());
        assertEquals(40, samples.get(1).getMinuteOfDay());
        assertEquals(72, samples.get(1).getHeartRate());
    }

    @Test
    public void ignoresEmptyPacketsAndSamplesAfterTheDay() {
        byte[] empty = new byte[16];
        empty[1] = (byte) 0xff;
        assertTrue(ColmiR0xHeartRatePacket.isEmpty(empty));
        assertTrue(ColmiR0xHeartRatePacket.decodeSamples(empty).isEmpty());

        byte[] lastPacket = new byte[16];
        lastPacket[1] = 23;
        for (int i = 2; i < 15; i++) {
            lastPacket[i] = 70;
        }

        List<ColmiR0xHeartRatePacket.HeartRateSample> samples = ColmiR0xHeartRatePacket.decodeSamples(lastPacket);

        assertEquals(6, samples.size());
        assertEquals(1435, samples.get(5).getMinuteOfDay());
    }
}
