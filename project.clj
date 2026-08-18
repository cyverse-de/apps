(require '[clojure.java.shell :refer (sh)]
         '[clojure.string :as string])

(defn git-ref
  []
  (or (System/getenv "GIT_COMMIT")
      (string/trim (:out (sh "git" "rev-parse" "HEAD")))
      ""))

(defproject org.cyverse/apps "3.0.3-SNAPSHOT"
  :description "Framework for hosting DiscoveryEnvironment metadata services."
  :url "https://github.com/cyverse-de/apps"
  :license {:name "BSD"
            :url "https://cyverse.org/license"}
  :manifest {"Git-Ref" ~(git-ref)}
  :uberjar-name "apps-standalone.jar"
  ;; Fail the build on a new dependency conflict rather than printing a
  ;; warning nobody reads.
  :pedantic? :abort
  ;; Records versions Leiningen already resolves, read off the resolved
  ;; classpath rather than copied from lein's "Consider using these
  ;; :managed-dependencies" hint -- that hint names the version that LOST the
  ;; conflict, so pasting it would be a silent upgrade.
  ;;
  ;; The jackson-* group is pinned to the coherent 2.17.2 family that main
  ;; resolves. It needs stating explicitly: compojure-api 1.1.14 is declared
  ;; directly here and drags cheshire 5.9.0 -> jackson-core 2.9.9, which wins by
  ;; nearest-wins over the newer jackson the org.cyverse libraries bring
  ;; transitively. Left alone, that pairs jackson-core 2.9.9 with databind
  ;; 2.18.3 -- an eight-minor spread that surfaces as a runtime
  ;; NoSuchMethodError, not a resolution failure, and that :pedantic? cannot see
  ;; because each artifact is individually unambiguous.
  :managed-dependencies [[com.fasterxml.jackson.core/jackson-annotations "2.17.2"]
                         [com.fasterxml.jackson.core/jackson-core "2.17.2"]
                         [com.fasterxml.jackson.core/jackson-databind "2.17.2"]
                         [com.fasterxml.jackson.dataformat/jackson-dataformat-cbor "2.17.2"]
                         [com.fasterxml.jackson.dataformat/jackson-dataformat-smile "2.17.2"]
                         [cheshire "5.9.0"]
                         [com.google.code.findbugs/jsr305 "1.3.9"]
                         [commons-codec "1.16.1"]
                         [prismatic/schema "1.1.12"]
                         [riddley "0.1.12"]
                         [ring/ring-codec "1.1.0"]
                         [tigris "0.1.1"]]
  :dependencies [[org.clojure/clojure "1.12.5"]
                 [clj-http "3.13.1"]
                 [com.cemerick/url "0.1.1" :exclusions [com.cemerick/clojurescript.test]]
                 [com.google.guava/guava "23.0"]
                 [com.github.seancorfield/honeysql "2.7.1437"]
                 [dev.weavejester/medley "1.10.0"]
                 [metosin/compojure-api "1.1.14"]
                 [org.cyverse/async-tasks-client "0.0.6"]
                 [org.cyverse/authy "3.0.2"]
                 [org.cyverse/clj-kondo-exports "0.1.1"]
                 [org.cyverse/clojure-commons "3.0.13"]
                 [org.cyverse/debug-utils "2.9.1"]
                 [org.cyverse/kameleon "3.0.11"]
                 [org.cyverse/mescal "4.1.1"]
                 [org.cyverse/metadata-client "3.2.2"]
                 [org.cyverse/common-cli "2.8.3"]
                 [org.cyverse/common-cfg "2.8.4"]
                 [org.cyverse/common-swagger-api "3.4.23"]
                 [org.cyverse/cyverse-groups-client "0.1.10"]
                 [org.cyverse/permissions-client "2.8.6"]
                 [org.cyverse/service-logging "2.8.6"]
                 [org.flatland/ordered "1.15.12"]
                 [io.github.clj-kondo/config-slingshot-slingshot "1.0.0"]
                 [me.raynes/fs "1.4.6"]
                 [mvxcvi/clj-pgp "1.1.0"] ; can't use 1.1.1 due to random decryption exceptions
                 [pandect "1.0.2"]
                 [ring/ring-jetty-adapter "1.12.2"]]
  :eastwood {:exclude-namespaces [apps.protocols :test-paths]
             :linters [:wrong-arity :wrong-ns-form :wrong-pre-post :wrong-tag :misplaced-docstrings]}
  :plugins [[jonase/eastwood "1.4.3"]
            [lein-ancient "1.0.0"]
            [test2junit "1.4.4"]]
  ;; cider-nrepl and refactor-nrepl are editor tooling, not build tooling. In
  ;; top-level :plugins they were on every invocation's classpath; they belong
  ;; in :repl (or a personal ~/.lein/profiles.clj), which is where they now live.
  ;;
  ;; clj-kondo and cljfmt each sit in their own profile because their dependency
  ;; trees are internally inconsistent -- clj-kondo pulls Clojure 1.11.4 while
  ;; its sci dependency pulls 1.12.0, and cljfmt's tree disagrees with
  ;; test2junit's. Both trip :pedantic? :abort on conflicts that live entirely
  ;; inside third-party plugins and never reach the runtime classpath.
  ;; Lint with `lein with-profile +kondo clj-kondo`, format with
  ;; `lein with-profile +cljfmt cljfmt check`.
  :profiles {:dev    {:plugins        [[lein-ring "0.12.6"]]
                      :resource-paths ["conf/test"]}
             :kondo  {:plugins [[com.github.clj-kondo/lein-clj-kondo "2026.08.04"]]
                      :pedantic? :warn}
             :cljfmt {:plugins [[dev.weavejester/lein-cljfmt "0.16.5"]]
                      :pedantic? :warn}
             :repl   {:source-paths ["repl"]
                      :plugins [[cider/cider-nrepl "0.62.2"]
                                [refactor-nrepl/refactor-nrepl "3.14.0"]]
                      :pedantic? :warn}
             :uberjar {:aot :all}}
  :repl-options {:timeout 120000}
  :main ^:skip-aot apps.core
  :ring {:handler apps.routes/app
         :init apps.core/load-config-from-file
         :port 31323}
  :uberjar-exclusions [#"(?i)META-INF/[^/]*[.](SF|DSA|RSA)"]
  :jvm-opts ["-Dlogback.configurationFile=/etc/iplant/de/logging/apps-logging.xml"])
