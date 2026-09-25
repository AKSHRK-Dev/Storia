#!/bin/sh
# Storia Relay: hands terrain work from Storia servers to Storia Workers.
cd "$(dirname "$0")" || exit 1
exec java -Xmx256M -jar storia-relay.jar relay.properties
