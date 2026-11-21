@echo off
setlocal EnableExtensions DisableDelayedExpansion

rem Agentic Debugger for Eclipse IDE - Local developer deployment.
rem Edit these two paths for your machine.
rem Author: Ayan Dutta
set "ECLIPSE_DIR=C:\All_Softwares\eclipse"
set "PROJECT_DIR=C:\All_GitHub_Desktop\agentic-debugger-for-eclipse"

rem Installation settings; normally these need no changes.
set "FEATURE_IU=org.learningfromexperience.eclipse.agentdebug.feature.feature.group"
set "START_ECLIPSE=YES"

set "ECLIPSEC=%ECLIPSE_DIR%\eclipsec.exe"
set "ECLIPSE_EXE=%ECLIPSE_DIR%\eclipse.exe"
set "REPOSITORY_DIR=%PROJECT_DIR%\eclipse-update-site\target\repository"
set "LOG_DIR=%PROJECT_DIR%\deploy-logs"
set "LOG="
set "STEP_LOG="
set "WORKING_DIR_PUSHED=NO"

if not exist "%PROJECT_DIR%\pom.xml" (
  echo [ERROR] Maven project not found: "%PROJECT_DIR%"
  exit /b 1
)
if not exist "%ECLIPSEC%" (
  echo [ERROR] Eclipse p2 launcher not found: "%ECLIPSEC%"
  exit /b 1
)
where mvn >nul 2>&1
if errorlevel 1 (
  echo [ERROR] Maven is not available on PATH.
  exit /b 1
)
if not exist "%LOG_DIR%" mkdir "%LOG_DIR%" >nul 2>&1
if not exist "%LOG_DIR%" (
  echo [ERROR] Cannot create log directory: "%LOG_DIR%"
  exit /b 1
)
set "LOG=%LOG_DIR%\deploy-%RANDOM%-%RANDOM%.log"
set "STEP_LOG=%LOG_DIR%\deploy-%RANDOM%-%RANDOM%.step.tmp"

>"%LOG%" echo Agentic Debugger for Eclipse IDE - local deployment
>>"%LOG%" echo Target Eclipse: %ECLIPSE_DIR%
>>"%LOG%" echo Repository: %PROJECT_DIR%
>>"%LOG%" echo Feature IU: %FEATURE_IU%
echo.
echo =============================================
echo Agentic Debugger - Local Eclipse Deployment
echo =============================================
echo Eclipse : %ECLIPSE_DIR%
echo Project : %PROJECT_DIR%
echo Log     : %LOG%
echo.
echo [IMPORTANT] Close the target Eclipse IDE before continuing.
choice /C YN /M "Is the target Eclipse IDE fully closed"
if errorlevel 2 goto :cancelled

pushd "%PROJECT_DIR%"
if errorlevel 1 (
  echo [ERROR] Cannot enter repository directory.
  goto :failed
)
set "WORKING_DIR_PUSHED=YES"

call :status "[1/5] Building current source (mvn clean package)..."
call mvn clean package >"%STEP_LOG%" 2>&1
if errorlevel 1 (
  call :appendStep "MAVEN BUILD - FAILED"
  call :showFailure "Maven build failed" "MAVEN"
  goto :failed
)
call :appendStep "MAVEN BUILD - PASSED"
findstr /C:"BUILD SUCCESS" "%STEP_LOG%" 2>nul
call :status "[OK] Maven build completed."

if not exist "%REPOSITORY_DIR%\content.jar" if not exist "%REPOSITORY_DIR%\content.xml" if not exist "%REPOSITORY_DIR%\content.xml.xz" (
  echo [ERROR] Generated p2 repository metadata not found:
  echo         "%REPOSITORY_DIR%"
  >>"%LOG%" echo [ERROR] p2 repository metadata missing from "%REPOSITORY_DIR%"
  goto :failed
)
set "REPOSITORY_URI=file:/%REPOSITORY_DIR:\=/%"
>>"%LOG%" echo Repository URI: %REPOSITORY_URI%

call :status "[2/5] Checking installed Eclipse features..."
"%ECLIPSEC%" -nosplash -application org.eclipse.equinox.p2.director -destination "%ECLIPSE_DIR%" -listInstalledRoots >"%STEP_LOG%" 2>&1
if errorlevel 1 (
  call :appendStep "QUERY INSTALLED ROOTS - FAILED"
  call :showFailure "Cannot inspect installed Eclipse features" "P2"
  goto :failed
)
call :appendStep "QUERY INSTALLED ROOTS - PASSED"
findstr /L /I /C:"%FEATURE_IU%" "%STEP_LOG%" >nul
if errorlevel 1 (
  set "INSTALLED=NO"
  call :status "[OK] Agentic Debugger is not currently installed."
) else (
  set "INSTALLED=YES"
  call :status "[OK] Existing Agentic Debugger installation detected."
)

