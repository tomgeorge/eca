(ns eca.llm-providers.anthropic-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [eca.llm-providers.anthropic :as llm-providers.anthropic]
   [matcher-combinators.test :refer [match?]]))

(deftest ->messages-with-history-test
  (testing "no previous history"
    (is (match?
         [{:role "user" :content [{:type :text :text "Hey"}]}]
         (#'llm-providers.anthropic/->messages-with-history [] "Hey"))))
  (testing "With basic text history"
    (is (match?
         [{:role "user" :content "Count with me: 1"}
          {:role "assistant" :content "2"}
          {:role "user" :content [{:type :text :text "3"}]}]
         (#'llm-providers.anthropic/->messages-with-history
          [{:role "user" :content "Count with me: 1"}
           {:role "assistant" :content "2"}]
          "3"))))
  (testing "With tool_call history"
    (is (match?
         [{:role "user" :content "List the files you are allowed"}
          {:role "assistant" :content "Ok!"}
          {:role "assistant" :content [{:type "tool_use"
                                        :id "call-1"
                                        :name "list_allowed_directories"
                                        :input {}}]}
          {:role "user" :content [{:type "tool_result"
                                   :tool_use_id "call-1"
                                   :content "Allowed directories: /foo/bar\n"}]}
          {:role "assistant" :content "I see /foo/bar"}
          {:role "user" :content [{:type :text :text "Thanks"}]}]
         (#'llm-providers.anthropic/->messages-with-history
          [{:role "user" :content "List the files you are allowed"}
           {:role "assistant" :content "Ok!"}
           {:role "tool_call" :content {:id "call-1" :name "list_allowed_directories" :arguments {}}}
           {:role "tool_call_output" :content {:id "call-1"
                                               :name "list_allowed_directories"
                                               :arguments {}
                                               :output {:contents [{:type :text
                                                                    :error false
                                                                    :content "Allowed directories: /foo/bar"}]}}}
           {:role "assistant" :content "I see /foo/bar"}]
          "Thanks")))))

(deftest add-cache-to-last-message-test
  (is (match?
       []
       (#'llm-providers.anthropic/add-cache-to-last-message [])))
  (is (match?
       [{:role "user" :content [{:type :text :text "Hey" :cache_control {:type "ephemeral"}}]}]
       (#'llm-providers.anthropic/add-cache-to-last-message
        [{:role "user" :content [{:type :text :text "Hey"}]}])))
  (is (match?
       [{:role "user" :content [{:type :text :text "Hey"}]}
        {:role "user" :content [{:type :text :text "Ho" :cache_control {:type "ephemeral"}}]}]
       (#'llm-providers.anthropic/add-cache-to-last-message
        [{:role "user" :content [{:type :text :text "Hey"}]}
         {:role "user" :content [{:type :text :text "Ho"}]}]))))
