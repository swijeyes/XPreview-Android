# JNI entry points and the classes native code constructs or calls back.
-keep class org.xpreview.pdf.PdfiumNative { native <methods>; }
-keep class org.opencv.** { *; }
-keep class com.googlecode.tesseract.android.** { *; }
-keep class com.googlecode.leptonica.android.** { *; }
-dontwarn com.caverock.androidsvg.**
