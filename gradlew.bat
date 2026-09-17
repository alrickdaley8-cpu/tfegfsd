@rem
@rem Doomsday Nukes - Gradle bootstrap launcher (Windows).
@rem Functional stand-in for the official wrapper: uses gradle-wrapper.jar when
@rem present, else `gradle` on PATH, else the distribution bootstrapped into
@rem .gradle-dist by the POSIX `gradlew` script.
@if "%DEBUG%"=="" @echo off
setlocal

set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
set APP_HOME=%DIRNAME%
set PROPS=%APP_HOME%gradle\wrapper\gradle-wrapper.properties
set WRAPPER_JAR=%APP_HOME%gradle\wrapper\gradle-wrapper.jar

if exist "%WRAPPER_JAR%" (
	java -Xmx64m -Xms64m -Dorg.gradle.appname=gradlew -classpath "%WRAPPER_JAR%" org.gradle.wrapper.GradleWrapperMain %*
	goto end
)

where gradle >nul 2>nul
if %ERRORLEVEL%==0 (
	gradle %*
	goto end
)

if exist "%APP_HOME%.gradle-dist" (
	for /d %%D in ("%APP_HOME%.gradle-dist\gradle-*") do (
		if exist "%%D\bin\gradle.bat" (
			"%%D\bin\gradle.bat" %*
			goto end
		)
	)
)

echo ERROR: no Gradle found. Install Gradle 8.8+ (or run `gradle wrapper --gradle-version 8.8`)
echo        and re-run. See README.md -^> "Bootstrap".
exit /b 1

:end
endlocal
