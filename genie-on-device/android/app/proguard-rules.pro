# The JNI layer calls back into this class by name/signature; R8 must not touch it.
-keep class com.qcs.geniechat.GenieBridge { *; }
-keep interface com.qcs.geniechat.TokenSink { *; }
