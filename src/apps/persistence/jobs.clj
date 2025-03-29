(ns apps.persistence.jobs
  "Functions for storing and retrieving information about jobs that the DE has
   submitted to any excecution service."
  (:require [apps.constants :as c]
            [apps.persistence.entities :refer [job-status-updates]]
            [apps.persistence.users :refer [get-user-id]]
            [apps.util.config :as config]
            [apps.util.db :as db]
            [cheshire.core :as cheshire]
            [clojure.java.jdbc :as jdbc]
            [clojure.set :as set]
            [clojure.string :as string]
            [clojure-commons.core :refer [remove-nil-values]]
            [clojure-commons.exception-util :as cxu]
            [honey.sql :as hsql]
            [honey.sql.helpers :as h]
            [kameleon.db :refer [now-str]]
            [kameleon.uuids :refer [uuidify]]
            [korma.core :as sql]
            [permissions-client.core :as pc]))

;; Declarations for special symbols used by Korma.
(declare count exists max)

(def de-job-type "DE")
(def agave-job-type "Agave")
(def tapis-job-type "Tapis")
(def interactive-job-type "Interactive")
(def osg-job-type "OSG")

(def de-client-name c/de-system-id)
(def tapis-client-name c/hpc-system-id)
(def interactive-client-name c/interactive-system-id)
(def osg-client-name c/osg-system-id)
(def combined-client-name "combined")

