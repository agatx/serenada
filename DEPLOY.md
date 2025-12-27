# Deployment Guide: self-hosted on Hetzner

This guide covers deploying the Connected app on a single linux VPS using Docker Compose. The project is structured to support both local development and production deployment.

## Prerequisites

1.  **Linux VPS**: Ubuntu 20.04+ recommended.
2.  **Domain Name**: Pointed to your VPS IP (e.g., `connected.dowhile.fun`).
3.  **Docker & Docker Compose**: Installed on the VPS.

## Local Development

To run the application locally for development:

```bash
docker-compose up -d --build
```

The app will be accessible at `http://localhost`. It uses `nginx/nginx.dev.conf` and `coturn/turnserver.dev.conf`.

## Production Deployment

### 1. Configuration

#### Environment Variables
Create a `.env` file in the project root on the VPS.

```bash
TURN_HOST=connected.dowhile.fun
TURN_SECRET=your_secure_random_secret
```

#### Nginx & Coturn
- Production Nginx config: [nginx.prod.conf](file:///Users/alexeygavrilov/Developer/src/connected/nginx/nginx.prod.conf)
- Production Coturn config: [turnserver.prod.conf](file:///Users/alexeygavrilov/Developer/src/connected/coturn/turnserver.prod.conf)

### 2. HTTPS (SSL) Setup

SSL is mandatory for WebRTC (camera/mic access).

1.  Stop Nginx if running: `docker stop connected-nginx`
2.  Install Certbot and generate certificates:
    ```bash
    sudo apt install certbot
    sudo certbot certonly --standalone -d connected.dowhile.fun
    ```
3.  The certificates are mounted into the Nginx container via `docker-compose.prod.yml`.

### 3. Deploying the Stack

To deploy in production, use both the base and production compose files:

```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build
```

## Verification

1.  Navigate to `https://connected.dowhile.fun`.
2.  Verify camera/microphone permissions are requested.
3.  Check logs if issues arise: `docker compose logs -f`.
