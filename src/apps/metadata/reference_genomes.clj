(ns apps.metadata.reference-genomes
  (:require [apps.persistence.entities :as entities]
            [apps.persistence.users :refer [get-user-id]]
            [apps.user :refer [current-user]]
            [apps.util.assertions :refer [assert-not-nil]]
            [clojure-commons.exception-util :as cxu]
            [korma.core :as sql]))

(defn- reference-genome-base-query
  "The base query used to list reference genomes."
  []
  (-> (sql/select* entities/genome_reference)
      (sql/fields :id :name :path :deleted :created_on :last_modified_on
                  [:created_by.username :created_by]
                  [:last_modified_by.username :last_modified_by])
      (sql/join entities/created_by)
      (sql/join entities/last_modified_by)))

(defn- get-reference-genomes-where
  "A convenience function to look up reference genomes that satisfy a simple set of conditions."
  [conditions]
  (-> (reference-genome-base-query)
      (sql/where conditions)
      sql/select))

(defn get-reference-genomes
  "Lists all of the reference genomes in the database."
  [{:keys [deleted created_by]}]
  (let [query (reference-genome-base-query)
        query (if-not deleted
                (sql/where query {:deleted false})
                query)
        query (if created_by
                (sql/where query {:created_by.username created_by})
                query)]
    (sql/select query)))

(defn get-reference-genomes-by-id
  "Lists all of the reference genomes in the database."
  [& uuids]
  (if (seq uuids)
    (sql/select (reference-genome-base-query)
                (sql/where {:id [:in uuids]}))
    (sql/select (reference-genome-base-query))))

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
   (when (seq (get-reference-genomes-where {:path path :id ['not= id]}))
     (cxu/exists "Another reference genome with the given path already exists." :path path)))
  ([path]
   (when (seq (get-reference-genomes-where {:path path}))
     (cxu/exists "A reference genome with the given path already exists." :path path))))

(defn- validate-reference-genome-name
  "Verifies that a reference genome with the same name doesn't already exist."
  ([name id]
   (when (seq (get-reference-genomes-where {:name name :id ['not= id]}))
     (cxu/exists "Another reference genome with the given name already exists." :name name)))
  ([name]
   (when (seq (get-reference-genomes-where {:name name}))
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
    (sql/delete entities/genome_reference (sql/where {:id reference-genome-id}))
    (sql/update entities/genome_reference (sql/set-fields {:deleted true}) (sql/where {:id reference-genome-id})))
  nil)

(defn update-reference-genome
  "Updates the name, path, and deleted flag of a reference genome."
  [{reference-genome-id :id :keys [name path] :as reference-genome}]
  (get-valid-reference-genome reference-genome-id)
  (validate-reference-genome-path path reference-genome-id)
  (validate-reference-genome-name name reference-genome-id)
  (sql/update entities/genome_reference
              (sql/set-fields (assoc (select-keys reference-genome [:name :path :deleted])
                                     :last_modified_by (get-user-id (:username current-user))
                                     :last_modified_on (sql/sqlfn :now)))
              (sql/where {:id reference-genome-id}))
  (get-reference-genome reference-genome-id))

(defn add-reference-genome
  "Adds a reference genome with the given name and path."
  [{:keys [name path]}]
  (let [user-id (get-user-id (:username current-user))]
    (validate-reference-genome-path path)
    (validate-reference-genome-name name)
    (-> (sql/insert entities/genome_reference
                    (sql/values {:name             name
                                 :path             path
                                 :created_by       user-id
                                 :last_modified_by user-id
                                 :created_on       (sql/sqlfn :now)
                                 :last_modified_on (sql/sqlfn :now)}))
        :id
        get-reference-genome)))
