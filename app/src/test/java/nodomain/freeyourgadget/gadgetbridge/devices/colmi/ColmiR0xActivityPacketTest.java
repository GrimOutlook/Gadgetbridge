package nodomain.freeyourgadget.gadgetbridge.devices.colmi;

import org.junit.Test;

import nodomain.freeyourgadget.gadgetbridge.entities.ColmiActivitySample;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ColmiR0xActivityPacketTest {
    @Test
    public void decodesBcdDateQuarterHourAndMetrics() {
        byte[] packet = new byte[16];
        packet[1] = 0x24;
        packet[2] = 0x08;
        packet[3] = 0x18;
        packet[4] = 0x07;
        packet[5] = 0x02;
        packet[6] = 0x04;
        packet[7] = 0x34;
        packet[8] = 0x12;
        packet[9] = 0x78;
        packet[10] = 0x56;
        packet[11] = (byte) 0x9a;

        ColmiR0xActivityPacket.ActivitySample sample = ColmiR0xActivityPacket.decode(packet);

        assertEquals(2024, sample.getYear());
        assertEquals(8, sample.getMonth());
        assertEquals(18, sample.getDay());
        assertEquals(1, sample.getHour());
        assertEquals(45, sample.getMinute());
        assertEquals(0x1234, sample.getCalories());
        assertEquals(0x1234 * 10, sample.getCalories(true));
        assertEquals(0x1234, sample.getCalories(false));
        assertEquals(0x5678, sample.getSteps());
        assertEquals(0x009a, sample.getDistance());
        assertEquals(2, sample.getCurrentPacket());
        assertEquals(4, sample.getTotalPackets());
    }

    @Test
    public void ignoresControlPackets() {
        byte[] header = new byte[16];
        header[1] = (byte) 0xf0;
        header[3] = 0x01;
        byte[] empty = new byte[16];
        empty[1] = (byte) 0xff;

        assertNull(ColmiR0xActivityPacket.decode(header));
        assertNull(ColmiR0xActivityPacket.decode(empty));
        assertTrue(ColmiR0xActivityPacket.usesNewCalorieProtocol(header));
    }

    @Test
    public void decodesTodaySummary() {
        byte[] packet = new byte[16];
        packet[0] = ColmiR0xConstants.CMD_SYNC_TODAY_ACTIVITY;
        packet[1] = 0x01;
        packet[2] = 0x02;
        packet[3] = 0x03;
        packet[7] = 0x04;
        packet[8] = 0x05;
        packet[9] = 0x06;
        packet[10] = 0x07;
        packet[11] = 0x08;
        packet[12] = 0x09;

        ColmiR0xActivityPacket.TodaySummary summary = ColmiR0xActivityPacket.decodeTodaySummary(packet);

        assertEquals(0x010203, summary.getSteps());
        assertEquals(0x040506, summary.getCalories());
        assertEquals(0x070809, summary.getDistance());
    }

    @Test
    public void exposesColmiMetricsInStandardUnits() {
        ColmiActivitySample sample = new ColmiActivitySample();
        sample.setDistance(123);
        sample.setCalories(42);

        assertEquals(12300, sample.getDistanceCm());
        assertEquals(42, sample.getActiveCalories());

        sample.setDistanceCm(ColmiActivitySample.NOT_MEASURED);
        sample.setActiveCalories(ColmiActivitySample.NOT_MEASURED);
        assertEquals(ColmiActivitySample.NOT_MEASURED, sample.getDistanceCm());
        assertEquals(ColmiActivitySample.NOT_MEASURED, sample.getActiveCalories());
    }
}
