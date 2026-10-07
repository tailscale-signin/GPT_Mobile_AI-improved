package dev.chungjungsoo.gptmobile.data.memory

import java.lang.reflect.Modifier

/** Fail in Java before a missing callback/field can abort ART on a native worker. */
internal object MediaPipeJniContract {
    fun verify(classLoader: ClassLoader = checkNotNull(MediaPipeJniContract::class.java.classLoader)) {
        try {
            fun type(name: String) = Class.forName("com.google.mediapipe.framework.$name", false, classLoader)
            val packet = type("Packet")
            val create = packet.getDeclaredMethod("create", java.lang.Long.TYPE)
            if (!Modifier.isStatic(create.modifiers) || create.returnType != packet) {
                throw NoSuchMethodException("Packet.create(long)")
            }
            if (packet.getDeclaredMethod("getNativeHandle").returnType != java.lang.Long.TYPE) {
                throw NoSuchMethodException("Packet.getNativeHandle()")
            }
            if (packet.getDeclaredMethod("release").returnType != Void.TYPE) {
                throw NoSuchMethodException("Packet.release()")
            }
            val callback = type("PacketListCallback").getDeclaredMethod("process", java.util.List::class.java)
            if (callback.returnType != Void.TYPE) throw NoSuchMethodException("PacketListCallback.process(List)")
            type("MediaPipeException").getDeclaredConstructor(Integer.TYPE, ByteArray::class.java)
            val message = type("ProtoUtil\$SerializedMessage")
            if (message.getDeclaredField("typeName").type != String::class.java || message.getDeclaredField("value").type != ByteArray::class.java) {
                throw NoSuchFieldException("ProtoUtil.SerializedMessage")
            }
        } catch (failure: ReflectiveOperationException) {
            // LocalSemanticMemory treats linkage failures as permanent for this
            // process and continues exact/topic recall without entering JNI.
            throw LinkageError("MediaPipe JNI bindings are missing from this build.", failure)
        }
    }
}
