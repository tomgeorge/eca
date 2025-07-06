(ns eca.features.tools.shell-test
  (:require
   [babashka.fs :as fs]
   [clojure.java.shell :as shell]
   [clojure.test :refer [deftest is testing]]
   [eca.features.tools.shell :as f.tools.shell]
   [eca.test-helper :as h]
   [matcher-combinators.test :refer [match?]]))

(deftest shell-command-test
  (testing "inexistent working_directory"
    (is (match?
         {:contents [{:type :text
                      :error true
                      :content (format "working directory %s does not exist" (h/file-path "/baz"))}]}
         (with-redefs [fs/exists? (constantly false)]
           ((get-in f.tools.shell/definitions ["shell_command" :handler])
            {"command" "ls -lh"
             "working_directory" (h/file-path "/baz")}
            {:db {:workspace-folders [{:uri (h/file-uri "file:///project/foo") :name "foo"}]}})))))
  (testing "command exited with non-zero exit code"
    (is (match?
         {:contents [{:type :text
                      :error true
                      :content "Command failed with exit code 1: Some error"}]}
         (with-redefs [fs/exists? (constantly true)
                       shell/sh (constantly {:exit 1 :err "Some error"})]
           ((get-in f.tools.shell/definitions ["shell_command" :handler])
            {"command" "ls -lh"}
            {:db {:workspace-folders [{:uri (h/file-uri "file:///project/foo") :name "foo"}]}})))))
  (testing "command succeeds"
    (is (match?
         {:contents [{:type :text
                      :error false
                      :content "Some text"}]}
         (with-redefs [fs/exists? (constantly true)
                       shell/sh (constantly {:exit 0 :out "Some text" :err "Other text"})]
           ((get-in f.tools.shell/definitions ["shell_command" :handler])
            {"command" "ls -lh"}
            {:db {:workspace-folders [{:uri (h/file-uri "file:///project/foo") :name "foo"}]}})))))
  (testing "command succeeds with different working directory"
    (is (match?
         {:contents [{:type :text
                      :error false
                      :content "Some text"}]}
         (with-redefs [fs/exists? (constantly true)
                       shell/sh (constantly {:exit 0 :out "Some text" :err "Other text"})]
           ((get-in f.tools.shell/definitions ["shell_command" :handler])
            {"command" "ls -lh"
             "working_directory" (h/file-path "/project/foo/src")}
            {:db {:workspace-folders [{:uri (h/file-uri "file:///project/foo") :name "foo"}]}})))))
  (testing "command does not fails if not in excluded config"
    (is (match?
         {:contents [{:type :text
                      :error false
                      :content "Some text"}]}
         (with-redefs [fs/exists? (constantly true)
                       shell/sh (constantly {:exit 0 :out "Some text" :err "Other text"})]
           ((get-in f.tools.shell/definitions ["shell_command" :handler])
            {"command" "rm -r /project/foo/src"}
            {:db {:workspace-folders [{:uri (h/file-uri "file:///project/foo") :name "foo"}]}
             :config {:nativeTools {:shell {:enabled true
                                            :excludeCommands ["ls" "cd"]}}}})))))
  (testing "command fails if in excluded config"
    (is (match?
         {:contents [{:type :text
                      :error true
                      :content "Cannot run command 'rm' because it is excluded by eca config."}]}
         (with-redefs [fs/exists? (constantly true)]
           ((get-in f.tools.shell/definitions ["shell_command" :handler])
            {"command" "rm -r /project/foo/src"}
            {:db {:workspace-folders [{:uri (h/file-uri "file:///project/foo") :name "foo"}]}
             :config {:nativeTools {:shell {:enabled true
                                            :excludeCommands ["ls" "rm"]}}}}))))))
