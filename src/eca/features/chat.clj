(ns eca.features.chat
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.set :as set]
   [clojure.string :as string]
   [eca.features.index :as f.index]
   [eca.features.prompt :as f.prompt]
   [eca.features.rules :as f.rules]
   [eca.features.tools :as f.tools]
   [eca.features.tools.mcp :as f.mcp]
   [eca.llm-api :as llm-api]
   [eca.logger :as logger]
   [eca.messenger :as messenger]
   [eca.shared :as shared :refer [assoc-some multi-str]]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[CHAT]")

(defn ^:private raw-contexts->refined [contexts]
  (mapcat (fn [{:keys [type path lines-range]}]
            (case type
              "file" [{:type :file
                       :path path
                       :partial (boolean lines-range)
                       :content (llm-api/refine-file-context path lines-range)}]
              "directory" (->> (fs/glob path "**")
                               (remove fs/directory?)
                               (map (fn [path]
                                      (let [filename (str (fs/canonicalize path))]
                                        {:type :file
                                         :path filename
                                         :content (llm-api/refine-file-context filename nil)}))))
              "repoMap" [{:type :repoMap}]))
          contexts))

(defn default-model [db config]
  (llm-api/default-model db config))

(defn ^:private send-content! [{:keys [messenger chat-id request-id]} role content]
  (messenger/chat-content-received
   messenger
   {:chat-id chat-id
    :request-id request-id
    :role role
    :content content}))

(defn finish-chat-prompt! [status {:keys [chat-id db*] :as chat-ctx}]
  (swap! db* assoc-in [:chats chat-id :status] status)
  (send-content! chat-ctx :system
                 {:type :progress
                  :state :finished}))

(defn ^:private assert-chat-not-stopped! [{:keys [chat-id db*] :as chat-ctx}]
  (when (identical? :stoping (get-in @db* [:chats chat-id :status]))
    (finish-chat-prompt! :idle chat-ctx)
    (logger/info logger-tag "Chat prompt stopped:" chat-id)
    (throw (ex-info "Chat prompt stopped" {:silent? true
                                           :chat-id chat-id}))))

