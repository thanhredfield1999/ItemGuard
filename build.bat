@echo off
cd /d "%~dp0"
echo Building ItemGuard...
call mvn clean package -DskipTests
echo.
echo Done! JAR file is at target\ItemGuard-1.0.0.jar
pause
