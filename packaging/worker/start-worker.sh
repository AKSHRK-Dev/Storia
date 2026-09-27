#!/bin/sh
# Storia Worker: runs part of a Storia Cluster's world. Players reach it through Storia Proxy.
cd "$(dirname "$0")" || exit 1
if ! grep -qs "eula=true" eula.txt; then
  echo "Read the Minecraft EULA (https://aka.ms/MinecraftEULA) and, if you agree, put eula=true in eula.txt."
  exit 1
fi
exec java -Xms2G -Xmx"${WORKER_MEMORY:-6G}" -jar storia.jar nogui
