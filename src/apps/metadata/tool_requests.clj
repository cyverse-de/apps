(ns apps.metadata.tool-requests
  (:require [apps.clients.notifications :as cn]
            [apps.persistence.entities :as entities]
            [apps.persistence.tool-requests :as queries]
            [apps.persistence.users :as users]
            [apps.user :refer [load-user]]
            [apps.util.conversions :refer [remove-nil-vals]]
            [apps.util.db :refer [transaction]]
            [apps.util.params :as params]
            [clojure.string :as string]
            [clojure-commons.exception-util :as cxu]
            [korma.core :as sql]
            [slingshot.slingshot :refer [throw+]])
  (:import [java.util UUID]))

;; Status codes.
(def ^:private initial-status-code "Submitted")
(def ^:private completion-status-code "Completion")

(defn- required-field
  "Extracts a required field from a map."
  [m & ks]
  (let [v (first (remove string/blank? (map m ks)))]
    (when (nil? v)
      (throw+ {:type :clojure-commons.exception/missing-request-field
               :accepted_keys ks}))
    v))

(defn- architecture-name-to-id
  "Gets the internal architecture identifier for an architecture name."
  [architecture]
  (let [id (:id (first (sql/select entities/tool_architectures (sql/where {:name architecture}))))]
    (when (nil? id)
      (throw+ {:type  :clojure-commons.exception/not-found
               :error (str "Could not locate ID for the architecture name: " architecture)}))
    id))

(defn- status-code-subselect
  "Creates a subselect statement to find the primary key of a status code."
  [status-code]
  (sql/subselect entities/tool_request_status_codes
                 (sql/fields :id)
                 (sql/where {:name status-code})))

(defn- handle-new-tool-request
  "Submits a tool request on behalf of the authenticated user."
  [username {:keys [tool_id] :as req}]
  (transaction
   (let [user-id         (users/get-user-id username)
         architecture-id (architecture-name-to-id (:architecture req "Others"))
         uuid            (UUID/randomUUID)]

     (sql/insert entities/tool_requests
                 (sql/values {:phone                (:phone req)
                              :id                   uuid
                              :tool_id              tool_id
                              :tool_name            (required-field req :name)
                              :description          (required-field req :description)
                              :source_url           (required-field req :source_url :source_upload_file)
                              :doc_url              (required-field req :documentation_url)
                              :version              (required-field req :version)
                              :attribution          (:attribution req "")
                              :multithreaded        (:multithreaded req)
                              :test_data_path       (required-field req :test_data_path)
                              :instructions         (required-field req :cmd_line)
                              :additional_info      (:additional_info req)
                              :additional_data_file (:additional_data_file req)
                              :requestor_id         user-id
                              :tool_architecture_id architecture-id}))

     (sql/insert entities/tool_request_statuses
                 (sql/values {:tool_request_id             uuid
                              :tool_request_status_code_id (status-code-subselect initial-status-code)
                              :updater_id                  user-id}))
     uuid)))

(defn- get-tool-req
  "Loads a tool request from the database."
  [uuid]
  (let [req (queries/get-tool-request-details uuid)]
    (when (nil? req)
      (let [msg (str "Could not locate tool request with the following id: " (string/upper-case (.toString uuid)))]
        (throw+ {:type  :clojure-commons.exception/not-found
                 :error msg})))
    req))

(def ^:private verify-tool-req-exists get-tool-req)

(defn- get-most-recent-status
  "Gets the most recent status for a tool request."
  [uuid]
  (let [status ((comp :name first)
                (sql/select [:tool_requests :tr]
                            (sql/fields :trsc.name)
                            (sql/join [:tool_request_statuses :trs]
                                      {:tr.id :trs.tool_request_id})
                            (sql/join [:tool_request_status_codes :trsc]
                                      {:trs.tool_request_status_code_id :trsc.id})
                            (sql/where {:tr.id uuid})
                            (sql/order :trs.date_assigned :DESC)
                            (sql/limit 1)))]
    (when (nil? status)
      (throw+ {:type :clojure-commons.exception/failed-dependency
               :error "no status found for tool request"
               :id (string/upper-case (.toString uuid))}))
    status))

(defn- get-status-code
  "Attempts to retrieve a status code from the database."
  [status-code]
  (first
   (sql/select entities/tool_request_status_codes
               (sql/fields :id :name :description)
               (sql/where {:name status-code}))))

(defn- new-status-code-record
  "Creates a new status code record."
  [status-code]
  {:id          (UUID/randomUUID)
   :name        status-code
   :description status-code})

(defn- add-status-code
  "Adds a new status code."
  [status-code]
  (let [rec (new-status-code-record status-code)]
    (sql/insert entities/tool_request_status_codes (sql/values rec))
    rec))

