@rem Gradle startup script for Windows
@if "%DEBUG%"=="" @echo off
set DIRNAME=%~dp0
set CLASSPATH=%DIRNAME%gradle\wrapper\gradle-wrapper.jar
java -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*