# Media Walkie: Comprehensive Architecture & System Guide

## 1. Executive Summary

**Media Walkie** is a hybrid, multi-platform communication system engineered to provide low-latency, frequency-based voice transmission across both localized offline mesh networks and global internet infrastructure. The platform features strict administrative controls, ensuring secure, isolated channels and verifiable user identities.

This document serves as a comprehensive technical manual detailing the system's architecture, network routing logic, backend schema, and security protocols.

---

## 2. System Architecture Overview

The system is divided into two primary environments:
1. **The Client (Android)**: A native Kotlin Android application utilizing Jetpack Compose for UI, Google Nearby Connections API for offline mesh networking, and Socket.io/WebRTC for internet routing.
2. **The Server (Node.js)**: A centralized signaling and management server built with Express.js and PostgreSQL (Render Postgres), handling user authentication, channel authorization, and audio packet relay.

### Hybrid Routing Protocol
The core innovation of Media Walkie is its **Hybrid Routing Protocol**. The client acts as a dual-node, simultaneously maintaining connections to the global server (via cellular/Wi-Fi) and local peers (via Bluetooth/Wi-Fi Direct). Audio packets are intelligently routed based on network availability, with the mesh network serving as a zero-data fallback when the internet is unavailable.

---

## 3. The Client: Android App Architecture

The Android app is built using modern Android development practices, specifically Kotlin and Jetpack Compose.

### 3.1 UI/UX Layer (`MainScreen.kt`)
The user interface is designed with a "Tactical OLED" aesthetic, prioritizing high visibility in low-light environments.
- **Frequency Card**: Centralized display of the current active channel, allowing users to scroll through authorized frequencies.
- **Status Indicators**: Real-time visualization of `ONL` (Online/Internet peers) and `MESH` (Local offline peers) counts.
- **SYNC Functionality**: A dedicated refresh protocol allowing users to fetch updated channel authorizations from the server without restarting the application.

### 3.2 Routing Engine (`RoutingManager.kt`)
The `RoutingManager` is the central brain of the client app. It is responsible for:
1. Orchestrating the `AudioEngine`.
2. Determining packet destinations (Mesh vs. Socket.io).
3. Handling incoming packets and avoiding echo loops.

**Logic Flow**:
When a user presses the PTT (Push-To-Talk) button, the `AudioEngine` begins recording and emits byte arrays. The `RoutingManager` intercepts these arrays and:
- Broadcasts them to all connected nearby devices via `MeshManager`.
- Simultaneously emits them to the Node.js server via `Socket.io` if an active internet connection exists.

### 3.3 Offline Mesh Layer (`MeshManager.kt`)
Powered by the `Google Nearby Connections API`, this layer establishes a resilient P2P network.
- **Strategy**: Utilizes `Strategy.P2P_CLUSTER` to support an M-to-N network topology where devices can form dynamic, multi-hop clusters.
- **Discovery & Advertising**: Devices continuously advertise their presence using their unique `userId`. Upon discovery, an automatic connection request is initiated.
- **Deadlock Prevention**: Simplified discovery logic ensures that symmetrical connection attempts resolve organically without infinite waiting states.

### 3.4 Audio Subsystem (`AudioEngine.kt`)
Responsible for raw audio capture and playback.
- **Configuration**: `16,000 Hz` sample rate, `MONO` channel, `PCM_16BIT` encoding.
- **Buffering**: Optimized byte buffering to reduce latency.
- **Packet Serialization**: Prepends the transmitting user's name to the audio byte array encoded strictly in `UTF-8` to prevent character corruption across different OS environments.

---

## 4. The Server: Node.js Backend

The backend is hosted on the Render platform and serves as the global signaling hub and administrative database.

### 4.1 Server Stack
- **Framework**: Express.js
- **Real-time Engine**: Socket.io
- **Database**: PostgreSQL (Render Postgres via `pg`)

### 4.2 WebSockets (`server.js`)
The server uses Socket.io "rooms" to isolate communication by frequency.
- **`join_frequency`**: When a client requests to join a channel, the server verifies their identity against the PostgreSQL database. If the user's `assignedGroups` relationship contains the requested frequency, they are added to the Socket room.
- **`audio_stream`**: The server receives raw byte arrays from a client and immediately broadcasts them to all other clients in the identical frequency room using `socket.to(frequency).emit()`.

### 4.3 Database Schema (PostgreSQL)
The system utilizes relational tables with foreign keys and automatic initialization on startup:

