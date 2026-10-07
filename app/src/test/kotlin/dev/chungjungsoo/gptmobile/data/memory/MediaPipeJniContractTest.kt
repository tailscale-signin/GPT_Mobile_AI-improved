package dev.chungjungsoo.gptmobile.data.memory

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaPipeJniContractTest {
    @Test fun `packaged embedding SDK has the Java entry points its native workers require`() {
        MediaPipeJniContract.verify()
    }

    @Test fun `missing native callback is rejected before native initialization`() {
        val loader = object : ClassLoader(MediaPipeJniContractTest::class.java.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name == "com.google.mediapipe.framework.PacketListCallback") throw ClassNotFoundException(name)
                return super.loadClass(name, resolve)
            }
        }
        val failure = assertThrows(LinkageError::class.java) { MediaPipeJniContract.verify(loader) }
        assertTrue(failure.cause is ClassNotFoundException)
    }
}
