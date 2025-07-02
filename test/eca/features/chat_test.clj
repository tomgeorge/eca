(ns eca.features.chat-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [eca.features.chat :as f.chat]
   [eca.llm-api :as llm-api]
   [eca.test-helper :as h]
   [matcher-combinators.test :refer [match?]]))

(h/reset-components-before-test)

(deftest prompt-test
  (testing "Simple hello"
    (let [req-id "1"
          {:keys [chat-id] :as resp}
          (with-redefs [llm-api/complete!
                        (fn [{:keys [on-first-message-received
                                     on-message-received]}]
                          (on-first-message-received {:type :text :text "Hey"})
                          (on-message-received {:type :text :text "Hey"})
                          (on-message-received {:type :text :text " you!"}))]
            (f.chat/prompt
             {:message "Hey!"
              :model "o4-mini"
              :request-id req-id}
             (h/db*)
             (h/messenger)
             {}))]
      (is (match?
           {:chat-id string?
            :status :success}
           resp))
      (is (match?
           {chat-id {:id chat-id}}
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
              :role :assistant}]}
           (h/messages))))))
