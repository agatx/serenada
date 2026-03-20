const STYLES_ID = 'serenada-callflow-styles';

const CALL_FLOW_CSS = `
@keyframes serenada-spin {
  to { transform: rotate(360deg); }
}

.serenada-callflow {
  --serenada-accent: #3b82f6;
  position: fixed;
  inset: 0;
  overflow: hidden;
  background: #000;
  color: #fff;
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
}

.serenada-callflow video {
  display: block;
}

.serenada-callflow .call-container {
  position: absolute;
  inset: 0;
  overflow: hidden;
  background: #000;
}

.serenada-callflow .video-remote-container.primary {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1;
}

.serenada-callflow .video-remote {
  width: 100%;
  height: 100%;
  object-fit: cover;
  transition: object-fit 0.3s ease;
  background: #000;
}

.serenada-callflow .video-local-container.pip,
.serenada-callflow .video-remote-container.pip {
  position: absolute;
  right: 20px;
  bottom: 100px;
  width: min(160px, 30vw);
  max-width: 30vw;
  max-height: 30vh;
  aspect-ratio: 9 / 16;
  overflow: hidden;
  background: #333;
  border: 2px solid rgba(255, 255, 255, 0.1);
  border-radius: 12px;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.5);
  cursor: pointer;
  z-index: 10;
  transform-origin: bottom right;
  transition: all 0.25s ease;
}

.serenada-callflow .video-local-container.primary {
  position: absolute;
  inset: 0;
  z-index: 1;
  background: #000;
}

@media (min-width: 768px) {
  .serenada-callflow .video-local-container.pip,
  .serenada-callflow .video-remote-container.pip {
    right: 32px;
    bottom: 100px;
    width: min(240px, 30vw);
    aspect-ratio: 4 / 3;
  }
}

.serenada-callflow .video-local {
  width: 100%;
  height: 100%;
  object-fit: cover;
  transition: object-fit 0.3s ease;
  background: #000;
}

.serenada-callflow .video-local.mirrored {
  transform: scaleX(-1);
}

.serenada-callflow.multi-party-call .video-stage {
  position: absolute;
  inset: 0;
  padding: 16px 16px 184px;
  background:
    radial-gradient(circle at top, rgba(47, 129, 247, 0.14), transparent 32%),
    radial-gradient(circle at bottom, rgba(255, 255, 255, 0.05), transparent 30%),
    #050608;
  z-index: 1;
}

.serenada-callflow.multi-party-call .video-stage-viewport {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
}

.serenada-callflow.multi-party-call .video-stage-rows {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  max-width: 100%;
  max-height: 100%;
}

.serenada-callflow.multi-party-call .video-stage-row {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  max-width: 100%;
}

.serenada-callflow.multi-party-call .video-stage-tile {
  position: relative;
  overflow: hidden;
  flex: 0 0 auto;
  border-radius: 10px;
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.03), transparent 30%),
    #111;
  border: 1px solid rgba(255, 255, 255, 0.08);
  box-shadow: 0 18px 38px rgba(0, 0, 0, 0.35);
}

.serenada-callflow.multi-party-call .video-stage-remote {
  width: 100%;
  height: 100%;
  object-fit: contain;
  background: #060708;
}

.serenada-callflow .video-grid-label {
  position: absolute;
  bottom: 12px;
  left: 12px;
  padding: 4px 10px;
  border-radius: 999px;
  background: rgba(0, 0, 0, 0.56);
  color: #fff;
  font-size: 12px;
  backdrop-filter: blur(6px);
}

.serenada-callflow .video-stage-placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 100%;
  background: #111;
  color: rgba(255, 255, 255, 0.6);
}

.serenada-callflow .video-stage-pin-indicator {
  position: absolute;
  top: 8px;
  left: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 4px;
  border-radius: 6px;
  background: rgba(0, 0, 0, 0.56);
  color: #fff;
  backdrop-filter: blur(6px);
}

.serenada-callflow.multi-party-call .video-local-container-stage {
  right: 16px;
  bottom: 108px;
  width: min(132px, 26vw);
  max-width: 26vw;
  max-height: 24vh;
  border-width: 1px;
  border-radius: 8px;
  background: rgba(17, 17, 17, 0.95);
  box-shadow: 0 14px 32px rgba(0, 0, 0, 0.42);
}

@media (min-width: 768px) {
  .serenada-callflow.multi-party-call .video-stage {
    padding: 24px 28px 136px;
  }

  .serenada-callflow.multi-party-call .video-stage-tile {
    border-radius: 12px;
  }

  .serenada-callflow.multi-party-call .video-local-container-stage {
    right: 32px;
    bottom: 112px;
    width: min(220px, 22vw);
    max-width: 22vw;
    max-height: 22vh;
    border-radius: 10px;
  }
}

.serenada-callflow .controls-bar {
  position: absolute;
  left: 50%;
  bottom: 20px;
  display: flex;
  gap: 1rem;
  padding: 1rem 2rem;
  border-radius: 50px;
  background: rgba(22, 27, 34, 0.8);
  backdrop-filter: blur(10px);
  z-index: 20;
  opacity: 1;
  transform: translateX(-50%) translateY(0);
  transition: opacity 0.25s ease, transform 0.25s ease;
}

.serenada-callflow.controls-hidden .controls-bar {
  opacity: 0;
  transform: translateX(-50%) translateY(20px);
  pointer-events: none;
}

.serenada-callflow.controls-hidden .video-local-container.pip,
.serenada-callflow.controls-hidden .video-remote-container.pip {
  bottom: 20px;
  box-shadow: 0 2px 10px rgba(0, 0, 0, 0.4);
}

.serenada-callflow .btn-control {
  width: 50px;
  height: 50px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  border-radius: 50%;
  background: #161b22;
  color: #fff;
  cursor: pointer;
  transition: all 0.2s ease;
}

.serenada-callflow .btn-control:hover {
  background: #30363d;
}

.serenada-callflow .btn-control:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.serenada-callflow .btn-control:disabled:hover {
  background: #161b22;
}

.serenada-callflow .btn-control.active {
  background: #fff;
  color: #000;
}

.serenada-callflow .btn-control.active-screen-share {
  background: #8b1b1b;
}

.serenada-callflow .btn-control.active-screen-share:hover {
  background: #da3633;
}

.serenada-callflow .btn-leave {
  background: #551111;
}

.serenada-callflow .btn-leave:hover {
  background: #da3633;
}

.serenada-callflow .btn-zoom {
  position: absolute;
  top: 20px;
  right: 20px;
  z-index: 21;
  width: 44px;
  height: 44px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 12px;
  background: rgba(22, 27, 34, 0.6);
  color: #fff;
  cursor: pointer;
  backdrop-filter: blur(8px);
  transition: all 0.2s ease;
}

.serenada-callflow .btn-zoom:hover {
  transform: scale(1.05);
  border-color: var(--serenada-accent);
  color: var(--serenada-accent);
}

.serenada-callflow .btn-zoom:active {
  transform: scale(0.95);
}

.serenada-callflow.controls-hidden .btn-zoom {
  opacity: 0;
  pointer-events: none;
}

.serenada-callflow .debug-toggle-zone {
  position: absolute;
  top: 0;
  left: 0;
  width: 72px;
  height: 72px;
  z-index: 41;
  pointer-events: auto;
  touch-action: manipulation;
  user-select: none;
}

.serenada-callflow .waiting-message {
  position: absolute;
  top: 50%;
  left: 50%;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 1rem;
  transform: translate(-50%, -50%);
  text-align: center;
  color: #8b949e;
  z-index: 15;
}

.serenada-callflow .qr-code-container {
  width: 200px;
  height: 200px;
  padding: 8px;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 6px 18px rgba(0, 0, 0, 0.35);
}

.serenada-callflow .btn-small {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 0.5rem 1rem;
  border: 1px solid #8b949e;
  border-radius: 20px;
  background: transparent;
  color: #8b949e;
  cursor: pointer;
  transition: border-color 0.2s ease, color 0.2s ease;
}

.serenada-callflow .btn-small:hover {
  border-color: var(--serenada-accent);
  color: var(--serenada-accent);
}

.serenada-callflow .waiting-actions {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0.75rem;
}
`;

export function ensureCallFlowStyles(): void {
    if (typeof document === 'undefined') return;
    if (document.getElementById(STYLES_ID)) return;

    const style = document.createElement('style');
    style.id = STYLES_ID;
    style.textContent = CALL_FLOW_CSS;
    document.head.appendChild(style);
}
