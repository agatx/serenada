package main

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"errors"
	"fmt"
	"os"
)

const (
	roomIDVersion      = "v1"
	roomIDEntity       = "room"
	roomIDRandomBytes  = 12
	roomIDTagBytes     = 8
	roomIDTotalBytes   = roomIDRandomBytes + roomIDTagBytes
	roomIDEncodedBytes = 27
)

var (
	ErrRoomIDSecretMissing = errors.New("room id secret not configured")
)

func roomIDContext() string {
	env := os.Getenv("ROOM_ID_ENV")
	if env == "" {
		env = "dev"
	}
	return fmt.Sprintf("id:%s|%s|%s", roomIDVersion, env, roomIDEntity)
}

func roomIDSecret() (string, error) {
	secret := os.Getenv("ROOM_ID_SECRET")
	if secret == "" {
		return "", ErrRoomIDSecretMissing
	}
	return secret, nil
}

func generateRoomID() (string, error) {
	secret, err := roomIDSecret()
	if err != nil {
		return "", err
	}

	random := make([]byte, roomIDRandomBytes)
	if _, err := rand.Read(random); err != nil {
		return "", err
	}

	mac := hmac.New(sha256.New, []byte(secret))
	mac.Write(random)
	mac.Write([]byte(roomIDContext()))
	tag := mac.Sum(nil)[:roomIDTagBytes]

	token := make([]byte, 0, roomIDTotalBytes)
	token = append(token, random...)
	token = append(token, tag...)

	return base64.RawURLEncoding.EncodeToString(token), nil
}

func validateRoomID(roomID string) error {
	if roomID == "" {
		return errors.New("missing room id")
	}
	if len(roomID) != roomIDEncodedBytes {
		return errors.New("room id must be a 27-character token")
	}

	secret, err := roomIDSecret()
	if err != nil {
		return err
	}

	raw, err := base64.RawURLEncoding.DecodeString(roomID)
	if err != nil {
		return errors.New("room id is invalid")
	}
	if len(raw) != roomIDTotalBytes {
		return errors.New("room id is invalid")
	}

	random := raw[:roomIDRandomBytes]
	tag := raw[roomIDRandomBytes:]

	mac := hmac.New(sha256.New, []byte(secret))
	mac.Write(random)
	mac.Write([]byte(roomIDContext()))
	expected := mac.Sum(nil)[:roomIDTagBytes]

	if !hmac.Equal(tag, expected) {
		return errors.New("room id is invalid")
	}

	return nil
}
