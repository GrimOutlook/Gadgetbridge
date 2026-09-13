package nodomain.freeyourgadget.gadgetbridge.devices.colmi;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

public class ColmiR0xSlotPacketTest {
    @Test
    public void decodesH59PacketOneFromByteTwo() {
        final byte[] packet = new byte[16];
        packet[1] = 0x01;
        packet[2] = 31;
        packet[14] = 44;

        final List<ColmiR0xSlotPacket.SlotSample> samples = ColmiR0xSlotPacket.decode(packet, true);

        assertEquals(2, samples.size());
        assertEquals(0, samples.get(0).getSlot());
        assertEquals(31, samples.get(0).getValue());
        assertEquals(12, samples.get(1).getSlot());
        assertEquals(44, samples.get(1).getValue());
    }
}
