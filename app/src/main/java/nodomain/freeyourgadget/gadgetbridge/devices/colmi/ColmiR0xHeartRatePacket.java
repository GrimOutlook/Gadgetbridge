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

import java.util.ArrayList;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.service.btle.BLETypeConversions;

public final class ColmiR0xHeartRatePacket {
    public static final int SAMPLES_PER_DAY = 24 * 60 / 5;

    private static final int FIRST_PACKET_DATA_START = 6;
    private static final int FIRST_PACKET_SAMPLES = 9;
    private static final int OTHER_PACKET_DATA_START = 2;
    private static final int OTHER_PACKET_SAMPLES = 13;

    private ColmiR0xHeartRatePacket() {
    }

    public static boolean isEmpty(final byte[] value) {
        return value != null && value.length > 1 && (value[1] & 0xff) == 0xff;
    }

    public static boolean isHeader(final byte[] value) {
        return value != null && value.length > 1 && (value[1] & 0xff) == 0;
    }

    public static int getPacketNumber(final byte[] value) {
        return value[1] & 0xff;
    }

    public static int getTotalPackets(final byte[] value) {
        return value[2] & 0xff;
    }

    public static int getSyncTimestamp(final byte[] value) {
        return BLETypeConversions.toUint32(value[2], value[3], value[4], value[5]);
    }

    public static List<HeartRateSample> decodeSamples(final byte[] value) {
        final List<HeartRateSample> samples = new ArrayList<>();
        if (value == null || value.length < 16 || isEmpty(value) || isHeader(value)) {
            return samples;
        }

        final int packetNumber = getPacketNumber(value);
        final int dataStart = packetNumber == 1 ? FIRST_PACKET_DATA_START : OTHER_PACKET_DATA_START;
        final int firstSlot = packetNumber == 1
                ? 0
                : FIRST_PACKET_SAMPLES + (packetNumber - 2) * OTHER_PACKET_SAMPLES;
        final int dataEnd = value.length - 1;

        for (int index = dataStart; index < dataEnd; index++) {
            final int slot = firstSlot + index - dataStart;
            if (slot >= SAMPLES_PER_DAY) {
                break;
            }
            final int heartRate = value[index] & 0xff;
            if (heartRate != 0) {
                samples.add(new HeartRateSample(slot * 5, heartRate));
            }
        }
        return samples;
    }

    public static final class HeartRateSample {
        private final int minuteOfDay;
        private final int heartRate;

        private HeartRateSample(final int minuteOfDay, final int heartRate) {
            this.minuteOfDay = minuteOfDay;
            this.heartRate = heartRate;
        }

        public int getMinuteOfDay() {
            return minuteOfDay;
        }

        public int getHeartRate() {
            return heartRate;
        }
    }
}
