(ns eca.features.index-test
  (:require
   [babashka.fs :as fs]
   [clojure.java.shell :as shell]
   [clojure.test :refer [deftest is testing]]
   [eca.features.index :as f.index]
   [eca.test-helper :as h]
   [matcher-combinators.test :refer [match?]]))

(def gitignore-config
  {:index {:ignoreFiles [{:type :gitignore}]}})

(deftest ignore?-test
  (testing "gitignore type"
    (let [root (h/file-path "/fake/repo")
          file1 (fs/path root "ignored.txt")
          file2 (fs/path root "not-ignored.txt")]
      (testing "returns filtered files when `git ls-files` works"
        (with-redefs [shell/sh (fn [& _args] {:exit 0 :out "not-ignored.txt"})
                      fs/canonicalize #(fs/path root %)]
          (is
           (match?
            [file2]
            (f.index/filter-allowed [file1 file2] root gitignore-config)))))

      (testing "returns all files when `git ls-files` exits non-zero"
        (with-redefs [shell/sh (fn [& _args] {:exit 1})]
          (is
           (match?
            [file2]
            (f.index/filter-allowed [file1 file2] root gitignore-config))))))))

(deftest repo-map-test
  (testing "returns correct tree for a simple git repo"
    (with-redefs [f.index/git-ls-files (constantly ["README.md"
                                                    "src/eca/core.clj"
                                                    "test/eca/core_test.clj"])]
      (is (match?
           {(h/file-path "/fake/repo")
            {"README.md" {}
             "src" {"eca" {"core.clj" {}}}
             "test" {"eca" {"core_test.clj" {}}}}}
           (eca.features.index/repo-map {:workspace-folders [{:uri (h/file-uri "file:///fake/repo")}]})))))
  (testing "returns string tree with as-string? true"
    (with-redefs [f.index/git-ls-files (constantly ["foo.clj" "bar/baz.clj"])]
      (is (= (str (h/file-path "/fake/repo") "\n"
                  " bar\n"
                  "  baz.clj\n"
                  " foo.clj\n")
             (eca.features.index/repo-map {:workspace-folders [{:uri (h/file-uri "file:///fake/repo")}]}
                                          {:as-string? true}))))))
