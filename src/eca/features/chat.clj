(ns eca.features.chat
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.set :as set]
   [clojure.string :as string]
   [eca.features.index :as f.index]
   [eca.features.rules :as f.rules]
   [eca.features.tools :as f.tools]
   [eca.llm-api :as llm-api]
   [eca.logger :as logger]
   [eca.messenger :as messenger]
   [eca.shared :as shared]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[CHAT]")

(defn ^:private raw-contexts->refined [contexts]
  (mapcat (fn [{:keys [type path]}]
            (case type
              "file" [{:type :file
                       :path path
                       :content (llm-api/refine-file-context path)}]
              "directory" (->> (fs/glob path "**")
                               (remove fs/directory?)
                               (map (fn [path]
                                      (let [filename (str (fs/canonicalize path))]
                                        {:type :file
                                         :path filename
                                         :content (llm-api/refine-file-context filename)}))))
              nil))
          contexts))

(defn ^:private build-context-str [refined-contexts rules]
  (str
   "<rules>\n"
   (reduce
    (fn [rule-str {:keys [name content]}]
      (str rule-str (format "<rule name=\"%s\">%s</rule>\n" name content)))
    ""
    rules)
   "</rules>\n"
   "<contexts>\n"
   (reduce
    (fn [context-str {:keys [type path content]}]
      (str context-str (case type
                         :file (format "<file path=\"%s\">%s</file>\n" path content)
                         "")))
    ""
    refined-contexts)
   "</contexts>"))

(defn ^:private behavior->behavior-str [behavior]
  (case behavior
    "chat" "Help with code changes only if user requested/agreed, ask first before do changes, answer questions, and provide explanations."
    "agent" "Help with code changes when applicable, suggesting you do the changes itself, answer questions, and provide explanations."))

(defn default-model [db config]
  (llm-api/default-model db config))

(defn finish-chat-prompt! [chat-id status messenger db*]
  (swap! db* assoc-in [:chats chat-id :status] status)
  (messenger/chat-content-received
   messenger
   {:chat-id chat-id
    :request-id (get-in @db* [:chats chat-id :current-request-id])
    :role :system
    :content {:type :progress
              :state :finished}}))

(defn ^:private assert-chat-not-stopped! [chat-id db* messenger]
  (when (identical? :stoping (get-in @db* [:chats chat-id :status]))
    (finish-chat-prompt! chat-id :idle messenger db*)
    (logger/info logger-tag "Chat prompt stopped:" chat-id)
    (throw (ex-info "Chat prompt stopped" {:silent? true
                                           :chat-id chat-id}))))

