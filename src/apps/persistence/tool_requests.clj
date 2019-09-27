(ns apps.persistence.tool-requests
  "Functions for storing and retrieving information about tool requests."
  (:use [korma.core :exclude [update]]
        [korma.db :only [with-db]]))

(def ^:private default-email-template "tool_request_updated")

(defn email-template-for
  "Determines the name of the email template to use for a tool request status code."
  [status]
  (or ((comp :email_template first)
       (select :tool_request_status_codes
               (fields :email_template)
               (where {:name status})))
      default-email-template))

(defn get-tool-request-details
  "Obtains detailed information about a tool request."
  [uuid]
  (first
   (select [:tool_requests :tr]
           (fields :tr.id
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
           (join [:users :requestor]
                 {:tr.requestor_id :requestor.id})
           (join [:tool_architectures :architecture]
                 {:tr.tool_architecture_id :architecture.id})
           (where {:tr.id uuid}))))

(defn get-tool-request-history
  "Obtains detailed information about the history of a tool request."
  [uuid]
  (select [:tool_request_statuses :trs]
          (fields [:trsc.name :status]
                  [:trs.date_assigned :status_date]
                  [:updater.username :updated_by]
                  :trs.comments)
          (join [:tool_requests :tr]
                {:trs.tool_request_id :tr.id})
          (join [:users :updater]
                {:trs.updater_id :updater.id})
          (join [:tool_request_status_codes :trsc]
                {:trs.tool_request_status_code_id :trsc.id})
          (where {:tr.id uuid})
          (order :trs.date_assigned :ASC)))

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
  (subselect [:tool_requests :tr]
             (fields [:tr.id :id]
                     [:tr.tool_name :name]
                     :tr.tool_id
                     [:tr.version :version]
                     [:trsc.name :status]
                     [:trs.date_assigned :status_date]
                     [:updater.username :updated_by]
                     [:requestor.username :requested_by])
             (join [:users :requestor] {:tr.requestor_id :requestor.id})
             (join [:tool_request_statuses :trs] {:tr.id :trs.tool_request_id})
             (join [:tool_request_status_codes :trsc]
                   {:trs.tool_request_status_code_id :trsc.id})
             (join [:users :updater] {:trs.updater_id :updater.id})
             (where-if-defined {:requestor.username user})
             (order :trs.date_assigned :ASC)))

(defn list-tool-requests
  "Lists the tool requests that have been submitted by the user."
  [& {user       :username
      row-offset :offset
      row-limit  :limit
      sort-field :sort-field
      sort-order :sort-order
      statuses   :statuses}]
  (let [status-clause (if (nil? statuses) nil ['in statuses])]
    (select
     [(subselect [(list-tool-requests-subselect user) :req]
                 (fields :id :name :version :requested_by :tool_id
                         [(sqlfn :first :status_date) :date_submitted]
                         [(sqlfn :last :status) :status]
                         [(sqlfn :last :status_date) :date_updated]
                         [(sqlfn :last :updated_by) :updated_by])
                 (group :id :name :version :requested_by :tool_id)
                 (order (or sort-field :date_submitted) (or sort-order :ASC))
                 (limit row-limit)
                 (offset row-offset))
      :reqs]
     (where-if-defined {:status status-clause}))))

(defn get-request-id-for-tool
  [tool-id]
  (-> (select :tool_requests
              (fields :id)
              (where {:tool_id tool-id}))
      first
      :id))

(defn delete-tool-request
  "Removes a tool request from the database."
  [tool-request-id]
  (delete :tool_requests
          (where {:id tool-request-id})))

(defn get-tool-request-status-code
  "Retrieves a tool request status code."
  [id]
  (-> (select* :tool_request_status_codes)
      (fields :id :name :description)
      (where {:id id})
      select first))

(defn- status-code-subselect
  [status-code-id]
  (subselect [:tool_request_statuses :trs]
             (where {:tr.id                           :trs.tool_request_id
                     :trs.tool_request_status_code_id status-code-id})))

(defn count-tool-requests-for-status-code
  "Counts the number of tool requests that have the given status code."
  [status-code-id]
  (-> (select* [:tool_requests :tr])
      (aggregate (count :tr.id) :count)
      (where (exists (status-code-subselect status-code-id)))
      select first :count))

(defn delete-tool-request-status-code
  "Deletes a tool request status code."
  [id]
  (delete :tool_request_status_codes
          (where {:id id})))
