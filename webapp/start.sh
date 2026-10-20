#!/bin/sh
# Virtual display for the remote WebView login, exposed through noVNC on :6080.
set -e
case "${PUBLIC_MODE:-1}" in 0|false|no|off) LAN=1 ;; *) LAN=0 ;; esac
if [ "$LAN" = 1 ]; then
Xvfb :99 -screen 0 412x860x24 -nolisten tcp &
sleep 1
if [ -n "$VNC_PASSWORD" ]; then
  x11vnc -storepasswd "$VNC_PASSWORD" /tmp/vncpass >/dev/null
  VNC_AUTH="-rfbauth /tmp/vncpass"
else
  VNC_AUTH="-nopw"
fi
x11vnc -display :99 -forever -shared -localhost -rfbport 5900 $VNC_AUTH -quiet &
websockify --web /usr/share/novnc 6080 localhost:5900 >/dev/null 2>&1 &
fi

# Single worker keeps the in-memory session store (and the one browser) consistent.
exec gunicorn -b 0.0.0.0:8080 -w 1 --threads 4 --timeout 120 app:app
