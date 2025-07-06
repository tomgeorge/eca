(ns eca.features.tools.filesystem-test
  (:require
   [babashka.fs :as fs]
   [clojure.java.shell :as shell]
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [eca.features.tools.filesystem :as f.tools.filesystem]
   [eca.features.tools.util :as tools.util]
   [eca.test-helper :as h]
   [matcher-combinators.test :refer [match?]])
  (:import
   [java.io ByteArrayInputStream]))

(deftest list-directory-test
  (testing "Invalid path"
    (is (match?
         {:contents [{:type :text
                      :error true
                      :content (str (h/file-path "/foo/qux") " is not a valid path")}]}
         (with-redefs [fs/canonicalize (constantly (h/file-path "/foo/qux"))
                       fs/exists? (constantly false)]
           ((get-in f.tools.filesystem/definitions ["list_directory" :handler])
            {"path" (h/file-path "/foo/qux")}
            {:db {:workspace-folders [{:uri (h/file-uri "file:///foo/bar/baz") :name "foo"}]}})))))
  (testing "Unallowed dir"
    (is (match?
         {:contents [{:type :text
                      :error true
                      :content (format "Access denied - path %s outside allowed directories: %s"
                                       (h/file-path "/foo/qux")
                                       (h/file-path "/foo/bar/baz"))}]}
         (with-redefs [fs/canonicalize (constantly (h/file-path "/foo/qux"))
                       fs/exists? (constantly true)]
           ((get-in f.tools.filesystem/definitions ["list_directory" :handler])
            {"path" (h/file-path "/foo/qux")}
            {:db {:workspace-folders [{:uri (h/file-uri "file:///foo/bar/baz") :name "foo"}]}})))))
  (testing "allowed dir"
    (is (match?
         {:contents [{:type :text
                      :error false
                      :content (format (str "[FILE] %s\n"
                                            "[DIR] %s\n")
                                       (h/file-path "/foo/bar/baz/some.clj")
                                       (h/file-path "/foo/bar/baz/qux"))}]}
         (with-redefs [fs/exists? (constantly true)
                       fs/starts-with? (constantly true)
                       fs/list-dir (constantly [(fs/path (h/file-path "/foo/bar/baz/some.clj"))
                                                (fs/path (h/file-path "/foo/bar/baz/qux"))])
                       fs/directory? (fn [path] (not (string/ends-with? (str path) ".clj")))
                       fs/canonicalize (constantly (h/file-path "/foo/bar/baz"))]
           ((get-in f.tools.filesystem/definitions ["list_directory" :handler])
            {"path" (h/file-path "/foo/bar/baz")}
            {:db {:workspace-folders [{:uri (h/file-uri "file:///foo/bar/baz") :name "foo"}]}}))))))

(deftest read-file-test
  (testing "Not readable path"
    (is (match?
         {:contents [{:type :text
                      :error true
                      :content (format "File %s is not readable" (h/file-path "/foo/qux"))}]}
         (with-redefs [fs/exists? (constantly true)
                       fs/readable? (constantly false)
                       f.tools.filesystem/allowed-path? (constantly true)]
           ((get-in f.tools.filesystem/definitions ["read_file" :handler])
            {"path" (h/file-path "/foo/qux")}
            {:db {:workspace-folders [{:uri (h/file-uri "file:///foo/bar/baz") :name "foo"}]}})))))
  (testing "Readable path"
    (is (match?
         {:contents [{:type :text
                      :error false
                      :content "fooo"}]}
         (with-redefs [slurp (constantly "fooo")
                       fs/exists? (constantly true)
                       fs/readable? (constantly true)
                       f.tools.filesystem/allowed-path? (constantly true)]
           ((get-in f.tools.filesystem/definitions ["read_file" :handler])
            {"path" (h/file-path "/foo/qux")}
            {:db {:workspace-folders [{:uri (h/file-uri "file:///foo/bar/baz") :name "foo"}]}})))))
  (testing "heading a file"
    (is (match?
         {:contents [{:type :text
                      :error false
                      :content "fooo\nbar"}]}
         (with-redefs [slurp (constantly "fooo\nbar\nbaz")
                       fs/exists? (constantly true)
                       fs/readable? (constantly true)
                       f.tools.filesystem/allowed-path? (constantly true)]
           ((get-in f.tools.filesystem/definitions ["read_file" :handler])
            {"path" (h/file-path "/foo/qux")
             "head" 2}
            {:db {:workspace-folders [{:uri (h/file-uri "file:///foo/bar/baz") :name "foo"}]}})))))
  (testing "tailling a file"
    (is (match?
         {:contents [{:type :text
                      :error false
                      :content "bar\nbaz"}]}
         (with-redefs [slurp (constantly "fooo\nbar\nbaz")
                       fs/exists? (constantly true)
                       fs/readable? (constantly true)
                       f.tools.filesystem/allowed-path? (constantly true)]
           ((get-in f.tools.filesystem/definitions ["read_file" :handler])
            {"path" (h/file-path "/foo/qux")
             "tail" 2}
            {:db {:workspace-folders [{:uri (h/file-uri "file:///foo/bar/baz") :name "foo"}]}}))))))

