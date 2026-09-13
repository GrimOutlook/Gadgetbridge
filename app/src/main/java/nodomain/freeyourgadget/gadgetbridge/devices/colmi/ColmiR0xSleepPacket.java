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
import java.util.Collections;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.service.btle.BLETypeConversions;

public final class ColmiR0xSleepPacket {
    private static final int HEADER_LENGTH = 6;

    private ColmiR0xSleepPacket() {
    }

    public static List<SleepSession> decode(final byte[] value) {
        if (value == null
                || value.length < HEADER_LENGTH + 1
                || (value[0] & 0xff) != (ColmiR0xConstants.CMD_BIG_DATA_V2 & 0xff)
                || (value[1] & 0xff) != (ColmiR0xConstants.BIG_DATA_TYPE_SLEEP & 0xff)) {
            return Collections.emptyList();
        }

        final int payloadLength = BLETypeConversions.toUint16(value[2], value[3]);
        final int dataEnd = Math.min(value.length, HEADER_LENGTH + payloadLength);
        final int daysInPacket = value[HEADER_LENGTH] & 0xff;
        final List<SleepSession> sessions = new ArrayList<>();
        int index = HEADER_LENGTH + 1;

        for (int day = 0; day < daysInPacket; day++) {
            if (index + 6 > dataEnd) {
                break;
            }

            final int daysAgo = value[index] & 0xff;
            final int sleepStart = BLETypeConversions.toUint16(value[index + 2], value[index + 3]);
            final int sleepEnd = BLETypeConversions.toUint16(value[index + 4], value[index + 5]);
            final int sleepDuration = (sleepEnd - sleepStart + 1440) % 1440;
            final List<SleepStage> stages = new ArrayList<>();
            int stageIndex = index + 6;
            int accumulatedDuration = 0;
            while (stageIndex + 1 < dataEnd && accumulatedDuration < sleepDuration) {
                final int stage = value[stageIndex] & 0xff;
                final int duration = value[stageIndex + 1] & 0xff;
                if (stage < ColmiR0xConstants.SLEEP_TYPE_LIGHT || stage > ColmiR0xConstants.SLEEP_TYPE_AWAKE) {
                    break;
                }
                if (duration > 0) {
                    stages.add(new SleepStage(stage, duration));
                }
                accumulatedDuration += duration;
                stageIndex += 2;
            }

            if (!stages.isEmpty()) {
                sessions.add(new SleepSession(daysAgo, sleepStart, sleepEnd, stages));
            }
            index = stageIndex;
        }

        return sessions;
    }

    public static final class SleepSession {
        private final int daysAgo;
        private final int sleepStart;
        private final int sleepEnd;
        private final List<SleepStage> stages;

        private SleepSession(
                final int daysAgo,
                final int sleepStart,
                final int sleepEnd,
                final List<SleepStage> stages
        ) {
            this.daysAgo = daysAgo;
            this.sleepStart = sleepStart;
            this.sleepEnd = sleepEnd;
            this.stages = stages;
        }

        public int getDaysAgo() {
            return daysAgo;
        }

        public int getSleepStart() {
            return sleepStart;
        }

        public int getSleepEnd() {
            return sleepEnd;
        }

        public List<SleepStage> getStages() {
            return stages;
        }
    }

    public static final class SleepStage {
        private final int stage;
        private final int duration;

        private SleepStage(final int stage, final int duration) {
            this.stage = stage;
            this.duration = duration;
        }

        public int getStage() {
            return stage;
        }

        public int getDuration() {
            return duration;
        }
    }
}
