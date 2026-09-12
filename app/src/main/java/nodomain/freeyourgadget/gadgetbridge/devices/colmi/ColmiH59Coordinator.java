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

import java.util.regex.Pattern;

import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettings;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.DeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.devices.colmi.ColmiH59DeviceSupport;

public class ColmiH59Coordinator extends AbstractColmiR0xCoordinator {
    @Override
    protected Pattern getSupportedDeviceName() {
        return Pattern.compile("^H59(?:_.*)?$");
    }

    @Override
    public int getDeviceNameResource() {
        return R.string.devicetype_colmi_h59;
    }

    @Override
    public boolean isExperimental() {
        return true;
    }

    @Override
    public Class<? extends DeviceSupport> getDeviceSupportClass() {
        return ColmiH59DeviceSupport.class;
    }

    @Override
    public boolean supportsPowerOff() {
        return false;
    }

    @Override
    public boolean supportsFindDevice() {
        return false;
    }

    @Override
    public boolean supportsRealtimeData() {
        return false;
    }

    @Override
    public boolean supportsManualHeartRateMeasurement(GBDevice device) {
        return false;
    }

    @Override
    public DeviceSpecificSettings getDeviceSpecificSettings(GBDevice device) {
        return new DeviceSpecificSettings();
    }
}
