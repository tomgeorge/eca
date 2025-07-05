(ns eca.llm-providers.openai-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [eca.llm-providers.openai :as llm-providers.openai]
   [matcher-combinators.test :refer [match?]]))

(deftest ->messages-with-history-test
  (testing "no previous history"
    (is (match?
         [{:role "user" :content "Hey"}]
         (#'llm-providers.openai/->input-with-history [] "Hey"))))
  (testing "With basic text history"
    (is (match?
         [{:role "user" :content "Count with me: 1"}
          {:role "assistant" :content "2"}
          {:role "user" :content "3"}]
         (#'llm-providers.openai/->input-with-history
          [{:role "user" :content "Count with me: 1"}
           {:role "assistant" :content "2"}]
          "3"))))
  (testing "With tool_call history"
    (is (match?
         [{:role "user" :content "List the files you are allowed"}
          {:role "assistant" :content "Ok!"}
          {:type "function_call"
           :call_id "call-1"
           :name "list_allowed_directories"
           :arguments "{}"}
          {:type "function_call_output"
           :call_id "call-1"
           :output "Allowed directories: /foo/bar\n"}
          {:role "assistant" :content "I see /foo/bar"}
          {:role "user" :content "Thanks"}]
         (#'llm-providers.openai/->input-with-history
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
