(ns apps.persistence.app-listing
  (:require [apps.constants :as c]
            [apps.persistence.app-search :refer [count-matching-app-ids find-matching-app-ids]]
            [apps.persistence.entities :as entities]
            [apps.util.assertions :refer [assert-app-version]]
            [apps.util.db :refer [add-date-limits-where-clause]]
            [clojure.java.jdbc]
            [clojure.string :as str]
            [kameleon.queries :as kq]
            [kameleon.util :refer [query-spy]]
            [korma.core :as sql])
  (:refer-clojure :exclude [count]))

;; Declarations for special symbols used by Korma.
(declare count exists)

;; The `app_listing` view has an array aggregate column that we need to be able to extract. This tells
;; `clojure.java.jdbc` how to convert the array to a (possibly nested) vector.
(extend-protocol clojure.java.jdbc/IResultSetReadColumn
  org.postgresql.jdbc.PgArray
  (result-set-read-column [x _2 _3]
    (letfn [(object-array? [x] (instance? (Class/forName "[Ljava.lang.Object;") x))
            (expand [x] (if (object-array? x) (mapv expand x) x))]
      (mapv expand (.getArray x)))))

(defn get-app-listing
  "Retrieves all app listing fields from the database for the given App ID."
  [app-id]
  (first (sql/select entities/app_listing (sql/where {:id app-id}))))

(defn get-app-extra-info
  [version-id]
  (when-let [htcondor (first
                       (sql/select entities/apps_htcondor_extra
                                   (sql/fields [:extra_requirements])
                                   (sql/where {:app_version_id version-id})))]
    {:htcondor htcondor}))

(defn- get-all-group-ids-subselect
  "Gets a subselect that fetches the app_categories and its subgroup IDs with
   the stored procedure app_category_hierarchy_ids."
  [app_group_id]
  (sql/subselect
   (sql/sqlfn :app_category_hierarchy_ids app_group_id)))

(defn- get-fav-group-id-subselect
  "Gets a subselect that fetches the ID for the Favorites group at the given
   index under the app_categories with the given ID."
  [workspace_root_group_id favorites_group_index]
  (sql/subselect
   :app_category_group
   (sql/fields :child_category_id)
   (sql/where {:parent_category_id workspace_root_group_id
               :child_index favorites_group_index})))

(defn- get-is-fav-sqlfn
  "Gets a sqlfn that returns true if the App ID in its subselect is found in the
   Favorites group with the ID returned by get-fav-group-id-subselect."
  [workspace_root_group_id favorites_group_index app-id-column]
  (let [fav_group_id_subselect (get-fav-group-id-subselect
                                workspace_root_group_id
                                favorites_group_index)]
    (sql/sqlfn* :exists
                (sql/subselect
                 :app_category_app
                 (sql/where {:app_category_app.app_id
                             app-id-column})
                 (sql/where {:app_category_app.app_category_id
                             fav_group_id_subselect})))))

(defn- get-app-listing-orphaned-condition
  []
  (sql/raw "NOT EXISTS (SELECT * FROM app_category_app aca WHERE aca.app_id = app_listing.id)"))

(defn- add-app-id-where-clause
  [query {:keys [app-ids orphans public-app-ids]}]
  (if (seq app-ids)
    (if (and orphans (seq public-app-ids))
      (sql/where query (or {:id [:in app-ids]}
                           (and {:id [:not-in (sequence public-app-ids)]}
                                (get-app-listing-orphaned-condition))))
      (sql/where query {:id [:in app-ids]}))
    query))

(defn- add-omitted-app-id-where-clause
  [query {:keys [omitted-app-ids]}]
  (if (seq omitted-app-ids)
    (sql/where query {:id [:not-in omitted-app-ids]})
    query))

(defn- add-tapis-pipeline-where-clause
  [query {tapis-enabled? :tapis-enabled :or {tapis-enabled? "false"}}]
  (let [tapis-enabled? (Boolean/parseBoolean tapis-enabled?)]
    (if-not tapis-enabled?
      (sql/where query {:step_count :task_count})
      query)))

(defn- add-app-group-where-clause
  "Adds a where clause to an analysis listing query to restrict app results to
   an app group and all of its descendents."
  [base_listing_query app_group_id]
  (-> base_listing_query
      (sql/join :app_category_app
                (= :app_category_app.app_id
                   :app_listing.id))
      (sql/where {:app_category_app.app_category_id
                  [:in (get-all-group-ids-subselect app_group_id)]})))

