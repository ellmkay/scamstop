#!/bin/bash
# ScamStop server runner with auto-restart
cd /root/www/nova/scamkill

while true; do
  echo "[$(date)] Starting ScamStop server..."
  node server.js 2>&1 | tee -a /tmp/scamstop.log
  EXIT_CODE=$?
  echo "[$(date)] Server exited with code $EXIT_CODE. Restarting in 2s..."
  sleep 2
done
