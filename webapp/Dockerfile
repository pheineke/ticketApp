# Playwright's image ships Chromium + its system deps (multi-arch, incl. arm64).
FROM mcr.microsoft.com/playwright/python:v1.48.0-jammy

ENV DEBIAN_FRONTEND=noninteractive \
    TZ=Europe/Berlin \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PORT=8080 \
    DISPLAY=:99

# Virtual display + VNC server + browser-based VNC viewer for the remote login.
RUN apt-get update \
 && apt-get install -y --no-install-recommends xvfb x11vnc novnc websockify \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app.py remote_browser.py vrn_client.py security.py remember.py start.sh ./
COPY templates ./templates
COPY static ./static
RUN chmod +x start.sh

EXPOSE 8080 6080

CMD ["./start.sh"]
