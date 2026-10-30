# R8 / ProGuard 规则（2026-10-31 开 R8 时补）
#
# 取舍：**先把自己代码全保住，只让 R8 压第三方**。
# 体积的大头在 ML Kit 模型、BouncyCastle、PDFBox、sshd，不在 app 自己的类上；
# 保住 com.dsmobile.app.** 意味着不会因为混淆/内联改变行为，代价只是少压几十 KB。

## 基础：保留泛型签名与调试信息（崩溃栈要能读）
-keepattributes Signature,InnerClasses,EnclosingMethod,AnnotationDefault,*Annotation*,SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

## 本 App：全部保留（见上）
-keep class com.dsmobile.app.** { *; }

## BouncyCastle：provider 靠反射与类名注册，必须整包保
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

## Apache MINA SSHD（调试用的 SSH/SFTP）：ServiceLoader + 反射构造
-keep class org.apache.sshd.** { *; }
-keepclassmembers class org.apache.sshd.** { *; }

-dontwarn org.apache.sshd.**

## slf4j：sshd 用它打日志，provider 是 ServiceLoader 找的，被裁掉会 ClassNotFound
-keep class org.slf4j.** { *; }
-keep class org.slf4j.impl.** { *; }
-keep class org.slf4j.simple.** { *; }
-dontwarn org.slf4j.**

## PDFBox（pdfbox-android）：COS 字典靠反射建对象
-keep class com.tom_roush.** { *; }
-dontwarn com.tom_roush.**

## 压缩/归档：commons-compress 与 junrar 都按 Codec 名反射
-keep class org.apache.commons.compress.** { *; }
-keep class com.github.junrar.** { *; }
-keep class org.tukaani.xz.** { *; }
-dontwarn org.apache.commons.compress.**
-dontwarn com.github.junrar.**

## ML Kit 文字识别（OCR）
-keep class com.google.mlkit.** { *; }

-dontwarn com.google.mlkit.**

## ServiceLoader：R8 会保留被 keep 的类所对应的 META-INF/services 条目

## Kotlin / 协程
-keep class kotlin.Metadata { *; }
-dontwarn kotlinx.coroutines.**

## OkHttp / Room / Compose / media3 自带 consumer 规则，不再重复写