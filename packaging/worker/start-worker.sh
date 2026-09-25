#!/bin/sh
# Stolia Worker: computes terrain for other Stolia servers. Opens no player port.
cd "$(dirname "$0")" || exit 1
if ! grep -qs "eula=true" eula.txt; then
  echo "Read the Minecraft EULA (https://aka.ms/MinecraftEULA) and, if you agree, put eula=true in eula.txt."
  exit 1
fi
exec java -Xms1G -Xmx"${WORKER_MEMORY:-4G}" -Dstolia.worker=true -jar stolia.jar nogui
