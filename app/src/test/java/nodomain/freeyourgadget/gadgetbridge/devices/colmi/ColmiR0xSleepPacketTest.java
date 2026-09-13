package nodomain.freeyourgadget.gadgetbridge.devices.colmi;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

public class ColmiR0xSleepPacketTest {
    @Test
    public void decodesH59SleepRecord() {
        final byte[] packet = new byte[19];
        packet[0] = ColmiR0xConstants.CMD_BIG_DATA_V2;
        packet[1] = ColmiR0xConstants.BIG_DATA_TYPE_SLEEP;
        packet[2] = 13;
        packet[6] = 1;
        packet[7] = 1;
        packet[8] = 0;
        packet[9] = (byte) 0x64;
        packet[10] = 0x05;
        packet[11] = (byte) 0xa4;
        packet[12] = 0x01;
        packet[13] = 0x02;
        packet[14] = 120;
        packet[15] = 0x03;
        packet[16] = (byte) 180;
        packet[17] = 0x04;
        packet[18] = (byte) 180;

        final List<ColmiR0xSleepPacket.SleepSession> sessions = ColmiR0xSleepPacket.decode(packet);

        assertEquals(1, sessions.size());
        final ColmiR0xSleepPacket.SleepSession session = sessions.get(0);
        assertEquals(1, session.getDaysAgo());
        assertEquals(1380, session.getSleepStart());
        assertEquals(420, session.getSleepEnd());
        assertEquals(3, session.getStages().size());
        assertEquals(2, session.getStages().get(0).getStage());
        assertEquals(120, session.getStages().get(0).getDuration());
        assertEquals(3, session.getStages().get(1).getStage());
        assertEquals(180, session.getStages().get(1).getDuration());
        assertEquals(4, session.getStages().get(2).getStage());
        assertEquals(180, session.getStages().get(2).getDuration());
    }
}
