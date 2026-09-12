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
package nodomain.freeyourgadget.gadgetbridge.entities;

public abstract class AbstractColmiActivitySample extends AbstractActivitySample {
    private int rawIntensity = 0;

    abstract public int getCalories();

    abstract public void setCalories(int calories);

    abstract public int getDistance();

    abstract public void setDistance(int distance);

    @Override
    public int getActiveCalories() {
        return getCalories();
    }

    @Override
    public void setActiveCalories(int activeCalories) {
        setCalories(activeCalories);
    }

    @Override
    public int getDistanceCm() {
        return getDistance() == NOT_MEASURED ? NOT_MEASURED : getDistance() * 100;
    }

    @Override
    public void setDistanceCm(int distanceCm) {
        setDistance(distanceCm == NOT_MEASURED ? NOT_MEASURED : distanceCm / 100);
    }

    @Override
    public void setRawIntensity(int rawIntensity) {
        this.rawIntensity = rawIntensity;
    }

    @Override
    public int getRawIntensity() {
        if (rawIntensity != 0) {
            return rawIntensity;
        } else {
            return getCalories();
        }
    }
}
