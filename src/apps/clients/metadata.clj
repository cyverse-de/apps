(ns apps.clients.metadata
  (:use [slingshot.slingshot :only [throw+]])
  (:require [apps.persistence.categories :as db-categories]
            [apps.util.config :as config]
            [metadata-client.core :as metadata-client]))

(def app-target-type "app")

(defn delete-avus
  [username app-ids avus]
  (metadata-client/delete-avus (config/metadata-client) username [app-target-type] app-ids avus))

(defn filter-by-avus
  [username app-ids avus]
  (metadata-client/filter-by-avus (config/metadata-client) username [app-target-type] app-ids avus))

(defn list-avus
  ([username app-id]
   (metadata-client/list-avus (config/metadata-client) username app-target-type app-id))
  ([username app-id opts]
   (metadata-client/list-avus (config/metadata-client) username app-target-type app-id opts)))

(defn update-avus
  [username app-id body]
  (metadata-client/update-avus (config/metadata-client) username app-target-type app-id body))

(defn set-avus
  [username app-id body]
  (metadata-client/set-avus (config/metadata-client) username app-target-type app-id body))

(defn get-active-hierarchy-version
  [& {:keys [validate] :or {validate true}}]
  (let [version (db-categories/get-active-hierarchy-version)]
    (if (empty? version)
      (when validate
        (throw+ {:type  :clojure-commons.exception/not-found
                 :error "An app hierarchy version has not been set."}))
      version)))

(defn delete-ontology
  [username ontology-version]
  (metadata-client/delete-ontology (config/metadata-client) username ontology-version))

(defn list-ontologies
  [username]
  (metadata-client/list-ontologies (config/metadata-client) username))

(defn list-hierarchies
  [username]
  (metadata-client/list-hierarchies (config/metadata-client) username (get-active-hierarchy-version)))

(defn filter-hierarchies
  [username ontology-version attrs app-id]
  (metadata-client/filter-hierarchies (config/metadata-client)
                                      username
                                      ontology-version
                                      attrs
                                      app-target-type
                                      app-id))

(defn filter-targets-by-ontology-search
  [username category-attrs search-term app-ids & {:keys [validate :or {validate true}]}]
  (if-let [active-hierarchy-version (get-active-hierarchy-version :validate validate)]
    (metadata-client/filter-targets-by-ontology-search (config/metadata-client)
                                                       username
                                                       active-hierarchy-version
                                                       category-attrs
                                                       search-term
                                                       [app-target-type]
                                                       app-ids)
    []))

(defn filter-hierarchy
  [username ontology-version root-iri attr app-ids]
  (metadata-client/filter-hierarchy (config/metadata-client)
                                    username
                                    ontology-version
                                    root-iri
                                    attr
                                    [app-target-type]
                                    app-ids))

(defn filter-hierarchy-targets
  [username ontology-version root-iri attr app-ids]
  (metadata-client/filter-hierarchy-targets (config/metadata-client)
                                            username
                                            ontology-version
                                            root-iri
                                            attr
                                            [app-target-type]
                                            app-ids))

(defn filter-unclassified
  [username ontology-version root-iri attr app-ids]
  (metadata-client/filter-unclassified (config/metadata-client)
                                       username
                                       ontology-version
                                       root-iri
                                       attr
                                       [app-target-type]
                                       app-ids))
