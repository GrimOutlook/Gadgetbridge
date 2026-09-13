/*  Copyright (C) 2024 Arjan Schrijver

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

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.UUID;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.SettingsActivity;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst;
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler;
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventBatteryInfo;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventVersionInfo;
import nodomain.freeyourgadget.gadgetbridge.devices.colmi.ColmiR0xActivityPacket;
import nodomain.freeyourgadget.gadgetbridge.devices.colmi.ColmiR0xConstants;
import nodomain.freeyourgadget.gadgetbridge.devices.colmi.ColmiR0xHeartRatePacket;
import nodomain.freeyourgadget.gadgetbridge.devices.colmi.ColmiR0xPacketHandler;
import nodomain.freeyourgadget.gadgetbridge.devices.colmi.ColmiR0xTimePacket;
import nodomain.freeyourgadget.gadgetbridge.devices.colmi.samples.ColmiHeartRateSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.entities.ColmiHeartRateSample;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityUser;
import nodomain.freeyourgadget.gadgetbridge.model.BatteryState;
import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLEDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.btle.BLETypeConversions;
import nodomain.freeyourgadget.gadgetbridge.service.btle.GattService;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.service.btle.actions.SetDeviceStateAction;
import nodomain.freeyourgadget.gadgetbridge.service.btle.profiles.IntentListener;
import nodomain.freeyourgadget.gadgetbridge.service.btle.profiles.deviceinfo.DeviceInfo;
import nodomain.freeyourgadget.gadgetbridge.service.btle.profiles.deviceinfo.DeviceInfoProfile;
import nodomain.freeyourgadget.gadgetbridge.service.serial.GBDeviceProtocol;
import nodomain.freeyourgadget.gadgetbridge.util.CheckSums;
import nodomain.freeyourgadget.gadgetbridge.util.DateTimeUtils;
import nodomain.freeyourgadget.gadgetbridge.util.GB;
import nodomain.freeyourgadget.gadgetbridge.util.Prefs;
import nodomain.freeyourgadget.gadgetbridge.util.StringUtils;

public class ColmiR0xDeviceSupport extends AbstractBTLEDeviceSupport {
    private static final Logger LOG = LoggerFactory.getLogger(ColmiR0xDeviceSupport.class);
    private static final String H59_RICH_CHANNEL_ACCOUNT = "ludoplus";
    private final Handler backgroundTasksHandler = new Handler(Looper.getMainLooper());

    private final DeviceInfoProfile<ColmiR0xDeviceSupport> deviceInfoProfile;
    private final boolean readOnly;
    private String cachedFirmwareVersion = null;

    private int daysAgo;
    private int packetsTotalNr;
    private Calendar syncingDay;
    private boolean activityCalorieNewProtocol;
    private boolean awaitingTodayActivitySummary;
    private volatile boolean historySyncActive;
    private final Runnable todayActivitySummaryTimeout = this::todayActivitySummaryTimedOut;

    private int bigDataPacketSize;
    private ByteBuffer bigDataPacket;

    public ColmiR0xDeviceSupport() {
        this(false);
    }

    protected ColmiR0xDeviceSupport(boolean readOnly) {
        super(LOG);
        this.readOnly = readOnly;
        addSupportedService(ColmiR0xConstants.CHARACTERISTIC_SERVICE_V1);
        addSupportedService(ColmiR0xConstants.CHARACTERISTIC_SERVICE_V2);
        addSupportedService(GattService.UUID_SERVICE_DEVICE_INFORMATION);

        IntentListener mListener = intent -> {
            String action = intent.getAction();
            if (DeviceInfoProfile.ACTION_DEVICE_INFO.equals(action)) {
                handleDeviceInfo(intent.getParcelableExtra(DeviceInfoProfile.EXTRA_DEVICE_INFO));
            }
        };

        deviceInfoProfile = new DeviceInfoProfile<>(this);
        deviceInfoProfile.addListener(mListener);
        addSupportedProfile(deviceInfoProfile);
    }

    @Override
    public void dispose() {
        historySyncActive = false;
        backgroundTasksHandler.removeCallbacksAndMessages(null);

        super.dispose();
    }

    @Override
    public void setContext(GBDevice gbDevice, BluetoothAdapter btAdapter, Context context) {
        if (gbDevice.getFirmwareVersion() != null) {
            setCachedFirmwareVersion(gbDevice.getFirmwareVersion());
        }
        super.setContext(gbDevice, btAdapter, context);
    }

    public String getCachedFirmwareVersion() {
        return this.cachedFirmwareVersion;
    }

    public void setCachedFirmwareVersion(String version) {
        this.cachedFirmwareVersion = version;
    }

    private void handleDeviceInfo(DeviceInfo info) {
        LOG.debug("Device info: {}", info);

        GBDeviceEventVersionInfo versionCmd = new GBDeviceEventVersionInfo();
        versionCmd.hwVersion = info.getHardwareRevision();
        versionCmd.fwVersion = info.getFirmwareRevision();
        handleGBDeviceEvent(versionCmd);
    }

    @Override
    public boolean useAutoConnect() {
        return false;
    }

    @Override
    protected TransactionBuilder initializeDevice(TransactionBuilder builder) {
        builder.add(new SetDeviceStateAction(getDevice(), GBDevice.State.INITIALIZING, getContext()));

        if (getDevice().getFirmwareVersion() == null) {
            getDevice().setFirmwareVersion(getCachedFirmwareVersion() != null ? getCachedFirmwareVersion() : "N/A");
        }
        deviceInfoProfile.requestDeviceInfo(builder);

        builder.add(new SetDeviceStateAction(getDevice(), GBDevice.State.INITIALIZED, getContext()));

        builder.notify(getCharacteristic(ColmiR0xConstants.CHARACTERISTIC_NOTIFY_V1), true);
        builder.notify(getCharacteristic(ColmiR0xConstants.CHARACTERISTIC_NOTIFY_V2), true);

        // Delay initialization with 2 seconds to give the ring time to settle
        backgroundTasksHandler.removeCallbacksAndMessages(null);
        backgroundTasksHandler.postDelayed(this::postConnectInitialization, 2000);

        return builder;
    }

    private void postConnectInitialization() {
        if (!readOnly) {
            setPhoneName();
        }
        if (supportsTimeSync()) {
            setDateTime();
        }
        if (!readOnly) {
            setUserPreferences();
        }
        requestBatteryInfo();
        requestSettingsFromRing();
    }

    @Override
    public boolean onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
        if (super.onCharacteristicChanged(gatt, characteristic)) {
            return true;
        }

        UUID characteristicUUID = characteristic.getUuid();
        byte[] value = characteristic.getValue();

        LOG.debug("Characteristic {} changed, value: {}", characteristicUUID, StringUtils.bytesToHex(characteristic.getValue()));

        if (characteristicUUID.equals(ColmiR0xConstants.CHARACTERISTIC_NOTIFY_V1)) {
            switch (value[0]) {
                case ColmiR0xConstants.CMD_SET_DATE_TIME:
                    LOG.info("Received set date/time response: {}", StringUtils.bytesToHex(value));
                    break;
                case ColmiR0xConstants.CMD_BATTERY:
                    int levelResponse = value[1];
                    boolean charging = value[2] == 1;
                    LOG.info("Received battery level response: {}% (charging: {})", levelResponse, charging);
                    GBDeviceEventBatteryInfo batteryEvent = createDeviceBatteryInfoEvent(levelResponse, charging);
                    evaluateGBDeviceEvent(batteryEvent);
                    break;
                case ColmiR0xConstants.CMD_PHONE_NAME:
                    LOG.info("Received phone name response: {}", StringUtils.bytesToHex(value));
                    break;
                case ColmiR0xConstants.CMD_PREFERENCES:
                    LOG.info("Received user preferences response: {}", StringUtils.bytesToHex(value));
                    break;
                case ColmiR0xConstants.CMD_SYNC_HEART_RATE:
                    if (!historySyncActive) {
                        LOG.debug("Ignoring unsolicited HR history packet");
                        break;
                    }
                    LOG.info("Received HR history sync packet: {}", StringUtils.bytesToHex(value));
                    int hrPacketNr = ColmiR0xHeartRatePacket.getPacketNumber(value);
                    if (ColmiR0xHeartRatePacket.isEmpty(value)) {
                        LOG.info("Empty HR history, sync aborted");
                        getDevice().unsetBusyTask();
                        getDevice().sendDeviceUpdateIntent(getContext());
                    } else if (ColmiR0xHeartRatePacket.isHeader(value)) {
                        packetsTotalNr = ColmiR0xHeartRatePacket.getTotalPackets(value);
                        LOG.info("HR history packet {} out of total {}", hrPacketNr, packetsTotalNr);
                    } else {
                        LOG.info("HR history packet {} out of total {} (data for {}:00-{}:00)", hrPacketNr, packetsTotalNr, hrPacketNr-1, hrPacketNr);
                        Calendar sampleCal = (Calendar) syncingDay.clone();
                        if (hrPacketNr == 1) {
                            int timestamp = ColmiR0xHeartRatePacket.getSyncTimestamp(value);
                            Date timestampDate = DateTimeUtils.parseTimeStamp(timestamp);
                            LOG.info("Receiving HR history sequence with timestamp {}", DateTimeUtils.formatIso8601UTC(timestampDate));
                        }
                        for (ColmiR0xHeartRatePacket.HeartRateSample sample : ColmiR0xHeartRatePacket.decodeSamples(value)) {
                            int minuteOfDay = sample.getMinuteOfDay();
                            sampleCal.set(Calendar.HOUR_OF_DAY, minuteOfDay / 60);
                            sampleCal.set(Calendar.MINUTE, minuteOfDay % 60);
                            sampleCal.set(Calendar.SECOND, 0);
                            LOG.info("Value at {} is {} bpm, time of day is {}", minuteOfDay, sample.getHeartRate(), sampleCal.getTime());
                            // Build sample object and save in database
                            try (DBHandler db = GBApplication.acquireDB()) {
                                ColmiHeartRateSampleProvider sampleProvider = new ColmiHeartRateSampleProvider(getDevice(), db.getDaoSession());
                                Long userId = DBHelper.getUser(db.getDaoSession()).getId();
                                Long deviceId = DBHelper.getDevice(getDevice(), db.getDaoSession()).getId();
                                ColmiHeartRateSample gbSample = new ColmiHeartRateSample();
                                gbSample.setDeviceId(deviceId);
                                gbSample.setUserId(userId);
                                gbSample.setTimestamp(sampleCal.getTimeInMillis());
                                gbSample.setHeartRate(sample.getHeartRate());
                                sampleProvider.addSample(gbSample);
                            } catch (Exception e) {
                                LOG.error("Error acquiring database for recording heart rate samples", e);
                            }
                        }
                        if (hrPacketNr == packetsTotalNr - 1) {
                            getDevice().unsetBusyTask();
                            getDevice().sendDeviceUpdateIntent(getContext());
                        }
                    }
                    if (!getDevice().isBusy()) {
                        if (daysAgo < 7) {
                            daysAgo++;
                            fetchHistoryHR();
                        } else {
                            daysAgo = 0;
                            fetchHistoryStress();
                        }
                    }
                    break;
                case ColmiR0xConstants.CMD_AUTO_HR_PREF:
                    ColmiR0xPacketHandler.hrIntervalSettings(this, value);
                    break;
                case ColmiR0xConstants.CMD_GOALS:
                    ColmiR0xPacketHandler.goalsSettings(value);
                    break;
                case ColmiR0xConstants.CMD_AUTO_SPO2_PREF:
                    ColmiR0xPacketHandler.spo2Settings(this, value);
                    break;
                case ColmiR0xConstants.CMD_PACKET_SIZE:
                    LOG.info("Received packet size indicator: {} bytes", value[1] & 0xff);
                    break;
                case ColmiR0xConstants.CMD_AUTO_STRESS_PREF:
                    ColmiR0xPacketHandler.stressSettings(this, value);
                    break;
                case ColmiR0xConstants.CMD_AUTO_HRV_PREF:
                    ColmiR0xPacketHandler.hrvSettings(this, value);
                    break;
                case ColmiR0xConstants.CMD_SYNC_STRESS:
                    if (!historySyncActive) {
                        LOG.debug("Ignoring unsolicited stress history packet");
                        break;
                    }
                    ColmiR0xPacketHandler.historicalStress(
                            getDevice(),
                            getContext(),
                            value,
                            daysAgo,
                            usesH59HistoryPacketLayout()
                    );
                    if (!getDevice().isBusy()) {
                        if (fetchMultipleStressHistoryDays() && daysAgo < 6) {
                            daysAgo++;
                            fetchHistoryStress();
                        } else {
                            daysAgo = 0;
                            fetchHistorySpo2();
                        }
                    }
                    break;
                case ColmiR0xConstants.CMD_SYNC_ACTIVITY:
                    if (!historySyncActive) {
                        LOG.debug("Ignoring unsolicited activity history packet");
                        break;
                    }
                    if (supportsTodayActivitySummary()
                            && daysAgo == 0
                            && ColmiR0xActivityPacket.isEmpty(value)) {
                        LOG.info("Activity history is empty for today, requesting today's activity summary");
                        fetchTodayActivitySummary();
                        break;
                    }
                    if (ColmiR0xActivityPacket.isHeader(value)) {
                        activityCalorieNewProtocol = ColmiR0xActivityPacket.usesNewCalorieProtocol(value);
                    }
                    ColmiR0xPacketHandler.historicalActivity(getDevice(), getContext(), value, activityCalorieNewProtocol);
                    if (!getDevice().isBusy()) {
                        continueActivityHistory();
                    }
                    break;
                case ColmiR0xConstants.CMD_SYNC_TODAY_ACTIVITY:
                    if (!awaitingTodayActivitySummary) {
                        LOG.info("Ignoring unsolicited today activity summary: {}", StringUtils.bytesToHex(value));
                        break;
                    }
                    awaitingTodayActivitySummary = false;
                    backgroundTasksHandler.removeCallbacks(todayActivitySummaryTimeout);
                    ColmiR0xPacketHandler.historicalTodayActivity(getDevice(), value, syncingDay);
                    continueActivityHistory();
                    break;
                case ColmiR0xConstants.CMD_SYNC_HRV:
                    if (!historySyncActive) {
                        LOG.debug("Ignoring unsolicited HRV history packet");
                        break;
                    }
                    getDevice().setBusyTask(getContext().getString(R.string.busy_task_fetch_hrv_data));
                    ColmiR0xPacketHandler.historicalHRV(
                            getDevice(),
                            getContext(),
                            value,
                            daysAgo,
                            usesH59HistoryPacketLayout()
                    );
                    if (!getDevice().isBusy()) {
                        if (daysAgo < 6) {
                            daysAgo++;
                            fetchHistoryHRV();
                        } else {
                            fetchRecordedDataFinished();
                        }
                    }
                    break;
                case ColmiR0xConstants.CMD_FIND_DEVICE:
                    LOG.info("Received find device response: {}", StringUtils.bytesToHex(value));
                    break;
                case ColmiR0xConstants.CMD_MANUAL_HEART_RATE:
                    ColmiR0xPacketHandler.liveHeartRate(getDevice(), getContext(), value);
                    break;
                case ColmiR0xConstants.CMD_NOTIFICATION:
                    switch (value[1]) {
                        case ColmiR0xConstants.NOTIFICATION_NEW_HR_DATA:
                            LOG.info("Received notification from ring that new HR data is available to sync");
                            break;
                        case ColmiR0xConstants.NOTIFICATION_NEW_SPO2_DATA:
                            LOG.info("Received notification from ring that new SpO2 data is available to sync");
                            break;
                        case ColmiR0xConstants.NOTIFICATION_NEW_STEPS_DATA:
                            LOG.info("Received notification from ring that new steps data is available to sync");
                            break;
                        case ColmiR0xConstants.NOTIFICATION_BATTERY_LEVEL:
                            int levelNotif = value[2];
                            charging = value[3] == 1;
                            LOG.info("Received battery level notification: {}% (charging: {})", levelNotif, charging);
                            GBDeviceEventBatteryInfo batteryNotifEvent = createDeviceBatteryInfoEvent(levelNotif, charging);
                            evaluateGBDeviceEvent(batteryNotifEvent);
                            break;
                        case ColmiR0xConstants.NOTIFICATION_LIVE_ACTIVITY:
                            ColmiR0xPacketHandler.liveActivity(value);
                            break;
                        default:
                            LOG.info("Received unrecognized notification: {}", StringUtils.bytesToHex(value));
                            break;
                    }
                    break;
                default:
                    LOG.info("Received unrecognized packet: {}", StringUtils.bytesToHex(value));
                    break;
            }
        }
        if (characteristicUUID.equals(ColmiR0xConstants.CHARACTERISTIC_NOTIFY_V2)) {
            // Big data responses can arrive in multiple packets that need to be concatenated
            if (bigDataPacket != null) {
                LOG.debug("Received {} bytes on big data characteristic while waiting for follow-up data", value.length);
                bigDataPacket.rewind();
                ByteBuffer concatenated = ByteBuffer
                        .allocate(bigDataPacket.limit() + value.length)
                        .put(bigDataPacket)
                        .put(value);
                bigDataPacket = concatenated;
                if (bigDataPacket.limit() < bigDataPacketSize + 6) {
                    // If the received data is smaller than the expected packet size (+ 6 bytes header),
                    // wait for the next packet and append it
                    LOG.debug("Big data packet is not complete yet, got {} bytes while expecting {}+6. Waiting for more...", bigDataPacket.limit(), bigDataPacketSize);
                    return true;
                } else {
                    value = bigDataPacket.array();
                    bigDataPacket = null;
                    LOG.debug("Big data packet complete, got {} bytes while expecting {}+6", value.length, bigDataPacketSize);
                }
            }
            switch (value[0]) {
                case ColmiR0xConstants.CMD_BIG_DATA_V2:
                    if (!historySyncActive) {
                        LOG.debug("Ignoring unsolicited big-data history packet");
                        return true;
                    }
                    int packetLength = BLETypeConversions.toUint16(value[2], value[3]);
                    if (value.length < packetLength + 6) {
                        // If the received packet is smaller than the expected packet size (+ 6 bytes header),
                        // wait for the next packet and append it
                        LOG.debug("Big data packet is not complete yet, got {} bytes while expecting {}+6. Waiting for more...", value.length, packetLength);
                        bigDataPacketSize = packetLength;
                        bigDataPacket = ByteBuffer.wrap(value);
                        return true;
                    }
                    switch (value[1]) {
                        case ColmiR0xConstants.BIG_DATA_TYPE_SLEEP:
                            ColmiR0xPacketHandler.historicalSleep(getDevice(), getContext(), value);

                            daysAgo = 0;
                            fetchHistoryHRV();
                            break;
                        case ColmiR0xConstants.BIG_DATA_TYPE_SPO2:
                            ColmiR0xPacketHandler.historicalSpo2(getDevice(), value);
                            fetchHistorySleep();
                            break;
                        default:
                            LOG.info("Received unrecognized big data packet: {}", StringUtils.bytesToHex(value));
                            break;
                    }
                    break;
                default:
                    LOG.info("Received unrecognized notify v2 packet: {}", StringUtils.bytesToHex(value));
                    break;
            }
            return true;
        }

        return false;
    }

    @NonNull
    private static GBDeviceEventBatteryInfo createDeviceBatteryInfoEvent(int levelResponse, boolean charging) {
        GBDeviceEventBatteryInfo batteryEvent = new GBDeviceEventBatteryInfo();
        batteryEvent.level = levelResponse;
        batteryEvent.state = charging ? BatteryState.BATTERY_CHARGING : BatteryState.BATTERY_NORMAL;
        return batteryEvent;
    }

    private byte[] buildPacket(byte[] contents) {
        ByteBuffer buffer = ByteBuffer.allocate(16);
        if (contents.length <= 15) {
            buffer.put(contents);
            int checksum = 0;
            for (byte content : contents) {
                checksum = (byte) (checksum + content) & 0xff;
            }
            buffer.put(15, (byte) checksum);
        } else {
            LOG.warn("Packet content too long!");
        }
        return buffer.array();
    }

    private void sendWrite(String taskName, byte[] contents) {
        TransactionBuilder builder = new TransactionBuilder(taskName);
        BluetoothGattCharacteristic characteristic = getCharacteristic(ColmiR0xConstants.CHARACTERISTIC_WRITE);
        if (characteristic != null) {
            builder.write(characteristic, contents);
            builder.queue(getQueue());
        }
    }

    private void sendCommand(String taskName, byte[] contents) {
        TransactionBuilder builder = new TransactionBuilder(taskName);
        BluetoothGattCharacteristic characteristic = getCharacteristic(ColmiR0xConstants.CHARACTERISTIC_COMMAND);
        if (characteristic != null) {
            builder.write(characteristic, contents);
            builder.queue(getQueue());
        }
    }

    private void requestBatteryInfo() {
        byte[] batteryRequestPacket = buildPacket(new byte[]{ColmiR0xConstants.CMD_BATTERY});
        LOG.info("Battery request sent: {}", StringUtils.bytesToHex(batteryRequestPacket));
        sendWrite("batteryRequest", batteryRequestPacket);
    }

    private void setPhoneName() {
        byte[] setPhoneNamePacket = buildPacket(new byte[]{
                ColmiR0xConstants.CMD_PHONE_NAME,
                0x02, // Client major version
                0x0a, // Client minor version
                'G',
                'B'
        });
        LOG.info("Phone name sent: {}", StringUtils.bytesToHex(setPhoneNamePacket));
        sendWrite("phoneNameRequest", setPhoneNamePacket);
    }

    private void setDateTime() {
        Calendar now = GregorianCalendar.getInstance();
        byte[] setDateTimePacket = ColmiR0xTimePacket.build(now, usesExtendedTimeSyncPacket());
        LOG.info("Set date/time request sent: {}", StringUtils.bytesToHex(setDateTimePacket));
        sendWrite("dateTimeRequest", setDateTimePacket);
    }

    protected boolean supportsTimeSync() {
        return !readOnly;
    }

    protected boolean usesExtendedTimeSyncPacket() {
        return false;
    }

    protected boolean supportsTodayActivitySummary() {
        return false;
    }

    protected boolean usesH59HistoryPacketLayout() {
        return false;
    }

    protected boolean fetchMultipleStressHistoryDays() {
        return false;
    }

    @Override
    public void onSetTime() {
        if (!supportsTimeSync()) {
            return;
        }
        setDateTime();
    }

    @Override
    public void onSendConfiguration(String config) {
        if (readOnly) {
            return;
        }
        final Prefs prefs = getDevicePrefs();
        switch (config) {
            case SettingsActivity.PREF_MEASUREMENT_SYSTEM:
                setUserPreferences();
                break;
            case DeviceSettingsPreferenceConst.PREF_SPO2_ALL_DAY_MONITORING:
                final boolean spo2Enabled = prefs.getBoolean(DeviceSettingsPreferenceConst.PREF_SPO2_ALL_DAY_MONITORING, false);
                byte[] spo2PrefsPacket = buildPacket(new byte[]{ColmiR0xConstants.CMD_AUTO_SPO2_PREF, ColmiR0xConstants.PREF_WRITE, (byte) (spo2Enabled ? 0x01 : 0x00)});
                LOG.info("SpO2 preference request sent: {}", StringUtils.bytesToHex(spo2PrefsPacket));
                sendWrite("spo2PreferenceRequest", spo2PrefsPacket);
                break;
            case DeviceSettingsPreferenceConst.PREF_HEARTRATE_STRESS_MONITORING:
                final boolean stressEnabled = prefs.getBoolean(DeviceSettingsPreferenceConst.PREF_HEARTRATE_STRESS_MONITORING, false);
                byte[] stressPrefsPacket = buildPacket(new byte[]{ColmiR0xConstants.CMD_AUTO_STRESS_PREF, ColmiR0xConstants.PREF_WRITE, (byte) (stressEnabled ? 0x01 : 0x00)});
                LOG.info("Stress preference request sent: {}", StringUtils.bytesToHex(stressPrefsPacket));
                sendWrite("stressPreferenceRequest", stressPrefsPacket);
                break;
            case DeviceSettingsPreferenceConst.PREF_HRV_ALL_DAY_MONITORING:
                final boolean hrvEnabled = prefs.getBoolean(DeviceSettingsPreferenceConst.PREF_HRV_ALL_DAY_MONITORING, false);
                byte[] hrvPrefsPacket = buildPacket(new byte[]{ColmiR0xConstants.CMD_AUTO_HRV_PREF, ColmiR0xConstants.PREF_WRITE, (byte) (hrvEnabled ? 0x01 : 0x00)});
                LOG.info("HRV preference request sent: {}", StringUtils.bytesToHex(hrvPrefsPacket));
                sendWrite("hrvPreferenceRequest", hrvPrefsPacket);
                break;
        }
    }

    @Override
    public void onSetHeartRateMeasurementInterval(int seconds) {
        if (readOnly) {
            return;
        }
        // Round to nearest 5 minutes and limit to 60 minutes due to device constraints
        long hrIntervalMins = Math.min(Math.round(seconds / 60.0 / 5.0) * 5, 60);
        byte[] hrIntervalPacket = buildPacket(new byte[]{
                ColmiR0xConstants.CMD_AUTO_HR_PREF,
                ColmiR0xConstants.PREF_WRITE,
                hrIntervalMins > 0 ? (byte) 0x01 : (byte) 0x02,
                (byte) hrIntervalMins
        });
        LOG.info("HR interval preference request sent: {}", StringUtils.bytesToHex(hrIntervalPacket));
        sendWrite("hrIntervalPreferenceRequest", hrIntervalPacket);
    }

    private void setUserPreferences() {
        final Prefs prefs = getDevicePrefs();
        final ActivityUser user = new ActivityUser();
        final String measurementSystem = prefs.getString(SettingsActivity.PREF_MEASUREMENT_SYSTEM, "metric");
        byte userGender;
        switch (user.getGender()) {
            case ActivityUser.GENDER_FEMALE:
                userGender = 0x01;
                break;
            case ActivityUser.GENDER_MALE:
                userGender = 0x00;
                break;
            default:
                userGender = 0x02;
                break;
        }
        byte[] userPrefsPacket = buildPacket(new byte[]{
                ColmiR0xConstants.CMD_PREFERENCES,
                ColmiR0xConstants.PREF_WRITE,
                0x00,  // 24h format, 0x01 is 12h format
                (byte) ("metric".equals(measurementSystem) ? 0x00 : 0x01),
                userGender,
                (byte) user.getAge(),
                (byte) user.getHeightCm(),
                (byte) user.getWeightKg(),
                0x00,  // systolic blood pressure (e.g. 120)
                0x00,  // diastolic blood pressure (e.g. 90)
                0x00  // heart rate value warning threshold: (e.g. 160)
        });
        LOG.info("User preferences request sent: {}", StringUtils.bytesToHex(userPrefsPacket));
        sendWrite("userPreferenceRequest", userPrefsPacket);
    }

    private void requestSettingsFromRing() {
        byte[] request = buildPacket(new byte[]{ColmiR0xConstants.CMD_AUTO_HR_PREF, ColmiR0xConstants.PREF_READ});
        LOG.info("Request HR measurement interval from ring: {}", StringUtils.bytesToHex(request));
        sendWrite("hrIntervalRequest", request);
        request = buildPacket(new byte[]{ColmiR0xConstants.CMD_AUTO_STRESS_PREF, ColmiR0xConstants.PREF_READ});
        LOG.info("Request stress measurement setting from ring: {}", StringUtils.bytesToHex(request));
        sendWrite("stressSettingRequest", request);
        request = buildPacket(new byte[]{ColmiR0xConstants.CMD_AUTO_SPO2_PREF, ColmiR0xConstants.PREF_READ});
        LOG.info("Request SpO2 measurement setting from ring: {}", StringUtils.bytesToHex(request));
        sendWrite("spo2SettingRequest", request);
        request = buildPacket(new byte[]{ColmiR0xConstants.CMD_AUTO_HRV_PREF, ColmiR0xConstants.PREF_READ});
        LOG.info("Request HRV measurement setting from ring: {}", StringUtils.bytesToHex(request));
        sendWrite("hrvSettingRequest", request);
        request = buildPacket(new byte[]{ColmiR0xConstants.CMD_GOALS, ColmiR0xConstants.PREF_READ});
        LOG.info("Request goals from ring: {}", StringUtils.bytesToHex(request));
        sendWrite("goalsSettingRequest", request);
    }

    @Override
    public void onPowerOff() {
        if (readOnly) {
            return;
        }
        byte[] poweroffPacket = buildPacket(new byte[]{ColmiR0xConstants.CMD_POWER_OFF, 0x01});
        LOG.info("Poweroff request sent: {}", StringUtils.bytesToHex(poweroffPacket));
        sendWrite("poweroffRequest", poweroffPacket);
    }

    @Override
    public void onReset(int flags) {
        if (readOnly) {
            return;
        }
        if ((flags & GBDeviceProtocol.RESET_FLAGS_FACTORY_RESET) != 0) {
            byte[] resetPacket = buildPacket(new byte[]{ColmiR0xConstants.CMD_FACTORY_RESET, 0x66, 0x66});
            LOG.info("Factory reset request sent: {}", StringUtils.bytesToHex(resetPacket));
            sendWrite("resetRequest", resetPacket);
        }
    }

    @Override
    public void onFindDevice(boolean start) {
        if (readOnly) {
            return;
        }
        if (!start) return;

        byte[] findDevicePacket = buildPacket(new byte[]{ColmiR0xConstants.CMD_FIND_DEVICE, 0x55, (byte) 0xAA});
        LOG.info("Find device request sent: {}", StringUtils.bytesToHex(findDevicePacket));
        sendWrite("findDeviceRequest", findDevicePacket);
    }

    @Override
    public void onHeartRateTest() {
        if (readOnly) {
            return;
        }
        byte[] measureHeartRatePacket = buildPacket(new byte[]{ColmiR0xConstants.CMD_MANUAL_HEART_RATE, 0x01});
        LOG.info("Measure HR request sent: {}", StringUtils.bytesToHex(measureHeartRatePacket));
        sendWrite("measureHRRequest", measureHeartRatePacket);
    }

    @Override
    public void onFetchRecordedData(int dataTypes) {
        if (historySyncActive) {
            LOG.debug("Ignoring duplicate historical data fetch request");
            return;
        }
        historySyncActive = true;
        GB.updateTransferNotification(getContext().getString(R.string.busy_task_fetch_activity_data), "", true, 0, getContext());
        daysAgo = 0;
        activityCalorieNewProtocol = false;
        awaitingTodayActivitySummary = false;
        backgroundTasksHandler.removeCallbacks(todayActivitySummaryTimeout);
        fetchHistoryActivity();
    }

    private void fetchRecordedDataFinished() {
        historySyncActive = false;
        GB.updateTransferNotification(null, "", false, 100, getContext());
        LOG.info("Sync finished!");
        getDevice().unsetBusyTask();
        getDevice().sendDeviceUpdateIntent(getContext());
        GB.signalActivityDataFinish(getDevice());
    }

    private void fetchHistoryActivity() {
        getDevice().setBusyTask(getContext().getString(R.string.busy_task_fetch_activity_data));
        getDevice().sendDeviceUpdateIntent(getContext());
        syncingDay = Calendar.getInstance();
        syncingDay.add(Calendar.DAY_OF_MONTH, 0 - daysAgo);
        syncingDay.set(Calendar.HOUR_OF_DAY, 0);
        syncingDay.set(Calendar.MINUTE, 0);
        syncingDay.set(Calendar.SECOND, 0);
        syncingDay.set(Calendar.MILLISECOND, 0);
        byte[] activityHistoryRequest = buildPacket(new byte[]{ColmiR0xConstants.CMD_SYNC_ACTIVITY, (byte) daysAgo, 0x0f, 0x00, 0x5f, 0x01});
        LOG.info("Fetch historical activity data request sent: {}", StringUtils.bytesToHex(activityHistoryRequest));
        sendWrite("activityHistoryRequest", activityHistoryRequest);
    }

    private void fetchTodayActivitySummary() {
        awaitingTodayActivitySummary = true;
        backgroundTasksHandler.removeCallbacks(todayActivitySummaryTimeout);
        byte[] todayActivityRequest = buildPacket(new byte[]{ColmiR0xConstants.CMD_SYNC_TODAY_ACTIVITY});
        LOG.info("Fetch today's activity summary request sent: {}", StringUtils.bytesToHex(todayActivityRequest));
        sendWrite("todayActivitySummaryRequest", todayActivityRequest);
        backgroundTasksHandler.postDelayed(todayActivitySummaryTimeout, 3000);
    }

    private void todayActivitySummaryTimedOut() {
        if (!awaitingTodayActivitySummary) {
            return;
        }

        awaitingTodayActivitySummary = false;
        LOG.warn("No response received for today's activity summary request");
        continueActivityHistory();
    }

    private void continueActivityHistory() {
        if (daysAgo < 7) {
            daysAgo++;
            fetchHistoryActivity();
        } else {
            daysAgo = 0;
            fetchHistoryHR();
        }
    }

    private void fetchHistoryHR() {
        getDevice().setBusyTask(getContext().getString(R.string.busy_task_fetch_hr_data));
        getDevice().sendDeviceUpdateIntent(getContext());
        syncingDay = Calendar.getInstance();
        syncingDay.add(Calendar.DAY_OF_MONTH, 0 - daysAgo);
        syncingDay.set(Calendar.HOUR_OF_DAY, 0);
        syncingDay.set(Calendar.MINUTE, 0);
        syncingDay.set(Calendar.SECOND, 0);
        syncingDay.set(Calendar.MILLISECOND, 0);
        ByteBuffer hrHistoryRequestBB = ByteBuffer.allocate(5);
        hrHistoryRequestBB.order(ByteOrder.LITTLE_ENDIAN);
        hrHistoryRequestBB.put(0, ColmiR0xConstants.CMD_SYNC_HEART_RATE);
        long requestTimestamp = syncingDay.getTimeInMillis() + syncingDay.get(Calendar.ZONE_OFFSET) + syncingDay.get(Calendar.DST_OFFSET);
        hrHistoryRequestBB.putInt(1, (int) (requestTimestamp / 1000));
        byte[] hrHistoryRequest = buildPacket(hrHistoryRequestBB.array());
        LOG.info("Fetch historical HR data request sent ({}): {}", DateTimeUtils.formatIso8601(syncingDay.getTime()), StringUtils.bytesToHex(hrHistoryRequest));
        sendWrite("hrHistoryRequest", hrHistoryRequest);
    }

    private void fetchHistoryStress() {
        getDevice().setBusyTask(getContext().getString(R.string.busy_task_fetch_stress_data));
        getDevice().sendDeviceUpdateIntent(getContext());
        syncingDay = Calendar.getInstance();
        byte[] stressHistoryRequest = buildPacket(new byte[]{ColmiR0xConstants.CMD_SYNC_STRESS, (byte) daysAgo});
        LOG.info("Fetch historical stress data request sent: {}", StringUtils.bytesToHex(stressHistoryRequest));
        sendWrite("stressHistoryRequest", stressHistoryRequest);
    }

    private void fetchHistorySpo2() {
        getDevice().setBusyTask(getContext().getString(R.string.busy_task_fetch_spo2_data));
        getDevice().sendDeviceUpdateIntent(getContext());
        byte[] spo2HistoryRequest = buildRichDataPacket(
                ColmiR0xConstants.BIG_DATA_TYPE_SPO2,
                new byte[]{(byte) 0xff}
        );
        LOG.info("Fetch historical SpO2 data request sent: {}", StringUtils.bytesToHex(spo2HistoryRequest));
        sendRichDataCommand("spo2HistoryRequest", spo2HistoryRequest);
    }

    private void fetchHistorySleep() {
        getDevice().setBusyTask(getContext().getString(R.string.busy_task_fetch_sleep_data));
        getDevice().sendDeviceUpdateIntent(getContext());
        byte[] sleepHistoryRequest = buildRichDataPacket(
                ColmiR0xConstants.BIG_DATA_TYPE_SLEEP,
                new byte[]{(byte) 0xff, 0x01}
        );
        LOG.info("Fetch historical sleep data request sent: {}", StringUtils.bytesToHex(sleepHistoryRequest));
        sendRichDataCommand("sleepHistoryRequest", sleepHistoryRequest);
    }

    private void fetchHistoryHRV() {
        getDevice().sendDeviceUpdateIntent(getContext());
        syncingDay = Calendar.getInstance();
        if (daysAgo != 0) {
            syncingDay.add(Calendar.DAY_OF_MONTH, 0 - daysAgo);
            syncingDay.set(Calendar.HOUR_OF_DAY, 0);
            syncingDay.set(Calendar.MINUTE, 0);
        }
        syncingDay.set(Calendar.SECOND, 0);
        syncingDay.set(Calendar.MILLISECOND, 0);
        byte[] hrvHistoryRequest = buildPacket(new byte[]{ColmiR0xConstants.CMD_SYNC_HRV, (byte) daysAgo});
        LOG.info("Fetch historical HRV data request sent ({}): {}", syncingDay.getTime(), StringUtils.bytesToHex(hrvHistoryRequest));
        sendWrite("hrvHistoryRequest", hrvHistoryRequest);
    }

    private byte[] buildRichDataPacket(byte action, byte[] payload) {
        ByteBuffer buffer = ByteBuffer.allocate(payload.length + 6).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(ColmiR0xConstants.CMD_BIG_DATA_V2);
        buffer.put(action);
        buffer.putShort((short) payload.length);
        buffer.putShort((short) CheckSums.getCRC16ansi(payload));
        buffer.put(payload);
        return buffer.array();
    }

    private void sendRichDataCommand(String taskName, byte[] command) {
        TransactionBuilder builder = new TransactionBuilder(taskName);
        BluetoothGattCharacteristic characteristic = getCharacteristic(ColmiR0xConstants.CHARACTERISTIC_COMMAND);
        if (characteristic == null) {
            return;
        }

        byte[] account = H59_RICH_CHANNEL_ACCOUNT.getBytes(StandardCharsets.UTF_16LE);
        byte[] loginPayload = ByteBuffer.allocate(5 + account.length)
                .put(new byte[]{0x01, 0x01, 0x00})
                .put((byte) 0xff)
                .put((byte) 0xfe)
                .put(account)
                .array();
        builder.write(characteristic, buildRichDataPacket(ColmiR0xConstants.BIG_DATA_TYPE_LOGIN, loginPayload))
                .wait(400)
                .write(characteristic, buildRichDataPacket(ColmiR0xConstants.BIG_DATA_TYPE_INIT, new byte[0]))
                .wait(400)
                .write(characteristic, command)
                .queue(getQueue());
    }
}
