(ns eca.features.tools.filesystem-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [eca.features.tools.filesystem :as f.tools.filesystem]
   [eca.test-helper :as h]
   [matcher-combinators.test :refer [match?]]))

(deftest list-allowed-directories-test
  (is (match?
       {:contents [{:type :text
                    :error false
                    :content (format "Allowed directories:\n%s"
                                     (h/file-path "/foo/bar/baz"))}]}
       ((get-in f.tools.filesystem/definitions ["list_allowed_directories" :handler])
        {}
        {:workspace-folders [{:uri (h/file-uri "file:///foo/bar/baz") :name "baz"}]}))))

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
            {:workspace-folders [{:uri (h/file-uri "file:///foo/bar/baz") :name "baz"}]})))))
  (testing "Unallowed dir"
    (is (match?
         {:contents [{:type :text
                      :error true
                      :content (format "Access denied - path %s outside allowed directories" (h/file-path "/foo/qux"))}]}
         (with-redefs [fs/canonicalize (constantly (h/file-path "/foo/qux"))
                       fs/exists? (constantly true)]
           ((get-in f.tools.filesystem/definitions ["list_directory" :handler])
            {"path" (h/file-path "/foo/qux")}
            {:workspace-folders [{:uri (h/file-uri "file:///foo/bar/baz") :name "baz"}]})))))
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
            {:workspace-folders [{:uri (h/file-uri "file:///foo/bar/baz") :name "baz"}]}))))))

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
            {:workspace-folders [{:uri (h/file-uri "file:///foo/bar/baz") :name "baz"}]})))))
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
            {:workspace-folders [{:uri (h/file-uri "file:///foo/bar/baz") :name "baz"}]}))))))
