/*  Copyright (C) 2026

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.devices.colmi;

import java.util.Calendar;

import nodomain.freeyourgadget.gadgetbridge.util.BcdUtil;

public final class ColmiR0xTimePacket {
    private ColmiR0xTimePacket() {
    }

    public static byte[] build(final Calendar calendar, final boolean extendedPayload) {
        final byte[] packet = new byte[16];
        packet[0] = ColmiR0xConstants.CMD_SET_DATE_TIME;
        packet[1] = BcdUtil.toBcd8(calendar.get(Calendar.YEAR) % 2000);
        packet[2] = BcdUtil.toBcd8(calendar.get(Calendar.MONTH) + 1);
        packet[3] = BcdUtil.toBcd8(calendar.get(Calendar.DAY_OF_MONTH));
        packet[4] = BcdUtil.toBcd8(calendar.get(Calendar.HOUR_OF_DAY));
        packet[5] = BcdUtil.toBcd8(calendar.get(Calendar.MINUTE));
        packet[6] = BcdUtil.toBcd8(calendar.get(Calendar.SECOND));
        if (extendedPayload) {
            packet[7] = 0x01;
        }

        int checksum = 0;
        for (int i = 0; i < packet.length - 1; i++) {
            checksum = (checksum + packet[i]) & 0xff;
        }
        packet[15] = (byte) checksum;
        return packet;
    }
}
