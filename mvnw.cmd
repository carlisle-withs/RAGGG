@REM ----------------------------------------------------------------------------
@REM Maven Wrapper startup batch script
@REM ----------------------------------------------------------------------------

@echo off
setlocal

set MAVEN_PROJECTBASEDIR=%~dp0
set WRAPPER_JAR="%MAVEN_PROJECTBASEDIR%.mvn\wrapper\maven-wrapper.jar"
set WRAPPER_URL="https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper/3.2.0/maven-wrapper-3.2.0.jar"

if exist %WRAPPER_JAR% (
    goto runMaven
) else (
    echo Downloading Maven Wrapper...
    powershell -Command "(New-Object Net.WebClient).DownloadFile('%WRAPPER_URL%', '%WRAPPER_JAR%')"
)

:runMaven
"%JAVA_HOME%\bin\java" ^
    -jar %WRAPPER_JAR% ^
    -Dmaven.multiModuleProjectDirectory="%MAVEN_PROJECTBASEDIR%" ^
    %*
