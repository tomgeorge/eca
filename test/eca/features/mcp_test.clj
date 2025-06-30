(ns eca.features.mcp-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [eca.features.mcp :as f.mcp]
   [matcher-combinators.test :refer [match?]]))

(deftest all-servers-test
  (testing "disabled servers"
    (is (match? [{:name "clojureMCP"
                  :command "clojure"
                  :args ["-X:mcp" ":port" "7888"]
                  :status :disabled}]
                (f.mcp/all-servers
                 {}
                 {:mcpServers
                  {:clojureMCP {:command "clojure"
                                :args ["-X:mcp" ":port" "7888"]
                                :disabled true}}}))))
  (testing "running servers"
    (is (match? [{:name "clojureMCP"
                  :command "clojure"
                  :args ["-X:mcp" ":port" "7888"]
                  :status :running
                  :tools [{:name "eval"
                           :description "Evaluate Clojure code"
                           :parameters {:type "object"
                                        :properties {:code {:type "string"}}}}]}]
                (f.mcp/all-servers
                 {:mcp-clients {:clojureMCP {:status :running}}
                  :mcp-tools {"eval" {:name "eval"
                                      :mcp-name :clojureMCP
                                      :description "Evaluate Clojure code"
                                      :parameters {:type "object"
                                                   :properties {:code {:type "string"}}}}}}
                 {:mcpServers
                  {:clojureMCP {:command "clojure"
                                :args ["-X:mcp" ":port" "7888"]}}})))))
