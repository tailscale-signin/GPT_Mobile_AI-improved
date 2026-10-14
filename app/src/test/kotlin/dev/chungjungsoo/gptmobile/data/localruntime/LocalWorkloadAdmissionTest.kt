package dev.chungjungsoo.gptmobile.data.localruntime

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LocalWorkloadAdmissionTest {
    @Test fun `helpers recover after stable headroom and yield immediately under pressure`() {
        var now = 0L
        val admission = LocalWorkloadAdmission { now }
        val normal = DeviceHardwareState()
        assertNull(admission.rejection(normal, 1000, 100, 100, true))
        now = 30_000
        assertNull(admission.rejection(normal, 1000, 100, 100, true))
        assertNotNull(admission.rejection(normal.copy(thermalState = DeviceThermalState.MODERATE), 1000, 100, 100, true))
        assertNotNull(admission.rejection(normal, 1000, 100, 100, true))
        assertNull(admission.rejection(normal, 1000, 100, 100, false))
        now = 59_999
        assertNotNull(admission.rejection(normal, 1000, 100, 100, true))
        now = 60_000
        assertNull(admission.rejection(normal, 1000, 100, 100, true))
    }

    @Test fun `low memory and severe heat reject work without mutating budgets`() {
        val admission = LocalWorkloadAdmission { 0L }
        assertNotNull(admission.rejection(DeviceHardwareState(), 199, 100, 100, false))
        assertNotNull(admission.rejection(DeviceHardwareState(thermalState = DeviceThermalState.SEVERE), 1000, 100, 100, false))
    }
    @Test fun `documented log values reject with four GB reserve but admit with recommended default`() {
        val admission = LocalWorkloadAdmission { 0L }
        val required = 4_123_590_656L
        val available = 6_505_603_072L
        val fourGiB = 4_294_967_296L
        val default = 768L * 1024 * 1024

        assertNotNull(admission.rejection(DeviceHardwareState(), available, required, fourGiB, false))
        assertNull(admission.rejection(DeviceHardwareState(), available, required, default, false))
    }

}
