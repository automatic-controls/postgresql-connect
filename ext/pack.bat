if /i "%*" EQU "--help" (
  echo PACK              Packages all relevant files into newly created .addon and .jar archives.
  exit /b 0
)
if "%*" NEQ "" (
  echo Unexpected parameter.
  exit /b 1
)
echo Packing...
rmdir /Q /S "%classes%" >nul 2>nul
for /D %%i in ("%trackingClasses%\*") do robocopy /E "%%~fi" "%classes%" >nul 2>nul
robocopy /E "%src%" "%classes%" /XF "*.java" >nul 2>nul
copy /Y "%workspace%\LICENSE" "%root%\LICENSE" >nul 2>nul
robocopy /MIR /MOVE "%lib%" "%workspace%\lib-sources" *-sources.jar >nul 2>nul
"%JDKBin%\jar.exe" -c -M -f "%addonFile%" -C "%root%" .
if %ERRORLEVEL% NEQ 0 (
  robocopy /E /MOVE "%workspace%\lib-sources" "%lib%" >nul 2>nul
  rmdir /Q /S "%workspace%\lib-sources" >nul 2>nul
  echo Packing unsuccessful.
  exit /b 1
)
del /F "%classes%\aces\webctrl\postgresql\resources\config.dat" >nul 2>nul
del /F "%classes%\aces\webctrl\postgresql\resources\pgsslkey.pfx" >nul 2>nul
del /F "%classes%\aces\webctrl\postgresql\resources\pgsslroot.cer" >nul 2>nul
"%JDKBin%\jar.exe" -c -M -f "%workspace%\!name!-blank.addon" -C "%root%" .
if %ERRORLEVEL% EQU 0 (
  robocopy /E /MOVE "%workspace%\lib-sources" "%lib%" >nul 2>nul
  rmdir /Q /S "%workspace%\lib-sources" >nul 2>nul
  echo Packing successful.
  exit /b 0
) else (
  robocopy /E /MOVE "%workspace%\lib-sources" "%lib%" >nul 2>nul
  rmdir /Q /S "%workspace%\lib-sources" >nul 2>nul
  echo Packing unsuccessful.
  exit /b 1
)