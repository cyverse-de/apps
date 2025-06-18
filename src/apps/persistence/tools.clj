(ns apps.persistence.tools
  (:require [apps.constants :as c]
            [apps.persistence.app-metadata :refer [get-integration-data
                                                   get-integration-data-by-tool-id
                                                   remove-tool-from-tasks]]
            [apps.persistence.entities :refer [tools
                                               tool_test_data_files
                                               tool_types]]
            [apps.util.assertions :refer [assert-not-nil]]
            [apps.util.conversions :refer [remove-nil-vals]]
            [apps.util.db :refer [transaction]]
            [clojure.string :refer [upper-case blank?]]
            [kameleon.queries :refer [add-query-limit
                                      add-query-offset
                                      add-query-sorting]]
            [kameleon.util.search :refer [format-query-wildcards]]
            [korma.core :as sql]))

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
                     :tool_type_id
                     :interactive]))

(defn- get-tool-type-name
  "Determines the correct tool type name to use for a tool."
  [tool]
  (cond (not (blank? (get-in tool [:container :image :osg_image_path]))) c/osg-tool-type
        (:interactive tool)                                              c/interactive-tool-type
        :else                                                            (:type tool)))

(defn- get-tool-type-id-for-tool
  "Gets the tool type ID to use for a tool."
  [tool]
  (when-let [tool-type (get-tool-type-name tool)]
    (:id (first (sql/select tool_types (sql/fields :id) (sql/where {:name tool-type}))))))

