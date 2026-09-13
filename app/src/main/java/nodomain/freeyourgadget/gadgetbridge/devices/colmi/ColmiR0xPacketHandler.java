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
package nodomain.freeyourgadget.gadgetbridge.devices.colmi;

import android.content.Context;
import android.database.Cursor;
import android.content.Intent;
import android.widget.Toast;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst;
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler;
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventUpdatePreferences;
import nodomain.freeyourgadget.gadgetbridge.devices.colmi.samples.ColmiActivitySampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.colmi.samples.ColmiHeartRateSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.colmi.samples.ColmiHrvValueSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.colmi.samples.ColmiSleepSessionSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.colmi.samples.ColmiSleepStageSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.colmi.samples.ColmiSpo2SampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.colmi.samples.ColmiStressSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.entities.ColmiActivitySample;
import nodomain.freeyourgadget.gadgetbridge.entities.ColmiHeartRateSample;
import nodomain.freeyourgadget.gadgetbridge.entities.ColmiHrvValueSample;
import nodomain.freeyourgadget.gadgetbridge.entities.ColmiSleepSessionSample;
import nodomain.freeyourgadget.gadgetbridge.entities.ColmiSleepStageSample;
import nodomain.freeyourgadget.gadgetbridge.entities.ColmiSpo2Sample;
import nodomain.freeyourgadget.gadgetbridge.entities.ColmiStressSample;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.Device;
import nodomain.freeyourgadget.gadgetbridge.entities.User;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind;
import nodomain.freeyourgadget.gadgetbridge.model.DeviceService;
import nodomain.freeyourgadget.gadgetbridge.service.btle.BLETypeConversions;
import nodomain.freeyourgadget.gadgetbridge.service.devices.colmi.ColmiR0xDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.util.GB;
import nodomain.freeyourgadget.gadgetbridge.util.StringUtils;

