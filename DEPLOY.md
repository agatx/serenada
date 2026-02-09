# Deployment Guide: self-hosted on Hetzner

This guide covers deploying the Serenada app on a single linux VPS using Docker Compose. The project is structured to support both local development and production deployment.

## Prerequisites

1.  **Linux VPS**: Ubuntu 20.04+ recommended.
2.  **Domain Name**: Pointed to your VPS IP (e.g., `serenada.app`).
3.  **Docker & Docker Compose**: Installed on the VPS.

## Local Development

To run the application locally for development:

```bash
docker compose up -d --build
```

The app will be accessible at `http://localhost`. It uses `nginx/nginx.dev.conf` and `coturn/turnserver.dev.conf`.

## Production Deployment

### 1. Configuration

#### Environment Variables
The repository includes an [.env.example](.env.example) template. Create your production environment file:

```bash
cp .env.example .env.production
```

Edit `.env.production` and set the following required variables:
- `VPS_HOST`: SSH connection string (e.g., `root@1.2.3.4`)
- `DOMAIN`: Your app domain (e.g., `serenada.app`)
- `REMOTE_DIR`: Deployment path on VPS (e.g., `/opt/serenada`)
- `IPV4`: VPS Public IPv4 address
- `IPV6`: VPS Public IPv6 address
- `TURN_SECRET`: Secure secret for TURN (generate with `openssl rand -hex 32`)
- `TURN_TOKEN_SECRET` *(optional, recommended)*: Separate secret for TURN tokens (falls back to `TURN_SECRET` if unset)
- `ROOM_ID_SECRET`: Secure secret for Room IDs (generate with `openssl rand -hex 32`)

#### Configuration Templates
Serenada uses templates to generate final configuration files during deployment. This ensures that domain names and IP addresses are consistently applied across all services.
- [nginx.prod.conf.template](nginx/nginx.prod.conf.template)
- [turnserver.prod.conf.template](coturn/turnserver.prod.conf.template)

### 2. Firewall

Ensure the following ports are open on your VPS firewall (e.g., UFW or Hetzner Cloud Firewall):
-   **80/tcp** (HTTP)
-   **443/tcp** (HTTPS)
-   **3478/udp & tcp** (STUN/TURN Signaling)
-   **5349/tcp** (STUN/TURN over TLS)
-   **49152-65535/udp** (WebRTC Media Range)

### 3. HTTPS (SSL) Setup

Serenada expects Let's Encrypt certificates to be located at `/etc/letsencrypt/live/${DOMAIN}/`.

1.  Stop Nginx if running: `docker stop serenada-nginx`
2.  Install Certbot and generate certificates:
    ```bash
    sudo apt install certbot
    sudo certbot certonly --standalone -d your-domain.com
    ```
3.  The certificates are mounted into the containers via `docker-compose.prod.yml`.

### 4. Deploying the Stack

A convenience script is provided for deployment. It uses `envsubst` to process templates, builds the frontend, syncs files via `rsync`, and restarts services via SSH.

> [!WARNING]
> **Manual Restarts**: If you need to restart services manually on the server, you **MUST** include both compose files.
> Do NOT run `docker compose up -d`.
> Run `docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d` instead.
> Omitting the production file will cause the app to revert to development defaults (bridge network) and break connectivity.

From the project root on your local machine:
```bash
./deploy.sh
```

`deploy.sh` also publishes the Android APK to `https://<your-domain>/tools/serenada.apk` by copying `client-android/app/build/outputs/apk/release/serenada.apk` into the built web assets (`client/dist/tools/serenada.apk`) before sync. If the release APK is missing, the script will attempt `./gradlew :app:assembleRelease` in `client-android/`. If that build fails, deployment continues and prints a warning instead of failing.

### 5. Android App Links (assetlinks.json)

Android deep links require `/.well-known/assetlinks.json` to be served from your domain. The file lives at:

```
client/public/.well-known/assetlinks.json
```

Update it with your **release** signing certificate SHA-256 fingerprint before deployment.

### 6. Advanced: Legacy Redirects
If you need to support redirects from old domains (e.g. `connected.dowhile.fun`), you can create a template at `nginx/nginx.legacy.conf.template`. The deployment script will automatically generate an `extra` configuration for Nginx if this file exists.

### 7. Android camera source modes
The Android in-call camera source cycle (`selfie -> world -> composite`) is fully client-side and does not require server, TURN, or deployment configuration changes.

## Verification

1.  Navigate to `https://your-domain.com`.
2.  Verify camera/microphone permissions are requested.
3.  Check logs if issues arise: `docker compose logs -f`.
