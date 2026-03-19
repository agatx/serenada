/**
 * @serenada/react-ui — React bindings and pre-built call UI components.
 */
export { SERENADA_CORE_VERSION as SERENADA_UI_VERSION } from '@serenada/core';

// React hooks
export { useSerenadaSession } from './hooks/useSerenadaSession.js';
export type { UseSerenadaSessionOptions, UseSerenadaSessionResult } from './hooks/useSerenadaSession.js';
export { useCallState } from './hooks/useCallState.js';

// Call Flow
export { SerenadaCallFlow } from './SerenadaCallFlow.js';
export type { SerenadaCallFlowConfig, SerenadaCallFlowTheme, SerenadaString, CallFlowProps } from './types.js';
export { serenadaDefaultStrings, resolveString } from './types.js';

export { StatusOverlay } from './components/StatusOverlay.js';
export type { StatusOverlayProps } from './components/StatusOverlay.js';
export { DebugPanel } from './components/DebugPanel.js';
export type { DebugPanelProps, DebugPanelSection, DebugPanelMetric, DebugStatus } from './components/DebugPanel.js';

// Permissions
export { SerenadaPermissions } from './SerenadaPermissions.js';
