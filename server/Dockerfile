# Build stage
FROM golang:1.24-alpine AS builder
WORKDIR /app
COPY go.mod go.sum ./
# If you don't have go.sum yet, this might fail, but checking previously I saw go.sum exists.
# If not, 'go mod download' might need just go.mod or running 'go mod tidy' locally first.
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -o server .

# Run stage
FROM alpine:latest
RUN apk add --no-cache ca-certificates
WORKDIR /root/
COPY --from=builder /app/server .
EXPOSE 8080
CMD ["./server"]
