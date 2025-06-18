(ns apps.test-fixtures
  (:require
   [apps.user :as user]
   [apps.util.config :as config]
   [korma.db :refer [create-db default-connection]]))

(def default-config-path "/etc/iplant/de/apps.properties")
(def default-db-uri "jdbc:postgresql://dedb/de?user=de&password=notprod")

(defn getenv [name default]
  (or (System/getenv name) default))

(defn with-config [f]
  (let [config-path (getenv "APPS_CONFIG_PATH" default-config-path)]
    (require 'apps.util.config :reload)
    (apps.util.config/load-config-from-file config-path {:log-config? false})
    (f)))

(defn with-test-db [f]
  (default-connection (create-db {:connection-uri (or (System/getenv "DBURI") default-db-uri)}))
  (f))

(defn with-test-user [f]
  (user/with-user [{:user       "ipctest"
                    :email      "ipctest@cyverse.org"
                    :first-name "IPC"
                    :last-name  "Test"}]
    (f)))

(defn run-integration-tests [f]
  (when (System/getenv "RUN_INTEGRATION_TESTS")
    (f)))
