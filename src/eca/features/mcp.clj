(ns eca.features.mcp
  (:require
   [cheshire.core :as json]
   [eca.logger :as logger])
  (:import
   [com.fasterxml.jackson.databind ObjectMapper]
   [io.modelcontextprotocol.client McpClient McpSyncClient]
   [io.modelcontextprotocol.client.transport ServerParameters StdioClientTransport]
   [io.modelcontextprotocol.spec
    McpSchema$CallToolRequest
    McpSchema$ClientCapabilities
    McpSchema$Content
    McpSchema$Root
    McpSchema$TextContent
    McpSchema$Tool
    McpTransport]
   [java.time Duration]
   [java.util List Map]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[MCP]")

(defn ^:private ->transport ^McpTransport [{:keys [command args env]}]
  (StdioClientTransport.
   (-> (ServerParameters/builder ^String command)
       (.args ^List args)
       (.env (update-keys env name))
       (.build))))

(defn ^:private ->client ^McpSyncClient [transport config]
  (-> (McpClient/sync transport)
      (.requestTimeout (Duration/ofSeconds (:mcpTimeoutSeconds config)))
      (.capabilities (-> (McpSchema$ClientCapabilities/builder)
                         (.roots true)
                         (.build)))
      (.build)))

(defn initialize! [{:keys [on-error]} db* config]
  (doseq [[name server-config] (:mcpServers config)]
    (try
      (when-not (and (get-in @db* [:mcp-clients name])
                     (get server-config :disabled false))
        (let [transport (->transport server-config)
              client (->client transport config)]
          (swap! db* assoc-in [:mcp-clients name :client] client)
          (doseq [{:keys [name uri]} (:workspace-folders @db*)]
            (.addRoot client (McpSchema$Root. uri name)))
          (.initialize client)))
      (catch Exception e
        (logger/warn logger-tag (format "Could not initialize MCP server %s. Error: %s" name (.getMessage e)))
        (on-error name e)))))

(defn tools-cached? [db]
  (boolean (:mcp-tools db)))

(defn cache-tools! [db*]
  (let [obj-mapper (ObjectMapper.)]
    (doseq [[name {:keys [^McpSyncClient client]}] (:mcp-clients @db*)]
      (when (.isInitialized client)
        (doseq [^McpSchema$Tool tool-client (.tools (.listTools client))]
          (let [tool {:name (.name tool-client)
                      :mcp-name name
                      :mcp-client client
                      :description (.description tool-client)
                      ;; We convert to json to then read so we have the clojrue map
                      ;; TODO avoid this converting to clojure map directly
                      :parameters (json/parse-string (.writeValueAsString obj-mapper (.inputSchema tool-client)) true)}]
            (swap! db* assoc-in [:mcp-tools (:name tool)] tool)))))))

(defn list-tools [db]
  (vals (:mcp-tools db)))

(defn call-tool! [^String name ^Map arguments db]
  (let [result (.callTool ^McpSyncClient (get-in db [:mcp-tools name :mcp-client])
                          (McpSchema$CallToolRequest. name arguments))]
    (if (.isError result)
      {:error (.content result)}
      {:contents (map (fn [content]
                        (case (.type ^McpSchema$Content content)
                          "text" {:type :text
                                  :content (.text ^McpSchema$TextContent content)}
                          nil))
                      (.content result))})))

(defn shutdown! [db*]
  (doseq [[_name {:keys [_client]}] (:mcp-clients @db*)]
    ;; TODO NoClassDefFound being thrown for some reason
    #_(.closeGracefully ^McpSyncClient client))
  (swap! db* assoc
         :mcp-clients {}
         :mcp-tools {}))
