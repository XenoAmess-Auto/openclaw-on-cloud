# OpenClaw on Cloud

[![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/XenoAmess-Auto/openclaw-on-cloud)

[English](README_EN.md) | [中文](README.md)

## Android App Download

GitHub Actions automatically builds Android APKs with **support for overlay installation** (no need to uninstall old versions).

**Download APK:**
- Visit [Actions page](https://github.com/XenoAmess-Auto/openclaw-on-cloud/actions/workflows/android-build.yml)
- Click the latest run → Artifacts → Download APK

**Overlay Installation Notes:**
- New APK can directly overlay the old version, data and settings are preserved
- Version number auto-increments with each build

## Android Local Build

For local builds:

```bash
cd frontend
./scripts/build-android.sh  # One-click build
# Or: pnpm mobile:build
```

See [frontend/android/README.md](frontend/android/README.md) for details

## Project Structure

```
openclaw-on-cloud/
├── backend/          # Java 25 + Spring Boot + MongoDB
├── frontend/         # Vue 3 + TypeScript + pnpm
├── .github/          # CI/CD configuration
├── docker-compose.yml
└── README.md
```

## Quick Start

### Local Development

**Start MongoDB:**
```bash
docker run -d -p 27017:27017 --name ooc-mongo mongo:7
```

**Start Backend:**
```bash
cd backend
./mvnw spring-boot:run
```

**Start Frontend:**
```bash
cd frontend
pnpm install
pnpm dev
```

### Docker Deployment

```bash
docker compose up -d
```

## Core Features

- **User System**: Registration/Login/Permission management
- **Chat Rooms**: Create/Join/Leave
- **OpenClaw Integration**:
  - Auto-trigger in private chats
  - @openclaw trigger in group chats
  - Automatic session save/restore/summarize
- **Mobile Adaptation**: Responsive design

## API Documentation

| Endpoint | Method | Description |
|----------|--------|-------------|
| /api/auth/login | POST | Login |
| /api/auth/register | POST | Register |
| /api/chat-rooms | GET | Get chat room list |
| /api/chat-rooms | POST | Create chat room |
| /ws/chat | WebSocket | Chat connection |

## Environment Variables

Copy `.env.example` to `.env` and configure:

- `JWT_SECRET`: JWT secret key
- `MONGODB_URI`: MongoDB connection string
- `OPENCLAW_URL`: OpenClaw gateway URL
- `OPENCLAW_API_KEY`: OpenClaw API key

## License

Apache License 2.0
