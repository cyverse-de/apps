(ns apps.persistence.tool-requests
  "Functions for storing and retrieving information about tool requests."
  (:require [korma.core :as sql]))

;; Declarations for special symbols used by Korma.
(declare exists count)

(def ^:private default-email-template "tool_request_updated")

(defn email-template-for
  "Determines the name of the email template to use for a tool request status code."
  [status]
  (or ((comp :email_template first)
       (sql/select :tool_request_status_codes
                   (sql/fields :email_template)
                   (sql/where {:name status})))
      default-email-template))

(defn get-tool-request-details
  "Obtains detailed information about a tool request."
  [uuid]
  (first
   (sql/select [:tool_requests :tr]
               (sql/fields :tr.id
                           [:requestor.username :submitted_by]
                           :tr.phone
                           :tr.tool_id
                           [:tr.tool_name :name]
                           :tr.description
                           :tr.source_url
                           [:tr.doc_url :documentation_url]
                           :tr.version
                           :tr.attribution
                           :tr.multithreaded
                           [:architecture.name :architecture]
                           :tr.test_data_path
                           [:tr.instructions :cmd_line]
                           :tr.additional_info
                           :tr.additional_data_file)
               (sql/join [:users :requestor]
                         {:tr.requestor_id :requestor.id})
               (sql/join [:tool_architectures :architecture]
                         {:tr.tool_architecture_id :architecture.id})
               (sql/where {:tr.id uuid}))))

(defn get-tool-request-history
  "Obtains detailed information about the history of a tool request."
  [uuid]
  (sql/select [:tool_request_statuses :trs]
              (sql/fields [:trsc.name :status]
                          [:trs.date_assigned :status_date]
                          [:updater.username :updated_by]
                          :trs.comments)
              (sql/join [:tool_requests :tr]
                        {:trs.tool_request_id :tr.id})
              (sql/join [:users :updater]
                        {:trs.updater_id :updater.id})
              (sql/join [:tool_request_status_codes :trsc]
                        {:trs.tool_request_status_code_id :trsc.id})
              (sql/where {:tr.id uuid})
              (sql/order :trs.date_assigned :ASC)))

(defn- remove-nil-values
  "Removes entries with nil values from a map."
  [m]
  (into {} (remove (fn [[_ v]] (nil? v)) m)))

(defmacro ^:private where-if-defined
  "Adds a where clause to a query, filtering out all conditions for which the value is nil."
  [query clause]
  `(where ~query (remove-nil-values ~clause)))

(defn- list-tool-requests-subselect
  "Creates a subselect query that can be used to list tool requests."
  [user]
  (sql/subselect [:tool_requests :tr]
                 (sql/fields [:tr.id :id]
                             [:tr.tool_name :name]
                             :tr.tool_id
                             [:tr.version :version]
                             [:trsc.name :status]
                             [:trs.date_assigned :status_date]
                             [:updater.username :updated_by]
                             [:requestor.username :requested_by])
                 (sql/join [:users :requestor] {:tr.requestor_id :requestor.id})
                 (sql/join [:tool_request_statuses :trs] {:tr.id :trs.tool_request_id})
                 (sql/join [:tool_request_status_codes :trsc]
                           {:trs.tool_request_status_code_id :trsc.id})
                 (sql/join [:users :updater] {:trs.updater_id :updater.id})
                 (where-if-defined {:requestor.username user})
                 (sql/order :trs.date_assigned :ASC)))

(defn list-tool-requests
  "Lists the tool requests that have been submitted by the user."
  [& {user       :username
      row-offset :offset
      row-limit  :limit
      sort-field :sort-field
      sort-order :sort-order
      statuses   :statuses}]
  (let [status-clause (if (nil? statuses) nil ['in statuses])]
    (sql/select
     [(sql/subselect [(list-tool-requests-subselect user) :req]
                     (sql/fields :id :name :version :requested_by :tool_id
                                 [(sql/sqlfn :first :status_date) :date_submitted]
                                 [(sql/sqlfn :last :status) :status]
                                 [(sql/sqlfn :last :status_date) :date_updated]
                                 [(sql/sqlfn :last :updated_by) :updated_by])
                     (sql/group :id :name :version :requested_by :tool_id)
                     (sql/order (or sort-field :date_submitted) (or sort-order :ASC))
                     (sql/limit row-limit)
                     (sql/offset row-offset))
      :reqs]
     (where-if-defined {:status status-clause}))))

(defn get-request-id-for-tool
  [tool-id]
  (-> (sql/select :tool_requests
                  (sql/fields :id)
                  (sql/where {:tool_id tool-id}))
      first
      :id))

(defn delete-tool-request
  "Removes a tool request from the database."
  [tool-request-id]
  (sql/delete :tool_requests
              (sql/where {:id tool-request-id})))

(defn get-tool-request-status-code
  "Retrieves a tool request status code."
  [id]
  (-> (sql/select* :tool_request_status_codes)
      (sql/fields :id :name :description)
      (sql/where {:id id})
      sql/select first))

(defn- status-code-subselect
  [status-code-id]
  (sql/subselect [:tool_request_statuses :trs]
                 (sql/where {:tr.id                           :trs.tool_request_id
                             :trs.tool_request_status_code_id status-code-id})))

(defn count-tool-requests-for-status-code
  "Counts the number of tool requests that have the given status code."
  [status-code-id]
  (-> (sql/select* [:tool_requests :tr])
      (sql/aggregate (count :tr.id) :count)
      (sql/where (exists (status-code-subselect status-code-id)))
      sql/select first :count))

(defn delete-tool-request-status-code
  "Deletes a tool request status code."
  [id]
  (sql/delete :tool_request_status_codes
              (sql/where {:id id})))
