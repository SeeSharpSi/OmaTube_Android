# OmaTube R8 configuration.
#
# NewPipeExtractor and its Rhino JavaScript engine load classes reflectively, so
# the following rules are required. They mirror the upstream NewPipe rules:
# https://github.com/TeamNewPipe/NewPipe/blob/dev/app/proguard-rules.pro

## NewPipeExtractor
-keep class org.schabi.newpipe.extractor.timeago.patterns.** { *; }
-keep class com.grack.nanojson.** { *; }
-dontwarn com.grack.nanojson.**

## Rhino and Rhino Engine (used by NewPipeExtractor for JavaScript parsing)
-keep class org.mozilla.javascript.* { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.javascript.engine.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.JavaToJSONConverters
-dontwarn org.mozilla.javascript.tools.**
-keep class javax.script.** { *; }
-dontwarn javax.script.**
-keep class jdk.dynalink.** { *; }
-dontwarn jdk.dynalink.**

## jsoup HTML parsing
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**

## OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

## Protobuf (NewPipeExtractor player responses)
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}

## Serializable model retention
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
}