if /I "%INSTALLED%"=="YES" (
  call :status "[3/5] Uninstalling existing Agentic Debugger feature..."
  "%ECLIPSEC%" -nosplash -application org.eclipse.equinox.p2.director -destination "%ECLIPSE_DIR%" -uninstallIU "%FEATURE_IU%" >"%STEP_LOG%" 2>&1
  if errorlevel 1 (
    call :appendStep "P2 UNINSTALL - FAILED"
    call :showFailure "p2 uninstall failed. Installation was NOT attempted" "P2"
    goto :failed
  )
  call :appendStep "P2 UNINSTALL - PASSED"
  call :status "[OK] Existing feature uninstalled."
) else (
  call :status "[3/5] No existing feature; uninstall skipped."
  >>"%LOG%" echo Uninstall skipped: feature absent.
)

call :status "[4/5] Installing freshly built feature..."
"%ECLIPSEC%" -nosplash -application org.eclipse.equinox.p2.director -destination "%ECLIPSE_DIR%" -repository "%REPOSITORY_URI%" -installIU "%FEATURE_IU%" >"%STEP_LOG%" 2>&1
if errorlevel 1 (
  call :appendStep "P2 INSTALL - FAILED"
  call :showFailure "p2 installation failed; Eclipse may need recovery" "P2"
  goto :failed
)
call :appendStep "P2 INSTALL - PASSED"
call :status "[OK] New feature installed."

call :status "[5/5] Verifying installed feature..."
"%ECLIPSEC%" -nosplash -application org.eclipse.equinox.p2.director -destination "%ECLIPSE_DIR%" -listInstalledRoots >"%STEP_LOG%" 2>&1
if errorlevel 1 (
  call :appendStep "P2 VERIFICATION - FAILED"
  call :showFailure "Cannot query installed features after installation" "P2"
  goto :failed
)
call :appendStep "P2 VERIFICATION - PASSED"
findstr /L /I /C:"%FEATURE_IU%" "%STEP_LOG%" >nul
if errorlevel 1 (
  call :showFailure "Agentic Debugger feature not found after installation" "P2"
  goto :failed
)
findstr /L /I /C:"%FEATURE_IU%" "%STEP_LOG%" 2>nul
call :status "[OK] Installed feature verified."

call :status "[SUCCESS] Deployment completed."
if exist "%STEP_LOG%" del /q "%STEP_LOG%" >nul 2>&1
popd
if /I "%START_ECLIPSE%"=="YES" (
  if exist "%ECLIPSE_EXE%" (
    echo [INFO] Starting Eclipse with -clean...
    >>"%LOG%" echo Starting Eclipse with -clean.
    start "" "%ECLIPSE_EXE%" -clean
  ) else (
    echo [WARNING] Eclipse launcher not found; start Eclipse manually.
  )
)
echo [NEXT] Wait until Eclipse has fully started; import examples\phase-one-debugging as Maven project.
echo [NEXT] Phase 1 Java bridge tests: scripts\windows\verify-phase1-eclipse.bat
echo [NEXT] Existing Claude installed regression (separate): mvn verify -Pinstalled-e2e
echo [LOG] %LOG%
exit /b 0

:status
 echo %~1
 >>"%LOG%" echo %~1
 exit /b 0

:appendStep
 >>"%LOG%" echo.
 >>"%LOG%" echo ===== %~1 =====
 type "%STEP_LOG%" >>"%LOG%"
 exit /b 0

:showFailure
 echo.
 echo [ERROR] %~1
 >>"%LOG%" echo [ERROR] %~1
 echo [DETAILS] Relevant output from the failed command:
 if /I "%~2"=="MAVEN" (
   findstr /L /C:"[ERROR]" /C:"BUILD FAILURE" "%STEP_LOG%" 2>nul
 ) else (
   type "%STEP_LOG%"
 )
 echo [LOG] Full command output: "%LOG%"
 exit /b 0

:cancelled
 echo [CANCELLED] Nothing was changed.
 exit /b 2

:failed
 if /I "%WORKING_DIR_PUSHED%"=="YES" popd
 if exist "%STEP_LOG%" del /q "%STEP_LOG%" >nul 2>&1
 echo.
 echo [FAILED] Deployment stopped. Review the error above.
 echo [LOG] %LOG%
 echo [NOTE] If uninstall succeeded but install failed, repair the installation before restarting Eclipse.
 exit /b 1
