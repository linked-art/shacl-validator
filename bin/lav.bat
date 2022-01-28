@echo off
set LAV=lav-0.1.jar
set LAV_HOME=%~dp0
set LAV_CONF=%~dp0\..\conf\

java -Dlog4j.configuration="file:%LAV_CONF%log4j.properties" -jar "%LAV_HOME%/%LAV%" %*
