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
  :dependencies [[org.clojure/clojure "1.11.3"]
                 [clj-http "3.13.0"]
                 [com.cemerick/url "0.1.1" :exclusions [com.cemerick/clojurescript.test]]
                 [com.google.guava/guava "23.0"]
                 [com.github.seancorfield/honeysql "2.6.1147"]
                 [medley "1.4.0"]
                 [metosin/compojure-api "1.1.14"]
                 [org.cyverse/async-tasks-client "0.0.5"]
                 [org.cyverse/authy "3.0.1"]
                 [org.cyverse/clj-kondo-exports "0.1.0"]
                 [org.cyverse/clojure-commons "3.0.11"]
                 [org.cyverse/debug-utils "2.9.0"]
                 [org.cyverse/kameleon "3.0.10"]
                 [org.cyverse/mescal "4.1.0"]
                 [org.cyverse/metadata-client "3.1.2"]
                 [org.cyverse/common-cli "2.8.2"]
                 [org.cyverse/common-cfg "2.8.3"]
                 [org.cyverse/common-swagger-api "3.4.17"]
                 [org.cyverse/cyverse-groups-client "0.1.9"]
                 [org.cyverse/permissions-client "2.8.4"]
                 [org.cyverse/service-logging "2.8.4"]
                 [org.flatland/ordered "1.15.12"]
                 [io.github.clj-kondo/config-slingshot-slingshot "1.0.0"]
                 [me.raynes/fs "1.4.6"]
                 [mvxcvi/clj-pgp "1.1.0"] ; can't use 1.1.1 due to random decryption exceptions
                 [pandect "1.0.2"]
                 [ring/ring-jetty-adapter "1.12.2"]]
  :eastwood {:exclude-namespaces [apps.protocols :test-paths]
             :linters [:wrong-arity :wrong-ns-form :wrong-pre-post :wrong-tag :misplaced-docstrings]}
  :plugins [[cider/cider-nrepl "0.45.0"]
            [com.github.clj-kondo/lein-clj-kondo "2025.02.20"]
            [jonase/eastwood "1.4.3"]
            [lein-ancient "0.7.0"]
            [lein-cljfmt "0.9.2"]
            [refactor-nrepl/refactor-nrepl "3.10.0"]
            [test2junit "1.4.4"]]
  :profiles {:dev {:plugins        [[lein-ring "0.12.6"]]
                   :resource-paths ["conf/test"]}
             :repl {:source-paths ["repl"]}
             :uberjar {:aot :all}}
  :repl-options {:timeout 120000}
  :main ^:skip-aot apps.core
  :ring {:handler apps.routes/app
         :init apps.core/load-config-from-file
         :port 31323}
  :uberjar-exclusions [#"(?i)META-INF/[^/]*[.](SF|DSA|RSA)"]
  :jvm-opts ["-Dlogback.configurationFile=/etc/iplant/de/logging/apps-logging.xml"])
