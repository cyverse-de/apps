(ns apps.test-fixtures
  (:use [korma.db :only [create-db default-connection]]
        [korma.core :only [delete where]]
        [slingshot.slingshot :only [throw+]])
  (:require [apps.persistence.categories :as cp]
            [apps.user :as user]
            [apps.util.config :as config]
            [apps.util.service :as service]
            [cemerick.url :as curl]
            [clj-http.client :as http]
            [clojure.java.io :as io]))

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

(defn- find-resource [resource-name]
  (or (io/resource resource-name)
      (throw+ {:message (str "resource " resource-name " not found")})))

(defn with-test-user [f]
  (user/with-user [{:user       "ipctest"
                    :email      "ipctest@cyverse.org"
                    :first-name "IPC"
                    :last-name  "Test"}]
                  (f)))

(defn run-integration-tests [f]
  (when (System/getenv "RUN_INTEGRATION_TESTS")
    (f)))
