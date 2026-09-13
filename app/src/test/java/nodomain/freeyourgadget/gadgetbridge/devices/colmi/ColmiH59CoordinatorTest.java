package nodomain.freeyourgadget.gadgetbridge.devices.colmi;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class ColmiH59CoordinatorTest {
    @Test
    public void supportsActiveCalories() {
        assertTrue(new ColmiH59Coordinator().supportsActiveCalories());
    }
}
