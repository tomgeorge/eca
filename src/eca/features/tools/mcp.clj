(ns eca.features.tools.mcp
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [eca.config :as config]
   [eca.logger :as logger]
   [eca.shared :as shared])
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

(def ^:private env-var-regex
  #"\$(\w+)|\$\{([^}]+)\}")

(defn ^:private replace-env-vars [s]
  (let [env (System/getenv)]
    (string/replace s
                    env-var-regex
                    (fn [[_ var1 var2]]
                      (or (get env (or var1 var2))
                          (str "$" var1)
                          (str "${" var2 "}"))))))

(defn ^:private ->transport ^McpTransport [{:keys [command args env]} workspaces]
  (let [command ^String (replace-env-vars command)
        b (ServerParameters/builder command)
        b (if args
            (.args b ^List (mapv replace-env-vars (or args [])))
            b)
        b (if env
            (.env b (update-keys env name))
            b)
        pb-init-args []
        ;; TODO we are hard coding the first workspace
        work-dir (or (some-> workspaces
                             first
                             :uri
                             shared/uri->filename)
                     (config/get-property "user.home"))]
    (proxy [StdioClientTransport] [(.build b)]
      (getProcessBuilder [] (-> (ProcessBuilder. ^List pb-init-args)
                                (.directory (io/file work-dir)))))))

(defn ^:private ->client ^McpSyncClient [transport config]
  (-> (McpClient/sync transport)
      (.requestTimeout (Duration/ofSeconds (:mcpTimeoutSeconds config)))
      (.capabilities (-> (McpSchema$ClientCapabilities/builder)
                         (.roots true)
                         (.build)))
      (.build)))

(defn ^:private ->server [mcp-name server-config status db]
  {:name (name mcp-name)
   :command (:command server-config)
   :args (:args server-config)
   :tools (get-in db [:mcp-clients mcp-name :tools])
   :status status})

(defn initialize-servers-async! [{:keys [on-server-updated]} db* config]
  (let [workspaces (:workspace-folders @db*)
        db @db*
        obj-mapper (ObjectMapper.)]
    (doseq [[name server-config] (:mcpServers config)]
      (when-not (get-in db [:mcp-clients name])
        (if (get server-config :disabled false)
          (on-server-updated (->server name server-config :disabled db))
          (future
            (try
              (let [transport (->transport server-config workspaces)
                    client (->client transport config)]
                (on-server-updated (->server name server-config :starting db))
                (swap! db* assoc-in [:mcp-clients name] {:client client})
                (doseq [{:keys [name uri]} workspaces]
                  (.addRoot client (McpSchema$Root. uri name)))
                (.initialize client)
                (let [tools (mapv (fn [^McpSchema$Tool tool-client]
                                    {:name (.name tool-client)
                                     :description (.description tool-client)
                                     ;; We convert to json to then read so we have a clojure map
                                     ;; TODO avoid this converting to clojure map directly
                                     :parameters (json/parse-string (.writeValueAsString obj-mapper (.inputSchema tool-client)) true)})
                                  (.tools (.listTools client)))]
                  (swap! db* assoc-in [:mcp-clients name :tools] tools))
                (on-server-updated (->server name server-config :running @db*)))
              (catch Exception e
                (logger/warn logger-tag (format "Could not initialize MCP server %s. Error: %s" name (.getMessage e)))
                (on-server-updated (->server name server-config :failed db))))))))))

(defn all-tools [db]
  (into []
        (mapcat (fn [[_name {:keys [tools]}]]
                  tools))
        (:mcp-clients db)))

(defn call-tool! [^String name ^Map arguments db]
  (let [mcp-client (->> (vals (:mcp-clients db))
                        (keep (fn [{:keys [client tools]}]
                                (when (some #(= name (:name %)) tools)
                                  client)))
                        first)]
    (try
      (let [result (.callTool ^McpSyncClient mcp-client
                              (McpSchema$CallToolRequest. name arguments))]
        (logger/debug logger-tag "ToolCall result: " result)
        {:contents (map (fn [content]
                          (case (.type ^McpSchema$Content content)
                            "text" {:type :text
                                    :error (.isError result)
                                    :content (.text ^McpSchema$TextContent content)}
                            nil))
                        (.content result))})
      (catch Exception e
        {:contents [{:type :text
                     :error true
                     :content (.getMessage e)}]}))))

(defn shutdown! [db*]
  (doseq [[_name {:keys [_client]}] (:mcp-clients @db*)]
    ;; TODO NoClassDefFound being thrown for some reason
    #_(.closeGracefully ^McpSyncClient client))
  (swap! db* assoc :mcp-clients {}))
