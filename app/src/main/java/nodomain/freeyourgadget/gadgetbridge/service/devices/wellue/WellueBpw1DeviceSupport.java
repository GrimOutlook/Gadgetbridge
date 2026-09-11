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

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.widget.Toast;

import androidx.annotation.NonNull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.UUID;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericBloodPressureSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericBloodPressureSample;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLESingleDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.service.btle.actions.SetDeviceStateAction;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

public final class WellueBpw1DeviceSupport extends AbstractBTLESingleDeviceSupport {
    private static final Logger LOG = LoggerFactory.getLogger(WellueBpw1DeviceSupport.class);

    static final UUID SERVICE_MT = UUID.fromString("81eea001-e735-49ec-8a11-7e32cae1e14e");
    private static final UUID CHARACTERISTIC_NOTIFY = UUID.fromString("81eea002-e735-49ec-8a11-7e32cae1e14e");
    private static final UUID CHARACTERISTIC_WRITE = UUID.fromString("81eea003-e735-49ec-8a11-7e32cae1e14e");
    private static final UUID SERVICE_BATTERY = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb");
    private static final UUID CHARACTERISTIC_BATTERY = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb");

    private final WellueBpw1Protocol.StreamParser parser = new WellueBpw1Protocol.StreamParser();
    private int deviceVersion;
    private boolean featureReceived;
    private boolean fetchRequested;
    private boolean syncRequested;
    private boolean syncInProgress;
    private int persistedMeasurements;

    public WellueBpw1DeviceSupport() {
        super(LOG);
        addSupportedService(SERVICE_MT);
        addSupportedService(SERVICE_BATTERY);
    }

    @Override
    public boolean useAutoConnect() {
        return false;
    }

    @Override
    protected TransactionBuilder initializeDevice(@NonNull final TransactionBuilder builder) {
        parser.reset();
        deviceVersion = 0;
        featureReceived = false;
        syncRequested = false;
        syncInProgress = false;
        persistedMeasurements = 0;

        builder.add(new SetDeviceStateAction(getDevice(), GBDevice.State.INITIALIZING, getContext()));

        final BluetoothGattCharacteristic notifyCharacteristic = getCharacteristic(CHARACTERISTIC_NOTIFY);
        if (notifyCharacteristic == null) {
            LOG.warn("BPW1 MT notification characteristic not found");
        } else {
            builder.notify(notifyCharacteristic, true);
        }

        final BluetoothGattCharacteristic batteryCharacteristic = getCharacteristic(CHARACTERISTIC_BATTERY);
        if (batteryCharacteristic != null) {
            builder.read(batteryCharacteristic);
        } else {
            LOG.warn("BPW1 battery characteristic not found");
        }

        final BluetoothGattCharacteristic writeCharacteristic = getWriteCharacteristic();
        if (writeCharacteristic == null) {
            LOG.warn("BPW1 MT write characteristic not found");
        } else {
            builder.write(writeCharacteristic, WellueBpw1Protocol.getDeviceFeatures());
        }

        builder.add(new SetDeviceStateAction(getDevice(), GBDevice.State.INITIALIZED, getContext()));
        return builder;
    }

    @Override
    public void onFetchRecordedData(final int dataTypes) {
        fetchRequested = true;
        if (!isConnected() || !isInitialized()) {
            try {
                performInitialized("fetch BPW1 history");
            } catch (IOException e) {
                LOG.error("Unable to initialize BPW1 for history fetch", e);
                GB.toast(getContext(), "Unable to fetch BPW1 history", Toast.LENGTH_LONG, GB.ERROR, e);
            }
            return;
        }
        requestSync();
    }

    @Override
    public boolean onCharacteristicRead(
            final BluetoothGatt gatt,
            final BluetoothGattCharacteristic characteristic,
            final byte[] value,
            final int status
    ) {
        if (super.onCharacteristicRead(gatt, characteristic, value, status)) {
            return true;
        }
        if (CHARACTERISTIC_BATTERY.equals(characteristic.getUuid())
                && status == BluetoothGatt.GATT_SUCCESS) {
            updateBattery(value);
            return true;
        }
        return false;
    }

    @Override
    public boolean onCharacteristicChanged(
            final BluetoothGatt gatt,
            final BluetoothGattCharacteristic characteristic,
            final byte[] value
    ) {
        if (super.onCharacteristicChanged(gatt, characteristic, value)) {
            return true;
        }
        if (CHARACTERISTIC_BATTERY.equals(characteristic.getUuid())) {
            updateBattery(value);
            return true;
        }
        if (!CHARACTERISTIC_NOTIFY.equals(characteristic.getUuid())) {
            return false;
        }

        for (WellueBpw1Protocol.Frame frame : parser.feed(value)) {
            handleFrame(frame);
        }
        return true;
    }

    @Override
    public void onConnectionStateChange(final BluetoothGatt gatt, final int status, final int newState) {
        super.onConnectionStateChange(gatt, status, newState);
        if (newState != BluetoothGatt.STATE_CONNECTED && syncInProgress) {
            finishSync();
        }
    }

