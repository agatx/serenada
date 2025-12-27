# Product Requirements Document: **Connected**

## Document info
- **Product name:** Connected  
- **Doc owner:** You  
- **Version:** v0.2 (MVP-only)  
- **Date:** 2025-12-26  

---

## 1) Summary

### 1.1 Problem statement
Family members need a **frictionless way to start a video call** without installing apps, creating accounts, or navigating complex UIs. The experience should be instant and link-based.

### 1.2 Proposed solution
**Connected** is a **single-page web application (SPA)** that enables quick **1:1 video calls** using **WebRTC**, accessible directly from modern browsers on desktop and mobile (especially Android).

Core interaction:
- Open the site
- Tap **Start Call**
- Share the generated link
- Another person opens the link and joins the call
- Either party can leave; the creator can end the call for both

---

## 2) Goals and non-goals

### 2.1 Goals
Connected MVP must:
1. Allow a user to **start a video call with one tap**
2. Generate a **unique, shareable URL** for each call
3. Allow another user to **join the same call from a browser**
4. Provide **basic in-call controls** (mute, camera toggle, end call)
5. Work reliably on:
   - Android Chrome (primary)
   - Desktop Chrome / Edge
   - iOS Safari (best effort)

### 2.2 Non-goals (explicitly out of scope)
- User accounts or authentication
- Contact lists
- Group calls (>2 participants)
- Text chat
- Call recording
- Screen sharing
- Call scheduling
- Notifications
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
- Call creator taps **End Call**
- Both participants are disconnected
- Both are returned to the homepage

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

### 4.2 Homepage (MVP)

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
- Clicking Join:
  - Requests camera and microphone permissions
  - Initializes WebRTC connection

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
- The **call creator is the host**
- Host has an **End Call** button
- Ending the call:
  - Disconnects both participants
  - Returns both to the homepage or ended screen

**Acceptance criteria**
- Remote participant is disconnected immediately
- No lingering media capture

### 5.6 Leaving a call
- Non-host participant may leave the call
- Leaving disconnects only that participant
- Host remains in the call

### 5.7 Rejoining behavior
- Rooms are persistent for a limited time
- Reopening the same link:
  - Rejoins the room
  - Starts a new session if no one is connected

**Room retention (MVP)**
- Rooms expire after a fixed inactivity period (e.g., 14 days)

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
  - WebSocket-based signaling
- **Networking**
  - STUN for NAT discovery
  - TURN for fallback relay

### 6.2 Signaling requirements (minimum)
- Join room
- Exchange SDP offer/answer
- Exchange ICE candidates
- Leave room
- End room (host only)

### 6.3 Security and transport
- Application served over HTTPS
- Signaling over WSS
- WebRTC encryption (DTLS-SRTP)

### 6.4 Browser and device support

**Primary**
- Android Chrome (latest)

**Supported**
- Desktop Chrome / Edge
- iOS Safari (best effort)

**Constraints**
- Explicit user gesture required to start media
- Backgrounding the tab may interrupt media

---

## 7) Privacy and safety

### 7.1 Privacy principles
- No accounts
- No tracking or analytics
- No media recording or storage
- Media flows peer-to-peer (or via TURN relay)

### 7.2 Security considerations
- Unpredictable room IDs
- Rate limiting on room creation and joins
- Immediate stop of camera/microphone on leave or end

---

## 8) MVP acceptance checklist
- [ ] SPA loads on mobile and desktop
- [ ] Start Call generates a unique link
- [ ] Second user can join via link
- [ ] Audio and video work reliably
- [ ] Mute and camera toggle work
- [ ] Host can end call for both users
- [ ] Reopening link rejoins the room
- [ ] No non-MVP features present
