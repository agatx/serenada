package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"strings"
	"time"
	"unicode/utf8"
)

type TelegramService struct {
	botToken   string
	chatID     string
	httpClient *http.Client
}

func initTelegramFromEnv() *TelegramService {
	token := strings.TrimSpace(os.Getenv("TELEGRAM_BOT_TOKEN"))
	chatID := strings.TrimSpace(os.Getenv("TELEGRAM_CHAT_ID"))
	if token == "" || chatID == "" {
		log.Printf("[FEEDBACK] Telegram not configured (set TELEGRAM_BOT_TOKEN and TELEGRAM_CHAT_ID)")
		return nil
	}
	log.Printf("[FEEDBACK] Telegram configured for chat %s", chatID)
	return &TelegramService{
		botToken: token,
		chatID:   chatID,
		httpClient: &http.Client{
			Timeout: 10 * time.Second,
		},
	}
}

func (s *TelegramService) SendMessage(text string) error {
	payload := map[string]string{
		"chat_id": s.chatID,
		"text":    text,
	}
	body, _ := json.Marshal(payload)

	endpoint := fmt.Sprintf("https://api.telegram.org/bot%s/sendMessage", s.botToken)
	req, err := http.NewRequest(http.MethodPost, endpoint, bytes.NewReader(body))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")

	resp, err := s.httpClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	io.Copy(io.Discard, resp.Body)

	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return fmt.Errorf("telegram API returned %d", resp.StatusCode)
	}
	return nil
}

type FeedbackRequest struct {
	Message   string `json:"message"`
	Platform  string `json:"platform"`
	Locale    string `json:"locale"`
	Version   string `json:"version"`
	UserAgent string `json:"userAgent"`
}

func handleFeedback(tg *TelegramService) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "Method Not Allowed", http.StatusMethodNotAllowed)
			return
		}

		var req FeedbackRequest
		if err := json.NewDecoder(io.LimitReader(r.Body, 8*1024)).Decode(&req); err != nil {
			http.Error(w, "Invalid body", http.StatusBadRequest)
			return
		}

		msg := strings.TrimSpace(req.Message)
		if msg == "" {
			http.Error(w, "Missing message", http.StatusBadRequest)
			return
		}
		if utf8.RuneCountInString(msg) > 2000 {
			http.Error(w, "Message too long (max 2000 characters)", http.StatusBadRequest)
			return
		}

		platform := strings.TrimSpace(req.Platform)
		locale := strings.TrimSpace(req.Locale)
		version := strings.TrimSpace(req.Version)
		userAgent := strings.TrimSpace(req.UserAgent)

		// Format the Telegram message
		var sb strings.Builder
		sb.WriteString("Feedback from Serenada\n")
		if platform != "" || locale != "" || version != "" {
			parts := []string{}
			if platform != "" {
				parts = append(parts, "Platform: "+platform)
			}
			if locale != "" {
				parts = append(parts, "Locale: "+locale)
			}
			if version != "" {
				parts = append(parts, "v"+version)
			}
			sb.WriteString(strings.Join(parts, " | "))
			sb.WriteString("\n")
		}
		if userAgent != "" {
			sb.WriteString("UA: " + userAgent + "\n")
		}
		sb.WriteString("---\n")
		sb.WriteString(msg)
		text := sb.String()

		if tg != nil {
			go func() {
				if err := tg.SendMessage(text); err != nil {
					log.Printf("[FEEDBACK] Telegram send failed: %v", err)
				}
			}()
		} else {
			log.Printf("[FEEDBACK] %s", text)
		}

		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(map[string]bool{"ok": true})
	}
}