(def pending-status "Pending")
(def canceled-status "Canceled")
(def failed-status "Failed")
(def completed-status "Completed")
(def submitted-status "Submitted")
(def idle-status "Idle")
(def running-status "Running")
(def impending-cancellation-status "ImpendingCancellation")
#_(def running-status-codes #{running-status impending-cancellation-status})
(def completed-status-codes #{canceled-status failed-status completed-status})

(def job-status-order
  {pending-status                0
   submitted-status              1
   idle-status                   2
   running-status                3
   impending-cancellation-status 3
   completed-status              4
   failed-status                 4
   canceled-status               4})

(def job-fields
  [:id
   :job_name
   :job_description
   :job_type_id
   :app_id
   :app_version_id
   :app_name
   :app_description
   :app_wiki_url
   :result_folder_path
   :start_date
   :end_date
   :status
   :deleted
   :user_id
   :notify
   :parent_id])

(def job-step-fields
  [:job_id
   :step_number
   :external_id
   :start_date
   :end_date
   :status
   :job_type_id
   :app_step_number])

(defn valid-status?
  "Determines whether or not the given status is a valid status code in the DE."
  [status]
  (contains? job-status-order status))

(defn status-follows?
  "Determines whether or not the new job status follows the old job status."
  [new-status old-status]
  (> (job-status-order new-status) (job-status-order old-status)))

(defn completed?
  [job-status]
  (completed-status-codes job-status))

(defn running?
  [job-status]
  (= running-status job-status))

(def not-completed? (complement completed?))

(defn- hsql-filter-map->where-clause
  "Returns a map for use in a where-clause for filtering job query results."
  [{:keys [field value]}]
  (case field
    "app_name"  [:like [:lower :j.app_name] [:lower (str "%" value "%")]]
    "name"      [:like [:lower :j.job_name] [:lower (str "%" value "%")]]
    "id"        [:= :j.id (when-not (string/blank? value) (uuidify value))]
    "parent_id" [:= :parent_id (when-not (string/blank? value) (uuidify value))]
    [:= (keyword (str "j." field)) value]))

(defn- hsql-apply-standard-filter
  "Applies 'standard' filters to a query. Standard filters are filters that search for fields that are
   included in the job listing response body."
  [query standard-filter]
  (condp = (clojure.core/count standard-filter)
    0 query
    1 (h/where query (map hsql-filter-map->where-clause standard-filter))
    (h/where query (cons :or (map hsql-filter-map->where-clause standard-filter)))))

(defn- hsql-apply-ownership-filter
  "Applies an 'ownership' filter to a query. An ownership filter is any filter for which the field is
   'ownership'. Only one ownership filter is supported in a single job query. If multiple ownership
   filters are included then the first one wins."
  [query username [ownership-filter & _]]
  (if ownership-filter
    (condp = (:value ownership-filter)
      "all"    query
      "mine"   (h/where query [:= :j.username username])
      "theirs" (h/where query [:not= :j.username username])
      (cxu/bad-request (str "invalid ownership filter value: " (:value ownership-filter))))
    query))

(defn- hsql-apply-type-filter
  "Applies a job type filter to a query. The type filter is not based on the overall type of the job,
   but rather on the types of its steps. A job falls under a specific type if at least one of its
   steps is of that type. Also note that jobs are already filtered by type based on the current DE
   settings. For example, Agave jobs will never be displayed if Agave support is currently disabled
   even if Agave appears in one of the job type filters."
  [query type-filters]
  (if (seq type-filters)
    (let [types (mapv :value type-filters)]
      (h/where query [:exists (-> (h/select :*)
                                  (h/from [:job_steps :s])
                                  (h/join [:job_types :t] [:= :s.job_type_id :t.id])
                                  (h/where [:= :j.id :s.job_id])
                                  (h/where [:in :t.name types]))]))
    query))

(defn- get-filter-type-category
  "Obtains the basic filter type category for a job query filter. The filter type category is dictated
   by the type of clause that needs to be applied in order for the filter to be applied. If a simple
   where clause will do then the filter type will be `standard` otherwise, a custom filter type will
   be used instead. The custom filter types are generally named after the `field` member of the filter."
  [{:keys [field]}]
  (let [custom-filter-fields #{"ownership" "type"}]
    (keyword (or (custom-filter-fields field) "standard"))))

(defn- hsql-add-job-query-filter-clause
  "Filters results returned by the given job query by adding a (sql/where (or ...)) clause based on the
   given filter map."
  [query username query-filter]
  (let [categorized-filters (group-by get-filter-type-category query-filter)]
    (-> (hsql-apply-standard-filter query (:standard categorized-filters))
        (hsql-apply-ownership-filter username (:ownership categorized-filters))
        (hsql-apply-type-filter (:type categorized-filters)))))

(defn- job-type-id-from-system-id [system-id]
  (or ((comp :id first) (sql/select :job_types (sql/where {:system_id system-id})))
      (cxu/bad-request (str "unrecognized system ID: " system-id))))

(defn- get-job-type-id
  "Fetches the primary key for the job type with the given name."
  [job-type]
  (or ((comp :id first) (sql/select :job_types (sql/where {:name job-type})))
      (cxu/bad-request (str "unrecognized job type name: " job-type))))

(defn- save-job-submission
  "Associated a job submission with a saved job in the database."
  [job-id submission]
  (sql/exec-raw ["UPDATE jobs SET submission = CAST ( ? AS json ) WHERE id = ?"
             [(cast Object submission) job-id]]))

(defn- save-job-with-submission
  "Saves information about a job in the database."
  [job-info submission]
  (let [job-info (sql/insert :jobs (sql/values job-info))]
    (save-job-submission (:id job-info) submission)
    job-info))

(defn- job-step-updates
  "Returns a list of all of the job update received for the job step"
  [external-id]
  (sql/select job-status-updates
          (sql/where {:external_id external-id})
          (sql/order :sent_on :DESC)))

(defn- update->date-completed
  [update]
  (let [status (clojure.string/lower-case (:status update))]
    (cond
      (= status "submitted") ""
      (= status "running")   ""
      (= status "completed") (str (:sent_on update))
      (= status "failed")    (str (:sent_on update))
      :else                  (str (:sent_on update)))))

(defn get-job-state
  "Returns a map in the following format:
     {:status \"state\"
      :enddate \"enddate\"}"
  [external-id]
  (let [state (first (job-step-updates external-id))]
    (if state
      {:status  (:state update)
       :enddate (update->date-completed update)}
      {:status "Failed"
       :enddate (now-str)})))

(defn get-job-status
  [job-id]
  (-> (sql/select :job_listings (sql/fields :status) (sql/where {:id job-id}))
      first
      :status))

(defn save-job
  "Saves information about a job in the database."
  [{system-id :system_id username :username :as job-info} submission]
  (-> (select-keys job-info job-fields)
      (assoc :job_type_id (job-type-id-from-system-id system-id)
             :user_id     (get-user-id username))
      remove-nil-values
      (save-job-with-submission (cheshire/encode submission))))

(defn save-job-step
  "Saves a single job step in the database."
  [{job-type :job_type :as job-step-info}]
  (-> (select-keys job-step-info job-step-fields)
      (assoc :job_type_id (get-job-type-id job-type))
      remove-nil-values
      (#(sql/insert :job_steps (sql/values %1)))))

(defn save-multistep-job
  [job-info job-steps submission]
  (save-job job-info submission)
  (dorun (map save-job-step job-steps)))

(defn- hsql-job-type-subselect
  [types]
  (-> (h/select :*)
      (h/from [:job_steps :s])
      (h/join [:job_types :t] [:= :s.job_type_id :t.id])
      (h/where [:= :j.id :s.job_id])
      (h/where [:not-in :t.name types])))

(defn- hsql-internal-app-subselect
  []
  (-> (h/select :*)
      (h/from :apps)
      (h/join :app_versions [:= :apps.id :app_versions.app_id]
              :app_steps [:= :app_versions.id :app_steps.app_version_id]
              :tasks [:= :app_steps.task_id :tasks.id]
              :tools [:= :tasks.tool_id :tools.id]
              :tool_types [:= :tools.tool_type_id :tool_types.id])
      (h/where [:and
                [:= :j.app_id [:cast :apps.id :text]]
                [:= :tool_types.name "internal"]])))

(defn- hsql-add-internal-app-clause
  [query include-hidden?]
  (if-not include-hidden?
    (h/where query [:not [:exists (hsql-internal-app-subselect)]])
    query))

(defn- hsql-accessible-resource-cte
  [subject-ids resource-type min-level]
  (pc/accessible-resource-query-dsl
   (config/permissions-client)
   (db/sql-array "text" subject-ids)
   resource-type min-level))

(defn- hsql-count-jobs-of-types-dsl
  [username filter include-hidden include-deleted types subject-ids]
  (as-> (h/with [:accessible_job_ids (hsql-accessible-resource-cte subject-ids "analysis" "read")]) q
    (h/select q [[:count :*] :count])
    (h/from q [:job_listings :j])
    (h/join q [:accessible_job_ids] [:= :j.id :accessible_job_ids.id])
    (hsql-add-internal-app-clause q include-hidden)
    (hsql-add-job-query-filter-clause q username filter)
    (if-not include-deleted
      (h/where q [:= :j.deleted false])
      q)
    (h/where q [:not [:exists (hsql-job-type-subselect types)]])))

(defn hsql-count-jobs-of-types
  "Counts the number of undeleted jobs of the given types in the database for a user."
  [username filter include-hidden include-deleted types subject-ids]
  (db/with-transaction [tx]
    (let [dsl (hsql-count-jobs-of-types-dsl username filter include-hidden include-deleted types subject-ids)]
      (-> (jdbc/query tx (hsql/format dsl))
          first
          :count))))

(defn hsql-count-jobs-of-statuses-dsl
  "Generates the HoneySQL DSL to count the number of undeleted jobs of the given types grouped by statuses in the
  database for a user."
  [username filter include-hidden include-deleted types subject-ids]
  (-> (hsql-count-jobs-of-types-dsl username filter include-hidden include-deleted types subject-ids)
      (h/select :j.status)
      (h/group-by :j.status)))

(defn hsql-count-jobs-of-statuses
  "Counts the number of undeleted jobs of the given types grouped by statuses in the database for a user."
  [username filter include-hidden include-deleted types subject-ids]
  (db/with-transaction [tx]
    (let [dsl (hsql-count-jobs-of-statuses-dsl username filter include-hidden include-deleted types subject-ids)]
      (jdbc/query tx (hsql/format dsl)))))

(defn- translate-sort-field
  "Translates the sort field sent to get-jobs to a value that can be used in the query."
  [field]
  (case field
    :name      :j.job_name
    :username  :j.username
    :app_name  :j.app_name
    :startdate :j.start_date
    :enddate   :j.end_date
    :status    :j.status
    (cxu/bad-request (str "unrecognized sort field: " (name field)))))

(defn- job-base-query
  "The base query used for retrieving job information from the database."
  []
  (-> (sql/select* [:job_listings :j])
      (sql/fields :j.app_description
              :j.system_id
              :j.app_id
              :j.app_version_id
              :j.app_name
              [:j.job_description :description]
              :j.end_date
              :j.id
              :j.job_name
              :j.result_folder_path
              :j.start_date
              :j.status
              :j.username
              :j.user_id
              :j.app_wiki_url
              :j.job_type
              :j.parent_id
              :j.is_batch
              :j.notify)))

(defn- hsql-job-base-query
  "The HoneySQL version of the base query used for retrieving job information from the database."
  []
  (-> (h/select :j.app_description
                :j.system_id
                :j.app_id
                :j.app_version_id
                :j.app_name
                [:j.job_description :description]
                :j.end_date
                :j.id
                :j.job_name
                :j.result_folder_path
                :j.start_date
                :j.status
                :j.username
                :j.user_id
                :j.app_wiki_url
                :j.job_type
                :j.parent_id
                :j.is_batch
                :j.notify)
      (h/from [:job_listings :j])))

(defn- job-step-base-query
  "The base query used for retrieving job step information from the database."
  []
  (-> (sql/select* [:job_steps :s])
      (sql/join :inner [:job_types :t] {:s.job_type_id :t.id})
      (sql/fields :s.job_id
              :s.step_number
              :s.external_id
              :s.start_date
              :s.end_date
              :s.status
              [:t.name :job_type]
              :s.app_step_number)))

(defn get-job-steps-by-external-id
  "Retrieves all of the job steps with an external identifier."
  [external-id]
  (sql/select (job-step-base-query)
          (sql/where {:s.external_id external-id})))

(defn get-max-step-number
  "Gets the maximum step number for a job."
  [job-id]
  ((comp :max-step first)
   (sql/select :job_steps
           (sql/aggregate (max :step_number) :max-step)
           (sql/where {:job_id job-id}))))

(defn- hsql-add-order
  [query {:keys [sort-field sort-dir]}]
  (h/order-by query [(translate-sort-field sort-field) sort-dir]))

(defn hsql-list-jobs-of-types-dsl
  "Generates the HoneySQL DSL to get a list of jobs that contain only steps of the given types."
  [username {:keys [include-deleted offset limit] :as search-params} types subject-ids]
  (as-> (hsql-job-base-query) q
    (h/with q [:accessible_job_ids (hsql-accessible-resource-cte subject-ids "analysis" "read")])
    (h/join q :accessible_job_ids [:= :j.id :accessible_job_ids.id])
    (hsql-add-job-query-filter-clause q username (:filter search-params))
    (if-not include-deleted
      (h/where q [:= :j.deleted false])
      q)
    (h/where q [:not [:exists (hsql-job-type-subselect types)]])
    (hsql-add-internal-app-clause q (:include-hdden search-params))
    (hsql-add-order q search-params)
    (if (pos? (or offset 0))
      (h/offset q offset)
      q)
    (if (pos? (or limit 0))
      (h/limit q limit)
      q)))

(defn hsql-list-jobs-of-types
  "Gets a list of jobs that contain only steps of the given types."
  [username search-params types subject-ids]
  (db/with-transaction [tx]
    (let [dsl (hsql-list-jobs-of-types-dsl username search-params types subject-ids)]
      (jdbc/query tx (hsql/format dsl)))))

(defn list-jobs-by-id
  "Gets a listing of jobs with the given identifiers."
  [job-ids]
  (-> (sql/select* (job-base-query))
      (sql/where {:j.id [:in (map uuidify job-ids)]})
      (sql/select)))

(defn list-jobs-by-external-id
  "Gets a listing of jobs with the given external identifiers. The where clause may seem a bit odd here because
   the job steps are joined in. This is necessary to ensure that all of the external IDs are listed for every
   job even if only one external ID from a particular job is provided to the query."
  [external-ids]
  (-> (sql/select* (job-base-query))
      (sql/join [:job_steps :s] {:j.id :s.job_id})
      (sql/fields [(sql/sqlfn :array_agg :s.external_id) :external_ids])
      (sql/group :j.app_description
             :j.system_id
             :j.app_id
             :j.app_version_id
             :j.app_name
             :j.job_description
             :j.end_date
             :j.id
             :j.job_name
             :j.result_folder_path
             :j.start_date
             :j.status
             :j.username
             :j.user_id
             :j.app_wiki_url
             :j.job_type
             :j.parent_id
             :j.is_batch
             :j.notify)
      (sql/where (exists (sql/subselect :job_steps (sql/where {:job_id :j.id :external_id [:in external-ids]}))))
      (sql/select)))

(defn list-child-jobs
  "Lists the child jobs within a batch job."
  [batch-id]
  (sql/select (job-base-query)
          (sql/fields :submission)
          (sql/where {:parent_id batch-id})))

(defn list-running-child-jobs
  "Lists the child jobs within a batch job that have not yet completed."
  [batch-id]
  (sql/select (job-base-query)
          (sql/where {:parent_id batch-id
                  :status    [:not-in (conj completed-status-codes
                                           impending-cancellation-status)]})))

(defn list-child-job-statuses
  "Lists the child job statuses within a batch job."
  [batch-id]
  (-> (sql/select* :job_listings)
      (sql/fields :status)
      (sql/aggregate (count :id) :count)
      (sql/where {:parent_id batch-id})
      (sql/group :status)
      (sql/select)))

(defn count-child-jobs
  "Counts the child jobs of a batch job."
  [batch-id]
  (-> (sql/select* :job_listings)
      (sql/aggregate (count :id) :count)
      (sql/where {:parent_id batch-id})
      (sql/select)
      first
      :count))

(defn get-job-by-id
  "Gets a single job by its internal identifier."
  [id]
  (first (sql/select (job-base-query)
                 (sql/fields :submission)
                 (sql/where {:j.id (uuidify id)}))))

(defn- lock-job*
  "Retrieves a job by its internal identifier, placing a lock on the row."
  [id]
  (-> (sql/select* [:jobs :j])
      (sql/fields :j.app_description
              :j.app_id
              :j.app_version_id
              :j.app_name
              [:j.job_description   :description]
              :j.end_date
              :j.id
              :j.job_name
              :j.result_folder_path
              :j.start_date
              :j.status
              :j.notify
              :j.app_wiki_url
              [:j.submission         :submission]
              :j.parent_id
              :j.user_id)
      (sql/where {:j.id id})
      (#(str (sql/as-sql %) " for update"))
      (#(sql/exec-raw [% [id]] :results))
      (first)))

(defn- add-job-type-info
  "Adds job type information to a job."
  [{:keys [id] :as job}]
  (merge job (first (sql/select [:jobs :j]
                            (sql/join [:job_types :t] {:j.job_type_id :t.id})
                            (sql/fields [:t.name :job_type] :t.system_id)
                            (sql/where {:j.id id})))))

(defn- add-job-username
  "Determines the username of the user who submitted a job."
  [{user-id :user_id :as job}]
  (merge job (first (sql/select [:users :u]
                            (sql/fields :u.username)
                            (sql/where {:u.id user-id})))))

(defn lock-job
  "Retrieves a job by its internal identifier, placing a lock on the row. For-update queries
   can't be used in conjunction with a group-by clause, so we have to use a separate query to
   determine the overall job type. A separate query also has to be used to retrieve the username
   so that a lock isn't placed on the users table.

   In most cases the MVCC behavior provided by Postgres is sufficient to ensure that the system
   stays in a consistent state. The one case where it isn't sufficient is when the status of
   a job is being updated. The reason the MVCC behavior doesn't work in this case is because a
   status update trigger a notification or another job in the case of a pipeline. Because of this,
   we need to ensure that only one thread is preparing to update a job status at any given time.

   Important note: in cases where both the job and the job step need to be locked, the job step
   should be locked first."
  [id]
  (some-> (lock-job* id)
          add-job-type-info
          add-job-username))

(defn- lock-job-step*
  "Retrieves a job step from the database by its job ID and external job ID, placing a lock on
   the row."
  [job-id external-id]
  (-> (sql/select* [:job_steps :s])
      (sql/fields :s.job_id
              :s.step_number
              :s.external_id
              :s.start_date
              :s.end_date
              :s.status
              :s.app_step_number)
      (sql/where (and {:s.job_id      job-id}
                  {:s.external_id external-id}))
      (#(str (sql/as-sql %) " for update"))
      (#(sql/exec-raw [% [job-id external-id]] :results))
      (first)))

(defn- determine-job-step-type
  "Dtermines the job type associated with a job step in the database."
  [job-id external-id]
  ((comp :job_type first)
   (sql/select [:job_steps :s]
           (sql/join [:job_types :t] {:s.job_type_id :t.id})
           (sql/fields [:t.name :job_type])
           (sql/where {:s.job_id      job-id
                   :s.external_id external-id}))))

(defn lock-job-step
  "Retrieves a job step by its associated job identifier and external job identifier. The lock on
   the job step is required in the same case and for the same reason as the lock on the job. Please
   see the documentation for lock-job for more details.

   Important note: in cases where both the job and the job step need to be locked, the job step
   should be locked first."
  [job-id external-id]
  (when-let [job-step (lock-job-step* job-id external-id)]
    (assoc job-step :job_type (determine-job-step-type job-id external-id))))

(defn update-job
  "Updates an existing job in the database."
  ([id {:keys [status end_date deleted name description]}]
   (when (or status end_date deleted name description)
     (sql/update :jobs
                 (sql/set-fields (remove-nil-values {:status          status
                                                 :end_date        end_date
                                                 :deleted         deleted
                                                 :job_name        name
                                                 :job_description description}))
                 (sql/where {:id id}))))
  ([id status end-date]
   (update-job id {:status   status
                   :end_date end-date})))

(defn update-job-step-number
  "Updates an existing job step in the database using the job ID and the step number as keys."
  [job-id step-number {:keys [external_id status end_date start_date]}]
  (when (or external_id status end_date start_date)
    (sql/update :job_steps
                (sql/set-fields (remove-nil-values {:external_id external_id
                                                :status      status
                                                :end_date    end_date
                                                :start_date  start_date}))
                (sql/where {:job_id      job-id
                        :step_number step-number}))))

(defn cancel-job-step-numbers
  "Marks a job step as canceled in the database."
  [job-id step-numbers]
  (sql/update :job_steps
              (sql/set-fields {:status     canceled-status
                           :start_date (sql/sqlfn :coalesce :start_date (sql/sqlfn :now))
                           :end_date   (sql/sqlfn :now)})
              (sql/where {:job_id      job-id
                      :step_number [:in step-numbers]})))

(defn get-job-step-number
  "Retrieves a job step from the database by its step number."
  [job-id step-number]
  (first
   (sql/select (job-step-base-query)
           (sql/where {:s.job_id      job-id
                   :s.step_number step-number}))))

(defn update-job-step
  "Updates an existing job step in the database."
  [job-id external-id status end-date]
  (when (or status end-date)
    (sql/update :job_steps
                (sql/set-fields (remove-nil-values {:status   status
                                                :end_date end-date}))
                (sql/where {:job_id      job-id
                        :external_id external-id}))))

(defn update-job-steps
  "Updates all steps for a job in the database."
  [job-id status end-date]
  (when (or status end-date)
    (sql/update :job_steps
                (sql/set-fields (remove-nil-values {:status   status
                                                :end_date end-date}))
                (sql/where {:job_id job-id}))))

(defn list-job-steps
  [job-id]
  (sql/select (job-step-base-query)
          (sql/where {:job_id job-id})
          (sql/order :step_number)))

(defn- related-job-ids-query
  "Returns a query that can be used to obtain the ID and parent ID of every job in the database whose ID or parent ID is
  in the given set of job IDs. This helps to force the representative job steps query to narrow its result set down
  early in the query execution."
  [job-ids]
  (let [job-ids (db/sql-array "uuid" job-ids)]
    (-> (h/select :id :parent_id)
        (h/from :jobs)
        (h/where [:or
                  [:= :id [:any job-ids]]
                  [:= :parent_id [:any job-ids]]]))))

(defn- batch-parent-job-ids-query
  "Returns a query that extracts the IDs of batch parent jobs from the related_job_ids query, which should be available
  as a common table expression (CTE). This exists just to make the representative_job_ids query a little cleaner."
  [related-job-ids-alias]
  (-> (h/select :id)
      (h/from [related-job-ids-alias :p])
      (h/where [:exists (-> (h/select :*)
                            (h/from [related-job-ids-alias :c])
                            (h/where [:= :p.id :c.parent_id]))])))

(defn- representative-job-ids-query
  "Returns a query that obtains the ID, parent ID, and representative job ID of every job in the related-job-ids-alias
  query, which should be available as a CTE. The representative job ID is the job ID whose steps represent the steps of
  every job in a batch. For individual jobs, the representative job ID is the job ID itself. For batch jobs, the
  representative job ID is the ID of any of the child jobs in the batch."
  [related-job-ids-alias batch-parent-job-ids-alias]
  {:union-all
   [(-> (h/select :id :parent_id [:id :representative_id])
        (h/from related-job-ids-alias)
        (h/where [:not-in :id (-> (h/select :id) (h/from batch-parent-job-ids-alias))]))
    (-> (h/select :id :parent_id
                  [(-> (h/select :c.id)
                       (h/from [related-job-ids-alias :c])
                       (h/where [:= :c.parent_id :p.id])
                       (h/limit 1))
                   :representative_id])
        (h/from [related-job-ids-alias :p])
        (h/where [:in :id (-> (h/select :id) (h/from batch-parent-job-ids-alias))]))]})

(defn list-representative-job-steps-query
  [job-ids]
  (-> {:with [[:related_job_ids (related-job-ids-query job-ids)]
              [:batch_parent_job_ids (batch-parent-job-ids-query :related_job_ids)]
              [:representative_job_ids (representative-job-ids-query :related_job_ids :batch_parent_job_ids)]]}
      (h/select [:r.id :job_id]
                :r.parent_id
                :s.step_number
                :s.external_id
                :s.start_date
                :s.end_date
                :s.status
                [:t.name :job_type]
                :s.app_step_number)
      (h/from [:representative_job_ids :r])
      (h/join [:job_steps :s] [:= :r.representative_id :s.job_id]
              [:job_types :t] [:= :s.job_type_id :t.id])
      hsql/format))

(defn list-representative-job-steps
  "Lists all of the job steps in a standalone job or all of the steps of one of the jobs in an HT batch. The purpose
   of this function is to ensure that steps of every job type that are used in a job are listed. The analysis listing
   code uses this function to determine whether or not a job can be shared."
  [job-ids]
  (db/with-transaction [tx]
    (jdbc/query tx (list-representative-job-steps-query job-ids))))

(defn delete-jobs
  [ids]
  (sql/update :jobs
              (sql/set-fields {:deleted true})
              (sql/where {:id [:in ids]})))

(defn- get-jobs
  [ids]
  (sql/select (job-base-query)
          (sql/where {:j.id [:in ids]})))

(defn list-non-existent-job-ids
  [job-id-set]
  (->> (get-jobs job-id-set)
       (map :id)
       (set)
       (set/difference job-id-set)))

(defn- get-job-stats-fields
  "Adds query fields with subselects similar to the app_listing view's job_count, job_count_failed,
   and last_used columns."
  [query]
  (sql/fields query
          [(sql/subselect [:jobs :jc]
                      (sql/aggregate (count :id) :job_count)
                      (sql/where {:app_id :j.app_id})
                      (sql/where (sql/raw "NOT EXISTS (SQL/SELECT parent_id FROM jobs jp WHERE jp.parent_id = jc.id)")))
           :job_count]
          [(sql/subselect [:jobs :jc]
                      (sql/aggregate (count :id) :job_count_failed)
                      (sql/where {:app_id :j.app_id
                              :status failed-status})
                      (sql/where (sql/raw "NOT EXISTS (SQL/SELECT parent_id FROM jobs jp WHERE jp.parent_id = jc.id)")))
           :job_count_failed]
          [(sql/subselect :jobs
                      (sql/aggregate (max :start_date) :last_used)
                      (sql/where {:app_id :j.app_id}))
           :last_used]))

(defn- get-job-stats-base-query
  "Fetches job stats for the given app ID, with fields via subselects similar to the app_listing view's
   job_count_completed and job_last_completed columns."
  [^String app-id]
  (-> (sql/select* [:jobs :j])
      (sql/fields [(sql/subselect [:jobs :jc]
                          (sql/aggregate (count :id) :job_count_completed)
                          (sql/where {:app_id :j.app_id
                                  :status completed-status})
                          (sql/where (sql/raw "NOT EXISTS (SQL/SELECT parent_id FROM jobs jp WHERE jp.parent_id = jc.id)")))
               :job_count_completed]
              [(sql/subselect :jobs
                          (sql/aggregate (max :end_date) :job_last_completed)
                          (sql/where {:app_id :j.app_id
                                  :status completed-status}))
               :job_last_completed])
      (sql/where {:app_id app-id})
      (sql/group :app_id)))

(defn get-job-stats
  [^String app-id params]
  (merge {:job_count 0
          :job_count_failed 0
          :job_count_completed 0}
         (-> (get-job-stats-base-query app-id)
             (db/add-date-limits-where-clause params)
             get-job-stats-fields
             sql/select
             first)))

(defn get-public-job-stats
  [^String app-id params]
  (merge {:job_count_completed 0}
         (-> (get-job-stats-base-query app-id)
             (db/add-date-limits-where-clause params)
             sql/select
             first)))

(defn record-tickets
  "Records tickets for a job."
  [job-id ticket-map]
  (when (seq ticket-map)
    (let [format-row (fn [[path ticket-id]] {:job_id job-id :ticket ticket-id :irods_path path})]
      (sql/insert :job_tickets (sql/values (mapv format-row ticket-map))))))

(defn mark-tickets-deleted
  "Marks a set of tickets as deleted."
  [ticket-map]
  (when-not (empty? ticket-map)
    (sql/update :job_tickets
                (sql/set-fields {:deleted true})
                (sql/where {:ticket [:in (vals ticket-map)]}))))

(defn load-job-ticket-map
  "Loads the ticket map for the given job ID."
  [job-id]
  (->> (sql/select :job_tickets (sql/fields :irods_path :ticket) (sql/where {:job_id job-id}))
       (map (juxt :irods_path :ticket))
       (into {})))

(defn add-job-status-update
  "Adds a job status update for a given external ID"
  [external-id message status]
  (sql/insert :job_status_updates
          (sql/values {:external_id        external-id
                   :message            message
                   :status             status
                   :sent_from          (sql/raw "'0.0.0.0'::inet")
                   :sent_from_hostname "0.0.0.0"
                   :sent_on            (System/currentTimeMillis)})))

(defn get-unpropagated-job-status-updates
  "Retrieves the list of unpropagated job status updates for an external ID."
  [external-id]
  (-> (sql/select* :job_status_updates)
      (sql/fields :id :status :sent_on)
      (sql/order :sent_on :ASC)
      (sql/where {:external_id          external-id
              :propagated           false
              :propagation_attempts [< 3]})
      sql/select))

(defn mark-job-status-updates-propagated
  "Marks a set of job status updates as propagated."
  [ids]
  (when (seq ids)
    (sql/update :job_status_updates
                (sql/set-fields {:propagated true})
                (sql/where {:id [:in ids]}))))

(defn mark-job-status-updates-for-external-id-completed
  "Marks all job status updates for an external ID as completed."
  [external-id]
  (sql/update :job_status_updates
              (sql/set-fields {:propagated true})
              (sql/where {:external_id external-id})))

(defn get-job-status-updates
  "Retrieves the list of job status updates for an external ID."
  [external-id]
  (-> (sql/select* :job_status_updates)
      (sql/fields :id :status :message :sent_on)
      (sql/order :sent_on :ASC)
      (sql/where {:external_id external-id})
      sql/select))

(defn set-lock-timeout
  "Sets a timeout for obtaining locks in the database."
  []
  (sql/exec-raw ["SET LOCAL lock_timeout='5s'" []]))
