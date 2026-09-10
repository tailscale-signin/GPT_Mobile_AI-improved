package dev.chungjungsoo.gptmobile.data.localruntime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceHardwareGovernorTest {

    @Test
    fun `normal conditions on high RAM device yield 8ms high refresh rate budget without throttling`() {
        val normalState = DeviceHardwareState(
            thermalState = DeviceThermalState.NORMAL,
            batteryPct = 85,
            isCharging = false,
            isPowerSaveMode = false
        )

        assertFalse(normalState.isThrottlingRequired)
        assertFalse(normalState.isModeratePressure)

        val policy = DeviceHardwareGovernor.computeThrottlingPolicy(normalState, isHighRamDevice = true)

        assertEquals(8L, policy.streamPublishIntervalMillis)
        assertEquals(1.0f, policy.topKReductionRatio, 0.001f)
        assertNull(policy.maxTokensClamp)
        assertFalse(policy.isCooperativeYieldAggressive)
    }

    @Test
    fun `normal conditions on standard RAM device yield standard 33ms interval`() {
        val normalState = DeviceHardwareState(
            thermalState = DeviceThermalState.NORMAL,
            batteryPct = 70,
            isCharging = true,
            isPowerSaveMode = false
        )

        val policy = DeviceHardwareGovernor.computeThrottlingPolicy(normalState, isHighRamDevice = false)

        assertEquals(33L, policy.streamPublishIntervalMillis)
        assertEquals(1.0f, policy.topKReductionRatio, 0.001f)
        assertNull(policy.maxTokensClamp)
    }

    @Test
    fun `moderate thermal state triggers moderate throttling`() {
        val moderateState = DeviceHardwareState(
            thermalState = DeviceThermalState.MODERATE,
            batteryPct = 60,
            isCharging = false,
            isPowerSaveMode = false
        )

        assertFalse(moderateState.isThrottlingRequired)
        assertTrue(moderateState.isModeratePressure)

        val policy = DeviceHardwareGovernor.computeThrottlingPolicy(moderateState, isHighRamDevice = true)

        assertEquals(33L, policy.streamPublishIntervalMillis)
        assertEquals(0.75f, policy.topKReductionRatio, 0.001f)
        assertEquals(2048, policy.maxTokensClamp)
        assertFalse(policy.isCooperativeYieldAggressive)
    }

    @Test
    fun `severe thermal state triggers aggressive throttling and clamps context`() {
        val severeState = DeviceHardwareState(
            thermalState = DeviceThermalState.SEVERE,
            batteryPct = 50,
            isCharging = false,
            isPowerSaveMode = false
        )

        assertTrue(severeState.isThrottlingRequired)

        val policy = DeviceHardwareGovernor.computeThrottlingPolicy(severeState, isHighRamDevice = true)

        assertEquals(250L, policy.streamPublishIntervalMillis)
        assertEquals(0.5f, policy.topKReductionRatio, 0.001f)
        assertEquals(1024, policy.maxTokensClamp)
        assertTrue(policy.isCooperativeYieldAggressive)
    }

    @Test
    fun `critical thermal state triggers aggressive throttling`() {
        val criticalState = DeviceHardwareState(
            thermalState = DeviceThermalState.CRITICAL,
            batteryPct = 80,
            isCharging = true,
            isPowerSaveMode = false
        )

        assertTrue(criticalState.isThrottlingRequired)

        val policy = DeviceHardwareGovernor.computeThrottlingPolicy(criticalState, isHighRamDevice = true)

        assertEquals(250L, policy.streamPublishIntervalMillis)
        assertEquals(0.5f, policy.topKReductionRatio, 0.001f)
        assertEquals(1024, policy.maxTokensClamp)
        assertTrue(policy.isCooperativeYieldAggressive)
    }

    @Test
    fun `low battery when discharging triggers throttling`() {
        val lowBatteryState = DeviceHardwareState(
            thermalState = DeviceThermalState.NORMAL,
            batteryPct = 12,
            isCharging = false,
            isPowerSaveMode = false
        )

        assertTrue(lowBatteryState.isThrottlingRequired)

        val policy = DeviceHardwareGovernor.computeThrottlingPolicy(lowBatteryState, isHighRamDevice = true)

        assertEquals(250L, policy.streamPublishIntervalMillis)
        assertEquals(0.5f, policy.topKReductionRatio, 0.001f)
        assertEquals(1024, policy.maxTokensClamp)
    }

    @Test
    fun `low battery while charging does not trigger severe throttling unless extreme`() {
        val chargingLowState = DeviceHardwareState(
            thermalState = DeviceThermalState.NORMAL,
            batteryPct = 12,
            isCharging = true,
            isPowerSaveMode = false
        )

        assertFalse(chargingLowState.isThrottlingRequired)
        assertFalse(chargingLowState.isModeratePressure)

        val policy = DeviceHardwareGovernor.computeThrottlingPolicy(chargingLowState, isHighRamDevice = true)

        assertEquals(8L, policy.streamPublishIntervalMillis)
        assertEquals(1.0f, policy.topKReductionRatio, 0.001f)
    }

    @Test
    fun `power save mode active triggers throttling regardless of battery level`() {
        val powerSaveState = DeviceHardwareState(
            thermalState = DeviceThermalState.NORMAL,
            batteryPct = 90,
            isCharging = false,
            isPowerSaveMode = true
        )

        assertTrue(powerSaveState.isThrottlingRequired)

        val policy = DeviceHardwareGovernor.computeThrottlingPolicy(powerSaveState, isHighRamDevice = true)

        assertEquals(250L, policy.streamPublishIntervalMillis)
        assertEquals(0.5f, policy.topKReductionRatio, 0.001f)
        assertEquals(1024, policy.maxTokensClamp)
    }
}
