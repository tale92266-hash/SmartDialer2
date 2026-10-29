# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
# SmartDialer2 release rules: the app uses no reflection, no
# serialization and no JNI, so R8 can shrink the libraries hard.
# Keep every app class by name (the app's own code is small; the
# size win comes from the Compose/Material libraries) and keep the
# entry points the system reaches by reflection (services, the
# application class, broadcast receivers).
-keep class com.smartdialerpro.android.** { *; }
-keep class com.smartdialerpro.android.SmartDialerApplication { *; }
-keepattributes SourceFile,LineNumberTable
-dontwarn java.lang.invoke.**
