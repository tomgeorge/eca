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
    McpSchema$GetPromptRequest
    McpSchema$Prompt
    McpSchema$PromptArgument
    McpSchema$PromptMessage
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

(defn ^:private list-server-tools [^ObjectMapper obj-mapper ^McpSyncClient client]
  (mapv (fn [^McpSchema$Tool tool-client]
          {:name (.name tool-client)
           :description (.description tool-client)
           ;; We convert to json to then read so we have a clojure map
           ;; TODO avoid this converting to clojure map directly
           :parameters (json/parse-string (.writeValueAsString obj-mapper (.inputSchema tool-client)) true)})
        (.tools (.listTools client))))

(defn ^:private list-server-prompts [^McpSyncClient client]
  (mapv (fn [^McpSchema$Prompt prompt-client]
          {:name (.name prompt-client)
           :description (.description prompt-client)
           :arguments (mapv (fn [^McpSchema$PromptArgument content]
                              {:name (.name content)
                               :description (.description content)
                               :required (.required content)})
                            (.arguments prompt-client))})
        (.prompts (.listPrompts client))))

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
                (swap! db* assoc-in [:mcp-clients name :tools] (list-server-tools obj-mapper client))
                (swap! db* assoc-in [:mcp-clients name :prompts] (list-server-prompts client))
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
        {:error (.isError result)
         :contents (map (fn [content]
                          (case (.type ^McpSchema$Content content)
                            "text" {:type :text
                                    :content (.text ^McpSchema$TextContent content)}
                            nil))
                        (.content result))})
      (catch Exception e
        {:error true
         :contents [{:type :text
                     :content (.getMessage e)}]}))))

(defn get-prompt! [^String name ^Map arguments db]
  (let [mcp-client (->> (vals (:mcp-clients db))
                        (keep (fn [{:keys [client prompts]}]
                                (when (some #(= name (:name %)) prompts)
                                  client)))
                        first)
        prompt (.getPrompt ^McpSyncClient mcp-client (McpSchema$GetPromptRequest. name arguments))]
    {:description (.description prompt)
     :messages (mapv (fn [^McpSchema$PromptMessage message]
                       {:role (.role message)
                        :content (.content message)})
                     (.messages prompt))}))

(defn shutdown! [db*]
  (doseq [[_name {:keys [_client]}] (:mcp-clients @db*)]
    ;; TODO NoClassDefFound being thrown for some reason
    #_(.closeGracefully ^McpSyncClient client))
  (swap! db* assoc :mcp-clients {}))

(comment
  (def db* (atom user/*db*))
  (user/with-workspace-root "/home/greg/dev/eca/eca"
    (initialize-servers-async! {:on-server-updated println}
                               db*
                               {:mcpTimeoutSeconds 10
                                :mcpServers {"fetch" {:command "docker" :args ["run" "-i" "--rm" "mcp/fetch"]}}}))
  (:prompts (second (first (:mcp-clients @db*))))
  (get-prompt! "fetch" {"url" "https://eca.dev"} @db*))