(defn- add-app-group-plus-public-apps-where-clause
  "Adds a where clause to an analysis listing query to restrict app results to
   an app group and all of its descendents plus the set of public apps
   integrated by a user."
  [query app-group-id username public-app-ids]
  (-> query
      (sql/join :app_category_app
                (= :app_category_app.app_id
                   :app_listing.id))
      (sql/where (or {:app_category_app.app_category_id
                      [:in (get-all-group-ids-subselect app-group-id)]}
                     {:integrator_username username
                      :id                  [:in (sequence public-app-ids)]}))))

(defn- add-public-apps-by-user-where-clause
  "Adds a where clause to an analysis listing query to restrict app results to
   the set of public apps integrated by a user."
  [query username {:keys [public-app-ids]}]
  (sql/where query
             {:integrator_username username
              :id                  [:in public-app-ids]}))

(defn- app-type-subselect
  "Returns a subselect statement to search for apps with a selected app type."
  [app-type]
  (sql/subselect [:app_steps :s]
                 (sql/join [:tasks :t] {:s.task_id :t.id})
                 (sql/join [:job_types :jt] {:t.job_type_id :jt.id})
                 (sql/where {:s.app_version_id :app_listing.version_id
                             :jt.name  app-type})))

(defn- add-app-type-where-clause
  "Adds a where clause to an app listing query to restrict results to a particular
   app type if an app type was specified in the HTTP query parameters."
  [query {:keys [app-type]}]
  (if app-type
    (sql/where query (exists (app-type-subselect app-type)))
    query))

(defn- get-all-apps-count-base-query
  "Returns a base query for counting the total number of apps in the
   app_listing table."
  [query-opts]
  (-> (sql/select* entities/app_listing)
      (sql/fields (sql/raw "count(DISTINCT app_listing.id) AS total"))
      (add-app-id-where-clause query-opts)
      (add-app-type-where-clause query-opts)
      (add-omitted-app-id-where-clause query-opts)
      (add-tapis-pipeline-where-clause query-opts)))

(defn- get-app-count-base-query
  "Adds a where clause to the get-all-apps-count-base-query, filtering out `deleted` apps."
  [query-opts]
  (-> (get-all-apps-count-base-query query-opts)
      (sql/where {:deleted false})
      (sql/where {:integrator_name [not= c/internal-app-integrator]})))

(defn count-apps-in-group-for-user
  "Counts all of the apps in an app group and all of its descendents."
  ([app-group-id query-opts]
   ((comp :total first)
    (-> (get-app-count-base-query query-opts)
        (add-app-group-where-clause app-group-id)
        (sql/select))))
  ([app-group-id username {:keys [public-app-ids] :as query-opts}]
   ((comp :total first)
    (-> (get-app-count-base-query query-opts)
        (add-app-group-plus-public-apps-where-clause app-group-id username public-app-ids)
        (sql/select)))))

(defn- add-app-listing-base-query-fields
  "Add minimum required columns to apps listing query results"
  [listing-query]
  (sql/fields listing-query
              :id
              :name
              :version
              :version_id
              :lower_case_name
              :description
              :integrator_name
              :integrator_email
              :integration_date
              :edited_date
              :wiki_url
              :average_rating
              :total_ratings
              :step_count
              :tool_count
              :external_app_count
              :task_count
              :deleted
              :disabled
              :overall_job_type))

(defn- add-app-listing-job-types-field
  "Adds the job_types field to apps listing query results"
  [listing-query]
  (sql/fields listing-query :job_types))

(defn- add-app-listing-is-favorite-field
  "Add user's is_favorite column to apps listing query results"
  [listing-query workspace_root_group_id favorites_group_index]
  (let [is_fav_subselect (get-is-fav-sqlfn
                          workspace_root_group_id
                          favorites_group_index
                          :app_listing.id)]
    (sql/fields listing-query [is_fav_subselect :is_favorite])))

(defn- add-app-versions-listing-is-favorite-field
  "Add user's is_favorite column to app versions listing query results"
  [listing-query workspace_root_group_id favorites_group_index]
  (let [is_fav_subselect (get-is-fav-sqlfn
                          workspace_root_group_id
                          favorites_group_index
                          :app_versions_listing.id)]
    (sql/fields listing-query [is_fav_subselect :is_favorite])))

