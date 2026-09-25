#!/bin/sh
# Stolia Relay: hands terrain work from Stolia servers to Stolia Workers.
cd "$(dirname "$0")" || exit 1
exec java -Xmx256M -jar stolia-relay.jar relay.properties
