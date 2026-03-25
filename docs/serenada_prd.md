# Product Requirements Document: **Serenada**

## Document info
- **Product name:** Serenada  
- **Doc owner:** You  
- **Version:** v0.2
- **Date:** 2025-12-26  

---

## 1) Summary

### 1.1 Problem statement
Family members need a **frictionless way to start a video call** without installing apps, creating accounts, or navigating complex UIs. The experience should be instant and link-based.

### 1.2 Proposed solution
**Serenada** is a **link-based calling product** that enables quick **1:1 and small-group video calls** using **WebRTC**, with web and native clients available across desktop and mobile platforms.

Core interaction:
- Open the site
- Tap **Start Call**
- Share the generated link
- Another person opens the link and joins the call
- Either party can leave; the remaining participant stays in the room (waiting)

---

## 2) Goals and non-goals

### 2.1 Goals
Serenada must:
1. Allow a user to **start a video call with one tap**
2. Generate a **unique, shareable URL** for each call
3. Allow another user to **join the same call from a browser or native client**
4. Provide **basic in-call controls** (mute, camera toggle, end call)
5. Work reliably on:
   - Android Chrome (primary)
   - Desktop Chrome / Edge
   - iOS Safari (best effort)
6. Provide **optional, encrypted push notifications** for join events (opt-in)

### 2.2 Non-goals (explicitly out of scope)
- User accounts or authentication
- Contact lists
- Group calls larger than the current small-room limit (more than 4 participants)
- Text chat
- Call recording
- Screen sharing
- Call scheduling
- Non-push notifications (e.g., SMS/email)
- Analytics or usage tracking

---

## 3) Target users and use cases

### 3.1 Target users
- Non-technical family members
- Users who prefer browser-based solutions
- Users on mobile devices (Android-first)

### 3.2 Core use cases

#### Use case 1: Start a call
- User opens the homepage
- Taps **Start Call**
- App generates a unique call ID
- User is taken directly to the call page

#### Use case 2: Join a call via link
- User opens a shared call link
- Grants camera and microphone permissions
- Joins the existing call

#### Use case 3: End a call
- User taps **End Call**
- Only that participant is disconnected
- The remaining participant stays in the room (waiting)

#### Use case 4: Rejoin a call
- Opening the same link again rejoins the same call room
- If no one is connected, a new session starts in that room

---

## 4) User experience requirements

### 4.1 Application routes
- `/`  
  Homepage with a single primary action
- `/call/:roomId`  
  Call page (pre-join → in-call → ended)

### 4.2 Homepage

**UI**
- One large primary button: **Start Call**
- Minimal copy
- Mobile-first layout

**Behavior**
- Clicking Start Call:
  - Generates a unique room ID
  - Navigates to `/call/:roomId`

**Acceptance criteria**
- Button is easily tappable on mobile
- Navigation is near-instant
- No additional setup required

### 4.3 Call page UX states

#### State A: Pre-join
Required due to browser permission and autoplay constraints.

**UI**
- “Join Call” button
- Display of call link with “Copy link” button
- Optional local camera preview

**Behavior**
- On load, the app attempts to start local media for a preview (browser may prompt or block).
- Clicking Join:
  - Sends the signaling `join`
  - Initializes WebRTC negotiation

#### State B: In-call

**UI**
- Remote video (primary)
- Local video preview (corner)
- Bottom control bar with:
  - Mute / unmute microphone
  - Camera on / off
  - End call (prominent)

**Behavior**
- Audio and video stream between two participants
- Controls reflect current media state

#### State C: Call ended

**UI**
- Message: “Call ended”
- Button: **Back to home**

**Behavior**
- User can start a new call
- Reopening the same link allows rejoining the room

---

## 5) Functional requirements

### 5.1 Room creation and identification
- Each call is identified by a unique `roomId`
- Room IDs must:
  - Be URL-safe
  - Be cryptographically random
  - Contain no personal data

**Acceptance criteria**
- Room IDs are not guessable
- No collisions under normal usage

### 5.2 Link sharing
- Call page must provide:
  - “Copy link” button (Clipboard API with fallback)
- Shared link opens the same call room

**Acceptance criteria**
- Copy works on Android and desktop browsers

### 5.3 Joining a call
- Loading `/call/:roomId` allows the user to join that room
- User must explicitly tap “Join Call”
- App handles permission prompts gracefully

**Acceptance criteria**
- Second user can join from a different device
- Clear messaging if permissions are denied

### 5.4 Call capacity
- **Exactly two participants per room**
- If a third participant attempts to join:
  - Display “This call is full”
  - Do not join the call

### 5.5 End call behavior
- The in-call **End Call** button disconnects the local participant only.
- The remaining participant stays in the room (waiting).

**Acceptance criteria**
- Local participant disconnects immediately

### 5.6 Leaving a call
- Non-host participant may leave the call
- Leaving disconnects only that participant
- Host remains in the call

### 5.7 Rejoining behavior
- Room sessions are ephemeral; room IDs remain valid for rejoin
- Reopening the same link:
  - Rejoins the room
  - Starts a new session if no one is connected

**Room retention**
- Room IDs are stateless HMAC tokens and do not "expire" on the server.
- The room link remains valid indefinitely for rejoining.
- Server-side room session state exists only while participants are connected.

### 5.8 Push notifications (optional)
- Users can opt in per room to receive join notifications.
- Notifications may include an encrypted snapshot preview.
- The server stores only encrypted snapshot payloads and delivery metadata.

---

## 6) Technical requirements

### 6.1 Architecture
- **Frontend**
  - Single-page application
  - Uses native WebRTC APIs:
    - `getUserMedia`
    - `RTCPeerConnection`
- **Backend**
  - Lightweight signaling service
  - WebSocket signaling with SSE fallback
- **Networking**
  - STUN for NAT discovery
  - TURN for fallback relay

### 6.2 Signaling requirements (minimum)
- Join room
- Exchange SDP offer/answer
- Exchange ICE candidates
- Leave room
- End room (host only, server-supported; not exposed in UI)
- WebSocket transport with SSE fallback when WS is unavailable

### 6.3 Security and transport
- Application served over HTTPS
- Signaling over WSS or SSE (HTTPS)
- WebRTC encryption (DTLS-SRTP)

### 6.4 Browser and device support

**Primary**
- Android Chrome (latest)

**Supported**
- Desktop Chrome / Edge
- iOS Safari (best effort)

**Constraints**
- Browsers may require a user gesture to start media; pre-join preview may be blocked until interaction
- Backgrounding the tab may interrupt media

---

## 7) Privacy and safety

### 7.1 Privacy principles
- No accounts
- No tracking or analytics
- No media recording or storage
- Media flows peer-to-peer (or via TURN relay)
- Push notification snapshots are encrypted client-side; server stores only ciphertext

### 7.2 Security considerations
- Unpredictable room IDs
- Rate limiting on room ID creation and signaling connections
- Stop camera/microphone on explicit leave; local media may remain active while waiting after remote leave/end

---

## 8) Acceptance checklist
- [ ] SPA loads on mobile and desktop
- [ ] Start Call generates a unique link
- [ ] Second user can join via link
- [ ] Audio and video work reliably
- [ ] Mute and camera toggle work
- [ ] End Call disconnects the local user only
- [ ] Reopening link rejoins the room
- [ ] No out-of-scope features present
- [ ] Optional encrypted push notifications work when enabled
