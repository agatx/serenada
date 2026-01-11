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

        function logIce(msg) {
            var logEl = document.getElementById('ice-log');
            if (!logEl) return;
            var div = document.createElement('div');
            div.textContent = '[' + new Date().toLocaleTimeString() + '] ' + msg;
            logEl.appendChild(div);
            logEl.scrollTop = logEl.scrollHeight;
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
            
            logIce('Requesting diagnostic token...');
            
            fetch('/api/diagnostic-token', { method: 'POST' })
                .then(function(res) {
                    if (!res.ok) throw new Error('Failed to fetch diagnostic token: ' + res.status);
                    return res.json();
                })
                .then(function(data) {
                    var token = data.token;
                    logIce('Token received. Fetching TURN credentials...');
                    return fetch('/api/turn-credentials', {
                        headers: { 'X-Turn-Token': token }
                    });
                })
                .then(function(res) {
                    if (!res.ok) throw new Error('Failed to fetch credentials: ' + res.status);
                    return res.json();
                })
                .then(function(config) {
                    if (turnsOnly) {
                        config.uris = config.uris.filter(function(u) { return u.startsWith('turns:'); });
                        logIce('Filtered for TURNS only.');
                    }
                    if (config.uris.length === 0) {
                        throw new Error('No compatible ICE servers found for this test mode.');
                    }
                    logIce('Credentials received. Starting ICE gathering...');
                    testIceConfig(config, turnsOnly);
                })
                .catch(function(err) {
                    logIce('Error: ' + err.message);
                    updateStatus('stun-status', 'error', 'FAILED');
                    updateStatus('turn-status', 'error', 'FAILED');
                    if (btn) btn.disabled = false;
                    if (btnT) btnT.disabled = false;
                });
        }

        function testIceConfig(config, turnsOnly) {
            logIce('ICE Servers: ' + JSON.stringify(config.uris));
            
            var iceServers = [];
            if (config.uris) {
                config.uris.forEach(function(url) {
                    var server = { urls: url };
                    if (url.indexOf('stun:') !== 0) {
                        server.username = config.username;
                        server.credential = config.password;
                    }
                    iceServers.push(server);
                });
            }
            
            var pc = new RTCPeerConnection({ iceServers: iceServers });

            var stunFound = false;
            var turnFound = false;
            var timeout = setTimeout(function() {
                logIce('ICE Gathering timed out (10s)');
                finish();
            }, 10000);

            var isTurnsTest = turnsOnly;
            pc.onicecandidate = function(event) {
                if (event.candidate) {
                    var c = event.candidate.candidate;
                    var parts = c.split(' ');
                    var ip = parts[4];
                    var port = parts[5];
                    var type = event.candidate.type;
                    var proto = event.candidate.protocol;
                    var relayProto = parts[2].toLowerCase(); // e.g. 'udp', 'tcp'
                    
                    var logMsg = 'Found candidate: ' + type + ' (' + proto + ') -> ' + ip + ':' + port;
                    if (type === 'relay') {
                        logMsg += ' [relay-proto: ' + relayProto + ']';
                        turnFound = true;
                        updateStatus('turn-status', 'ok', isTurnsTest ? 'TURNS SUCCESS' : 'SUCCESS');
                    }
                    logIce(logMsg);
                    
                    if (event.candidate.type === 'srflx') {
                        stunFound = true;
                        updateStatus('stun-status', 'ok', 'SUCCESS');
                    }
                } else {
                    logIce('ICE Gathering complete.');
                    if (isTurnsTest && turnFound) {
                        logIce('NOTE: "relay (udp)" with TURNS means you connected via TLS, but the server is relaying media via UDP (ideal).');
                    }
                    finish();
                }
            };

            // Trigger ICE gathering
            pc.createDataChannel('test');
            pc.createOffer().then(function(offer) {
                return pc.setLocalDescription(offer);
            }).catch(function(err) {
                logIce('Offer error: ' + err.message);
                finish();
            });

            function finish() {
                clearTimeout(timeout);
                if (!stunFound) updateStatus('stun-status', 'error', 'FAILED');
                if (!turnFound) updateStatus('turn-status', 'error', 'FAILED');
                
                var btn = document.getElementById('ice-test-btn');
                var btnT = document.getElementById('ice-test-turns-btn');
                if (btn) btn.disabled = false;
                if (btnT) btnT.disabled = false;
                
                // Cleanup
                try { pc.close(); } catch(e) {}
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
