package main

import (
	"html/template"
	"net/http"
)

const deviceCheckHTML = `
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Serenada - Device Diagnostics</title>
    <style>
        :root {
            --bg-color: #0f172a;
            --card-bg: #1e293b;
            --text-primary: #f8fafc;
            --text-secondary: #94a3b8;
            --accent: #38bdf8;
            --success: #22c55e;
            --error: #ef4444;
            --warning: #f59e0b;
        }
        body {
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
            background-color: #0f172a; /* Fallback */
            background-color: var(--bg-color);
            color: #f8fafc; /* Fallback */
            color: var(--text-primary);
            margin: 0;
            padding: 1rem;
            line-height: 1.5;
        }
        .container {
            max-width: 800px;
            margin: 0 auto;
        }
        header {
            margin-bottom: 2rem;
            text-align: center;
        }
        h1 { margin: 0; color: #38bdf8; color: var(--accent); }
        .subtitle { color: #94a3b8; color: var(--text-secondary); }
        
        .card {
            background-color: #1e293b;
            background-color: var(--card-bg);
            border-radius: 0.75rem;
            padding: 1.5rem;
            margin-bottom: 1.5rem;
            box-shadow: 0 4px 6px -1px rgb(0 0 0 / 0.1);
        }
        .card-title {
            font-size: 1.25rem;
            font-weight: 600;
            margin-bottom: 1rem;
            border-bottom: 1px solid #334155;
            padding-bottom: 0.5rem;
            display: flex;
            justify-content: space-between;
            align-items: center;
        }
        
        .item {
            display: flex;
            justify-content: space-between;
            padding: 0.5rem 0;
            border-bottom: 1px solid #33415544;
            word-break: break-all;
        }
        .item:last-child { border-bottom: none; }
        .label { color: var(--text-secondary); margin-right: 1rem; flex-shrink: 0; }
        .value { font-family: monospace; text-align: right; }
        
        .status-badge {
            padding: 0.25rem 0.75rem;
            border-radius: 1rem;
            font-size: 0.875rem;
            font-weight: 600;
        }
        .status-ok { background-color: #05966922; color: #22c55e; color: var(--success); }
        .status-error { background-color: #dc262622; color: #ef4444; color: var(--error); }
        .status-warning { background-color: #d9770622; color: #f59e0b; color: var(--warning); }
        
        .btn {
            background-color: var(--accent);
            color: var(--bg-color);
            border: none;
            padding: 0.625rem 1.25rem;
            border-radius: 0.375rem;
            cursor: pointer;
            font-weight: 600;
            margin-top: 1rem;
            transition: opacity 0.2s;
        }
        .btn:hover { opacity: 0.9; }
        .btn-secondary {
            background-color: #334155;
            color: white;
        }

        .actions {
            display: flex;
            gap: 1rem;
            justify-content: center;
            margin-bottom: 2rem;
        }
        
        #media-list {
            margin-top: 1rem;
            font-size: 0.875rem;
        }

        @media (max-width: 600px) {
            .item { flex-direction: column; align-items: flex-start; }
            .value { text-align: left; margin-top: 0.25rem; }
            .card-title { font-size: 1.1rem; }
        }
    </style>
</head>
<body>
    <div class="container">
        <header>
            <h1>Device Diagnostics</h1>
            <p class="subtitle">Troubleshooting tool for Serenada</p>
        </header>

        <div class="actions">
            <a href="/" class="btn btn-secondary" style="text-decoration: none; display: flex; align-items: center; justify-content: center;">Back to Home</a>
            <button class="btn" id="copy-btn" onclick="copyDiagnostics()">Copy Diagnostic Data</button>
            <button class="btn btn-secondary" onclick="window.location.reload()">Refresh</button>
        </div>

        <div class="card">
            <div class="card-title">Browser Information</div>
            <div class="item">
                <span class="label">Date/Time</span>
                <span class="value" id="datetime">-</span>
            </div>
            <div class="item">
                <span class="label">Client IP</span>
                <span class="value" id="client-ip">{{.ClientIP}}</span>
            </div>
            <div class="item">
                <span class="label">User Agent</span>
                <span class="value" id="ua">-</span>
            </div>
            <div class="item">
                <span class="label">Platform</span>
                <span class="value" id="platform">-</span>
            </div>
            <div class="item">
                <span class="label">Cookies Enabled</span>
                <span class="value" id="cookies">-</span>
            </div>
            <div class="item">
                <span class="label">LocalStorage</span>
                <span class="value" id="storage">-</span>
            </div>
        </div>

        <div class="card">
            <div class="card-title">WebRTC Capabilities</div>
            <div class="item">
                <span class="label">RTCPeerConnection</span>
                <span id="webrtc-support">-</span>
            </div>
            <div class="item">
                <span class="label">getUserMedia</span>
                <span id="getusermedia-support">-</span>
            </div>
            <div class="item">
                <span class="label">Enumerate Devices</span>
                <span id="enumerate-support">-</span>
            </div>
        </div>

        <div class="card">
            <div class="card-title">Audio Processing Capabilities</div>
            <div id="audio-constraints"></div>
            <div class="item">
                <span class="label">Track Capabilities</span>
                <span id="audio-track-status" class="value">Run "Test Permissions"</span>
            </div>
            <div id="audio-track-info"></div>
        </div>

        <div class="card">
            <div class="card-title">
                Media Devices
                <button class="btn" onclick="requestMediaPermissions()" style="margin: 0; padding: 0.25rem 0.5rem; font-size: 0.75rem;">Test Permissions</button>
            </div>
            <div id="media-status-container" class="item">
                <span class="label">Permission Status</span>
                <span id="media-status-value" class="value">Click "Test Permissions"</span>
            </div>
            <div id="media-list"></div>
        </div>

        <div class="card">
            <div class="card-title">Network Connectivity</div>
            <div class="item">
                <span class="label">Server Connection (REST)</span>
                <span id="api-status">-</span>
            </div>
            <div class="item">
                <span class="label">WebSocket Support</span>
                <span id="ws-support">-</span>
            </div>
            <div class="item">
                <span class="label">WebSocket Connection</span>
                <span id="ws-status">-</span>
            </div>
            <div class="item">
                <span class="label">SSE Support</span>
                <span id="sse-support">-</span>
            </div>
            <div class="item">
                <span class="label">SSE Connection</span>
                <span id="sse-status">-</span>
            </div>
        </div>

            <div class="card-title">
                ICE Connectivity (STUN/TURN)
                <div style="display: flex; gap: 0.5rem;">
                    <button class="btn" id="ice-test-btn" onclick="runIceTest()" style="margin: 0; padding: 0.25rem 0.5rem; font-size: 0.75rem;">Run Full Test</button>
                    <button class="btn btn-secondary" id="ice-test-turns-btn" onclick="runIceTest(true)" style="margin: 0; padding: 0.25rem 0.5rem; font-size: 0.75rem; background-color: #6366f1;">Run TURNS Only</button>
                </div>
            </div>
            <div class="item">
                <span class="label">STUN Status</span>
                <span id="stun-status" class="status-badge">NOT TESTED</span>
            </div>
            <div class="item">
                <span class="label">TURN Status</span>
                <span id="turn-status" class="status-badge">NOT TESTED</span>
            </div>
            <div id="ice-log" style="font-size: 0.75rem; color: var(--text-secondary); margin-top: 1rem; max-height: 150px; overflow-y: auto; font-family: monospace;">
                Click "Run ICE Test" to verify STUN/TURN servers.
            </div>
        </div>
    </div>

    <script>
        // Use var for better compatibility with older JS engines
        function updateStatus(id, status, text) {
            var el = document.getElementById(id);
            if (!el) return;
            el.className = 'status-badge status-' + status;
            el.textContent = text || status.toUpperCase();
        }

        function appendItem(container, label, value) {
            if (!container) return;
            var div = document.createElement('div');
            div.className = 'item';
            var labelEl = document.createElement('span');
            labelEl.className = 'label';
            labelEl.textContent = label;
            var valueEl = document.createElement('span');
            valueEl.className = 'value';
            valueEl.textContent = value;
            div.appendChild(labelEl);
            div.appendChild(valueEl);
            container.appendChild(div);
        }

        function formatValue(value) {
            if (value === undefined || value === null) return 'N/A';
            if (typeof value === 'boolean') return value ? 'YES' : 'NO';
            if (Array.isArray(value)) return JSON.stringify(value);
            if (typeof value === 'object') {
                if (value.min !== undefined || value.max !== undefined) {
                    var minVal = value.min !== undefined ? value.min : '';
                    var maxVal = value.max !== undefined ? value.max : '';
                    if (minVal !== '' && maxVal !== '') return minVal + ' - ' + maxVal;
                    if (minVal !== '') return '>= ' + minVal;
                    if (maxVal !== '') return '<= ' + maxVal;
                }
                return JSON.stringify(value);
            }
            return String(value);
        }

        function logIce(msg) {
            var logEl = document.getElementById('ice-log');
            if (!logEl) return;
            var div = document.createElement('div');
            div.textContent = '[' + new Date().toLocaleTimeString() + '] ' + msg;
            logEl.appendChild(div);
            logEl.scrollTop = logEl.scrollHeight;
        }

        // Helper: Make XHR request with callbacks (more reliable than fetch in old browsers)
        function xhrRequest(method, url, headers, onSuccess, onError) {
            try {
                var xhr = new XMLHttpRequest();
                xhr.open(method, url, true);
                
                if (headers) {
                    for (var key in headers) {
                        if (headers.hasOwnProperty(key)) {
                            xhr.setRequestHeader(key, headers[key]);
                        }
                    }
                }
                
                xhr.onreadystatechange = function() {
                    if (xhr.readyState === 4) {
                        logIce('XHR ' + method + ' ' + url + ' -> ' + xhr.status);
                        if (xhr.status >= 200 && xhr.status < 300) {
                            try {
                                var data = JSON.parse(xhr.responseText);
                                onSuccess(data);
                            } catch (e) {
                                onError('Failed to parse response: ' + e.message);
                            }
                        } else {
                            onError('HTTP ' + xhr.status + ': ' + xhr.statusText);
                        }
                    }
                };
                
                xhr.onerror = function() {
                    logIce('XHR network error for ' + url);
                    onError('Network error');
                };
                
                xhr.ontimeout = function() {
                    logIce('XHR timeout for ' + url);
                    onError('Request timed out');
                };
                
                xhr.timeout = 15000; // 15 second timeout
                xhr.send();
            } catch (e) {
                logIce('XHR exception: ' + e.message);
                onError('Exception: ' + e.message);
            }
        }

        function runIceTest(turnsOnly) {
            var btn = document.getElementById('ice-test-btn');
            var btnT = document.getElementById('ice-test-turns-btn');
            if (btn) btn.disabled = true;
            if (btnT) btnT.disabled = true;

            updateStatus('stun-status', 'warning', 'TESTING...');
            updateStatus('turn-status', 'warning', 'TESTING...');
            var logEl = document.getElementById('ice-log');
            if (logEl) logEl.innerHTML = '';
            
            logIce('Starting ICE test (turnsOnly=' + !!turnsOnly + ')...');
            logIce('Requesting diagnostic token...');
            
            function handleError(msg) {
                logIce('Error: ' + msg);
                updateStatus('stun-status', 'error', 'FAILED');
                updateStatus('turn-status', 'error', 'FAILED');
                if (btn) btn.disabled = false;
                if (btnT) btnT.disabled = false;
            }
            
            // Step 1: Get diagnostic token
            xhrRequest('POST', '/api/diagnostic-token', null, 
                function(data) {
                    logIce('Token received: ' + (data.token ? data.token.substring(0, 20) + '...' : 'EMPTY'));
                    if (!data.token) {
                        handleError('No token in response');
                        return;
                    }
                    
                    // Step 2: Get TURN credentials
                    logIce('Fetching TURN credentials...');
                    // Use query parameter instead of header to avoid CORS preflight (OPTIONS) which fails on old WebViews
                    xhrRequest('GET', '/api/turn-credentials?token=' + encodeURIComponent(data.token), null,
                        function(config) {
                            logIce('Credentials received.');
                            if (!config.uris || config.uris.length === 0) {
                                handleError('No ICE servers in response');
                                return;
                            }
                            
                            // Filter for TURNS only if requested
                            if (turnsOnly) {
                                var filtered = [];
                                for (var i = 0; i < config.uris.length; i++) {
                                    if (config.uris[i].indexOf('turns:') === 0) {
                                        filtered.push(config.uris[i]);
                                    }
                                }
                                config.uris = filtered;
                                logIce('Filtered for TURNS only: ' + filtered.length + ' servers');
                            }
                            
                            if (config.uris.length === 0) {
                                handleError('No compatible ICE servers found for this test mode.');
                                return;
                            }
                            
                            logIce('Starting ICE gathering with ' + config.uris.length + ' servers...');
                            testIceConfig(config, turnsOnly, btn, btnT);
                        },
                        handleError
                    );
                },
                handleError
            );
        }

        function testIceConfig(config, turnsOnly, btn, btnT) {
            try {
                logIce('ICE Servers: ' + JSON.stringify(config.uris));
            } catch(e) {
                logIce('ICE Servers: (could not stringify)');
            }
            
            var iceServers = [];
            try {
                for (var i = 0; i < config.uris.length; i++) {
                    var url = config.uris[i];
                    var server = { urls: url };
                    if (url.indexOf('stun:') !== 0) {
                        server.username = config.username;
                        server.credential = config.password;
                    }
                    iceServers.push(server);
                }
            } catch(e) {
                logIce('Error building ICE servers: ' + e.message);
            }
            
            var pc;
            try {
                var RTCPeer = window.RTCPeerConnection || window.webkitRTCPeerConnection || window.mozRTCPeerConnection;
                if (!RTCPeer) {
                    logIce('ERROR: RTCPeerConnection not supported');
                    updateStatus('stun-status', 'error', 'NOT SUPPORTED');
                    updateStatus('turn-status', 'error', 'NOT SUPPORTED');
                    if (btn) btn.disabled = false;
                    if (btnT) btnT.disabled = false;
                    return;
                }
                pc = new RTCPeer({ iceServers: iceServers });
                logIce('RTCPeerConnection created successfully');
            } catch(e) {
                logIce('ERROR creating RTCPeerConnection: ' + e.message);
                updateStatus('stun-status', 'error', 'PC ERROR');
                updateStatus('turn-status', 'error', 'PC ERROR');
                if (btn) btn.disabled = false;
                if (btnT) btnT.disabled = false;
                return;
            }

            var stunFound = false;
            var turnFound = false;
            var finished = false;
            var timeout = setTimeout(function() {
                logIce('ICE Gathering timed out (15s)');
                finish();
            }, 15000);

            var isTurnsTest = turnsOnly;
            
            pc.onicecandidate = function(event) {
                try {
                    if (event.candidate && event.candidate.candidate) {
                        var c = event.candidate.candidate;
                        var parts = c.split(' ');
                        var ip = parts[4] || 'unknown';
                        var port = parts[5] || 'unknown';
                        var type = event.candidate.type || 'unknown';
                        var proto = event.candidate.protocol || 'unknown';
                        var relayProto = (parts[2] || '').toLowerCase();
                        
                        var logMsg = 'Candidate: ' + type + ' (' + proto + ') -> ' + ip + ':' + port;
                        if (type === 'relay') {
                            logMsg += ' [' + relayProto + ']';
                            turnFound = true;
                            updateStatus('turn-status', 'ok', isTurnsTest ? 'TURNS OK' : 'OK');
                        }
                        logIce(logMsg);
                        
                        if (type === 'srflx') {
                            stunFound = true;
                            updateStatus('stun-status', 'ok', 'OK');
                        }
                    } else if (event.candidate === null) {
                        logIce('ICE Gathering complete.');
                        if (isTurnsTest && turnFound) {
                            logIce('NOTE: relay (udp) with TURNS = TLS connection, UDP relay (ideal).');
                        }
                        finish();
                    }
                } catch(e) {
                    logIce('Error processing candidate: ' + e.message);
                }
            };
            
            pc.onicegatheringstatechange = function() {
                logIce('ICE gathering state: ' + pc.iceGatheringState);
            };

            // Trigger ICE gathering
            try {
                pc.createDataChannel('test');
                logIce('DataChannel created');
            } catch(e) {
                logIce('DataChannel error: ' + e.message);
            }
            
            try {
                pc.createOffer(
                    function(offer) {
                        logIce('Offer created');
                        pc.setLocalDescription(
                            offer,
                            function() {
                                logIce('Local description set, gathering candidates...');
                            },
                            function(err) {
                                logIce('setLocalDescription error: ' + (err.message || err));
                                finish();
                            }
                        );
                    },
                    function(err) {
                        logIce('createOffer error: ' + (err.message || err));
                        finish();
                    }
                );
            } catch(e) {
                logIce('Offer exception: ' + e.message);
                finish();
            }

            function finish() {
                if (finished) return;
                finished = true;
                clearTimeout(timeout);
                if (!stunFound) updateStatus('stun-status', 'error', 'FAILED');
                if (!turnFound) updateStatus('turn-status', 'error', 'FAILED');
                
                if (btn) btn.disabled = false;
                if (btnT) btnT.disabled = false;
                
                try { pc.close(); } catch(e) {}
                logIce('Test finished. STUN: ' + (stunFound ? 'OK' : 'FAILED') + ', TURN: ' + (turnFound ? 'OK' : 'FAILED'));
            }
        }

        function checkBrowser() {
            document.getElementById('datetime').textContent = new Date().toISOString();
            document.getElementById('ua').textContent = navigator.userAgent;
            document.getElementById('platform').textContent = navigator.platform;
            document.getElementById('cookies').textContent = navigator.cookieEnabled ? 'YES' : 'NO';
            
            try {
                localStorage.setItem('test', 'test');
                localStorage.removeItem('test');
                document.getElementById('storage').textContent = 'AVAILABLE';
            } catch(e) {
                document.getElementById('storage').textContent = 'UNAVAILABLE';
            }
        }

        function checkWebRTC() {
            var rtc = !!(window.RTCPeerConnection || window.webkitRTCPeerConnection || window.mozRTCPeerConnection);
            updateStatus('webrtc-support', rtc ? 'ok' : 'error');

            var gum = !!(navigator.mediaDevices && navigator.mediaDevices.getUserMedia);
            updateStatus('getusermedia-support', gum ? 'ok' : 'error');

            var enumDev = !!(navigator.mediaDevices && navigator.mediaDevices.enumerateDevices);
            updateStatus('enumerate-support', enumDev ? 'ok' : 'error');
        }

        function checkAudioCapabilities() {
            var container = document.getElementById('audio-constraints');
            if (!container) return;
            container.innerHTML = '';

            if (!navigator.mediaDevices || !navigator.mediaDevices.getSupportedConstraints) {
                appendItem(container, 'getSupportedConstraints', 'NOT SUPPORTED');
                return;
            }

            var supported = navigator.mediaDevices.getSupportedConstraints();
            appendItem(container, 'echoCancellation', formatValue(supported.echoCancellation));
            appendItem(container, 'noiseSuppression', formatValue(supported.noiseSuppression));
            appendItem(container, 'autoGainControl', formatValue(supported.autoGainControl));
            appendItem(container, 'channelCount', formatValue(supported.channelCount));
            appendItem(container, 'sampleRate', formatValue(supported.sampleRate));
            appendItem(container, 'sampleSize', formatValue(supported.sampleSize));
            appendItem(container, 'latency', formatValue(supported.latency));
            appendItem(container, 'contentHint', formatValue(!!(window.MediaStreamTrack && MediaStreamTrack.prototype && 'contentHint' in MediaStreamTrack.prototype)));
            appendItem(container, 'getCapabilities()', formatValue(!!(window.MediaStreamTrack && MediaStreamTrack.prototype && MediaStreamTrack.prototype.getCapabilities)));
        }

        function updateAudioTrackDiagnostics(stream) {
            var statusEl = document.getElementById('audio-track-status');
            var infoEl = document.getElementById('audio-track-info');
            if (!infoEl) return;
            infoEl.innerHTML = '';

            if (!stream) {
                if (statusEl) statusEl.textContent = 'NO STREAM';
                return;
            }
            var audioTrack = stream.getAudioTracks()[0];
            if (!audioTrack) {
                if (statusEl) statusEl.textContent = 'NO AUDIO TRACK';
                return;
            }

            if (statusEl) statusEl.textContent = 'AVAILABLE';
            appendItem(infoEl, 'track.label', audioTrack.label || 'N/A');
            appendItem(infoEl, 'track.enabled', formatValue(audioTrack.enabled));
            appendItem(infoEl, 'track.muted', formatValue(audioTrack.muted));
            if ('contentHint' in audioTrack) {
                appendItem(infoEl, 'track.contentHint', audioTrack.contentHint || '(empty)');
            }

            if (audioTrack.getSettings) {
                var settings = audioTrack.getSettings();
                appendItem(infoEl, 'settings.echoCancellation', formatValue(settings.echoCancellation));
                appendItem(infoEl, 'settings.noiseSuppression', formatValue(settings.noiseSuppression));
                appendItem(infoEl, 'settings.autoGainControl', formatValue(settings.autoGainControl));
                appendItem(infoEl, 'settings.channelCount', formatValue(settings.channelCount));
                appendItem(infoEl, 'settings.sampleRate', formatValue(settings.sampleRate));
                appendItem(infoEl, 'settings.sampleSize', formatValue(settings.sampleSize));
                appendItem(infoEl, 'settings.latency', formatValue(settings.latency));
            } else {
                appendItem(infoEl, 'getSettings()', 'NOT SUPPORTED');
            }

            if (audioTrack.getCapabilities) {
                var caps = audioTrack.getCapabilities();
                appendItem(infoEl, 'caps.echoCancellation', formatValue(caps.echoCancellation));
                appendItem(infoEl, 'caps.noiseSuppression', formatValue(caps.noiseSuppression));
                appendItem(infoEl, 'caps.autoGainControl', formatValue(caps.autoGainControl));
                appendItem(infoEl, 'caps.channelCount', formatValue(caps.channelCount));
                appendItem(infoEl, 'caps.sampleRate', formatValue(caps.sampleRate));
                appendItem(infoEl, 'caps.sampleSize', formatValue(caps.sampleSize));
                appendItem(infoEl, 'caps.latency', formatValue(caps.latency));
            } else {
                appendItem(infoEl, 'getCapabilities()', 'NOT SUPPORTED');
            }
        }

        function checkNetwork() {
            // Check API
            var start = Date.now();
            fetch('/api/turn-credentials', { method: 'OPTIONS' })
                .then(function(res) {
                    var lat = Date.now() - start;
                    updateStatus('api-status', res.ok ? 'ok' : 'warning', res.ok ? 'OK (' + lat + 'ms)' : 'ERROR ' + res.status);
                })
                .catch(function(err) {
                    updateStatus('api-status', 'error', 'FAILED TO REACH SERVER');
                });

            // Check WS
            if (window.WebSocket) {
                updateStatus('ws-support', 'ok');
                checkWebSocket();
            } else {
                updateStatus('ws-support', 'error');
                updateStatus('ws-status', 'error', 'NOT SUPPORTED');
            }

            // Check SSE (SSE + POST)
            if (window.EventSource) {
                updateStatus('sse-support', 'ok');
                checkSSE();
            } else {
                updateStatus('sse-support', 'error');
                updateStatus('sse-status', 'error', 'NOT SUPPORTED');
            }
        }

        function checkWebSocket() {
            var protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
            var wsUrl = protocol + '//' + window.location.host + '/ws';
            var start = Date.now();
            var ws = new WebSocket(wsUrl);
            var finished = false;

            updateStatus('ws-status', 'warning', 'CONNECTING...');

            var timeout = setTimeout(function() {
                if (!finished) {
                    finished = true;
                    updateStatus('ws-status', 'error', 'TIMEOUT');
                    try { ws.close(); } catch(e) {}
                }
            }, 5000);

            ws.onopen = function() {
                if (!finished) {
                    finished = true;
                    clearTimeout(timeout);
                    var lat = Date.now() - start;
                    updateStatus('ws-status', 'ok', 'OK (' + lat + 'ms)');
                    ws.close();
                }
            };

            ws.onerror = function() {
                if (!finished) {
                    finished = true;
                    clearTimeout(timeout);
                    updateStatus('ws-status', 'error', 'FAILED');
                }
            };
        }

        function checkSSE() {
            var start = Date.now();
            var sid = 'S-' + Math.random().toString(16).slice(2, 10) + Math.random().toString(16).slice(2, 10);
            var sseUrl = window.location.protocol + '//' + window.location.host + '/sse?sid=' + encodeURIComponent(sid);
            var es = new EventSource(sseUrl);
            var finished = false;

            updateStatus('sse-status', 'warning', 'CONNECTING...');

            var timeout = setTimeout(function() {
                if (!finished) {
                    finished = true;
                    updateStatus('sse-status', 'error', 'TIMEOUT');
                    try { es.close(); } catch(e) {}
                }
            }, 5000);

            es.onopen = function() {
                if (!finished) {
                    finished = true;
                    clearTimeout(timeout);
                    var lat = Date.now() - start;
                    updateStatus('sse-status', 'ok', 'OK (' + lat + 'ms)');
                    es.close();
                }
            };

            es.onerror = function() {
                if (!finished) {
                    finished = true;
                    clearTimeout(timeout);
                    updateStatus('sse-status', 'error', 'FAILED');
                    try { es.close(); } catch(e) {}
                }
            };
        }

        function requestMediaPermissions() {
            var statusEl = document.getElementById('media-status-value');
            var listEl = document.getElementById('media-list');
            if (!statusEl || !listEl) return;
            
            listEl.innerHTML = 'Requesting...';
            
            if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
                statusEl.textContent = 'NOT SUPPORTED';
                listEl.innerHTML = '';
                return;
            }

            navigator.mediaDevices.getUserMedia({ video: true, audio: true })
                .then(function(stream) {
                    statusEl.textContent = 'GRANTED';
                    statusEl.style.color = '#22c55e';

                    updateAudioTrackDiagnostics(stream);

                    // Stop the stream immediately
                    stream.getTracks().forEach(function(track) { track.stop(); });

                    listDevices();
                })
                .catch(function(err) {
                    statusEl.textContent = 'DENIED / ERROR: ' + err.name;
                    statusEl.style.color = '#ef4444';
                    listEl.innerHTML = '';
                });
        }

        function listDevices() {
            var listEl = document.getElementById('media-list');
            if (!listEl) return;
            
            if (!navigator.mediaDevices || !navigator.mediaDevices.enumerateDevices) {
                return;
            }

            navigator.mediaDevices.enumerateDevices()
                .then(function(devices) {
                    listEl.innerHTML = '';
                    devices.forEach(function(device) {
                        var div = document.createElement('div');
                        div.className = 'item';
                        div.innerHTML = 
                            "<span class=\"label\">" + device.kind + "</span>" +
                            "<span class=\"value\">" + (device.label || "Unknown Device (" + device.deviceId.substring(0,8) + "...)") + "</span>";
                        listEl.appendChild(div);
                    });
                })
                .catch(function(err) {
                    listEl.innerHTML = 'Error listing devices: ' + err.message;
                });
        }

        function copyDiagnostics() {
            var btn = document.getElementById('copy-btn');
            var data = "SERENADA DIAGNOSTICS DATA\n";
            data += "===========================\n";
            data += "URL: " + window.location.href + "\n";
            data += "Generated: " + new Date().toString() + "\n\n";

            var cards = document.querySelectorAll('.card');
            cards.forEach(function(card) {
                var title = card.querySelector('.card-title');
                if (!title) return;
                data += "## " + title.innerText.split('\n')[0].trim() + "\n";
                
                var items = card.querySelectorAll('.item');
                items.forEach(function(item) {
                    var label = item.querySelector('.label');
                    var value = item.querySelector('.value') || item.querySelector('span:not(.label)');
                    if (label && value) {
                        data += label.innerText.trim() + ": " + value.innerText.trim() + "\n";
                    }
                });
                data += "\n";
            });
            
            // Add ICE log
            var iceLog = document.getElementById('ice-log');
            if (iceLog) {
                data += "## ICE Connectivity Log\n";
                data += iceLog.innerText.trim() + "\n";
            }

            function fallbackCopy(text) {
                var textArea = document.createElement("textarea");
                textArea.value = text;
                textArea.style.position = "fixed";
                textArea.style.left = "-9999px";
                textArea.style.top = "0";
                document.body.appendChild(textArea);
                textArea.focus();
                textArea.select();
                try {
                    document.execCommand('copy');
                    showSuccess();
                } catch (err) {
                    alert('Could not copy data: ' + err);
                }
                document.body.removeChild(textArea);
            }

            function showSuccess() {
                var originalText = btn.textContent;
                btn.textContent = 'COPIED!';
                btn.style.backgroundColor = '#22c55e';
                setTimeout(function() {
                    btn.textContent = originalText;
                    btn.style.backgroundColor = '';
                }, 2000);
            }

            if (navigator.clipboard && navigator.clipboard.writeText) {
                navigator.clipboard.writeText(data).then(showSuccess, function() {
                    fallbackCopy(data);
                });
            } else {
                fallbackCopy(data);
            }
        }

        // Run core checks on load
        checkBrowser();
        checkWebRTC();
        checkAudioCapabilities();
        checkNetwork();
        listDevices();
    </script>
</body>
</html>
`

func handleDeviceCheck(w http.ResponseWriter, r *http.Request) {
	tmpl, err := template.New("deviceCheck").Parse(deviceCheckHTML)
	if err != nil {
		http.Error(w, "Error loading template", http.StatusInternalServerError)
		return
	}
	clientIP := getClientIP(r)
	if clientIP == "" {
		clientIP = "Unknown"
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	tmpl.Execute(w, struct {
		ClientIP string
	}{
		ClientIP: clientIP,
	})
}