**`users`**
```sql
CREATE TABLE users (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name VARCHAR(255) NOT NULL,
  pin VARCHAR(255) NOT NULL,
  email VARCHAR(255),
  phone VARCHAR(255),
  device_id VARCHAR(255),
  is_verified BOOLEAN DEFAULT FALSE,
  is_admin BOOLEAN DEFAULT FALSE,
  created_at TIMESTAMPTZ DEFAULT NOW()
);
```

**`groups` (Channels)**
```sql
CREATE TABLE groups (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name VARCHAR(255) UNIQUE NOT NULL,
  frequency VARCHAR(50) NOT NULL,
  pin VARCHAR(50) DEFAULT '',
  range_description VARCHAR(255) DEFAULT 'Standard Range (100m)',
  created_at TIMESTAMPTZ DEFAULT NOW()
);
```

**`user_groups` (Channel Assignments)**
```sql
CREATE TABLE user_groups (
  user_id UUID REFERENCES users(id) ON DELETE CASCADE,
  group_id UUID REFERENCES groups(id) ON DELETE CASCADE,
  PRIMARY KEY (user_id, group_id)
);
```

---

## 5. Security & Administrative Controls

Media Walkie implements a strict, Zero-Trust network policy for internet communications.

### 5.1 Verification Protocol
By default, all new user registrations are flagged as `isVerified: false`. Unverified users are completely isolated; they cannot join any channels, receive audio, or transmit audio. A Superadmin must manually verify the user via the Web Dashboard.

### 5.2 Strict Channel Isolation
A user can only interact with a frequency if a Superadmin has explicitly granted them access. 
- The server checks the `assignedGroups` array on every connection attempt.
- The default public channel (`104.5`) has been explicitly locked down. No anonymous internet users can join. It requires explicit Admin assignment.

### 5.3 Channel PIN Protection
Channels can be secured with a PIN code.
- When an Admin creates a channel, they can optionally define a PIN.
- The Android app parses the channel metadata. If a PIN exists, the app renders a secure lock screen overlay preventing access to the channel until the correct PIN is provided.

### 5.4 The Superadmin Dashboard (`admin.html`)
A secure web interface (`/admin.html`) accessible only via the Superadmin credentials.
Features:
- **Checkbox-based Assignment**: Granular, intuitive assignment of users to specific channels without affecting unrelated group assignments.
- **Real-time Updates**: PIN modifications, channel deletions, and user verifications apply instantly to the live database.

---

## 6. API Endpoint Reference

### Authentication
- `POST /api/auth/register`: Registers a new user. Default `isVerified = false`.
- `POST /api/auth/verify`: Authenticates user and returns user credentials and `_id`.

### Channel Management
- `GET /api/groups`: Returns a list of channels. If Admin, returns all channels. If regular user, returns only `assignedGroups`.
- `POST /api/groups`: Creates a new channel. (Admin Only)
- `PUT /api/groups/:id`: Updates channel details, including PIN. (Admin Only)
- `DELETE /api/groups/:id`: Deletes a channel and cascades removal from all users' `assignedGroups`. (Admin Only)

### User Management
- `GET /api/admin/users`: Returns all users for dashboard management. (Admin Only)
- `PUT /api/admin/users/:id`: Updates user verification status and channel assignments. (Admin Only)

---

## 7. Deployment & Operations

### Build Pipeline
1. **Backend**: Any push to the `main` branch triggers an automated build and deployment on Render. The server executes `npm install` and `node server.js`.
2. **Frontend (Android)**: The Android application is compiled locally using Gradle (`./gradlew assembleDebug` or `assembleRelease`) into an APK file for distribution.

### Troubleshooting Guide
- **Garbage Name Bug**: If user names appear as strange characters during transmission, ensure the Android `AudioEngine` is explicitly decoding the prefix using `Charsets.UTF_8`.
- **Mesh Connection Hangs**: If devices fail to connect offline, ensure `MeshManager` is not utilizing over-engineered lexicographical tie-breakers during the `onEndpointFound` callback.
- **UI State Stale**: If channels do not appear after admin assignment, instruct the user to tap the `SYNC` refresh icon on the Android `MainScreen`.

---

## 8. Future Roadmap

1. **End-to-End Encryption (E2EE)**: Implement AES-256 encryption on the audio payload before transmission to the Node.js server, ensuring the server acts only as a blind relay.
2. **Push Notifications**: Integrate Firebase Cloud Messaging (FCM) to wake devices when a transmission occurs on an inactive channel.
3. **Background Services**: Transition the `RoutingManager` to a Foreground Service in Android to allow walkie-talkie functionality while the phone screen is off.
4. **iOS Client**: Port the Jetpack Compose UI to Kotlin Multiplatform (KMP) or Swift to support Apple devices via WebRTC.

---
*Generated by Antigravity AI on 2026-05-03.*