(deftest write-file-test
  (testing "Not allowed path"
    (is (match?
         {:contents [{:type :text
                      :error true
                      :content (format "Access denied - path %s outside allowed directories: %s"
                                       (h/file-path "/foo/qux/new_file.clj")
                                       (h/file-path "/foo/bar"))}]}
         (with-redefs [f.tools.filesystem/allowed-path? (constantly false)]
           ((get-in f.tools.filesystem/definitions ["write_file" :handler])
            {"path" (h/file-path "/foo/qux/new_file.clj")}
            {:db {:workspace-folders [{:uri (h/file-uri "file:///foo/bar") :name "bar"}]}}))))))

(deftest search-files-test
  (testing "invalid pattern"
    (is (match?
          {:contents [{:type :text
                       :error true
                       :content "Invalid glob pattern ' '"}]}
          (with-redefs [fs/exists? (constantly true)]
            ((get-in f.tools.filesystem/definitions ["search_files" :handler])
             {"path" (h/file-path "/project/foo")
              "pattern" " "}
             {:db {:workspace-folders [{:uri (h/file-uri "file:///project/foo") :name "foo"}]}})))))
  (testing "no matches"
    (is (match?
          {:contents [{:type :text
                       :error false
                       :content "No matches found"}]}
          (with-redefs [fs/exists? (constantly true)
                        fs/glob (constantly [])]
            ((get-in f.tools.filesystem/definitions ["search_files" :handler])
             {"path" (h/file-path "/project/foo")
              "pattern" "foo"}
             {:db {:workspace-folders [{:uri (h/file-uri "file:///project/foo") :name "foo"}]}})))))
  (testing "matches with wildcard"
    (is (match?
          {:contents [{:type :text
                       :error false
                       :content (str (h/file-path "/project/foo/bar/baz.txt") "\n"
                                     (h/file-path "/project/foo/qux.txt") "\n"
                                     (h/file-path "/project/foo/qux.clj"))}]}
          (with-redefs [fs/exists? (constantly true)
                        fs/glob (constantly [(fs/path (h/file-path "/project/foo/bar/baz.txt"))
                                             (fs/path (h/file-path "/project/foo/qux.txt"))
                                             (fs/path (h/file-path "/project/foo/qux.clj"))])]
            ((get-in f.tools.filesystem/definitions ["search_files" :handler])
             {"path" (h/file-path "/project/foo")
              "pattern" "**"}
             {:db {:workspace-folders [{:uri (h/file-uri "file:///project/foo") :name "foo"}]}})))))
  (testing "matches without wildcard"
    (is (match?
          {:contents [{:type :text
                       :error false
                       :content (str (h/file-path "/project/foo/bar/baz.txt") "\n"
                                     (h/file-path "/project/foo/qux.txt"))}]}
          (with-redefs [fs/exists? (constantly true)
                        fs/glob (constantly [(fs/path (h/file-path "/project/foo/bar/baz.txt"))
                                             (fs/path (h/file-path "/project/foo/qux.txt"))])]
            ((get-in f.tools.filesystem/definitions ["search_files" :handler])
             {"path" (h/file-path "/project/foo")
              "pattern" ".txt"}
             {:db {:workspace-folders [{:uri (h/file-uri "file:///project/foo") :name "foo"}]}}))))))