(defn- load-status-code
  "Gets status code information from the database, adding a new entry if necessary."
  [status-code]
  (or (get-status-code status-code)
      (add-status-code status-code)))

(defn- handle-tool-request-update
  "Updates a tool request."
  [update uuid uid-domain]
  (transaction
   (let [prev-status (get-most-recent-status uuid)
         status      (:status update prev-status)
         status-id   (:id (load-status-code status))
         username    (required-field update :username)
         username    (str (string/replace username #"@.*$" "") "@" uid-domain)
         user-id     (users/get-user-id username)
         comments    (:comments update)
         comments    (when-not (string/blank? comments) comments)]
     (sql/insert entities/tool_request_statuses
                 (sql/values {:tool_request_id             uuid
                              :tool_request_status_code_id status-id
                              :updater_id                  user-id
                              :comments                    comments}))
     uuid)))

(defn- get-tool-request-list
  [params]
  (let [limit      (params/optional-long [:limit] params)
        offset     (params/optional-long [:offset] params)
        sort-field (params/optional-keyword [:sort-field] params)
        sort-order (params/optional-keyword [:sort-dir] params)
        statuses   (params/optional-vector [:status] params)
        username   (params/optional-string [:username] params)]
    (queries/list-tool-requests :username   username
                                :limit      limit
                                :offset     offset
                                :sort-field sort-field
                                :sort-order sort-order
                                :statuses   statuses)))

(defn- format-tool-request-status
  "Formats a single status record for a tool request."
  [req-status]
  (assoc req-status
         :status_date (.getTime (:status_date req-status))
         :comments    (or (:comments req-status) "")))

(defn- get-tool-request-details
  "Retrieves the details of a single tool request from the database."
  [uuid]
  (let [req     (get-tool-req uuid)
        history (map format-tool-request-status (queries/get-tool-request-history uuid))]
    (assoc req :history history)))

(defn get-tool-request
  "Lists the details of a single tool request."
  [uuid]
  (remove-nil-vals (get-tool-request-details uuid)))

(defn delete-tool-request
  "Deletes a tool request."
  [uuid]
  (verify-tool-req-exists uuid)
  (queries/delete-tool-request uuid))

(defn- send-tool-request-notification
  [tool-request user]
  (cn/send-tool-request-notification tool-request user)
  tool-request)

(defn submit-tool-request
  "Submits a tool request on behalf of a user."
  [{:keys [username] :as user} request]
  (-> (handle-new-tool-request username request)
      (get-tool-request)
      (send-tool-request-notification user)))

(defn- send-tool-request-update-notification
  [tool-request]
  (->> (load-user (:submitted_by tool-request))
       (cn/send-tool-request-update-notification tool-request))
  tool-request)

(defn update-tool-request
  "Updates the status of a tool request."
  [uuid uid-domain {:keys [username]} body]
  (-> (assoc body :username username)
      (handle-tool-request-update uuid uid-domain)
      (get-tool-request)
      (send-tool-request-update-notification)))

(defn complete-tool-request
  "Updates the status of a tool request associated with the given tool-id to Completion."
  [tool-id uid-domain user]
  (when-let [request-id (queries/get-request-id-for-tool tool-id)]
    (update-tool-request request-id uid-domain user {:status completion-status-code})))

(defn- format-tool-request-dates
  [{:keys [date_submitted date_updated] :as tool-request}]
  (assoc tool-request
         :date_submitted (.getTime date_submitted)
         :date_updated   (.getTime date_updated)))

(defn list-tool-requests
  "Lists tool requests."
  [params]
  {:tool_requests
   (map (comp remove-nil-vals format-tool-request-dates)
        (get-tool-request-list params))})

(defn- add-filter
  [query field filter]
  (if filter
    (sql/where query {(sql/sqlfn :lower field) [:like (str "%" (string/lower-case filter) "%")]})
    query))

(defn list-tool-request-status-codes
  "Lists the known tool request status codes."
  [{:keys [filter]}]
  {:status_codes
   (-> (sql/select* entities/tool_request_status_codes)
       (sql/fields :id :name :description)
       (sql/order :name :ASC)
       (add-filter :name filter)
       (sql/select))})

(defn delete-tool-request-status-code
  "Deletes a tool request status code as long as it's not referenced by a tool request."
  [status-code-id]
  (when-not (queries/get-tool-request-status-code status-code-id)
    (cxu/not-found (str "could not find tool request status code with id: " status-code-id)))
  (when-not (zero? (queries/count-tool-requests-for-status-code status-code-id))
    (cxu/bad-request (str "status code " status-code-id " is used in one or more tool requests")))
  (queries/delete-tool-request-status-code status-code-id))
