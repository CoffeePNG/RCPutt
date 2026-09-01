package com.redcoffee.puttputt.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PowerMeterConfigTest {

    private final PowerMeterConfig oscillate =
            new PowerMeterConfig(PowerMeterConfig.Mode.OSCILLATE, 0.1, 0.9, 20, 0.35);
    private final PowerMeterConfig fill =
            new PowerMeterConfig(PowerMeterConfig.Mode.FILL, 0.1, 0.9, 20, 0.35);

    /** The sweep must go up and come back down, or there is no timing skill in the meter. */
    @Test
    void oscillateSweepsUpThenBackDown() {
        assertEquals(0.0, oscillate.powerAt(0), 1.0e-9);
        assertEquals(0.5, oscillate.powerAt(10), 1.0e-9);
        assertEquals(1.0, oscillate.powerAt(20), 1.0e-9);
        assertEquals(0.5, oscillate.powerAt(30), 1.0e-9, "past the peak the bar falls again");
        assertEquals(0.0, oscillate.powerAt(40), 1.0e-9);
        assertEquals(0.5, oscillate.powerAt(50), 1.0e-9, "and then repeats");
    }

    @Test
    void fillRisesAndHoldsAtFull() {
        assertEquals(0.5, fill.powerAt(10), 1.0e-9);
        assertEquals(1.0, fill.powerAt(20), 1.0e-9);
        assertEquals(1.0, fill.powerAt(200), 1.0e-9, "holding longer must not overshoot");
    }

    @Test
    void powerMapsOntoTheConfiguredVelocityBand() {
        assertEquals(0.1, oscillate.velocityFor(0.0), 1.0e-9);
        assertEquals(0.9, oscillate.velocityFor(1.0), 1.0e-9);
        assertEquals(0.5, oscillate.velocityFor(0.5), 1.0e-9);
    }

    @Test
    void velocityIsClampedForOutOfRangeReadings() {
        assertEquals(0.1, oscillate.velocityFor(-3.0), 1.0e-9);
        assertEquals(0.9, oscillate.velocityFor(4.0), 1.0e-9);
    }

    @Test
    void oscillateNeverLeavesTheUnitRange() {
        for (int tick = 0; tick < 500; tick++) {
            double power = oscillate.powerAt(tick);
            assertTrue(power >= 0.0 && power <= 1.0, "power out of range at tick " + tick + ": " + power);
        }
    }

    @Test
    void rejectsAnInvertedVelocityBand() {
        assertThrows(IllegalArgumentException.class,
                () -> new PowerMeterConfig(PowerMeterConfig.Mode.FILL, 0.9, 0.2, 20, 0.35));
    }

    /** Sneaking must crawl the meter, not stop or reverse it. */
    @Test
    void sneakRateSlowsTheSweepWithoutBreakingIt() {
        PowerMeterConfig config = new PowerMeterConfig(PowerMeterConfig.Mode.FILL, 0.1, 0.9, 20, 0.25);

        assertEquals(0.25, config.sneakRate(), 1.0e-9);
        // A quarter-speed hold covers a quarter of the sweep in the same number of ticks.
        assertEquals(config.powerAt(5.0), config.powerAt(20 * 0.25), 1.0e-9);
        assertTrue(config.powerAt(20 * 0.25) < config.powerAt(20.0));
    }

    @Test
    void rejectsANonsenseSneakRate() {
        assertThrows(IllegalArgumentException.class,
                () -> new PowerMeterConfig(PowerMeterConfig.Mode.FILL, 0.1, 0.9, 20, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> new PowerMeterConfig(PowerMeterConfig.Mode.FILL, 0.1, 0.9, 20, 1.5));
    }

    @Test
    void modeAliasesParse() {
        assertEquals(PowerMeterConfig.Mode.FILL,
                PowerMeterConfig.Mode.parse("fill", PowerMeterConfig.Mode.OSCILLATE));
        assertEquals(PowerMeterConfig.Mode.OSCILLATE,
                PowerMeterConfig.Mode.parse("sweep", PowerMeterConfig.Mode.FILL));
        assertEquals(PowerMeterConfig.Mode.FILL,
                PowerMeterConfig.Mode.parse("nonsense", PowerMeterConfig.Mode.FILL));
    }
}
