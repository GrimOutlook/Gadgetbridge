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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ColmiR0xNotificationPacket {
    private static final int MAX_MESSAGE_LENGTH = 256;
    private static final int MESSAGE_CHUNK_SIZE = 11;
    private static final int PACKET_LENGTH = 16;

    private ColmiR0xNotificationPacket() {
    }

    public static List<byte[]> encode(byte type, String message) {
        if (message == null || message.isEmpty()) {
            return Collections.emptyList();
        }

        if (message.length() > MAX_MESSAGE_LENGTH) {
            message = message.substring(0, MAX_MESSAGE_LENGTH);
        }

        byte[] messageBytes = message.getBytes(StandardCharsets.UTF_8);
        int packetCount = (messageBytes.length + MESSAGE_CHUNK_SIZE - 1) / MESSAGE_CHUNK_SIZE;
        List<byte[]> packets = new ArrayList<>(packetCount);

        for (int packetIndex = 0; packetIndex < packetCount; packetIndex++) {
            byte[] packet = new byte[PACKET_LENGTH];
            packet[0] = ColmiR0xConstants.CMD_PUSH_MESSAGE;
            packet[1] = type;
            packet[2] = (byte) packetCount;
            packet[3] = (byte) (packetIndex + 1);

            int messageOffset = packetIndex * MESSAGE_CHUNK_SIZE;
            int chunkLength = Math.min(MESSAGE_CHUNK_SIZE, messageBytes.length - messageOffset);
            System.arraycopy(messageBytes, messageOffset, packet, 4, chunkLength);

            int checksum = 0;
            for (int i = 0; i < PACKET_LENGTH - 1; i++) {
                checksum += packet[i];
            }
            packet[PACKET_LENGTH - 1] = (byte) checksum;
            packets.add(packet);
        }

        return packets;
    }
}