(defn ^:private tool-name->origin [name all-tools]
  (:origin (first (filter #(= name (:name %)) all-tools))))

(defn ^:private tokens->cost [input-tokens input-cache-creation-tokens input-cache-read-tokens output-tokens model db]
  (let [normalized-model (if (string/includes? model "/")
                           (last (string/split model #"/"))
                           model)
        {:keys [input-token-cost output-token-cost
                input-cache-creation-token-cost input-cache-read-token-cost]} (get-in db [:models normalized-model])
        input-cost (* input-tokens input-token-cost)
        input-cost (if input-cache-creation-tokens
                     (+ input-cost (* input-cache-creation-tokens input-cache-creation-token-cost))
                     input-cost)
        input-cost (if input-cache-read-tokens
                     (+ input-cost (* input-cache-read-tokens input-cache-read-token-cost))
                     input-cost)]
    (when (and input-token-cost output-token-cost)
      (format "%.2f" (+ input-cost
                        (* output-tokens output-token-cost))))))

(defn ^:private usage-msg->usage
  [{:keys [input-tokens output-tokens
           input-cache-creation-tokens input-cache-read-tokens]}
   model
   {:keys [chat-id db*] :as chat-ctx}]
  (when (and output-tokens input-tokens)
    (swap! db* update-in [:chats chat-id :total-input-tokens] (fnil + 0) input-tokens)
    (swap! db* update-in [:chats chat-id :total-output-tokens] (fnil + 0) output-tokens)
    (when input-cache-creation-tokens
      (swap! db* update-in [:chats chat-id :total-input-cache-creation-tokens] (fnil + 0) input-cache-creation-tokens))
    (when input-cache-read-tokens
      (swap! db* update-in [:chats chat-id :total-input-cache-read-tokens] (fnil + 0) input-cache-read-tokens))
    (let [db @db*
          message-input-cache-tokens (or input-cache-creation-tokens 0)
          total-input-tokens (get-in db [:chats chat-id :total-input-tokens] 0)
          total-input-cache-creation-tokens (get-in db [:chats chat-id :total-input-cache-creation-tokens] nil)
          total-input-cache-read-tokens (get-in db [:chats chat-id :total-input-cache-read-tokens] nil)
          total-input-cache-tokens (or total-input-cache-creation-tokens 0)
          total-output-tokens (get-in db [:chats chat-id :total-output-tokens] 0)]
      (send-content! chat-ctx :system
                     (assoc-some {:type :usage
                                  :message-output-tokens output-tokens
                                  :message-input-tokens (+ input-tokens message-input-cache-tokens)
                                  :session-tokens (+ total-input-tokens total-input-cache-tokens total-output-tokens)}
                                 :message-cost (tokens->cost input-tokens input-cache-creation-tokens input-cache-read-tokens output-tokens model db)
                                 :session-cost (tokens->cost total-input-tokens total-input-cache-creation-tokens total-input-cache-read-tokens total-output-tokens model db))))))

(defn ^:private message->decision [message]
  (let [slash? (string/starts-with? message "/")
        mcp-prompt? (string/includes? (first (string/split message #" ")) ":")]
    (cond
      (and slash? mcp-prompt?)
      (let [message (subs message 1)
            parts (string/split message #" ")
            [server] (string/split message #":")]
        {:type :mcp-prompt
         :server server
         :prompt (second (string/split (first parts) #":"))
         :args (if (seq parts)
                 (vec (rest parts))
                 [])})

      slash?
      {:type :eca-command
       :command (subs message 1)}

      :else
      {:type :prompt-message
       :message message})))

(defn ^:private prompt-messages!
  [user-messages
   reason?
   {:keys [db* config chat-id contexts behavior model] :as chat-ctx}]
  (when (seq contexts)
    (send-content! chat-ctx :system {:type :progress
                                     :state :running
                                     :text "Parsing given context"}))
  (let [db @db*
        manual-approval? (get-in config [:toolCall :manualApproval] false)
        rules (f.rules/all config (:workspace-folders db))
        refined-contexts (raw-contexts->refined contexts)
        repo-map* (delay (f.index/repo-map db {:as-string? true}))
        instructions (f.prompt/build-instructions refined-contexts rules repo-map* (or behavior (:chat-default-behavior db)))
        past-messages (get-in db [:chats chat-id :messages] [])
        all-tools (f.tools/all-tools @db* config)
        received-msgs* (atom "")
        received-thinking* (atom "")
        tool-call-args-by-id* (atom {})
        add-to-history! (fn [msg]
                          (swap! db* update-in [:chats chat-id :messages] (fnil conj []) msg))]

    (send-content! chat-ctx :system {:type :progress
                                     :state :running
                                     :text "Waiting model"})
    (llm-api/complete!
     {:model model
      :model-config (get-in db [:models model])
      :user-messages user-messages
      :instructions instructions
      :past-messages past-messages
      :config config
      :tools all-tools
      :reason? reason?
      :on-first-response-received (fn [& _]
                                    (assert-chat-not-stopped! chat-ctx)
                                    (doseq [message user-messages]
                                      (add-to-history! message))
                                    (send-content! chat-ctx :system {:type :progress
                                                                     :state :running
                                                                     :text "Generating"}))
      :on-message-received (fn [{:keys [type] :as msg}]
                             (assert-chat-not-stopped! chat-ctx)
                             (case type
                               :text (do
                                       (swap! received-msgs* str (:text msg))
                                       (send-content! chat-ctx :assistant {:type :text
                                                                           :text (:text msg)}))
                               :url (send-content! chat-ctx :assistant {:type :url
                                                                        :title (:title msg)
                                                                        :url (:url msg)})
                               :limit-reached (do
                                                (send-content! chat-ctx :system
                                                               {:type :text
                                                                :text (str "API limit reached. Tokens: " (json/generate-string (:tokens msg)))})

                                                (finish-chat-prompt! :idle chat-ctx))
                               :finish (do
                                         (add-to-history! {:role "assistant" :content @received-msgs*})
                                         (when-let [usage (usage-msg->usage (:usage msg) model chat-ctx)]
                                           (send-content! chat-ctx :system
                                                          (merge usage
                                                                 {:type :usage})))
                                         (finish-chat-prompt! :idle chat-ctx))))
      :on-prepare-tool-call (fn [{:keys [id name arguments-text]}]
                              (assert-chat-not-stopped! chat-ctx)
                              (swap! tool-call-args-by-id* update id str arguments-text)
                              (send-content! chat-ctx :assistant
                                             {:type :toolCallPrepare
                                              :name name
                                              :origin (tool-name->origin name all-tools)
                                              :arguments-text (get @tool-call-args-by-id* id)
                                              :id id
                                              :manual-approval manual-approval?}))
      :on-tool-called (fn [{:keys [id name arguments] :as tool-call}]
                        (assert-chat-not-stopped! chat-ctx)
                        (send-content! chat-ctx :assistant
                                       {:type :toolCallRun
                                        :name name
                                        :origin (tool-name->origin name all-tools)
                                        :arguments arguments
                                        :id id
                                        :manual-approval manual-approval?})
                        (let [approved?* (promise)]
                          (swap! db* assoc-in [:chats chat-id :tool-calls id :approved?*] approved?*)
                          (when-not (string/blank? @received-msgs*)
                            (add-to-history! {:role "assistant" :content @received-msgs*})
                            (reset! received-msgs* ""))
                          (if manual-approval?
                            (send-content! chat-ctx :system
                                           {:type :progress
                                            :state :running
                                            :text "Waiting for tool call approval"})
                             ;; Otherwise auto approve
                            (deliver approved?* true))
                          (if @approved?*
                            (let [result (f.tools/call-tool! name arguments @db* config)]
                              (add-to-history! {:role "tool_call" :content tool-call})
                              (add-to-history! {:role "tool_call_output" :content (assoc tool-call :output result)})
                              (swap! tool-call-args-by-id* dissoc id)
                              (send-content! chat-ctx :assistant
                                             {:type :toolCalled
                                              :origin (tool-name->origin name all-tools)
                                              :name name
                                              :arguments arguments
                                              :error (:error result)
                                              :id id
                                              :outputs (:contents result)})
                              {:new-messages (get-in @db* [:chats chat-id :messages])})
                            (do
                              (add-to-history! {:role "tool_call" :content tool-call})
                              (add-to-history! {:role "tool_call_output" :content (assoc tool-call :output {:contents [{:content "Tool call rejected by user"
                                                                                                                        :error true
                                                                                                                        :type :text}]})})
                              (swap! tool-call-args-by-id* dissoc id)
                              (send-content! chat-ctx :system
                                             {:type :progress
                                              :state :running
                                              :text "Generating"})
                              (send-content! chat-ctx :assistant
                                             {:type :toolCallRejected
                                              :origin (tool-name->origin name all-tools)
                                              :name name
                                              :arguments arguments
                                              :reason :user
                                              :id id})
                              {:new-messages (get-in @db* [:chats chat-id :messages])}))))
      :on-reason (fn [{:keys [status id text external-id]}]
                   (assert-chat-not-stopped! chat-ctx)
                   (case status
                     :started (send-content! chat-ctx :assistant
                                             {:type :reasonStarted
                                              :id id})
                     :thinking (do
                                 (swap! received-thinking* str text)
                                 (send-content! chat-ctx :assistant
                                                {:type :reasonText
                                                 :id id
                                                 :text text}))
                     :finished (do
                                 (add-to-history! {:role "reason" :content {:external-id external-id
                                                                            :text @received-thinking*}})
                                 (send-content! chat-ctx :assistant
                                                {:type :reasonFinished
                                                 :id id}))
                     nil))
      :on-error (fn [{:keys [message exception]}]
                  (send-content! chat-ctx :system
                                 {:type :text
                                  :text (or message (ex-message exception))})
                  (finish-chat-prompt! :idle chat-ctx))})))

(defn ^:private send-mcp-prompt! [{:keys [prompt args]} {:keys [db*] :as chat-ctx}]
  (let [{:keys [arguments]} (first (filter #(= prompt (:name %)) (f.mcp/all-prompts @db*)))
        i (atom -1)
        args-vals (reduce
                   (fn [a {:keys [name]}]
                     (swap! i inc)
                     (assoc a name (nth args @i)))
                   {}
                   arguments)
        {:keys [messages]} (f.mcp/get-prompt! prompt args-vals @db*)]
    (prompt-messages! messages false chat-ctx)))

(defn ^:private handle-command! [{:keys [command]} {:keys [chat-id db*] :as chat-ctx}]
  (case command
    "costs" (let [db @db*
                  total-input-tokens (get-in db [:chats chat-id :total-input-tokens] 0)
                  total-input-cache-creation-tokens (get-in db [:chats chat-id :total-input-cache-creation-tokens] nil)
                  total-input-cache-read-tokens (get-in db [:chats chat-id :total-input-cache-read-tokens] nil)
                  total-output-tokens (get-in db [:chats chat-id :total-output-tokens] 0)
                  text (multi-str (str "Total input tokens: " total-input-tokens)
                                  (when total-input-cache-creation-tokens
                                    (str "Total input cache creation tokens: " total-input-cache-creation-tokens))
                                  (when total-input-cache-read-tokens
                                    (str "Total input cache read tokens: " total-input-cache-read-tokens))
                                  (str "Total output tokens: " total-output-tokens))]
              (send-content! chat-ctx :system {:type :text
                                               :text text}))
    (send-content! chat-ctx :system {:type :text
                                     :text (str "Unknown command: " command)}))
  (finish-chat-prompt! :idle chat-ctx))

(defn prompt
  [{:keys [message model behavior contexts chat-id request-id]}
   db*
   messenger
   config]
  (let [chat-id (or chat-id
                    (let [new-id (str (random-uuid))]
                      (swap! db* assoc-in [:chats new-id] {:id new-id})
                      new-id))
        chosen-model (or model (default-model @db* config))
        chat-ctx {:chat-id chat-id
                  :request-id request-id
                  :contexts contexts
                  :behavior behavior
                  :model chosen-model
                  :db* db*
                  :config config
                  :messenger messenger}
        decision (message->decision message)]
    (swap! db* assoc-in [:chats chat-id :current-request-id] request-id)
    (swap! db* assoc-in [:chats chat-id :status] :running)
    (send-content! chat-ctx :user {:type :text
                                   :text (str message "\n")})
    (case (:type decision)
      :mcp-prompt (send-mcp-prompt! decision chat-ctx)
      :eca-command (handle-command! decision chat-ctx)
      :prompt-message (prompt-messages! [{:role "user" :content message}] true chat-ctx))
    {:chat-id chat-id
     :model chosen-model
     :status :success}))

(defn tool-call-approve [{:keys [chat-id tool-call-id]} db*]
  (deliver (get-in @db* [:chats chat-id :tool-calls tool-call-id :approved?*]) true))

(defn tool-call-reject [{:keys [chat-id tool-call-id]} db*]
  (deliver (get-in @db* [:chats chat-id :tool-calls tool-call-id :approved?*]) false))

(defn ^:private contexts-for [root-filename query config]
  (let [all-files (fs/glob root-filename (str "**" (or query "") "**"))
        allowed-files (f.index/filter-allowed all-files root-filename config)]
    allowed-files))

(defn query-context
  [{:keys [query contexts chat-id]}
   db*
   config]
  (let [all-subfiles-and-dirs (into []
                                    (comp
                                     (map :uri)
                                     (map shared/uri->filename)
                                     (mapcat #(contexts-for % query config))
                                     (take 200) ;; for performance, user can always make query specific for better results.
                                     (map (fn [file-or-dir]
                                            {:type (if (fs/directory? file-or-dir)
                                                     "directory"
                                                     "file")
                                             :path (str (fs/canonicalize file-or-dir))})))
                                    (:workspace-folders @db*))
        root-dirs (mapv (fn [{:keys [uri]}] {:type "directory"
                                             :path (shared/uri->filename uri)})
                        (:workspace-folders @db*))
        all-contexts (concat [{:type "repoMap"}]
                             root-dirs
                             all-subfiles-and-dirs)]
    {:chat-id chat-id
     :contexts (set/difference (set all-contexts)
                               (set contexts))}))

(defn query-commands
  [{:keys [query chat-id]}
   db*]
  (let [mcp-prompts (->> (f.mcp/all-prompts @db*)
                         (mapv #(-> %
                                    (assoc :name (str (:server %) ":" (:name %))
                                           :type :mcpPrompt)
                                    (dissoc :server))))
        eca-commands [{:name "costs"
                       :type :native
                       :description "Show the total costs of the current chat session."
                       :arguments []}]
        commands (concat mcp-prompts
                         eca-commands)
        commands (if (string/blank? query)
                   commands
                   (filter #(or (string/includes? (:name %) query)
                                (string/includes? (:description %) query))
                           commands))]
    {:chat-id chat-id
     :commands commands}))

(defn prompt-stop
  [{:keys [chat-id]} db* messenger]
  (when (identical? :running (get-in @db* [:chats chat-id :status]))
    (let [request-id (get-in @db* [:chats chat-id :current-request-id])
          chat-ctx {:chat-id chat-id
                    :request-id request-id
                    :db* db*
                    :messenger messenger}]
      (send-content! chat-ctx :system {:type :text
                                       :text "\nPrompt stopped"})
      (finish-chat-prompt! :stoping chat-ctx))))

(defn delete-chat
  [{:keys [chat-id]} db*]
  (swap! db* update :chats dissoc chat-id))