public class ColmiR0xPacketHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ColmiR0xPacketHandler.class);

    public static void hrIntervalSettings(ColmiR0xDeviceSupport support, byte[] value) {
        if (value[1] == ColmiR0xConstants.PREF_WRITE) return;  // ignore empty response when writing setting
        boolean enabled = value[2] == 0x01;
        int minutes = value[3];
        LOG.info("Received HR interval preference: {} minutes, enabled={}", minutes, enabled);
        GBDeviceEventUpdatePreferences eventUpdatePreferences = new GBDeviceEventUpdatePreferences();
        eventUpdatePreferences.withPreference(
                DeviceSettingsPreferenceConst.PREF_HEARTRATE_MEASUREMENT_INTERVAL,
                String.valueOf(minutes * 60)
        );
        support.evaluateGBDeviceEvent(eventUpdatePreferences);
    }

    public static void spo2Settings(ColmiR0xDeviceSupport support, byte[] value) {
        boolean enabled = value[2] == 0x01;
        LOG.info("Received SpO2 preference: {}", enabled ? "enabled" : "disabled");
        GBDeviceEventUpdatePreferences eventUpdatePreferences = new GBDeviceEventUpdatePreferences();
        eventUpdatePreferences.withPreference(
                DeviceSettingsPreferenceConst.PREF_SPO2_ALL_DAY_MONITORING,
                enabled
        );
        support.evaluateGBDeviceEvent(eventUpdatePreferences);
    }

    public static void stressSettings(ColmiR0xDeviceSupport support, byte[] value) {
        boolean enabled = value[2] == 0x01;
        LOG.info("Received stress preference: {}", enabled ? "enabled" : "disabled");
        GBDeviceEventUpdatePreferences eventUpdatePreferences = new GBDeviceEventUpdatePreferences();
        eventUpdatePreferences.withPreference(
                DeviceSettingsPreferenceConst.PREF_HEARTRATE_STRESS_MONITORING,
                enabled
        );
        support.evaluateGBDeviceEvent(eventUpdatePreferences);
    }

    public static void hrvSettings(ColmiR0xDeviceSupport support, byte[] value) {
        boolean enabled = value[2] == 0x01;
        LOG.info("Received HRV preference: {}", enabled ? "enabled" : "disabled");
        GBDeviceEventUpdatePreferences eventUpdatePreferences = new GBDeviceEventUpdatePreferences();
        eventUpdatePreferences.withPreference(
                DeviceSettingsPreferenceConst.PREF_HRV_ALL_DAY_MONITORING,
                enabled
        );
        support.evaluateGBDeviceEvent(eventUpdatePreferences);
    }

    public static void goalsSettings(byte[] value) {
        int steps = BLETypeConversions.toUint32(value[2], value[3], value[4], (byte) 0);
        int calories = BLETypeConversions.toUint32(value[5], value[6], value[7], (byte) 0);
        int distance = BLETypeConversions.toUint32(value[8], value[9], value[10], (byte) 0);
        int sport = BLETypeConversions.toUint16(value[11], value[12]);
        int sleep = BLETypeConversions.toUint16(value[13], value[14]);
        LOG.info("Received goals preferences: {} steps, {} calories, {}m distance, {}min sport, {}min sleep", steps, calories, distance, sport, sleep);
    }

    public static void liveHeartRate(GBDevice device, Context context, byte[] value) {
        int errorCode = value[2];
        int hrResponse = value[3] & 0xff;
        switch (errorCode) {
            case 0:
                LOG.info("Received live heart rate response: {} bpm", hrResponse);
                break;
            case 1:
                GB.toast(context.getString(R.string.smart_ring_measurement_error_worn_incorrectly), Toast.LENGTH_LONG, GB.ERROR);
                LOG.warn("Live HR error code {} received from ring", errorCode);
                return;
            case 2:
                LOG.warn("Live HR error 2 (temporary error / missing data) received");
                return;
            default:
                GB.toast(String.format(context.getString(R.string.smart_ring_measurement_error_unknown), errorCode), Toast.LENGTH_LONG, GB.ERROR);
                LOG.warn("Live HR error code {} received from ring", errorCode);
                return;
        }
        if (hrResponse > 0) {
            try (DBHandler db = GBApplication.acquireDB()) {
                // Build sample object and save in database
                ColmiHeartRateSampleProvider sampleProvider = new ColmiHeartRateSampleProvider(device, db.getDaoSession());
                Long userId = DBHelper.getUser(db.getDaoSession()).getId();
                Long deviceId = DBHelper.getDevice(device, db.getDaoSession()).getId();
                ColmiHeartRateSample gbSample = new ColmiHeartRateSample();
                gbSample.setDeviceId(deviceId);
                gbSample.setUserId(userId);
                gbSample.setTimestamp(Calendar.getInstance().getTimeInMillis());
                gbSample.setHeartRate(hrResponse);
                sampleProvider.addSample(gbSample);
                // Send local intent with sample for listeners like the heart rate dialog
                Intent liveIntent = new Intent(DeviceService.ACTION_REALTIME_SAMPLES);
                liveIntent.putExtra(GBDevice.EXTRA_DEVICE, device);
                liveIntent.putExtra(DeviceService.EXTRA_REALTIME_SAMPLE, gbSample);
                LocalBroadcastManager.getInstance(context)
                        .sendBroadcast(liveIntent);
            } catch (Exception e) {
                LOG.error("Error acquiring database for recording heart rate samples", e);
            }
        }
    }

    public static void liveActivity(byte[] value) {
        int steps = BLETypeConversions.toUint32(value[4], value[3], value[2], (byte) 0);
        int calories = BLETypeConversions.toUint32(value[7], value[6], value[5], (byte) 0) / 10;
        int distance = BLETypeConversions.toUint32(value[10], value[9], value[8], (byte) 0);
        LOG.info("Received live activity notification: {} steps, {} calories, {}m distance", steps, calories, distance);
    }

    public static void historicalActivity(GBDevice device, Context context, byte[] value, boolean calorieNewProtocol) {
        if (ColmiR0xActivityPacket.isEmpty(value)) {
            device.unsetBusyTask();
            device.sendDeviceUpdateIntent(context);
            LOG.info("Empty activity history, sync aborted");
        } else if (ColmiR0xActivityPacket.isHeader(value)) {
            // initial packet, doesn't contain anything interesting
        } else {
            ColmiR0xActivityPacket.ActivitySample activitySample = ColmiR0xActivityPacket.decode(value);
            if (activitySample == null) {
                LOG.warn("Ignoring malformed activity history packet");
                device.unsetBusyTask();
                device.sendDeviceUpdateIntent(context);
                return;
            }

            // Unpack timestamp and data
            Calendar sampleCal = Calendar.getInstance();
            sampleCal.clear();
            sampleCal.setLenient(false);
            sampleCal.set(
                    activitySample.getYear(),
                    activitySample.getMonth() - 1,
                    activitySample.getDay(),
                    activitySample.getHour(),
                    activitySample.getMinute(),
                    0
            );
            try {
                sampleCal.getTimeInMillis();
            } catch (IllegalArgumentException e) {
                LOG.warn("Ignoring activity history packet with invalid date", e);
                device.unsetBusyTask();
                device.sendDeviceUpdateIntent(context);
                return;
            }
            sampleCal.set(Calendar.SECOND, 0);
            sampleCal.set(Calendar.MILLISECOND, 0);
            int calories = activitySample.getCalories(calorieNewProtocol);
            int steps = activitySample.getSteps();
            int distance = activitySample.getDistance();
            LOG.info("Received activity sample: {} - {} calories, {} steps, {} distance", sampleCal.getTime(), calories, steps, distance);
            // Build sample object and save in database
            try (DBHandler db = GBApplication.acquireDB()) {
                ColmiActivitySampleProvider sampleProvider = new ColmiActivitySampleProvider(device, db.getDaoSession());
                Long userId = DBHelper.getUser(db.getDaoSession()).getId();
                Long deviceId = DBHelper.getDevice(device, db.getDaoSession()).getId();
                // Real hourly data supersedes a 0x48 fallback aggregate stored at midnight.
                if (activitySample.getHour() != 0 || activitySample.getMinute() != 0) {
                    removeDailySummary(db, deviceId, sampleCal);
                }
                ColmiActivitySample gbSample = sampleProvider.createActivitySample();
                gbSample.setProvider(sampleProvider);
                gbSample.setDeviceId(deviceId);
                gbSample.setUserId(userId);
                gbSample.setRawKind(ActivityKind.ACTIVITY.getCode());
                gbSample.setTimestamp((int) (sampleCal.getTimeInMillis() / 1000));
                gbSample.setCalories(calories);
                gbSample.setSteps(steps);
                gbSample.setDistance(distance);
                sampleProvider.addGBActivitySample(gbSample);
            } catch (Exception e) {
                LOG.error("Error acquiring database for recording activity samples", e);
            }
            // Determine if this sync is done
            int currentActivityPacket = activitySample.getCurrentPacket();
            int totalActivityPackets = activitySample.getTotalPackets();
            if (currentActivityPacket == totalActivityPackets - 1) {
                device.unsetBusyTask();
                device.sendDeviceUpdateIntent(context);
            }
        }
    }

    private static void removeDailySummary(DBHandler db, long deviceId, Calendar sampleCal) {
        Calendar dayStart = (Calendar) sampleCal.clone();
        dayStart.set(Calendar.HOUR_OF_DAY, 0);
        dayStart.set(Calendar.MINUTE, 0);
        dayStart.set(Calendar.SECOND, 0);
        dayStart.set(Calendar.MILLISECOND, 0);
        int timestamp = (int) (dayStart.getTimeInMillis() / 1000);

        int deleted = db.getDatabase().delete(
                "COLMI_ACTIVITY_SAMPLE",
                "TIMESTAMP = ? AND DEVICE_ID = ?",
                new String[]{String.valueOf(timestamp), String.valueOf(deviceId)}
        );
        if (deleted > 0) {
            LOG.info("Removed stale daily activity summary at {}", dayStart.getTime());
        }
    }

    public static void historicalTodayActivity(GBDevice device, byte[] value, Calendar sampleDay) {
        ColmiR0xActivityPacket.TodaySummary summary = ColmiR0xActivityPacket.decodeTodaySummary(value);
        if (summary == null) {
            LOG.warn("Ignoring malformed today activity packet: {}", StringUtils.bytesToHex(value));
            return;
        }

        if (summary.getSteps() == 0 && summary.getCalories() == 0 && summary.getDistance() == 0) {
            LOG.info("Received empty today activity summary");
            return;
        }

        Calendar sampleCal = sampleDay == null ? Calendar.getInstance() : (Calendar) sampleDay.clone();
        sampleCal.set(Calendar.HOUR_OF_DAY, 0);
        sampleCal.set(Calendar.MINUTE, 0);
        sampleCal.set(Calendar.SECOND, 0);
        sampleCal.set(Calendar.MILLISECOND, 0);
        LOG.info(
                "Received today activity summary: {} calories, {} steps, {} distance",
                summary.getCalories(),
                summary.getSteps(),
                summary.getDistance()
        );

        try (DBHandler db = GBApplication.acquireDB()) {
            ColmiActivitySampleProvider sampleProvider = new ColmiActivitySampleProvider(device, db.getDaoSession());
            Long userId = DBHelper.getUser(db.getDaoSession()).getId();
            Long deviceId = DBHelper.getDevice(device, db.getDaoSession()).getId();
            int dayStartTimestamp = (int) (sampleCal.getTimeInMillis() / 1000);
            Calendar nextDay = (Calendar) sampleCal.clone();
            nextDay.add(Calendar.DAY_OF_YEAR, 1);
            int nextDayTimestamp = (int) (nextDay.getTimeInMillis() / 1000);
            boolean hasHourlySamples;
            try (Cursor cursor = db.getDatabase().rawQuery(
                    "SELECT 1 FROM COLMI_ACTIVITY_SAMPLE WHERE DEVICE_ID = ? AND TIMESTAMP > ? AND TIMESTAMP < ? LIMIT 1",
                    new String[]{
                            String.valueOf(deviceId),
                            String.valueOf(dayStartTimestamp),
                            String.valueOf(nextDayTimestamp)
                    }
            )) {
                hasHourlySamples = cursor.moveToFirst();
            }
            if (hasHourlySamples) {
                removeDailySummary(db, deviceId, sampleCal);
                LOG.info("Skipping today's activity summary because hourly activity samples already exist");
                return;
            }
            ColmiActivitySample gbSample = sampleProvider.createActivitySample();
            gbSample.setProvider(sampleProvider);
            gbSample.setDeviceId(deviceId);
            gbSample.setUserId(userId);
            gbSample.setRawKind(ActivityKind.ACTIVITY.getCode());
            gbSample.setTimestamp(dayStartTimestamp);
            gbSample.setCalories(summary.getCalories());
            gbSample.setSteps(summary.getSteps());
            gbSample.setDistance(summary.getDistance());
            sampleProvider.addGBActivitySample(gbSample);
        } catch (Exception e) {
            LOG.error("Error acquiring database for recording today's activity summary", e);
        }
    }

    public static void historicalStress(GBDevice device, Context context, byte[] value) {
        historicalStress(device, context, value, 0, false);
    }

    public static void historicalStress(
            GBDevice device,
            Context context,
            byte[] value,
            int daysAgo,
            boolean h59Layout
    ) {
        ArrayList<ColmiStressSample> stressSamples = new ArrayList<>();
        int stressPacketNr = value[1] & 0xff;
        if (stressPacketNr == 0xff) {
            device.unsetBusyTask();
            device.sendDeviceUpdateIntent(context);
            LOG.info("Empty stress history, sync aborted");
        } else if (stressPacketNr == 0) {
            LOG.info("Received initial stress history response");
        } else {
            Calendar sampleCal = Calendar.getInstance();
            sampleCal.add(Calendar.DAY_OF_MONTH, -daysAgo);
            sampleCal.set(Calendar.HOUR_OF_DAY, 0);
            sampleCal.set(Calendar.MINUTE, 0);
            sampleCal.set(Calendar.SECOND, 0);
            sampleCal.set(Calendar.MILLISECOND, 0);
            for (final ColmiR0xSlotPacket.SlotSample slotSample : ColmiR0xSlotPacket.decode(value, h59Layout)) {
                int minuteOfDay = slotSample.getSlot() * 30;
                if (minuteOfDay < 24 * 60) {
                    sampleCal.set(Calendar.HOUR_OF_DAY, minuteOfDay / 60);
                    sampleCal.set(Calendar.MINUTE, minuteOfDay % 60);
                    LOG.info("Stress level is {} at {}", slotSample.getValue(), sampleCal.getTime());
                    // Build sample object and save in database
                    ColmiStressSample gbSample = new ColmiStressSample();
                    gbSample.setTimestamp(sampleCal.getTimeInMillis());
                    gbSample.setStress(slotSample.getValue());
                    stressSamples.add(gbSample);
                }
            }
            if (!stressSamples.isEmpty()) {
                try (DBHandler db = GBApplication.acquireDB()) {
                    ColmiStressSampleProvider sampleProvider = new ColmiStressSampleProvider(device, db.getDaoSession());
                    Long userId = DBHelper.getUser(db.getDaoSession()).getId();
                    Long deviceId = DBHelper.getDevice(device, db.getDaoSession()).getId();
                    for (final ColmiStressSample sample : stressSamples) {
                        sample.setDeviceId(deviceId);
                        sample.setUserId(userId);
                    }
                    LOG.info("Will persist {} stress samples", stressSamples.size());
                    sampleProvider.addSamples(stressSamples);
                } catch (Exception e) {
                    LOG.error("Error acquiring database for recording stress samples", e);
                }
            }
            if (stressPacketNr == 4) {
                device.unsetBusyTask();
                device.sendDeviceUpdateIntent(context);
            }
        }
    }

    public static void historicalSpo2(GBDevice device, byte[] value) {
        ArrayList<ColmiSpo2Sample> spo2Samples = new ArrayList<>();
        int length = BLETypeConversions.toUint16(value[2], value[3]);
        int index = 6; // start of data (day nr, followed by values)
        int spo2_days_ago = -1;
        while (spo2_days_ago != 0 && index - 6 < length) {
            spo2_days_ago = value[index];
            Calendar syncingDay = Calendar.getInstance();
            syncingDay.add(Calendar.DAY_OF_MONTH, 0 - spo2_days_ago);
            syncingDay.set(Calendar.MINUTE, 0);
            syncingDay.set(Calendar.SECOND, 0);
            syncingDay.set(Calendar.MILLISECOND, 0);
            index++;
            for (int hour=0; hour<=23; hour++) {
                syncingDay.set(Calendar.HOUR_OF_DAY, hour);
                float spo2_min = value[index];
                index++;
                float spo2_max = value[index];
                index++;
                if (spo2_min > 0 && spo2_max > 0) {
                    LOG.info("Received SpO2 data from {} days ago at {}:00: min={}, max={}", spo2_days_ago, hour, spo2_min, spo2_max);
                    ColmiSpo2Sample spo2Sample = new ColmiSpo2Sample();
                    spo2Sample.setTimestamp(syncingDay.getTimeInMillis());
                    spo2Sample.setSpo2(Math.round((spo2_min + spo2_max) / 2.0f));
                    spo2Samples.add(spo2Sample);
                }
                if (index - 6 >= length) {
                    break;
                }
            }
        }
        if (!spo2Samples.isEmpty()) {
            try (DBHandler db = GBApplication.acquireDB()) {
                ColmiSpo2SampleProvider sampleProvider = new ColmiSpo2SampleProvider(device, db.getDaoSession());
                Long userId = DBHelper.getUser(db.getDaoSession()).getId();
                Long deviceId = DBHelper.getDevice(device, db.getDaoSession()).getId();
                for (final ColmiSpo2Sample sample : spo2Samples) {
                    sample.setDeviceId(deviceId);
                    sample.setUserId(userId);
                }
                LOG.info("Will persist {} SpO2 samples", spo2Samples.size());
                sampleProvider.addSamples(spo2Samples);
            } catch (Exception e) {
                LOG.error("Error acquiring database for recording SpO2 samples", e);
            }
        }
    }

    public static void historicalSleep(GBDevice gbDevice, Context context, byte[] value) {
        final List<ColmiR0xSleepPacket.SleepSession> sleepSessions = ColmiR0xSleepPacket.decode(value);
        if (sleepSessions.isEmpty()) {
            LOG.info("Received empty sleep data packet: {}", StringUtils.bytesToHex(value));
            return;
        }

        LOG.debug("Received {} sleep sessions", sleepSessions.size());
        for (final ColmiR0xSleepPacket.SleepSession sleepSession : sleepSessions) {
            final Calendar sessionDay = Calendar.getInstance();
            sessionDay.add(Calendar.DAY_OF_MONTH, -sleepSession.getDaysAgo());
            sessionDay.set(Calendar.HOUR_OF_DAY, 0);
            sessionDay.set(Calendar.MINUTE, 0);
            sessionDay.set(Calendar.SECOND, 0);
            sessionDay.set(Calendar.MILLISECOND, 0);

            final Calendar sessionStart = (Calendar) sessionDay.clone();
            if (sleepSession.getSleepStart() > sleepSession.getSleepEnd()) {
                sessionStart.add(Calendar.MINUTE, sleepSession.getSleepStart() - 1440);
            } else {
                sessionStart.add(Calendar.MINUTE, sleepSession.getSleepStart());
            }

            final Calendar sessionEnd = (Calendar) sessionDay.clone();
            sessionEnd.add(Calendar.MINUTE, sleepSession.getSleepEnd());
            LOG.info("Sleep session starts at {} and ends at {}", sessionStart.getTime(), sessionEnd.getTime());

            final ColmiSleepSessionSample sessionSample = new ColmiSleepSessionSample();
            sessionSample.setTimestamp(sessionStart.getTimeInMillis());
            sessionSample.setWakeupTime(sessionEnd.getTimeInMillis());

            final List<ColmiSleepStageSample> stageSamples = new ArrayList<>();
            final Calendar sleepStage = (Calendar) sessionStart.clone();
            for (final ColmiR0xSleepPacket.SleepStage sleepStageData : sleepSession.getStages()) {
                final ColmiSleepStageSample sample = new ColmiSleepStageSample();
                sample.setTimestamp(sleepStage.getTimeInMillis());
                sample.setDuration(sleepStageData.getDuration());
                sample.setStage(sleepStageData.getStage());
                LOG.info(
                        "Sleep stage type={} starts at {} and lasts for {} minutes",
                        sleepStageData.getStage(),
                        sleepStage.getTime(),
                        sleepStageData.getDuration()
                );
                stageSamples.add(sample);
                sleepStage.add(Calendar.MINUTE, sleepStageData.getDuration());
            }

            try (DBHandler handler = GBApplication.acquireDB()) {
                final DaoSession session = handler.getDaoSession();
                final Device device = DBHelper.getDevice(gbDevice, session);
                final User user = DBHelper.getUser(session);

                final ColmiSleepSessionSampleProvider sessionProvider = new ColmiSleepSessionSampleProvider(gbDevice, session);
                sessionSample.setDevice(device);
                sessionSample.setUser(user);
                sessionProvider.addSample(sessionSample);

                final ColmiSleepStageSampleProvider stageProvider = new ColmiSleepStageSampleProvider(gbDevice, session);
                for (final ColmiSleepStageSample sample : stageSamples) {
                    sample.setDevice(device);
                    sample.setUser(user);
                }
                stageProvider.addSamples(stageSamples);
                LOG.debug("Persisted sleep session with {} stages", stageSamples.size());
            } catch (final Exception e) {
                GB.toast(context, "Error saving sleep samples", Toast.LENGTH_LONG, GB.ERROR, e);
            }
        }
    }

    public static void historicalHRV(GBDevice device, Context context, byte[] value, int daysAgo) {
        historicalHRV(device, context, value, daysAgo, false);
    }

    public static void historicalHRV(
            GBDevice device,
            Context context,
            byte[] value,
            int daysAgo,
            boolean h59Layout
    ) {
        LOG.info("Received HRV history sync packet: {}", StringUtils.bytesToHex(value));
        int hrvPacketNr = value[1] & 0xff;
        if (hrvPacketNr == 0xff) {
            LOG.info("Empty HRV history, sync aborted");
            device.unsetBusyTask();
            device.sendDeviceUpdateIntent(context);
        } else if (hrvPacketNr == 0) {
            int packetsTotalNr = value[2];
            LOG.info("HRV history packet {} out of total {}", hrvPacketNr, packetsTotalNr);
        } else {
            LOG.info("HRV history packet {}", hrvPacketNr);
            Calendar sampleCal = Calendar.getInstance();
            if (daysAgo != 0) {
                sampleCal.add(Calendar.DAY_OF_MONTH, 0 - daysAgo);
                sampleCal.set(Calendar.HOUR_OF_DAY, 0);
                sampleCal.set(Calendar.MINUTE, 0);
            }
            sampleCal.set(Calendar.SECOND, 0);
            sampleCal.set(Calendar.MILLISECOND, 0);
            for (final ColmiR0xSlotPacket.SlotSample slotSample : ColmiR0xSlotPacket.decode(value, h59Layout)) {
                int minuteOfDay = slotSample.getSlot() * 30;
                if (minuteOfDay < 24 * 60) {
                    sampleCal.set(Calendar.HOUR_OF_DAY, minuteOfDay / 60);
                    sampleCal.set(Calendar.MINUTE, minuteOfDay % 60);
                    LOG.info("Value {} is {} ms, time of day is {}", slotSample.getSlot(), slotSample.getValue(), sampleCal.getTime());
                    // Build sample object and save in database
                    try (DBHandler db = GBApplication.acquireDB()) {
                        ColmiHrvValueSampleProvider sampleProvider = new ColmiHrvValueSampleProvider(device, db.getDaoSession());
                        Long userId = DBHelper.getUser(db.getDaoSession()).getId();
                        Long deviceId = DBHelper.getDevice(device, db.getDaoSession()).getId();
                        ColmiHrvValueSample gbSample = new ColmiHrvValueSample();
                        gbSample.setDeviceId(deviceId);
                        gbSample.setUserId(userId);
                        gbSample.setTimestamp(sampleCal.getTimeInMillis());
                        gbSample.setValue(slotSample.getValue());
                        sampleProvider.addSample(gbSample);
                    } catch (Exception e) {
                        LOG.error("Error acquiring database for recording HRV samples", e);
                    }
                }
            }
            if (hrvPacketNr == 4) {
                device.unsetBusyTask();
                device.sendDeviceUpdateIntent(context);
            }
        }
    }
}
