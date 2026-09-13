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

public final class ColmiR0xSlotPacket {
    private ColmiR0xSlotPacket() {
    }

    public static List<SlotSample> decode(final byte[] value, final boolean h59Layout) {
        if (value == null || value.length < 3) {
            return Collections.emptyList();
        }

        final int packetNumber = value[1] & 0xff;
        if (packetNumber == 0 || packetNumber == 0xff) {
            return Collections.emptyList();
        }

        final int firstValue = h59Layout || packetNumber != 1 ? 2 : 3;
        if (firstValue >= value.length - 1) {
            return Collections.emptyList();
        }

        final int firstSlot;
        if (h59Layout) {
            firstSlot = (packetNumber - 1) * 13;
        } else if (packetNumber == 1) {
            firstSlot = 0;
        } else {
            firstSlot = 12 + (packetNumber - 2) * 13;
        }

        final List<SlotSample> samples = new ArrayList<>();
        for (int i = firstValue; i < value.length - 1; i++) {
            final int measurement = value[i] & 0xff;
            if (measurement != 0) {
                samples.add(new SlotSample(firstSlot + i - firstValue, measurement));
            }
        }
        return samples;
    }

    public static final class SlotSample {
        private final int slot;
        private final int value;

        private SlotSample(final int slot, final int value) {
            this.slot = slot;
            this.value = value;
        }

        public int getSlot() {
            return slot;
        }

        public int getValue() {
            return value;
        }
    }
}
