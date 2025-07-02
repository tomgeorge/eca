(ns eca.features.chat-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [eca.features.chat :as f.chat]
   [eca.llm-api :as llm-api]
   [eca.test-helper :as h]
   [matcher-combinators.matchers :as m]
   [matcher-combinators.test :refer [match?]]))

(h/reset-components-before-test)

(defn ^:private complete! [params api-mock-fn]
  (let [req-id (:request-id params)
        {:keys [chat-id] :as resp}
        (with-redefs [llm-api/complete! api-mock-fn]
          (f.chat/prompt params (h/db*) (h/messenger) {}))]
    (is (match? {:chat-id string? :status :success} resp))
    {:chat-id chat-id
     :req-id req-id}))

(deftest basic-prompt-test
  (testing "Simple hello"
    (h/reset-components!)
    (let [{:keys [chat-id req-id]}
          (complete!
           {:message "Hey!"
            :request-id "1"}
           (fn [{:keys [on-first-message-received
                        on-message-received]}]
             (on-first-message-received {:type :text :text "Hey"})
             (on-message-received {:type :text :text "Hey"})
             (on-message-received {:type :text :text " you!"})
             (on-message-received {:type :finish})))]
      (is (match?
           {chat-id {:id chat-id
                     :messages [{:role "user" :content "Hey!"}
                                {:role "assistant" :content "Hey you!"}]}}
           (:chats (h/db))))
      (is (match?
           {:chat-content-received
            [{:chat-id chat-id
              :request-id req-id
              :content {:type :text :text "Hey!\n"}
              :role :user}
             {:chat-id chat-id
              :request-id req-id
              :content {:type :progress :state :running :text "Waiting model"}
              :role :system}
             {:chat-id chat-id
              :request-id req-id
              :content {:type :progress :state :running :text "Generating"}
              :role :system}
             {:chat-id chat-id
              :request-id req-id
              :content {:type :text :text "Hey"}
              :role :assistant}
             {:chat-id chat-id
              :request-id req-id
              :content {:type :text :text " you!"}
              :role :assistant}
             {:chat-id chat-id
              :request-id req-id
              :content {:state :finished :type :progress}
              :role :system}]}
           (h/messages)))))
  (testing "LLM error"
    (h/reset-components!)
    (let [{:keys [chat-id req-id]}
          (complete!
           {:message "Hey!"
            :request-id "1"}
           (fn [{:keys [on-error]}]
             (on-error {:message "Error from mocked API"})))]
      (is (match?
           {chat-id {:id chat-id :messages m/absent}}
           (:chats (h/db))))
      (is (match?
           {:chat-content-received
            [{:chat-id chat-id
              :request-id req-id
              :content {:type :text :text "Hey!\n"}
              :role :user}
             {:chat-id chat-id
              :request-id req-id
              :content {:type :progress :state :running :text "Waiting model"}
              :role :system}
             {:chat-id chat-id
              :request-id req-id
              :content {:type :text :text "Error from mocked API\n"}
              :role :system}
             {:chat-id chat-id
              :request-id req-id
              :content {:state :finished :type :progress}
              :role :system}]}
           (h/messages))))))