(defn- add-listing-query-ratings-fields
  "Add ratings columns to listing query results"
  [listing-query user-id app-id-column]
  (-> listing-query
      (sql/fields [:ratings.rating :user_rating]
                  :ratings.comment_id)
      (sql/join entities/ratings
                (and (= :ratings.app_id
                        app-id-column)
                     (= :ratings.user_id
                        user-id)))))

(defn- add-app-listing-ratings-fields
  "Add ratings columns to apps listing query results"
  [listing-query user-id]
  (add-listing-query-ratings-fields listing-query user-id :app_listing.id))

(defn- add-app-version-listing-ratings-fields
  "Add ratings columns to app versions listing query results"
  [listing-query user-id]
  (add-listing-query-ratings-fields listing-query user-id :app_versions_listing.id))

(defn- get-base-app-listing-base-query
  "Gets an app_listing select query, setting any query limits and sorting
   found in the query_opts, using the given workspace (as returned by
   fetch-workspace-by-user-id) to mark whether each app is a favorite and to
   include the user's rating in each app."
  [workspace favorites_group_index query_opts]
  (let [user_id (:user_id workspace)
        workspace_root_group_id (:root_category_id workspace)
        row_offset (or (:offset query_opts) 0)
        row_limit (or (:limit query_opts) -1)
        sort_field (keyword (or (:sort-field query_opts) (:sortfield query_opts)))
        sort_dir (keyword (or (:sort-dir query_opts) (:sortdir query_opts)))]
    (-> (sql/select* entities/app_listing)
        (sql/modifier "DISTINCT")
        (add-app-listing-base-query-fields)
        (add-app-listing-job-types-field)
        (add-app-listing-is-favorite-field workspace_root_group_id favorites_group_index)
        (add-app-listing-ratings-fields user_id)
        (kq/add-query-limit row_limit)
        (kq/add-query-offset row_offset)
        (kq/add-query-sorting sort_field sort_dir))))

(defn- get-all-apps-listing-base-query
  "Gets an app_listing select query, setting any query limits and sorting
   found in the query_opts, using the given workspace (as returned by
   fetch-workspace-by-user-id) to mark whether each app is a favorite and to
   include the user's rating in each app.."
  [workspace favorites_group_index query_opts]
  (-> (get-base-app-listing-base-query workspace favorites_group_index query_opts)
      (add-app-id-where-clause query_opts)
      (add-app-type-where-clause query_opts)
      (add-omitted-app-id-where-clause query_opts)
      (add-tapis-pipeline-where-clause query_opts)))

(defn- get-app-listing-base-query
  "Adds a where clause to the get-all-apps-listing-base-query, filtering out `deleted` apps."
  [workspace favorites_group_index query_opts]
  (-> (get-all-apps-listing-base-query workspace favorites_group_index query_opts)
      (sql/where {:deleted false})
      (sql/where {:integrator_name [not= c/internal-app-integrator]})))

(defn get-apps-in-group-for-user
  "Lists all of the apps in an app group and all of its descendents, using the
   given workspace (as returned by fetch-workspace-by-user-id) to mark
   whether each app is a favorite and to include the user's rating in each app."
  ([app-group-id workspace faves-index query-opts]
   (-> (get-app-listing-base-query workspace faves-index query-opts)
       (add-app-group-where-clause app-group-id)
       (sql/select)))
  ([app-group-id workspace faves-index {:keys [public-app-ids] :as query-opts} username]
   (-> (get-app-listing-base-query workspace faves-index query-opts)
       (add-app-group-plus-public-apps-where-clause app-group-id username public-app-ids)
       (sql/select))))

;; TODO: reinsert the subselect for the date a job for the app was most recently completed as soon as we
;; can do it efficiently
(defn- get-job-stats-fields
  "Adds query fields via subselects for an app's job_count_completed and job_last_completed timestamp."
  [query query-opts]
  (sql/fields query
              [(sql/subselect [:jobs :j]
                              (sql/aggregate (count :id) :job_count_completed)
                              (sql/where {:app_id (sql/raw "app_listing.id::varchar")
                                          :status "Completed"})
                              (add-date-limits-where-clause query-opts)
                              (sql/where (sql/raw "NOT EXISTS (SELECT parent_id FROM jobs jp WHERE jp.parent_id = j.id)")))
               :job_count_completed]))

