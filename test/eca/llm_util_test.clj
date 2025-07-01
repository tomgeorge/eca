(ns eca.llm-util-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [eca.llm-util :as llm-util]
   [matcher-combinators.test :refer [match?]])
  (:import
   [java.io ByteArrayInputStream]))

(deftest event-data-seq-test
  (testing "when there is a event line and another data line"
    (with-open [r (io/reader (ByteArrayInputStream. (.getBytes (str "event: foo.bar\n"
                                                                    "data: {\"type\": \"foo.bar\"}\n"
                                                                    "\n"
                                                                    "event: foo.baz\n"
                                                                    "data: {\"type\": \"foo.baz\"}"))))]
      (is (match?
           [["foo.bar" {:type "foo.bar"}]
            ["foo.baz" {:type "foo.baz"}]]
           (llm-util/event-data-seq r)))))
  (testing "when there is no event line, only a data line"
    (with-open [r (io/reader (ByteArrayInputStream. (.getBytes (str "data: {\"type\": \"foo.bar\"}\n"
                                                                    "\n"
                                                                    "data: {\"type\": \"foo.baz\"}"))))]
      (is (match?
           [["foo.bar" {:type "foo.bar"}]
            ["foo.baz" {:type "foo.baz"}]]
           (llm-util/event-data-seq r)))))
  (testing "when there is no event line, only a data line with the content directly in each line"
    (with-open [r (io/reader (ByteArrayInputStream. (.getBytes (str "{\"bar\": \"baz\"}\n"
                                                                    "{\"bar\": \"foo\"}"))))]
      (is (match?
           [[nil {:bar "baz"}]
            [nil {:bar "foo"}]]
           (llm-util/event-data-seq r)))))
  (testing "Ignore [DONE] when exists"
    (with-open [r (io/reader (ByteArrayInputStream. (.getBytes (str "data: {\"type\": \"foo.bar\"}\n"
                                                                    "\n"
                                                                    "data: {\"type\": \"foo.baz\"}\n"
                                                                    "\n"
                                                                    "data: [DONE]\n"))))]
      (is (match?
           [["foo.bar" {:type "foo.bar"}]
            ["foo.baz" {:type "foo.baz"}]]
           (llm-util/event-data-seq r))))))
