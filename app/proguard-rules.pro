# Sherpa-ONNX and llama.cpp expose JNI/Kotlin bridge classes by name.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keep class org.nehuatl.llamacpp.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
