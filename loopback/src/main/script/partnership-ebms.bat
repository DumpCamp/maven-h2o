@echo off

set LIB_PATH=./lib
set ARGS=%*

set CLASSPATH=./conf

for %%j in (%LIB_PATH%/*.jar) do (call  set-classpath.bat %LIB_PATH%/%%j )

@echo on

"%JAVA_HOME%\bin\java" -classpath "%CLASSPATH%" hk.hku.cecid.corvus.partnership.EbmsPartnership %1 %2
