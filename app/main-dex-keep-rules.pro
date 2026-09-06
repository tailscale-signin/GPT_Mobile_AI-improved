# Keep all classes required by Android runtime at application bootstrap
-keep class dev.melo.gptmobile.improved.presentation.GPTMobileApp { <init>(); }
-keep class dev.melo.gptmobile.improved.presentation.Hilt_GPTMobileApp { <init>(); }
-keep class * extends android.app.Application { <init>(); }
-keep class androidx.multidex.MultiDexApplication { <init>(); }