;; TODO: reinsert the subselect for the date the app was most recently used as soon as we can do it
;; efficiently
(defn- get-admin-job-stats-fields
  "Adds query fields via subselects for an app's job_count, job_count_failed, and last_used timestamp."
  [query query-opts]
  (sql/fields query
              [(sql/subselect [:jobs :j]
                              (sql/aggregate (count :id) :job_count)
                              (sql/where {:app_id (sql/raw "app_listing.id::varchar")})
                              (add-date-limits-where-clause query-opts)
                              (sql/where (sql/raw "NOT EXISTS (SELECT parent_id FROM jobs jp WHERE jp.parent_id = j.id)")))
               :job_count]
              [(sql/subselect [:jobs :j]
                              (sql/aggregate (count :id) :job_count_failed)
                              (sql/where {:app_id (sql/raw "app_listing.id::varchar")
                                          :status "Failed"})
                              (add-date-limits-where-clause query-opts)
                              (sql/where (sql/raw "NOT EXISTS (SELECT parent_id FROM jobs jp WHERE jp.parent_id = j.id)")))
               :job_count_failed]))

(defn count-apps-for-user
  "Counts Apps in all public groups and groups under the given workspace_id.
   If search_term is not empty, results are limited to apps that contain search_term in their name,
   description, integrator_name, or tool name(s).

   Note: as far as I can tell the where clauses for the category are defunct. I'm ignoring them in
   this implementation of the function for now. The workspace ID parameter can be removed completely
   once the old implementation of this function has been removed."
  [search-term _ params]
  (count-matching-app-ids search-term params))

(defn count-apps-for-admin
  "Counts Apps in all public groups and groups under the given workspace_id.
   If search_term is not empty, results are limited to apps that contain search_term in their name,
   description, integrator_name, or tool name(s)."
  [search-term _ params]
  (count-matching-app-ids search-term (assoc params :admin true)))

(defn get-apps-for-user
  "Fetches Apps in all public groups and groups in `workspace`
   (as returned by fetch-workspace-by-user-id),
   marking whether each app is a favorite and including the user's rating in each app by the user_id
   found in workspace.
  If search_term is not empty, results are limited to apps that contain search_term in their name,
   description, integrator_name, or tool name(s)."
  [search_term workspace favorites_group_index query_opts]
  (let [app-ids (find-matching-app-ids search_term query_opts)]
    (-> (get-base-app-listing-base-query workspace favorites_group_index query_opts)
        (sql/where {:id [:in app-ids]})
        ((partial query-spy "get-apps-for-user::search_query:"))
        sql/select)))

(defn get-apps-for-admin
  "Returns the same results as get-apps-for-user, but also includes deleted apps and job_count,
   job_count_failed, job_count_completed, last_used timestamp, and job_last_completed timestamp fields
   for each result."
  [search_term workspace favorites_group_index query_opts]
  (let [app-ids (find-matching-app-ids search_term (assoc query_opts :admin true))]
    (-> (get-base-app-listing-base-query workspace favorites_group_index query_opts)
        (sql/where {:id [:in app-ids]})
        (get-job-stats-fields query_opts)
        (get-admin-job-stats-fields query_opts)
        ((partial query-spy "get-apps-for-admin::search_query:"))
        sql/select)))

(defn get-single-app
  "Fetches a listing for a single app."
  [workspace favorites-group-index app-id]
  (as-> (get-app-listing-base-query workspace favorites-group-index {:app-ids [app-id]}) q
    (query-spy "get-single-app::search-query:" q)
    (sql/select q)))

(defn get-app-version-details
  "Retrieves the details for a single app."
  [user-id workspace-root-group-id favorites-group-index app-id version-id]
  (let [rating-avg-subselect (sql/subselect
                              entities/ratings
                              (sql/fields (sql/raw "CAST(COALESCE(AVG(rating), 0.0) AS DOUBLE PRECISION) AS average_rating"))
                              (sql/where {:ratings.app_id
                                          :app_versions_listing.id}))
        rating-total-subselect (sql/subselect
                                entities/ratings
                                (sql/aggregate (count :ratings.rating) :total_ratings)
                                (sql/where {:ratings.app_id
                                            :app_versions_listing.id}))]
    (assert-app-version
     [app-id version-id]
     (-> (sql/select* :app_versions_listing)
         (sql/fields :*
                     [rating-avg-subselect :average_rating]
                     [rating-total-subselect :total_ratings])
         (add-app-versions-listing-is-favorite-field
          workspace-root-group-id
          favorites-group-index)
         (add-app-version-listing-ratings-fields user-id)
         (sql/where {:id         app-id
                     :version_id version-id})
         sql/select
         first))))

