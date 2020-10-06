(use '[clojure.java.shell :only (sh)])
(require '[clojure.string :as string])

(defn git-ref
  []
  (or (System/getenv "GIT_COMMIT")
      (string/trim (:out (sh "git" "rev-parse" "HEAD")))
      ""))

(defproject org.cyverse/apps "2.15.0-SNAPSHOT"
  :description "Framework for hosting DiscoveryEnvironment metadata services."
  :url "https://github.com/cyverse-de/apps"
  :license {:name "BSD"
            :url "http://iplantcollaborative.org/sites/default/files/iPLANT-LICENSE.txt"}
  :manifest {"Git-Ref" ~(git-ref)}
  :uberjar-name "apps-standalone.jar"
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [clj-http "3.4.1"]
                 [com.cemerick/url "0.1.1" :exclusions [com.cemerick/clojurescript.test]]
                 [com.google.guava/guava "18.0"]
                 [de.ubercode.clostache/clostache "1.4.0"]
                 [medley "1.1.0"]
                 [metosin/compojure-api "1.1.12"]
                 [org.cyverse/authy "2.8.0"]
                 [org.cyverse/clojure-commons "3.0.4"]
                 [org.cyverse/debug-utils "2.8.0"]
                 [org.cyverse/kameleon "3.0.4"]
                 [org.cyverse/mescal "3.1.3"]
                 [org.cyverse/metadata-client "3.1.1"]
                 [org.cyverse/common-cli "2.8.1"]
                 [org.cyverse/common-cfg "2.8.1"]
                 [org.cyverse/common-swagger-api "3.0.5"]
                 [org.cyverse/cyverse-groups-client "0.1.5"]
                 [org.cyverse/permissions-client "2.8.0"]
                 [org.cyverse/service-logging "2.8.0"]
                 [org.cyverse/event-messages "0.0.1"]
                 [org.flatland/ordered "1.5.7"]
                 [com.novemberain/langohr "3.7.0"]
                 [me.raynes/fs "1.4.6"]
                 [mvxcvi/clj-pgp "0.8.0"]
                 [pandect "0.6.1"]
                 [ring/ring-jetty-adapter "1.6.0"]]
  :eastwood {:exclude-namespaces [apps.protocols :test-paths]
             :linters [:wrong-arity :wrong-ns-form :wrong-pre-post :wrong-tag :misplaced-docstrings]}
  :plugins [[lein-cljfmt "0.6.4"]
            [lein-swank "1.4.4"]
            [test2junit "1.1.3"]
            [jonase/eastwood "0.3.5"]]
  :profiles {:dev {:plugins        [[lein-ring "0.12.5"]]
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
