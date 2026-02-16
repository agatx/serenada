package main

import (
	"log"
	"net/http"
	"time"

	"github.com/gorilla/websocket"
)

const (
	wsWriteWait   = 10 * time.Second
	wsPongWait    = 30 * time.Second
	wsPingPeriod  = 12 * time.Second
	wsGracePeriod = 6 * time.Second
)

var wsUpgrader = websocket.Upgrader{
	ReadBufferSize:  1024,
	WriteBufferSize: 1024,
	CheckOrigin: func(r *http.Request) bool {
		return isOriginAllowed(r)
	},
}

type wsClient struct {
	client *Client
	conn   *websocket.Conn
}

func serveWs(hub *Hub, w http.ResponseWriter, r *http.Request) {
	conn, err := wsUpgrader.Upgrade(w, r, nil)
	if err != nil {
		log.Println(err)
		return
	}

	ip := getClientIP(r)
	sid := generateID("S-")
	client := &Client{hub: hub, send: make(chan []byte, 256), sid: sid, ip: ip, transport: TransportWS}

	hub.registerClient(client)

	ws := &wsClient{client: client, conn: conn}
	go ws.writePump()
	go ws.readPump()
}

func (c *wsClient) readPump() {
	defer func() {
		c.client.hub.handleDisconnectWS(c.client)
		c.conn.Close()
	}()
	c.conn.SetReadLimit(maxMessageSize)
	c.conn.SetReadDeadline(time.Now().Add(wsPongWait))
	c.conn.SetPongHandler(func(string) error { c.conn.SetReadDeadline(time.Now().Add(wsPongWait)); return nil })

	for {
		_, message, err := c.conn.ReadMessage()
		if err != nil {
			if websocket.IsUnexpectedCloseError(err, websocket.CloseGoingAway, websocket.CloseAbnormalClosure) {
				log.Printf("error: %v", err)
			}
			break
		}
		c.client.hub.handleMessage(c.client, message)
	}
}

func (h *Hub) handleDisconnectWS(c *Client) {
	go h.delayDisconnectWS(c)
}

func (h *Hub) delayDisconnectWS(c *Client) {
	time.Sleep(wsGracePeriod)
	h.mu.RLock()
	_, exists := h.clients[c]
	h.mu.RUnlock()
	if !exists {
		return
	}
	h.disconnectClient(c)
}

func (c *wsClient) writePump() {
	ticker := time.NewTicker(wsPingPeriod)
	defer func() {
		ticker.Stop()
		c.conn.Close()
	}()
	for {
		select {
		case message, ok := <-c.client.send:
			c.conn.SetWriteDeadline(time.Now().Add(wsWriteWait))
			if !ok {
				c.conn.WriteMessage(websocket.CloseMessage, []byte{})
				return
			}

			w, err := c.conn.NextWriter(websocket.TextMessage)
			if err != nil {
				return
			}
			w.Write(message)

			// Coalescing disabled to prevent JSON parsing errors on client
			// if multiple messages are sent in one frame.

			if err := w.Close(); err != nil {
				return
			}
		case <-ticker.C:
			c.conn.SetWriteDeadline(time.Now().Add(wsWriteWait))
			if err := c.conn.WriteMessage(websocket.PingMessage, nil); err != nil {
				return
			}
		}
	}
}
