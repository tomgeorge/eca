(ns eca.features.chat
  (:require
   [babashka.fs :as fs]
   [clojure.set :as set]
   [eca.config :as config]
   [eca.llm-api :as llm-api]
   [eca.messenger :as messenger]
   [eca.shared :as shared]))

(defn ^:private raw-context->refined [context]
  (mapcat (fn [{:keys [type path]}]
            (case type
              "file" [{:type :file
                       :path path
                       :content-map (llm-api/refine-file-context path)}]
              "directory" (->> (fs/glob path "**")
                               (remove fs/directory?)
                               (map (fn [path]
                                      (let [filename (str (fs/canonicalize path))]
                                        {:type :file
                                         :path filename
                                         :content-map (llm-api/refine-file-context filename)}))))
              nil))
          context))

(defn ^:private behavior->prompt-input [behavior]
  (case (keyword behavior)
    :agent "Help suggesting what needs to be changed if requested, offering help to make itself."
    :ask "Only answer questions and doubts."
    :manual "Help suggesting what needs to be changed."
    ""))

(defn ^:private build-prompt [message behavior refined-context]
  (format (str "You are an expert AI coding tool called ECA (Editor Code Assistant). Structure your answer in markdown *WITHOUT* using markdown code block.\n"
               "Your behavior is to '%s'.\n"
               "The user is asking: '%s'\n"
               "Context: %s")
          (behavior->prompt-input behavior)
          message
          (reduce (fn [msg {:keys [type path content-map]}]
                    (str
                     msg
                     (case type
                       :file (str path ":\n" content-map)
                       "")
                     "\n")) "" refined-context)))

(defn prompt
  [{:keys [message model behavior contexts chat-id request-id]}
   db*
   messenger
   config]
  (let [chat-id (or chat-id
                    (let [new-id (str (random-uuid))]
                      (swap! db* update :chats conj {:id new-id})
                      new-id))]
    (messenger/chat-content-received
     messenger
     {:chat-id chat-id
      :request-id request-id
      :is-complete false
      :role :user
      :content {:type :text
                :text (str message "\n")}})
    (when (seq contexts)
      (messenger/chat-content-received
       messenger
       {:chat-id chat-id
        :request-id request-id
        :is-complete false
        :role :system
        :content {:type :temporary-text
                  :text "Parsing given context..."}}))
    (let [refined-context (raw-context->refined contexts)]
      (messenger/chat-content-received
       messenger
       {:chat-id chat-id
        :request-id request-id
        :is-complete false
        :role :system
        :content {:type :temporary-text
                  :text "Generating..."}})
      (llm-api/complete! {:model (or model (:default-model @db*))
                          :message (build-prompt message (or behavior (:chat-behavior @db*)) refined-context)
                          :config config
                          :on-message-received (fn [{:keys [message finish-reason]}]
                                                 (messenger/chat-content-received
                                                  messenger
                                                  {:chat-id chat-id
                                                   :request-id request-id
                                                   :role :assistant
                                                   :is-complete (boolean finish-reason)
                                                   :content {:type :text
                                                             :text message}}))
                          :on-error (fn [e]
                                      (messenger/chat-content-received
                                       messenger
                                       {:chat-id chat-id
                                        :request-id request-id
                                        :is-complete true
                                        :role :system
                                        :content {:type :text
                                                  :text (str "\nError: " (ex-message e))}}))}))
    {:chat-id chat-id
     :status :success}))

(defn query-context
  [{:keys [query contexts chat-id]}
   db*]
  (let [config (config/all)
        all-subfiles-and-dirs (into []
                                    (comp
                                     (map :uri)
                                     (map shared/uri->filename)
                                     (mapcat (fn [root-filename]
                                               (let [ignores (config/index-ignores-patterns root-filename config)]
                                                 (->> (fs/glob root-filename (str "**" (or query "") "**"))
                                                      (remove #(shared/any-path-matches? % root-filename ignores))))))
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
