package nodomain.freeyourgadget.gadgetbridge.devices.colmi;

import org.junit.Test;

import java.util.Calendar;
import java.util.GregorianCalendar;

import static org.junit.Assert.assertArrayEquals;

public class ColmiR0xTimePacketTest {
    @Test
    public void buildsH59BcdTimePacket() {
        Calendar calendar = new GregorianCalendar(2026, Calendar.SEPTEMBER, 12, 14, 7, 9);

        assertArrayEquals(
                new byte[]{
                        0x01, 0x26, 0x09, 0x12, 0x14, 0x07, 0x09, 0x01,
                        0, 0, 0, 0, 0, 0, 0, 0x67
                },
                ColmiR0xTimePacket.build(calendar, true)
        );
    }

    @Test
    public void buildsLegacyTimePacketWithoutExtendedByte() {
        Calendar calendar = new GregorianCalendar(2026, Calendar.SEPTEMBER, 12, 14, 7, 9);

        assertArrayEquals(
                new byte[]{
                        0x01, 0x26, 0x09, 0x12, 0x14, 0x07, 0x09, 0,
                        0, 0, 0, 0, 0, 0, 0, 0x66
                },
                ColmiR0xTimePacket.build(calendar, false)
        );
    }
}
