# Preserve JNI method names in retained upstream codecs; no vehicle components are kept.
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }
-dontwarn java.lang.invoke.StringConcatFactory