(defn- get-tool-data-files
  "Fetches a tool's test data files."
  [tool-id]
  (let [data-files (sql/select tool_test_data_files
                               (sql/fields :input_file :filename)
                               (sql/where {:tool_id tool-id}))
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
  (sql/insert tool_test_data_files (sql/values {:tool_id tool-id
                                                :input_file input-file
                                                :filename filename})))

(defn add-tool
  "Adds a new tool and its test data files to the database"
  [{{:keys [implementor_email implementor test]} :implementation :as tool}]
  (transaction
   (let [integration-data-id (:id (get-integration-data implementor_email implementor))
         tool (-> tool
                  (assoc :integration_data_id integration-data-id
                         :tool_type_id        (get-tool-type-id-for-tool tool))
                  (filter-valid-tool-values))
         tool-id (:id (sql/insert tools (sql/values tool)))]
     (dorun (map (partial add-tool-data-file tool-id true) (:input_files test)))
     (dorun (map (partial add-tool-data-file tool-id false) (:output_files test)))
     tool-id)))

(defn update-tool
  "Updates a tool and its test data files in the database"
  [{tool-id :id
    {:keys [implementor_email implementor test] :as implementation} :implementation
    :as tool}]
  (transaction
   (let [integration-data-id (when implementation
                               (:id (get-integration-data implementor_email implementor)))
         type-id (get-tool-type-id-for-tool tool)
         tool (-> tool
                  (assoc :integration_data_id integration-data-id
                         :tool_type_id        type-id)
                  (dissoc :id)
                  filter-valid-tool-values
                  remove-nil-vals)]
     (when-not (empty? tool)
       (sql/update tools (sql/set-fields tool) (sql/where {:id tool-id})))
     (when-not (empty? test)
       (sql/delete tool_test_data_files (sql/where {:tool_id tool-id}))
       (dorun (map (partial add-tool-data-file tool-id true) (:input_files test)))
       (dorun (map (partial add-tool-data-file tool-id false) (:output_files test)))))))

(defn delete-tool
  [tool-id]
  (transaction
   (remove-tool-from-tasks tool-id)
   (sql/delete tools (sql/where {:id tool-id}))))

(defn- add-listing-where-clause
  [query tool-ids]
  (if tool-ids
    (sql/where query {:tools.id [:in tool-ids]})
    query))

(defn- add-search-where-clauses
  "Adds where clauses to a base tool search query to restrict results to tools that contain the
   given search term in their name or description."
  [base-query search-term]
  (if search-term
    (let [search-term   (format-query-wildcards search-term)
          search-term   (str "%" search-term "%")
          search-clause #(hash-map (sql/sqlfn :lower %)
                                   ['like (sql/sqlfn :lower search-term)])]
      (sql/where base-query
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
    (sql/where base-query {:tool_types.hidden false})
    base-query))

(defn- add-deprecated-tools-clause
  "Adds the clause used to filter out deprecated tools if the tool's image is marked as deprecated."
  [base-query deprecated]
  (if-not (nil? deprecated)
    (sql/where base-query {:container_images.deprecated deprecated})
    base-query))

(defn- tool-listing-base-query
  "Obtains a listing query for tools, with common fields for tool details and listings."
  []
  (-> (sql/select* tools)
      (sql/fields [:tools.id :id]
                  [:tools.name :name]
                  [:tools.description :description]
                  [:tools.location :location]
                  [:tool_types.name :type]
                  [:tools.version :version]
                  [:tools.attribution :attribution]
                  [:tools.restricted :restricted]
                  [:tools.time_limit_seconds :time_limit_seconds]
                  [:tools.interactive :interactive])
      (sql/join tool_types)))

(defn- tool-request-status-subselect
  []
  (sql/subselect [:tool_request_status_codes :trsc]
                 (sql/fields [:trsc.name :status])
                 (sql/join [:tool_request_statuses :trs] {:trs.tool_request_status_code_id :trsc.id})
                 (sql/where {:trs.tool_request_id :tool_requests.id})
                 (sql/order :date_assigned :DESC)
                 (sql/limit 1)))

(defn get-tool-count
  "Counts tools given the parameters. If search-term is not empty, results are limited to tools that
  contain search-term in their name, description, container image name, integrator name, or integrator
  email."
  [{search-term :search :keys [tool-ids deprecated include-hidden] :or {include-hidden false}}]
  (-> (sql/select* tools)
      (sql/fields (sql/raw "count(DISTINCT tools.id) AS total"))
      (sql/join tool_types)
      (sql/join :container_images {:container_images.id :tools.container_images_id})
      (sql/join :integration_data {:integration_data.id :tools.integration_data_id})
      (add-search-where-clauses search-term)
      (add-listing-where-clause tool-ids)
      (add-deprecated-tools-clause deprecated)
      (add-hidden-tool-types-clause include-hidden)
      sql/select
      first
      :total))

(defn get-tool-listing
  "Obtains a listing of tools, with optional search and paging params."
  [{search-term :search :keys [tool-ids deprecated sort-field sort-dir limit offset include-hidden]
    :or {include-hidden false}}]
  (let [sort-field (when sort-field (keyword (str "tools." sort-field)))
        sort-dir (when sort-dir (keyword (upper-case sort-dir)))]
    (-> (tool-listing-base-query)
        (sql/join :container_settings {:container_settings.tools_id :tools.id})
        (sql/join :container_images {:container_images.id :tools.container_images_id})
        (sql/join :integration_data {:integration_data.id :tools.integration_data_id})
        (sql/join :tool_requests {:tool_requests.tool_id :tools.id})
        (sql/fields [:container_images.name             :image_name]
                    [:container_images.tag              :image_tag]
                    [:container_images.deprecated       :image_deprecated]
                    [:container_images.url              :image_url]
                    [:container_settings.entrypoint     :container_entrypoint]
                    [:integration_data.integrator_name  :implementor]
                    [:integration_data.integrator_email :implementor_email]
                    [:tool_requests.id                  :tool_request_id]
                    [(tool-request-status-subselect)    :tool_request_status])
        (add-search-where-clauses search-term)
        (add-listing-where-clause tool-ids)
        (add-deprecated-tools-clause deprecated)
        (add-query-sorting sort-field sort-dir)
        (add-query-limit limit)
        (add-query-offset offset)
        (add-hidden-tool-types-clause include-hidden)
        sql/select)))

(defn get-tool
  "Obtains a tool for the given tool ID, throwing a `not-found` error if the tool doesn't exist."
  [tool-id]
  (->> (sql/select (tool-listing-base-query) (sql/where {:tools.id tool-id}))
       first
       (assert-not-nil [:tool-id tool-id])))

(defn get-tools-by-id
  "Obtains a listing of tools for the given list of IDs."
  [tool-ids]
  (map remove-nil-vals
       (sql/select (tool-listing-base-query) (sql/where {:tools.id [:in tool-ids]}))))

(defn get-tool-ids
  "Obtains a list of all tool IDs."
  []
  (map :id (sql/select tools (sql/fields :id))))