(deftest grep-test
  (testing "invalid pattern"
    (is (match?
         {:contents [{:type :text
                      :error true
                      :content "Invalid content regex pattern ' '"}]}
         (with-redefs [fs/exists? (constantly true)
                       fs/readable? (constantly true)]
           ((get-in f.tools.filesystem/definitions ["grep" :handler])
            {"path" (h/file-path "/project/foo")
             "pattern" " "}
            {:db {:workspace-folders [{:uri (h/file-uri "file:///project/foo") :name "foo"}]}})))))
  (testing "invalid include"
    (is (match?
         {:contents [{:type :text
                      :error true
                      :content "Invalid file pattern ' '"}]}
         (with-redefs [fs/exists? (constantly true)
                       fs/readable? (constantly true)]
           ((get-in f.tools.filesystem/definitions ["grep" :handler])
            {"path" (h/file-path "/project/foo")
             "pattern" ".*"
             "include" " "}
            {:db {:workspace-folders [{:uri (h/file-uri "file:///project/foo") :name "foo"}]}})))))
  (testing "no files found"
    (is (match?
         {:contents [{:type :text
                      :error false
                      :content "No files found for given pattern"}]}
         (with-redefs [fs/exists? (constantly true)
                       fs/readable? (constantly true)
                       tools.util/command-available? (fn [command & _args] (= "rg" command))
                       shell/sh (constantly {:out ""})]
           ((get-in f.tools.filesystem/definitions ["grep" :handler])
            {"path" (h/file-path "/project/foo")
             "pattern" ".*"}
            {:db {:workspace-folders [{:uri (h/file-uri "file:///project/foo") :name "foo"}]}})))))
  (testing "ripgrep search"
    (is (match?
         {:contents [{:type :text
                      :error false
                      :content "/project/foo/bla.txt\n/project/foo/qux.txt"}]}
         (with-redefs [fs/exists? (constantly true)
                       fs/readable? (constantly true)
                       tools.util/command-available? (fn [command & _args] (= "rg" command))
                       shell/sh (constantly {:out "/project/foo/bla.txt\n/project/foo/qux.txt"})]
           ((get-in f.tools.filesystem/definitions ["grep" :handler])
            {"path" (h/file-path "/project/foo")
             "pattern" "some-cool-content"}
            {:db {:workspace-folders [{:uri (h/file-uri "file:///project/foo") :name "foo"}]}})))))
  (testing "grep search"
    (is (match?
         {:contents [{:type :text
                      :error false
                      :content "/project/foo/bla.txt\n/project/foo/qux.txt"}]}
         (with-redefs [fs/exists? (constantly true)
                       fs/readable? (constantly true)
                       tools.util/command-available? (fn [command & _args] (= "grep" command))
                       shell/sh (constantly {:out "/project/foo/bla.txt\n/project/foo/qux.txt"})]
           ((get-in f.tools.filesystem/definitions ["grep" :handler])
            {"path" (h/file-path "/project/foo")
             "pattern" "some-cool-content"}
            {:db {:workspace-folders [{:uri (h/file-uri "file:///project/foo") :name "foo"}]}})))))
  (testing "java grep search"
    (is (match?
         {:contents [{:type :text
                      :error false
                      :content (h/file-path "/project/foo/bla.txt")}]}
         (with-redefs [fs/exists? (constantly true)
                       fs/readable? (constantly true)
                       tools.util/command-available? (constantly false)
                       shell/sh (constantly {:out "/project/foo/bla.txt\n/project/foo/qux.txt"})
                       fs/list-dir (constantly [(fs/path (h/file-path "/project/foo/bla.txt"))])
                       fs/canonicalize identity
                       fs/directory? (constantly false)
                       fs/hidden? (constantly false)
                       fs/file (constantly (ByteArrayInputStream. (.getBytes "some-cool-content")))]
           ((get-in f.tools.filesystem/definitions ["grep" :handler])
            {"path" (h/file-path "/project/foo")
             "pattern" "some-cool-content"}
            {:db {:workspace-folders [{:uri (h/file-uri "file:///project/foo") :name "foo"}]}}))))))

