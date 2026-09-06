# Keep all classes required by Android runtime at application bootstrap
-keep class dev.melo.gptmobile.improved.presentation.GPTMobileApp { *; }
-keep class dev.melo.gptmobile.improved.presentation.Hilt_GPTMobileApp { *; }
-keep class * extends android.app.Application { *; }
-keep class androidx.multidex.MultiDexApplication { *; }