(defn prompt
  [{:keys [message model behavior contexts chat-id request-id]}
   db*
   messenger
   config]
  (let [chat-id (or chat-id
                    (let [new-id (str (random-uuid))]
                      (swap! db* assoc-in [:chats new-id] {:id new-id})
                      new-id))
        _ (swap! db* assoc-in [:chats chat-id :current-request-id] request-id)
        _ (swap! db* assoc-in [:chats chat-id :status] :running)
        _ (messenger/chat-content-received
           messenger
           {:chat-id chat-id
            :request-id request-id
            :role :user
            :content {:type :text
                      :text (str message "\n")}})
        _ (when (seq contexts)
            (messenger/chat-content-received
             messenger
             {:chat-id chat-id
              :request-id request-id
              :role :system
              :content {:type :progress
                        :state :running
                        :text "Parsing given context"}}))
        db @db*
        rules (f.rules/all config
                           (:workspace-folders db)
                           {:behavior (behavior->behavior-str (or behavior (:chat-default-behavior db)))})
        refined-contexts (raw-contexts->refined contexts)
        context-str (build-context-str refined-contexts rules)
        chosen-model (or model (default-model db config))
        past-messages (get-in db [:chats chat-id :messages] [])
        user-prompt message
        all-tools (f.tools/all-tools @db* config)
        received-msgs* (atom "")
        add-to-history! (fn [msg]
                          (swap! db* update-in [:chats chat-id :messages] (fnil conj []) msg))]
    (messenger/chat-content-received
     messenger
     {:chat-id chat-id
      :request-id request-id
      :role :system
      :content {:type :progress
                :state :running
                :text "Waiting model"}})
    (llm-api/complete!
     {:model chosen-model
      :model-config (get-in db [:models chosen-model])
      :user-prompt user-prompt
      :context context-str
      :past-messages past-messages
      :config config
      :tools all-tools
      :on-first-response-received (fn [& _]
                                    (assert-chat-not-stopped! chat-id db* messenger)
                                    (add-to-history! {:role "user" :content user-prompt})
                                    (messenger/chat-content-received
                                     messenger
                                     {:chat-id chat-id
                                      :request-id request-id
                                      :role :system
                                      :content {:type :progress
                                                :state :running
                                                :text "Generating"}}))
      :on-message-received (fn [{:keys [type] :as msg}]
                             (assert-chat-not-stopped! chat-id db* messenger)
                             (case type
                               :text (do
                                       (swap! received-msgs* str (:text msg))
                                       (messenger/chat-content-received
                                        messenger
                                        {:chat-id chat-id
                                         :request-id request-id
                                         :role :assistant
                                         :content {:type :text
                                                   :text (:text msg)}}))
                               :url (messenger/chat-content-received
                                     messenger
                                     {:chat-id chat-id
                                      :request-id request-id
                                      :role :assistant
                                      :content {:type :url
                                                :title (:title msg)
                                                :url (:url msg)}})
                               :limit-reached (do
                                                (messenger/chat-content-received
                                                 messenger
                                                 {:chat-id chat-id
                                                  :request-id request-id
                                                  :role :system
                                                  :content {:type :text
                                                            :text (str "API limit reached. Tokens: " (json/generate-string (:tokens msg)))}})
                                                (finish-chat-prompt! chat-id :idle messenger db*))
                               :finish (do
                                         (add-to-history! {:role "assistant" :content @received-msgs*})
                                         (finish-chat-prompt! chat-id :idle messenger db*))))
      :on-prepare-tool-call (fn [{:keys [id name argument]}]
                              (assert-chat-not-stopped! chat-id db* messenger)
                              (messenger/chat-content-received
                               messenger
                               {:chat-id chat-id
                                :request-id request-id
                                :role :assistant
                                :content {:type :toolCallPrepare
                                          :name name
                                          :argumentText argument
                                          :id id
                                          :manual-approval false}}))
      :on-tool-called (fn [{:keys [id name arguments] :as tool-call}]
                        (assert-chat-not-stopped! chat-id db* messenger)
                        (messenger/chat-content-received
                         messenger
                         {:chat-id chat-id
                          :request-id request-id
                          :role :assistant
                          :content {:type :toolCallRun
                                    :name name
                                    :arguments arguments
                                    :id id
                                    :manual-approval false}})
                        (let [result (f.tools/call-tool! name arguments @db* config)]
                          (when-not (string/blank? @received-msgs*)
                            (add-to-history! {:role "assistant" :content @received-msgs*})
                            (reset! received-msgs* ""))
                          (add-to-history! {:role "tool_call" :content tool-call})
                          (add-to-history! {:role "tool_call_output" :content (assoc tool-call :output result)})
                          (messenger/chat-content-received
                           messenger
                           {:chat-id chat-id
                            :request-id request-id
                            :role :assistant
                            :content {:type :toolCalled
                                      :name name
                                      :arguments arguments
                                      :id id
                                      :outputs (:contents result)}})
                          {:result result
                           :past-messages (get-in @db* [:chats chat-id :messages] [])}))
      :on-reason (fn [{:keys [status]}]
                   (assert-chat-not-stopped! chat-id db* messenger)
                   (let [msg (case status
                               :started "Reasoning"
                               :finished "Waiting model"
                               nil)]
                     (messenger/chat-content-received
                      messenger
                      {:chat-id chat-id
                       :request-id request-id
                       :role :system
                       :content {:type :progress
                                 :state :running
                                 :text msg}})))
      :on-error (fn [{:keys [message exception]}]
                  (messenger/chat-content-received
                   messenger
                   {:chat-id chat-id
                    :request-id request-id
                    :role :system
                    :content {:type :text
                              :text (or message (ex-message exception))}})
                  (finish-chat-prompt! chat-id :idle messenger db*))})
    {:chat-id chat-id
     :model chosen-model
     :status :success}))

(defn query-context
  [{:keys [query contexts chat-id]}
   db*
   config]
  (let [all-subfiles-and-dirs (into []
                                    (comp
                                     (map :uri)
                                     (map shared/uri->filename)
                                     (mapcat (fn [root-filename]
                                               (let [all-files (fs/glob root-filename (str "**" (or query "") "**"))
                                                     all-dirs (filter fs/directory? all-files)
                                                     excluded-dirs (filter #(f.index/ignore? (str %) root-filename config) all-dirs)]
                                                 (->> all-files
                                                      (remove (fn [path]
                                                                (or (some #(fs/starts-with? (str path) %) excluded-dirs)
                                                                    (f.index/ignore? (str path) root-filename config))))))))
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
        all-contexts (concat root-dirs
                             all-subfiles-and-dirs)]
    {:chat-id chat-id
     :contexts (set/difference (set all-contexts)
                               (set contexts))}))
(defn prompt-stop
  [{:keys [chat-id]} db* messenger]
  (when (identical? :running (get-in @db* [:chats chat-id :status]))
    (let [request-id (get-in @db* [:chats chat-id :current-request-id])]
      (messenger/chat-content-received
       messenger
       {:chat-id chat-id
        :request-id request-id
        :role :system
        :content {:type :text
                  :text "\nPrompt stopped"}})
      (finish-chat-prompt! chat-id :stoping messenger db*))))
