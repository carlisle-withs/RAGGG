@echo off
REM 启动 RAGGG 后端
cd /d D:\Workspace\RAGGG
REM 先杀掉旧的 Java 进程
for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":8081 LISTENING"') do taskkill /F /PID %%a 2>nul
ping -n 2 localhost >nul
mvn spring-boot:run -Dspring-boot.run.jvmArguments="-Dserver.port=8081"
