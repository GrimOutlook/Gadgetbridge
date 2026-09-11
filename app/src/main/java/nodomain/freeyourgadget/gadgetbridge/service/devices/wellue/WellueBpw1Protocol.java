/*
    Copyright (C) 2026 Gadgetbridge contributors

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
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

package nodomain.freeyourgadget.gadgetbridge.service.devices.wellue;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class WellueBpw1Protocol {
    static final int DATA_HEADER = 0xe0;
    static final int ACK_HEADER = 0x0e;
    static final int HEALTH_DATA_ID = 0x03;
    static final int BP_HEALTH_KEY = 0x01;

    private static final int MAX_FRAME_LENGTH = 4096;

    private WellueBpw1Protocol() {
    }

    static byte[] getDeviceFeatures() {
        return buildFrame(0x00, 0x00, 0, new byte[]{0x00});
    }

    static byte[] syncData(final int deviceVersion) {
        return buildFrame(0x01, 0x00, deviceVersion, new byte[]{0x01});
    }

    static byte[] buildAck(final int commandId, final int key, final int status, final int deviceVersion) {
        return buildFrame(ACK_HEADER, commandId, deviceVersion, key, new byte[]{(byte) status});
    }

    static byte[] buildFrame(final int commandId, final int key, final int deviceVersion, final byte[] payload) {
        return buildFrame(DATA_HEADER, commandId, deviceVersion, key, payload);
    }

    private static byte[] buildFrame(
            final int header,
            final int commandId,
            final int deviceVersion,
            final int key,
            final byte[] payload
    ) {
        if ((header != DATA_HEADER && header != ACK_HEADER)
                || commandId < 0 || commandId > 0xff
                || deviceVersion < 0 || deviceVersion > 0xff
                || key < 0 || key > 0xff
                || payload == null || payload.length > 0xfffa) {
            throw new IllegalArgumentException("invalid MT frame fields");
        }

        final int declaredLength = payload.length + 5;
        final byte[] logical = new byte[8 + payload.length];
        logical[0] = (byte) header;
        logical[1] = (byte) (declaredLength >> 8);
        logical[2] = (byte) declaredLength;
        logical[3] = (byte) commandId;
        logical[4] = (byte) deviceVersion;
        logical[5] = (byte) key;
        logical[6] = (byte) (payload.length >> 8);
        logical[7] = (byte) payload.length;
        System.arraycopy(payload, 0, logical, 8, payload.length);

        final byte[] frame = new byte[logical.length + 1];
        System.arraycopy(logical, 0, frame, 0, 3);
        frame[3] = (byte) sumAll(logical);
        System.arraycopy(logical, 3, frame, 4, logical.length - 3);
        return frame;
    }

    static Frame parseFrame(final byte[] raw) {
        if (raw == null || raw.length < 9) {
            throw new IllegalArgumentException("MT frame is too short");
        }
        final int header = raw[0] & 0xff;
        if (header != DATA_HEADER && header != ACK_HEADER) {
            throw new IllegalArgumentException("unsupported MT frame header");
        }

        final int declaredLength = u16(raw, 1);
        if (declaredLength < 5 || declaredLength + 4 != raw.length) {
            throw new IllegalArgumentException("MT frame length mismatch");
        }

        final int payloadLength = u16(raw, 7);
        if (payloadLength + 5 != declaredLength || payloadLength != raw.length - 9) {
            throw new IllegalArgumentException("MT payload length mismatch");
        }
        if ((raw[3] & 0xff) != checksum(raw)) {
            throw new IllegalArgumentException("MT checksum mismatch");
        }

        return new Frame(
                header,
                declaredLength,
                raw[4] & 0xff,
                raw[5] & 0xff,
                raw[6] & 0xff,
                Arrays.copyOfRange(raw, 9, raw.length),
                Arrays.copyOf(raw, raw.length)
        );
    }

    private static int u16(final byte[] data, final int offset) {
        return ((data[offset] & 0xff) << 8) | (data[offset + 1] & 0xff);
    }

    private static int checksum(final byte[] data) {
        int result = 0;
        for (int i = 0; i < data.length; i++) {
            if (i != 3) {
                result = (result + (data[i] & 0xff)) & 0xff;
            }
        }
        return result;
    }

    private static int sumAll(final byte[] data) {
        int result = 0;
        for (byte value : data) {
            result = (result + (value & 0xff)) & 0xff;
        }
        return result;
    }

    static BloodPressureBatch decodeBloodPressure(final Frame frame) {
        if (frame.header != DATA_HEADER
                || frame.commandId != HEALTH_DATA_ID
                || frame.key != BP_HEALTH_KEY
                || frame.payload.length < 4) {
            throw new IllegalArgumentException("not a BP health frame");
        }

        final byte[] payload = frame.payload;
        final PackedDate date = decodePackedDate(payload[0], payload[1]);
        final int count = u16(payload, 2);
        if (payload.length - 4 != count * 8) {
            throw new IllegalArgumentException("BP record count mismatch");
        }

        final List<BloodPressureRecord> records = new ArrayList<>(count);
        for (int offset = 4; offset < payload.length; offset += 8) {
            final byte[] raw = Arrays.copyOfRange(payload, offset, offset + 8);
            records.add(new BloodPressureRecord(
                    readU32(raw, 0),
                    raw[4] & 0xff,
                    raw[5] & 0xff,
                    raw[6] & 0xff,
                    raw[7] & 0xff,
                    raw
            ));
        }
        return new BloodPressureBatch(date, records);
    }

    private static long readU32(final byte[] data, final int offset) {
        return ((long) (data[offset] & 0xff) << 24)
                | ((long) (data[offset + 1] & 0xff) << 16)
                | ((long) (data[offset + 2] & 0xff) << 8)
                | (data[offset + 3] & 0xffL);
    }

    private static PackedDate decodePackedDate(final int first, final int second) {
        return new PackedDate(
                ((first & 0x7f) >> 1) + 2000,
                ((first & 0x01) << 3) | ((second & 0xe0) >> 5),
                second & 0x1f
        );
    }

    static final class Frame {
        final int header;
        final int declaredLength;
        final int commandId;
        final int deviceVersion;
        final int key;
        final byte[] payload;
        final byte[] raw;

        private Frame(
                final int header,
                final int declaredLength,
                final int commandId,
                final int deviceVersion,
                final int key,
                final byte[] payload,
                final byte[] raw
        ) {
            this.header = header;
            this.declaredLength = declaredLength;
            this.commandId = commandId;
            this.deviceVersion = deviceVersion;
            this.key = key;
            this.payload = payload;
            this.raw = raw;
        }

        boolean isAck() {
            return header == ACK_HEADER;
        }
    }

    static final class StreamParser {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        void reset() {
            buffer.reset();
        }

        List<Frame> feed(final byte[] fragment) {
            if (fragment == null || fragment.length == 0) {
                return List.of();
            }
            buffer.write(fragment, 0, fragment.length);

            final List<Frame> frames = new ArrayList<>();
            while (buffer.size() > 0) {
                final byte[] data = buffer.toByteArray();
                final int start = findHeader(data);
                if (start < 0) {
                    buffer.reset();
                    break;
                }
                if (start > 0) {
                    retain(data, start);
                    continue;
                }
                if (data.length < 3) {
                    break;
                }

                final int declaredLength = u16(data, 1);
                final int totalLength = declaredLength + 4;
                if (declaredLength < 5 || totalLength > MAX_FRAME_LENGTH) {
                    retain(data, 1);
                    continue;
                }
                if (data.length < totalLength) {
                    break;
                }

                final byte[] candidate = Arrays.copyOf(data, totalLength);
                try {
                    frames.add(parseFrame(candidate));
                    retain(data, totalLength);
                } catch (IllegalArgumentException e) {
                    // Drop one byte and resynchronize on a later valid header.
                    retain(data, 1);
                }
            }
            return frames;
        }

        private static int findHeader(final byte[] data) {
            for (int i = 0; i < data.length; i++) {
                final int value = data[i] & 0xff;
                if (value == DATA_HEADER || value == ACK_HEADER) {
                    return i;
                }
            }
            return -1;
        }

        private void retain(final byte[] data, final int offset) {
            buffer.reset();
            buffer.write(data, offset, data.length - offset);
        }
    }

    static final class PackedDate {
        final int year;
        final int month;
        final int day;

        private PackedDate(final int year, final int month, final int day) {
            this.year = year;
            this.month = month;
            this.day = day;
        }
    }

    static final class BloodPressureRecord {
        final long timeSeconds;
        final int mode;
        final int auxiliary;
        final int systolic;
        final int diastolic;
        final byte[] raw;

        private BloodPressureRecord(
                final long timeSeconds,
                final int mode,
                final int auxiliary,
                final int systolic,
                final int diastolic,
                final byte[] raw
        ) {
            this.timeSeconds = timeSeconds;
            this.mode = mode;
            this.auxiliary = auxiliary;
            this.systolic = systolic;
            this.diastolic = diastolic;
            this.raw = raw;
        }
    }

    static final class BloodPressureBatch {
        final PackedDate date;
        final List<BloodPressureRecord> records;

        private BloodPressureBatch(final PackedDate date, final List<BloodPressureRecord> records) {
            this.date = date;
            this.records = records;
        }
    }
}