(defn- add-deleted-and-orphaned-where-clause
  [query public-app-ids]
  (sql/where query
             (or {:deleted true
                  :id      [:in (sequence public-app-ids)]}
                 (and (get-app-listing-orphaned-condition)
                      {:id [:not-in (sequence public-app-ids)]}))))

(defn count-deleted-and-orphaned-apps
  "Counts the number of deleted, public apps, plus apps that are not listed under any category."
  [{:keys [public-app-ids] :as params}]
  ((comp :count first)
   (-> (sql/select* entities/app_listing)
       (sql/aggregate (count :*) :count)
       (add-deleted-and-orphaned-where-clause public-app-ids)
       (add-app-type-where-clause params)
       (sql/select))))

(defn list-deleted-and-orphaned-apps
  "Fetches a list of deleted, public apps, plus apps that are not listed under any category."
  [{:keys [limit offset sort-field sort-dir public-app-ids] :as params}]
  (-> (sql/select* entities/app_listing)
      (add-app-listing-base-query-fields)
      (add-deleted-and-orphaned-where-clause public-app-ids)
      (add-app-type-where-clause params)
      (kq/add-query-limit limit)
      (kq/add-query-offset offset)
      (kq/add-query-sorting (when sort-field (keyword sort-field))
                            (when sort-dir (keyword (str/upper-case sort-dir))))
      (sql/select)))

(defn count-public-apps-by-user
  "Counts the number of apps integrated by a user."
  [username params]
  ((comp :count first)
   (-> (sql/select* entities/app_listing)
       (sql/aggregate (count :*) :count)
       (sql/where {:deleted false})
       (add-public-apps-by-user-where-clause username params)
       (add-app-id-where-clause params)
       (add-app-type-where-clause params)
       (add-omitted-app-id-where-clause params)
       (add-tapis-pipeline-where-clause params)
       (sql/select))))

(defn list-public-apps-by-user
  "Lists the apps integrated by the user with the given"
  [workspace favorites-group-index username query-opts]
  (-> (get-app-listing-base-query workspace favorites-group-index query-opts)
      (add-public-apps-by-user-where-clause username query-opts)
      (sql/select)))

(defn list-shared-apps
  "Lists apps that have been shared with a user. For the time being, this works by listing all non-public
   apps that the user did not integrate."
  [workspace favorites-group-index params]
  (let [params (assoc params :omitted-app-ids (:public-app-ids params))]
    (-> (get-app-listing-base-query workspace favorites-group-index params)
        (sql/where {:integrator_id [not= (:user_id workspace)]})
        sql/select)))

(defn count-shared-apps
  "Lists apps that have been shared with a user. For the time being, this works by counting all non-public
   apps that the user did not integrate."
  [workspace _favorites-group-index params]
  (let [params (assoc params :omitted-app-ids (:public-app-ids params))]
    (-> (sql/select* entities/app_listing)
        (sql/aggregate (count :*) :count)
        (sql/where {:deleted false})
        (sql/where {:integrator_id [not= (:user_id workspace)]})
        (add-app-id-where-clause params)
        (add-app-type-where-clause params)
        (add-omitted-app-id-where-clause params)
        sql/select first :count)))

(defn list-apps-by-id
  "Lists all apps with an ID in the the given app-ids list."
  [workspace favorites-group-index app-ids params]
  (get-apps-for-user nil workspace favorites-group-index (assoc params :app-ids app-ids)))

(defn admin-list-apps-by-id
  "Lists all apps with an ID in the the given app-ids list,
   including job_count, job_count_failed, job_count_completed, last_used timestamp, and
   job_last_completed timestamp fields for each result."
  [workspace favorites-group-index app-ids params]
  (let [app-ids (find-matching-app-ids nil (assoc params :app-ids app-ids))]
    (-> (get-base-app-listing-base-query workspace favorites-group-index params)
        (get-job-stats-fields params)
        (get-admin-job-stats-fields params)
        (sql/where {:id [:in app-ids]})
        ((partial query-spy "admin-list-apps-by-id::search_query:"))
        sql/select)))

(defn get-all-app-ids
  "Lists all app IDs in the database."
  []
  (->> (sql/select :apps (sql/fields :id))
       (map :id)
       set))
