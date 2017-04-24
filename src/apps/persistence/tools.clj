(ns apps.persistence.tools
  (:use [apps.persistence.entities :only [tools
                                          tool_test_data_files
                                          tool_types]]
        [apps.persistence.app-metadata :only [get-integration-data
                                              get-integration-data-by-tool-id
                                              remove-tool-from-tasks]]
        [apps.util.assertions :only [assert-not-nil]]
        [apps.util.conversions :only [remove-nil-vals]]
        [clojure.string :only [upper-case]]
        [kameleon.queries :only [add-query-limit
                                 add-query-offset
                                 add-query-sorting]]
        [kameleon.util.search :only [format-query-wildcards]]
        [korma.core :exclude [update]]
        [korma.db :only [transaction]])
  (:require [korma.core :as sql]))

(defn- filter-valid-tool-values
  "Filters valid keys from the given Tool for inserting or updating in the database."
  [tool]
  (select-keys tool [:id
                     :container_images_id
                     :name
                     :description
                     :attribution
                     :location
                     :version
                     :integration_data_id
                     :restricted
                     :time_limit_seconds
                     :tool_type_id]))

(defn- get-tool-type-id
  "Gets the ID of the given tool type name."
  [tool-type]
  (:id (first (select tool_types (fields :id) (where {:name tool-type})))))

(defn- get-tool-data-files
  "Fetches a tool's test data files."
  [tool-id]
  (let [data-files (select tool_test_data_files
                           (fields :input_file :filename)
                           (where {:tool_id tool-id}))
        [input-files output-files] ((juxt filter remove) :input_file data-files)]
    {:input_files  (map :filename input-files)
     :output_files (map :filename output-files)}))

(defn get-tool-implementation-details
  "Fetches a tool's implementation details."
  [tool-id]
  (let [{:keys [integrator_name integrator_email]} (get-integration-data-by-tool-id tool-id)
        tool-data-files (get-tool-data-files tool-id)]
    {:implementor_email integrator_email
     :implementor       integrator_name
     :test              tool-data-files}))

(defn- add-tool-data-file
  "Adds a tool's test data files to the database"
  [tool-id input-file filename]
  (insert tool_test_data_files (values {:tool_id tool-id
                                        :input_file input-file
                                        :filename filename})))

(defn add-tool
  "Adds a new tool and its test data files to the database"
  [{type :type {:keys [implementor_email implementor test]} :implementation :as tool}]
  (transaction
    (let [integration-data-id (:id (get-integration-data implementor_email implementor))
          tool (-> tool
                   (assoc :integration_data_id integration-data-id
                          :tool_type_id (get-tool-type-id type))
                   (filter-valid-tool-values))
          tool-id (:id (insert tools (values tool)))]
      (dorun (map (partial add-tool-data-file tool-id true) (:input_files test)))
      (dorun (map (partial add-tool-data-file tool-id false) (:output_files test)))
      tool-id)))

(defn update-tool
  "Updates a tool and its test data files in the database"
  [{tool-id :id
    type :type
    {:keys [implementor_email implementor test] :as implementation} :implementation
    :as tool}]
  (transaction
    (let [integration-data-id (when implementation
                                (:id (get-integration-data implementor_email implementor)))
          type-id (when type (get-tool-type-id type))
          tool (-> tool
                   (assoc :integration_data_id integration-data-id
                          :tool_type_id        type-id)
                   (dissoc :id)
                   filter-valid-tool-values
                   remove-nil-vals)]
      (when-not (empty? tool)
        (sql/update tools (set-fields tool) (where {:id tool-id})))
      (when-not (empty? test)
        (delete tool_test_data_files (where {:tool_id tool-id}))
        (dorun (map (partial add-tool-data-file tool-id true) (:input_files test)))
        (dorun (map (partial add-tool-data-file tool-id false) (:output_files test)))))))

(defn delete-tool
  [tool-id]
  (transaction
    (remove-tool-from-tasks tool-id)
    (delete tools (where {:id tool-id}))))

(defn- add-listing-where-clause
  [query tool-ids]
  (if (empty? tool-ids)
    query
    (where query {:tools.id [in tool-ids]})))

(defn- add-search-where-clauses
  "Adds where clauses to a base tool search query to restrict results to tools that contain the
   given search term in their name or description."
  [base-query search-term]
  (if search-term
    (let [search-term   (format-query-wildcards search-term)
          search-term   (str "%" search-term "%")
          search-clause #(hash-map (sqlfn lower %)
                                   ['like (sqlfn lower search-term)])]
      (where base-query
             (or
               (search-clause :tools.name)
               (search-clause :tools.description)
               (search-clause :container_images.name)
               (search-clause :integration_data.integrator_name)
               (search-clause :integration_data.integrator_email))))
    base-query))

(defn- add-hidden-tool-types-clause
  "Adds the clause used to filter out hidden tool types if hidden tool types are not supposed to
   be included in the result set."
  [base-query include-hidden]
  (if-not include-hidden
    (where base-query {:tool_types.hidden false})
    base-query))

(defn- tool-listing-base-query
  "Obtains a listing query for tools, with common fields for tool details and listings."
  []
  (-> (select* tools)
      (fields [:tools.id :id]
              [:tools.name :name]
              [:tools.description :description]
              [:tools.location :location]
              [:tool_types.name :type]
              [:tools.version :version]
              [:tools.attribution :attribution]
              [:tools.restricted :restricted]
              [:tools.time_limit_seconds :time_limit_seconds])
      (join tool_types)))

(defn get-tool-listing
  "Obtains a listing of tools, with optional search and paging params."
  [{search-term :search :keys [tool-ids sort-field sort-dir limit offset include-hidden]
    :or {include-hidden false}}]
  (let [sort-field (when sort-field (keyword (str "tools." sort-field)))
        sort-dir (when sort-dir (keyword (upper-case sort-dir)))]
    (-> (tool-listing-base-query)
        (join :container_images {:container_images.id :tools.container_images_id})
        (join :integration_data {:integration_data.id :tools.integration_data_id})
        (fields [:container_images.name :image_name]
                [:container_images.tag  :image_tag]
                [:integration_data.integrator_name  :implementor]
                [:integration_data.integrator_email :implementor_email])
        (add-search-where-clauses search-term)
        (add-listing-where-clause tool-ids)
        (add-query-sorting sort-field sort-dir)
        (add-query-limit limit)
        (add-query-offset offset)
        (add-hidden-tool-types-clause include-hidden)
        select)))

(defn get-tool
  "Obtains a tool for the given tool ID, throwing a `not-found` error if the tool doesn't exist."
  [tool-id]
  (->> (select (tool-listing-base-query) (where {:tools.id tool-id}))
       first
       (assert-not-nil [:tool-id tool-id])))

(defn get-tools-by-id
  "Obtains a listing of tools for the given list of IDs."
  [tool-ids]
  (map remove-nil-vals
       (select (tool-listing-base-query) (where {:tools.id [in tool-ids]}))))
