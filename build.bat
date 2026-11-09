@echo off
setlocal
java -version 2>&1 | findstr /R /C:"version \"25" >nul
if errorlevel 1 (
  echo ERROR: JDK 25 is required for Paper 26.2 builds. Install/select Java 25 and retry.
  exit /b 1
)
where mvn >nul 2>nul
if errorlevel 1 (
  echo ERROR: Maven 3.9+ is required. Install Maven and retry.
  exit /b 1
)
mvn -U clean package
if errorlevel 1 exit /b 1
echo Build complete: target\ClasherCity-1.0.0.jar
endlocal
