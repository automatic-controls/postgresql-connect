if /i "%*" EQU "--help" (
  echo SIGN              Signs the .addon archive.
  exit /b 0
)
if "%*" NEQ "" (
  echo Unexpected parameter.
  exit /b 1
)
if exist "%addonFile%" (
  echo Signing...
  "%JDKBin%\jarsigner.exe" -keystore "%keystore%" -storepass "!pass!" "%addonFile%" %alias% >nul
  if !ERRORLEVEL! EQU 0 (
    "%JDKBin%\jarsigner.exe" -keystore "%keystore%" -storepass "!pass!" "%workspace%\!name!-blank.addon" %alias% >nul
    if !ERRORLEVEL! EQU 0 (
      echo Signing successful.
      exit /b 0
    ) else (
      echo Signing unsuccessful.
      exit /b 1
    )
  ) else (
    echo Signing unsuccessful.
    exit /b 1
  )
) else (
  echo Cannot sign because !name!.addon does not exist.
  exit /b 1
)