(ns eca.messenger
  "An interface for sending messages to a 'client',
   whether it's a editor or a no-op for test.")

(set! *warn-on-reflection* true)

(defprotocol IMessenger
  (chat-content-received [this data])
  (mcp-server-updated [this params])
  (showMessage [this msg]))
