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

import nodomain.freeyourgadget.gadgetbridge.service.btle.BLETypeConversions;
import nodomain.freeyourgadget.gadgetbridge.util.BcdUtil;

public final class ColmiR0xActivityPacket {
    private static final int MIN_DATA_LENGTH = 13;
    private static final int TODAY_SUMMARY_MIN_LENGTH = 15;

    private ColmiR0xActivityPacket() {
    }

    public static boolean isEmpty(final byte[] value) {
        return value != null && value.length > 1 && (value[1] & 0xff) == 0xff;
    }

    public static boolean isHeader(final byte[] value) {
        return value != null && value.length > 1 && (value[1] & 0xff) == 0xf0;
    }

    public static boolean usesNewCalorieProtocol(final byte[] value) {
        return isHeader(value) && value.length > 3 && value[3] == 0x01;
    }

    public static ActivitySample decode(final byte[] value) {
        if (value == null || value.length < MIN_DATA_LENGTH || isEmpty(value) || isHeader(value)) {
            return null;
        }

        return new ActivitySample(
                BcdUtil.fromBcd8(value[1]) + 2000,
                BcdUtil.fromBcd8(value[2]),
                BcdUtil.fromBcd8(value[3]),
                (value[4] & 0xff) / 4,
                ((value[4] & 0xff) % 4) * 15,
                BLETypeConversions.toUint16(value[7], value[8]),
                BLETypeConversions.toUint16(value[9], value[10]),
                BLETypeConversions.toUint16(value[11], value[12]),
                value[5] & 0xff,
                value[6] & 0xff
        );
    }

    public static TodaySummary decodeTodaySummary(final byte[] value) {
        if (value == null
                || value.length < TODAY_SUMMARY_MIN_LENGTH
                || (value[0] & 0xff) != (ColmiR0xConstants.CMD_SYNC_TODAY_ACTIVITY & 0xff)) {
            return null;
        }

        return new TodaySummary(
                uint24BigEndian(value, 1),
                uint24BigEndian(value, 7),
                uint24BigEndian(value, 10)
        );
    }

    private static int uint24BigEndian(final byte[] value, final int offset) {
        return ((value[offset] & 0xff) << 16)
                | ((value[offset + 1] & 0xff) << 8)
                | (value[offset + 2] & 0xff);
    }

    public static final class ActivitySample {
        private final int year;
        private final int month;
        private final int day;
        private final int hour;
        private final int minute;
        private final int calories;
        private final int steps;
        private final int distance;
        private final int currentPacket;
        private final int totalPackets;

        private ActivitySample(
                final int year,
                final int month,
                final int day,
                final int hour,
                final int minute,
                final int calories,
                final int steps,
                final int distance,
                final int currentPacket,
                final int totalPackets
        ) {
            this.year = year;
            this.month = month;
            this.day = day;
            this.hour = hour;
            this.minute = minute;
            this.calories = calories;
            this.steps = steps;
            this.distance = distance;
            this.currentPacket = currentPacket;
            this.totalPackets = totalPackets;
        }

        public int getYear() {
            return year;
        }

        public int getMonth() {
            return month;
        }

        public int getDay() {
            return day;
        }

        public int getHour() {
            return hour;
        }

        public int getMinute() {
            return minute;
        }

        public int getCalories() {
            return calories;
        }

        public int getCalories(final boolean calorieNewProtocol) {
            return calorieNewProtocol ? calories * 10 : calories;
        }

        public int getSteps() {
            return steps;
        }

        public int getDistance() {
            return distance;
        }

        public int getCurrentPacket() {
            return currentPacket;
        }

        public int getTotalPackets() {
            return totalPackets;
        }
    }

    public static final class TodaySummary {
        private final int steps;
        private final int calories;
        private final int distance;

        private TodaySummary(final int steps, final int calories, final int distance) {
            this.steps = steps;
            this.calories = calories;
            this.distance = distance;
        }

        public int getSteps() {
            return steps;
        }

        public int getCalories() {
            return calories;
        }

        public int getDistance() {
            return distance;
        }
    }
}
