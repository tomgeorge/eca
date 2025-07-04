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
            {:workspace-folders [{:uri (h/file-uri "file:///foo/bar/baz") :name "foo"}]})))))
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
            {:workspace-folders [{:uri (h/file-uri "file:///foo/bar/baz") :name "foo"}]})))))
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
            {:workspace-folders [{:uri (h/file-uri "file:///foo/bar/baz") :name "foo"}]}))))))

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
            {:workspace-folders [{:uri (h/file-uri "file:///foo/bar/baz") :name "foo"}]})))))
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
            {:workspace-folders [{:uri (h/file-uri "file:///foo/bar/baz") :name "foo"}]})))))
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
            {:workspace-folders [{:uri (h/file-uri "file:///foo/bar/baz") :name "foo"}]})))))
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
            {:workspace-folders [{:uri (h/file-uri "file:///foo/bar/baz") :name "foo"}]}))))))

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
            {:workspace-folders [{:uri (h/file-uri "file:///project/foo") :name "foo"}]})))))
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
            {:workspace-folders [{:uri (h/file-uri "file:///project/foo") :name "foo"}]})))))
  (testing "matches with wildcard"
    (is (match?
         {:contents [{:type :text
                      :error false
                      :content (str (h/file-path "/project/foo/bar/baz.txt") "\n"
                                    (h/file-path "/project/foo/qux.txt"))}]}
         (with-redefs [fs/exists? (constantly true)
                       fs/glob (fn [_roo pattern]
                                 (when (= "**" pattern)
                                   [(fs/path (h/file-path "/project/foo/bar/baz.txt"))
                                    (fs/path (h/file-path "/project/foo/qux.txt"))]))]
           ((get-in f.tools.filesystem/definitions ["search_files" :handler])
            {"path" (h/file-path "/project/foo")
             "pattern" "**"}
            {:workspace-folders [{:uri (h/file-uri "file:///project/foo") :name "foo"}]})))))
  (testing "matches without wildcard"
    (is (match?
         {:contents [{:type :text
                      :error false
                      :content (str (h/file-path "/project/foo/bar/baz.txt") "\n"
                                    (h/file-path "/project/foo/qux.txt"))}]}
         (with-redefs [fs/exists? (constantly true)
                       fs/glob (fn [_roo pattern]
                                 (when (= "**/*.txt*" pattern)
                                   [(fs/path (h/file-path "/project/foo/bar/baz.txt"))
                                    (fs/path (h/file-path "/project/foo/qux.txt"))]))]
           ((get-in f.tools.filesystem/definitions ["search_files" :handler])
            {"path" (h/file-path "/project/foo")
             "pattern" ".txt"}
            {:workspace-folders [{:uri (h/file-uri "file:///project/foo") :name "foo"}]}))))))

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
            {:workspace-folders [{:uri (h/file-uri "file:///project/foo") :name "foo"}]})))))
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
            {:workspace-folders [{:uri (h/file-uri "file:///project/foo") :name "foo"}]})))))
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
            {:workspace-folders [{:uri (h/file-uri "file:///project/foo") :name "foo"}]})))))
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
            {:workspace-folders [{:uri (h/file-uri "file:///project/foo") :name "foo"}]})))))
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
            {:workspace-folders [{:uri (h/file-uri "file:///project/foo") :name "foo"}]})))))
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
            {:workspace-folders [{:uri (h/file-uri "file:///project/foo") :name "foo"}]}))))))
