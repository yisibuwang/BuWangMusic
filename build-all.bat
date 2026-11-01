@echo off
rem 构建本仓库维护的所有 Minecraft 版本，并把 jar 收进 dist\
rem 1.8.9（legacy）是上游代码、没有一起听歌改造，这里不构建也不发布
setlocal
cd /d "%~dp0"
if not exist dist mkdir dist
rem 只清掉旧的 jar，保留 dist 里的发布说明、校验文件等
del /q dist\*.jar 2>nul
rem 只收集当前版本号的产物（旧版本的构建结果留在各自的 build\libs）
for /f "tokens=2 delims==" %%v in ('findstr /b "version=" version.properties') do set "VER=%%v"

rem 1.20.1 / 1.21.1 / 1.21.11（Fabric / Forge / NeoForge）需要 JDK 21
if not defined DANGO_JDK21 set "DANGO_JDK21=C:\Program Files\Java\jdk-21"
echo === 1.20.1 / 1.21.1 / 1.21.11 (Fabric / Forge / NeoForge) ===
pushd modern
call gradlew.bat "-Dorg.gradle.java.home=%DANGO_JDK21%" build
if errorlevel 1 (
    popd
    echo Modern build failed
    exit /b 1
)
popd
rem 文件名里的 "+" 在 for 里会被当成连接符，所以用 xcopy 而不是 for %%f
for /d %%d in (modern\versions\*) do xcopy /y /q "%%d\build\libs\BuWangMusic-%VER%+*.jar" dist\ >nul

rem 26.x（Fabric / NeoForge）需要 JDK 25
if not defined DANGO_JDK25 set "DANGO_JDK25=C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot"
echo.
echo === 26.1.2 / 26.2 / 26.3 (Fabric / NeoForge) ===
pushd modern26
call gradlew.bat "-Dorg.gradle.java.home=%DANGO_JDK25%" build
if errorlevel 1 (
    popd
    echo 26.x build failed
    exit /b 1
)
popd
for /d %%d in (modern26\versions\*) do xcopy /y /q "%%d\build\libs\BuWangMusic-%VER%+*.jar" dist\ >nul

echo.
echo Done. Jars in dist\:
dir /b dist
endlocal