(deftest replace-in-file-test
  (testing "Not readable path"
    (is (match?
         {:contents [{:type :text
                      :error true
                      :content (format "File %s is not readable" (h/file-path "/foo/qux"))}]}
         (with-redefs [fs/exists? (constantly true)
                       fs/readable? (constantly false)
                       f.tools.filesystem/allowed-path? (constantly true)]
           ((get-in f.tools.filesystem/definitions ["replace_in_file" :handler])
            {"path" (h/file-path "/foo/qux")
             "original_content" "some-cool-text"
             "new_content" "another-boring-text"}
            {:db {:workspace-folders [{:uri (h/file-uri "file:///foo/bar/baz") :name "foo"}]}})))))
  (testing "original content not found"
    (is (match?
         {:contents [{:type :text
                      :error false
                      :content (format "Original content not found in %s" (h/file-path "/project/foo/my-file.txt"))}]}
         (with-redefs [fs/exists? (constantly true)
                       fs/readable? (constantly true)
                       f.tools.filesystem/allowed-path? (constantly true)
                       slurp (constantly "Hey, here is some-cool-text in this file!")]
           ((get-in f.tools.filesystem/definitions ["replace_in_file" :handler])
            {"path" (h/file-path "/project/foo/my-file.txt")
             "original_content" "other-cool-text"
             "new_content" "another-boring-text"}
            {:db {:workspace-folders [{:uri (h/file-uri "file:///project/foo") :name "foo"}]}})))))
  (testing "original content found and replaced first"
    (let [file-content* (atom {})]
      (is (match?
           {:contents [{:type :text
                        :error false
                        :content (format "Successfully replaced content in %s." (h/file-path "/project/foo/my-file.txt"))}]}
           (with-redefs [fs/exists? (constantly true)
                         fs/readable? (constantly true)
                         f.tools.filesystem/allowed-path? (constantly true)
                         slurp (constantly "Hey, here is some-cool-text in this file! here as well: some-cool-text")
                         spit (fn [f content] (swap! file-content* assoc f content))]
             ((get-in f.tools.filesystem/definitions ["replace_in_file" :handler])
              {"path" (h/file-path "/project/foo/my-file.txt")
               "original_content" "some-cool-text"
               "new_content" "another-boring-text"}
              {:db {:workspace-folders [{:uri (h/file-uri "file:///project/foo") :name "foo"}]}}))))
      (is (match?
           {(h/file-path "/project/foo/my-file.txt") "Hey, here is another-boring-text in this file! here as well: some-cool-text"}
           @file-content*))))
  (testing "original content found and replaced all"
    (let [file-content* (atom {})]
      (is (match?
           {:contents [{:type :text
                        :error false
                        :content (format "Successfully replaced content in %s." (h/file-path "/project/foo/my-file.txt"))}]}
           (with-redefs [fs/exists? (constantly true)
                         fs/readable? (constantly true)
                         f.tools.filesystem/allowed-path? (constantly true)
                         slurp (constantly "Hey, here is some-cool-text in this file! here as well: some-cool-text")
                         spit (fn [f content] (swap! file-content* assoc f content))]
             ((get-in f.tools.filesystem/definitions ["replace_in_file" :handler])
              {"path" (h/file-path "/project/foo/my-file.txt")
               "original_content" "some-cool-text"
               "new_content" "another-boring-text"
               "all_occurrences" true}
              {:db {:workspace-folders [{:uri (h/file-uri "file:///project/foo") :name "foo"}]}}))))
      (is (match?
           {(h/file-path "/project/foo/my-file.txt") "Hey, here is another-boring-text in this file! here as well: another-boring-text"}
           @file-content*)))))

(deftest move-file-test
  (testing "Not readable source path"
    (is (match?
         {:contents [{:type :text
                      :error true
                      :content (format "%s is not a valid path" (h/file-path "/foo/qux"))}]}
         (with-redefs [fs/exists? (constantly false)
                       f.tools.filesystem/allowed-path? (constantly true)]
           ((get-in f.tools.filesystem/definitions ["move_file" :handler])
            {"source" (h/file-path "/foo/qux")}
            {:db {:workspace-folders [{:uri (h/file-uri "file:///foo/bar/baz") :name "foo"}]}})))))
  (testing "Destination already exists"
    (is (match?
         {:contents [{:type :text
                      :error true
                      :content (format "Path %s already exists" (h/file-path "/foo/bar/other_file.clj"))}]}
         (with-redefs [fs/exists? (constantly true)
                       f.tools.filesystem/allowed-path? (constantly true)]
           ((get-in f.tools.filesystem/definitions ["move_file" :handler])
            {"source" (h/file-path "/foo/bar/some_file.clj")
             "destination" (h/file-path "/foo/bar/other_file.clj")}
            {:db {:workspace-folders [{:uri (h/file-uri "file:///foo/bar") :name "foo"}]}})))))
  (testing "Move successfully"
    (is (match?
         {:contents [{:type :text
                      :error false
                      :content (format "Successfully moved %s to %s"
                                       (h/file-path "/foo/bar/some_file.clj")
                                       (h/file-path "/foo/bar/other_file.clj"))}]}
         (with-redefs [fs/exists? (fn [path] (not (string/includes? path "other_file.clj")))
                       f.tools.filesystem/allowed-path? (constantly true)
                       fs/move (constantly true)]
           ((get-in f.tools.filesystem/definitions ["move_file" :handler])
            {"source" (h/file-path "/foo/bar/some_file.clj")
             "destination" (h/file-path "/foo/bar/other_file.clj")}
            {:db {:workspace-folders [{:uri (h/file-uri "file:///foo/bar") :name "foo"}]}}))))))
