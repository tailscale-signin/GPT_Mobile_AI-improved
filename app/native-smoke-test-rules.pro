# AndroidJUnitRunner discovers this test by class name and @Test annotations.
# This applies only to the test APK; production code still uses release R8 rules.
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keep class dev.chungjungsoo.gptmobile.data.memory.LocalSemanticMemoryInstrumentedTest { *; }
