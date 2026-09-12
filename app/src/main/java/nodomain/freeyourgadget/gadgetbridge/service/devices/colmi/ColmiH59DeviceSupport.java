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
package nodomain.freeyourgadget.gadgetbridge.service.devices.colmi;

import android.bluetooth.BluetoothGattCharacteristic;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.devices.colmi.ColmiR0xConstants;
import nodomain.freeyourgadget.gadgetbridge.devices.colmi.ColmiR0xNotificationPacket;
import nodomain.freeyourgadget.gadgetbridge.model.CallSpec;
import nodomain.freeyourgadget.gadgetbridge.model.NotificationSpec;
import nodomain.freeyourgadget.gadgetbridge.model.NotificationType;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.util.StringUtils;

public class ColmiH59DeviceSupport extends ColmiR0xDeviceSupport {
    private static final Logger LOG = LoggerFactory.getLogger(ColmiH59DeviceSupport.class);

    private String lastCallMessage;

    public ColmiH59DeviceSupport() {
        super(true);
    }

    @Override
    public void onNotification(NotificationSpec notificationSpec) {
        if (notificationSpec == null) {
            return;
        }

        String message = StringUtils.join(
                ": ",
                notificationSpec.sender,
                notificationSpec.title,
                notificationSpec.subject,
                notificationSpec.body
        ).toString();
        if (message.isEmpty()) {
            message = notificationSpec.sourceName;
        }

        sendPhoneMessage(getMessageType(notificationSpec.type), message);
    }

    @Override
    public void onSetCallState(CallSpec callSpec) {
        if (callSpec == null) {
            return;
        }

        switch (callSpec.command) {
            case CallSpec.CALL_INCOMING:
                lastCallMessage = getCallMessage(callSpec, "Incoming call");
                sendPhoneMessage(ColmiR0xConstants.MESSAGE_TYPE_PHONE_RING, lastCallMessage);
                break;
            case CallSpec.CALL_ACCEPT:
            case CallSpec.CALL_REJECT:
            case CallSpec.CALL_END:
                if (lastCallMessage != null) {
                    sendPhoneMessage(ColmiR0xConstants.MESSAGE_TYPE_PHONE_ACTION, lastCallMessage);
                    lastCallMessage = null;
                }
                break;
            default:
                break;
        }
    }

    private byte getMessageType(NotificationType type) {
        if (type == null) {
            return ColmiR0xConstants.MESSAGE_TYPE_GENERIC;
        }

        switch (type) {
            case GENERIC_PHONE:
                return ColmiR0xConstants.MESSAGE_TYPE_PHONE_RING;
            case GENERIC_SMS:
                return ColmiR0xConstants.MESSAGE_TYPE_SMS;
            case FACEBOOK:
            case FACEBOOK_MESSENGER:
                return ColmiR0xConstants.MESSAGE_TYPE_FACEBOOK;
            case INSTAGRAM:
                return ColmiR0xConstants.MESSAGE_TYPE_INSTAGRAM;
            case LINE:
                return ColmiR0xConstants.MESSAGE_TYPE_LINE;
            case LINKEDIN:
                return ColmiR0xConstants.MESSAGE_TYPE_LINKEDIN;
            case SKYPE:
                return ColmiR0xConstants.MESSAGE_TYPE_SKYPE;
            case SNAPCHAT:
                return ColmiR0xConstants.MESSAGE_TYPE_SNAPCHAT;
            case TWITTER:
                return ColmiR0xConstants.MESSAGE_TYPE_TWITTER;
            case WECHAT:
                return ColmiR0xConstants.MESSAGE_TYPE_WECHAT;
            case WHATSAPP:
                return ColmiR0xConstants.MESSAGE_TYPE_WHATSAPP;
            default:
                return ColmiR0xConstants.MESSAGE_TYPE_GENERIC;
        }
    }

    private String getCallMessage(CallSpec callSpec, String fallback) {
        String message = StringUtils.getFirstOf(callSpec.name, callSpec.number);
        return message.isEmpty() ? fallback : message;
    }

    private void sendPhoneMessage(byte type, String message) {
        List<byte[]> packets = ColmiR0xNotificationPacket.encode(type, message);
        if (packets.isEmpty()) {
            return;
        }

        try {
            TransactionBuilder builder = performInitialized("H59 phone notification");
            BluetoothGattCharacteristic characteristic = getCharacteristic(ColmiR0xConstants.CHARACTERISTIC_WRITE);
            if (characteristic == null) {
                LOG.warn("Unable to send H59 phone notification: write characteristic unavailable");
                return;
            }

            for (byte[] packet : packets) {
                builder.write(characteristic, packet);
            }
            builder.queue(getQueue());
        } catch (IOException e) {
            LOG.warn("Unable to send H59 phone notification", e);
        }
    }
}
