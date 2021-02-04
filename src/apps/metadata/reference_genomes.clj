(ns apps.metadata.reference-genomes
  (:use [apps.persistence.entities]
        [apps.persistence.users :only [get-user-id]]
        [apps.user :only [current-user]]
        [apps.util.assertions :only [assert-not-nil]]
        [apps.util.conversions :only [date->timestamp]]
        [clojure.string :only [blank?]]
        [korma.core :exclude [update]]
        [slingshot.slingshot :only [throw+]])
  (:require [clojure.tools.logging :as log]
            [clojure-commons.exception-util :as cxu]
            [korma.core :as sql]))

(defn- reference-genome-base-query
  "The base query used to list reference genomes."
  []
  (-> (select* genome_reference)
      (fields :id :name :path :deleted :created_on :last_modified_on
              [:created_by.username :created_by]
              [:last_modified_by.username :last_modified_by])
      (join created_by)
      (join last_modified_by)))

(defn- get-reference-genomes-where
  "A convenience function to look up reference genomes that satisfy a simple set of conditions."
  [conditions]
  (-> (reference-genome-base-query)
      (where conditions)
      select))

(defn get-reference-genomes
  "Lists all of the reference genomes in the database."
  [{:keys [deleted created_by]}]
  (let [query (reference-genome-base-query)
        query (if-not deleted
                (where query {:deleted false})
                query)
        query (if created_by
                (where query {:created_by.username created_by})
                query)]
    (select query)))

(defn get-reference-genomes-by-id
  "Lists all of the reference genomes in the database."
  [& uuids]
  (if (seq uuids)
    (select (reference-genome-base-query)
            (where {:id [in uuids]}))
    (select (reference-genome-base-query))))

(defn list-reference-genomes
  "Lists the reference genomes in the database."
  ([]
   (list-reference-genomes nil))
  ([params]
   (let [reference-genomes (get-reference-genomes params)]
     {:genomes reference-genomes})))

(defn- get-valid-reference-genome
  [reference-genome-id]
  (assert-not-nil [:reference-genome-id reference-genome-id]
                  (first (get-reference-genomes-by-id reference-genome-id))))

(defn- validate-reference-genome-path
  "Verifies that a reference genome with the same path doesn't already exist."
  ([path id]
   (if (seq (get-reference-genomes-where {:path path :id ['not= id]}))
     (cxu/exists "Another reference genome with the given path already exists." :path path)))
  ([path]
   (if (seq (get-reference-genomes-where {:path path}))
     (cxu/exists "A reference genome with the given path already exists." :path path))))

(defn- validate-reference-genome-name
  "Verifies that a reference genome with the same name doesn't already exist."
  ([name id]
   (if (seq (get-reference-genomes-where {:name name :id ['not= id]}))
     (cxu/exists "Another reference genome with the given name already exists." :name name)))
  ([name]
   (if (seq (get-reference-genomes-where {:name name}))
     (cxu/exists "A reference genome with the given name already exists." :name name))))

(defn get-reference-genome
  "Gets a reference genome by its ID."
  [reference-genome-id]
  (get-valid-reference-genome reference-genome-id))

(defn delete-reference-genome
  "Logically deletes a reference genome by setting its 'deleted' flag to true."
  [reference-genome-id {:keys [permanent] :or {permanent false}}]
  (get-valid-reference-genome reference-genome-id)
  (if permanent
    (sql/delete genome_reference (where {:id reference-genome-id}))
    (sql/update genome_reference (set-fields {:deleted true}) (where {:id reference-genome-id})))
  nil)

(defn update-reference-genome
  "Updates the name, path, and deleted flag of a reference genome."
  [{reference-genome-id :id :keys [name path] :as reference-genome}]
  (get-valid-reference-genome reference-genome-id)
  (validate-reference-genome-path path reference-genome-id)
  (validate-reference-genome-name name reference-genome-id)
  (sql/update genome_reference
              (set-fields (assoc (select-keys reference-genome [:name :path :deleted])
                                 :last_modified_by (get-user-id (:username current-user))
                                 :last_modified_on (sqlfn now)))
              (where {:id reference-genome-id}))
  (get-reference-genome reference-genome-id))

(defn add-reference-genome
  "Adds a reference genome with the given name and path."
  [{:keys [name path] :as reference-genome}]
  (let [user-id (get-user-id (:username current-user))]
    (validate-reference-genome-path path)
    (validate-reference-genome-name name)
    (-> (insert genome_reference
                (values {:name             name
                         :path             path
                         :created_by       user-id
                         :last_modified_by user-id
                         :created_on       (sqlfn now)
                         :last_modified_on (sqlfn now)}))
        :id
        get-reference-genome)))
