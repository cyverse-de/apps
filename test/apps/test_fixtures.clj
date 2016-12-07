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
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.tools.logging :as log]
            [metadata-client.core :as metadata-client]))

(def default-config-path "/etc/iplant/de/apps.properties")
(def default-db-uri "jdbc:postgresql://dedb/de?user=de&password=notprod")
(def default-ontology-file "EDAM_1.14.owl")

(defn getenv [name default]
  (or (System/getenv name) default))

(defn with-config [f]
  (let [config-path (getenv "APPS_CONFIG_PATH" default-config-path)]
    (require 'apps.util.config :reload)
    (apps.util.config/load-config-from-file config-path {:log-config? false})
    (metadata-client/with-metadata-base (config/metadata-base) (f))))

(defn with-test-db [f]
  (default-connection (create-db {:connection-uri (or (System/getenv "DBURI") default-db-uri)}))
  (f))

(defn- find-resource [resource-name]
  (or (io/resource resource-name)
      (throw+ {:message (str "resource " resource-name " not found")})))

(defn- load-ontology [username]
  (let [ontology-file     (getenv "APPS_ONTOLOGY_FILE" default-ontology-file)
        ontology-location (find-resource ontology-file)]
    (->> (http/post (str (curl/url (config/metadata-base) "admin" "ontologies"))
                    {:query-params     {:user username}
                     :multipart        [{:part-name "ontology-xml"
                                         :name      ontology-file
                                         :mime-type "application/xml"
                                         :content   (io/file ontology-location)}]
                     :follow-redirects false})
         :body
         service/parse-json
         :version)))

(defn- delete-ontology [username ontology-version]
  (http/delete (str (curl/url (config/metadata-base) "admin" "ontologies" (curl/url-encode ontology-version)))
               {:query-params     {:user username}
                :follow-redirects false}))

(defn with-ontology [f]
  (let [username         "ipctest"
        ontology-version (load-ontology username)]
    (cp/add-hierarchy-version username ontology-version)
    (f)
    (delete :app_hierarchy_version (where {:version ontology-version}))
    (delete-ontology username ontology-version)))

(defn with-test-user [f]
  (user/with-user [{:user       "ipctest"
                    :email      "ipctest@cyverse.org"
                    :first-name "IPC"
                    :last-name  "Test"}]
                  (f)))

(defn run-integration-tests [f]
  (when (System/getenv "RUN_INTEGRATION_TESTS")
    (f)))