    private BluetoothGattCharacteristic getWriteCharacteristic() {
        final BluetoothGattCharacteristic characteristic = getCharacteristic(CHARACTERISTIC_WRITE);
        if (characteristic != null) {
            characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        }
        return characteristic;
    }

    private void handleFrame(final WellueBpw1Protocol.Frame frame) {
        if (frame.isAck()) {
            return;
        }

        final int ackVersion = featureReceived ? deviceVersion : 0;
        sendAck(frame.commandId, frame.key, ackVersion);

        if (frame.commandId == 0x00 && frame.key == 0x00) {
            deviceVersion = frame.deviceVersion;
            featureReceived = true;
            if (fetchRequested) {
                requestSync();
            }
        } else if (frame.commandId == WellueBpw1Protocol.HEALTH_DATA_ID
                && frame.key == WellueBpw1Protocol.BP_HEALTH_KEY) {
            persistBloodPressure(frame);
        } else if (frame.commandId == 0x01 && frame.key == 0x03) {
            finishSync();
        }
    }

    private void requestSync() {
        if (!featureReceived || syncRequested) {
            return;
        }
        final BluetoothGattCharacteristic writeCharacteristic = getWriteCharacteristic();
        if (writeCharacteristic == null) {
            LOG.warn("Cannot request BPW1 history without MT write characteristic");
            return;
        }

        syncRequested = true;
        syncInProgress = true;
        final TransactionBuilder builder = createTransactionBuilder("fetch BPW1 history");
        builder.setBusyTask(R.string.busy_task_fetch_blood_pressure_data);
        builder.write(writeCharacteristic, WellueBpw1Protocol.syncData(deviceVersion));
        builder.queue();
    }

    private void sendAck(final int commandId, final int key, final int deviceVersionForAck) {
        final BluetoothGattCharacteristic writeCharacteristic = getWriteCharacteristic();
        if (writeCharacteristic == null) {
            return;
        }
        final TransactionBuilder builder = createTransactionBuilder("ack BPW1 MT frame");
        builder.write(
                writeCharacteristic,
                WellueBpw1Protocol.buildAck(commandId, key, 0, deviceVersionForAck)
        );
        builder.queue();
    }

    private void persistBloodPressure(final WellueBpw1Protocol.Frame frame) {
        final WellueBpw1Protocol.BloodPressureBatch batch;
        try {
            batch = WellueBpw1Protocol.decodeBloodPressure(frame);
        } catch (IllegalArgumentException e) {
            LOG.warn("Ignoring malformed BPW1 blood-pressure frame", e);
            return;
        }

        final List<GenericBloodPressureSample> samples = new ArrayList<>(batch.records.size());
        for (WellueBpw1Protocol.BloodPressureRecord record : batch.records) {
            final long timestamp = timestamp(batch.date, record.timeSeconds);
            if (timestamp < 0) {
                LOG.warn("Ignoring BPW1 record with invalid date/time");
                continue;
            }
            final GenericBloodPressureSample sample = new GenericBloodPressureSample();
            sample.setTimestamp(timestamp);
            sample.setBpSystolic(record.systolic);
            sample.setBpDiastolic(record.diastolic);
            samples.add(sample);
        }

        if (samples.isEmpty()) {
            return;
        }

        try (DBHandler handler = GBApplication.acquireDB()) {
            final DaoSession session = handler.getDaoSession();
            final GenericBloodPressureSampleProvider provider =
                    new GenericBloodPressureSampleProvider(getDevice(), session);
            if (provider.persistSamples(samples, getContext())) {
                persistedMeasurements += samples.size();
            }
        } catch (Exception e) {
            GB.toast(getContext(), "Error saving BPW1 blood-pressure data", Toast.LENGTH_LONG, GB.ERROR, e);
        }
    }

    private static long timestamp(final WellueBpw1Protocol.PackedDate date, final long timeSeconds) {
        if (date.month < 1 || date.month > 12 || date.day < 1 || date.day > 31 || timeSeconds >= 24 * 60 * 60) {
            return -1;
        }
        final Calendar calendar = Calendar.getInstance();
        calendar.clear();
        calendar.setLenient(false);
        calendar.set(
                date.year,
                date.month - 1,
                date.day,
                (int) (timeSeconds / 3600),
                (int) ((timeSeconds % 3600) / 60),
                (int) (timeSeconds % 60)
        );
        try {
            return calendar.getTimeInMillis();
        } catch (IllegalArgumentException e) {
            return -1;
        }
    }

    private void updateBattery(final byte[] value) {
        if (value == null || value.length == 0) {
            return;
        }
        getDevice().setBatteryLevel(value[0] & 0xff, 0);
        getDevice().sendDeviceUpdateIntent(getContext());
    }

    private void finishSync() {
        if (!syncInProgress) {
            return;
        }
        syncInProgress = false;
        syncRequested = false;
        fetchRequested = false;
        getDevice().unsetBusyTask();
        getDevice().sendDeviceUpdateIntent(getContext());
        GB.signalActivityDataFinish(getDevice());
    }
}
