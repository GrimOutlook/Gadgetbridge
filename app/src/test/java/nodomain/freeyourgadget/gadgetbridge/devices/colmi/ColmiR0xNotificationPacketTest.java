package nodomain.freeyourgadget.gadgetbridge.devices.colmi;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ColmiR0xNotificationPacketTest {
    @Test
    public void encodesSinglePacketWithChecksum() {
        List<byte[]> packets = ColmiR0xNotificationPacket.encode(
                ColmiR0xConstants.MESSAGE_TYPE_SMS,
                "Hello"
        );

        assertEquals(1, packets.size());
        assertArrayEquals(
                new byte[]{
                        0x72, 0x01, 0x01, 0x01,
                        'H', 'e', 'l', 'l', 'o', 0, 0, 0, 0, 0, 0,
                        0x69
                },
                packets.get(0)
        );
    }

    @Test
    public void splitsMessagesIntoElevenByteChunks() {
        List<byte[]> packets = ColmiR0xNotificationPacket.encode(
                ColmiR0xConstants.MESSAGE_TYPE_GENERIC,
                "Hello world!"
        );

        assertEquals(2, packets.size());
        assertEquals(0x01, packets.get(0)[3]);
        assertEquals(0x02, packets.get(1)[3]);
        assertEquals('d', packets.get(0)[14]);
        assertEquals('!', packets.get(1)[4]);
        assertEquals(0, packets.get(1)[5]);
        assertEquals(0, packets.get(1)[14]);
        assertTrue((packets.get(0)[15] & 0xff) > 0);
        assertTrue((packets.get(1)[15] & 0xff) > 0);
    }

    @Test
    public void ignoresEmptyMessages() {
        assertTrue(ColmiR0xNotificationPacket.encode(
                ColmiR0xConstants.MESSAGE_TYPE_GENERIC,
                ""
        ).isEmpty());
    }
}
