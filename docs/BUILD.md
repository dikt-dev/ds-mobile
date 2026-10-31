# 没有 Android Studio 怎么出包

这台机器上只装了 JDK 17 + Android SDK（`D:\Android\Sdk`），没有 Android Studio，
所以命令行出包。2026-10-31 实测通过（`assembleRelease` 6 分钟）。

## 一次性准备

1. **Gradle 8.9**（工程用 8.9，比系统自带的 9.8 低，AGP 8.7.3 不支持 9.x）

   ```powershell
   # 官方源在本机有吊销检查问题，curl 要加 --ssl-no-revoke；用腾讯镜像更快
   curl.exe -L --ssl-no-revoke -o D:\Android\gradle-8.9-bin.zip https://mirrors.cloud.tencent.com/gradle/gradle-8.9-bin.zip
   Expand-Archive D:\Android\gradle-8.9-bin.zip -DestinationPath D:\Android\
   ```

2. **国内 Maven 镜像**（临时 init 脚本，不改工程文件；Google/Maven Central 直连经常超时）

   `D:\Android\init-mirror.gradle`：

   ```groovy
   def mirrors = [
       'https://maven.aliyun.com/repository/google',
       'https://maven.aliyun.com/repository/public',
       'https://maven.aliyun.com/repository/gradle-plugin',
   ]
   settingsEvaluated { s ->
       s.pluginManagement.repositories { mirrors.each { u -> maven { url u } }; gradlePluginPortal() }
       s.dependencyResolutionManagement.repositories { mirrors.each { u -> maven { url u } } }
   }
   ```

   注意：不要再往 `allprojects { repositories { ... } }` 里加——工程开了
   `prefer settings repositories`，会直接构建失败。

## 出包

```powershell
# 只出 release：私人自用，release 用 debug 签名，R8 开，运行比 debug 顺
D:\Android\gradle-8.9\bin\gradle.bat -I D:\Android\init-mirror.gradle :app:assembleRelease

# 产物
app\build\outputs\apk\release\app-release.apk
```

## 装机

```powershell
$env:Path="D:\Android\Sdk\platform-tools;$env:Path"
adb install -r app\build\outputs\apk\release\app-release.apk
```

## 已知坑

- Kotlin 扩展属性不能用全限定名调用：`androidx.lifecycle.lifecycleScope.launch {}` 不行，
  必须 `import androidx.lifecycle.lifecycleScope` 后写 `lifecycleScope.launch {}`。
- Gradle wrapper（`gradlew`）会去下载 8.9 分发包，本机 Java 的 PKIX 校验过不去；
  直接用解压好的 `gradle.bat`，绕开 wrapper。
- `stripReleaseDebugSymbols` 会对 `libmlkit_google_ocr_pipeline.so` 报警告，可忽略。
