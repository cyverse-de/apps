(ns apps.persistence.app-search
  (:require [apps.constants :as c]
            [kameleon.util :refer [query-spy]]
            [kameleon.util.search :refer [format-query-wildcards]]
            [korma.core :as sql]))

;; Declarations for special symbols used by Korma.
(declare exists)

(defn- get-app-category-subselect
  []
  (sql/subselect [:app_category_app :aca] (sql/where {:aca.app_id :a.id})))

(defn- add-app-id-where-clause
  [q {:keys [app-ids orphans public-app-ids]}]
  (if (and orphans (seq public-app-ids))
    (sql/where q (or {:a.id [:in app-ids]}
                     (and {:a.id [:not-in public-app-ids]}
                          (not (exists (get-app-category-subselect))))))
    (sql/where q {:a.id [:in app-ids]})))

(defn- add-app-type-where-clause
  [q {:keys [app-type]}]
  (if app-type
    (sql/where q {:jt.name app-type})
    q))

(defn- add-omitted-app-id-where-clause
  [q {:keys [omitted-app-ids]}]
  (if (seq omitted-app-ids)
    (sql/where q {:a.id [:not-in omitted-app-ids]})
    q))

(defn- external-step-subselect
  []
  (sql/subselect [:app_steps :es]
                 (sql/where {:es.app_version_id :v.id
                             :es.task_id        nil})))

(defn- add-tapis-pipeline-where-clause
  [q {tapis-enabled? :tapis-enabled :or {tapis-enabled? "false"}}]
  (let [tapis-enabled? (Boolean/parseBoolean tapis-enabled?)]
    (if-not tapis-enabled?
      (sql/where q (not (exists (external-step-subselect))))
      q)))

(defn- add-search-term-where-clauses
  "Adds where clauses to a base App search query to restrict results to apps that
   contain search-term in the app name, app description, app integrator name, or
   the tool name."
  [q search-term pre-matched-app-ids]
  (cond
    (empty? search-term)
    q

    (seq pre-matched-app-ids)
    (let [search-term (str "%" (format-query-wildcards search-term) "%")]
      (sql/where q (or {(sql/sqlfn :lower :a.name)            [:like (sql/sqlfn :lower search-term)]}
                       {(sql/sqlfn :lower :a.description)     [:like (sql/sqlfn :lower search-term)]}
                       {(sql/sqlfn :lower :i.integrator_name) [:like (sql/sqlfn :lower search-term)]}
                       {(sql/sqlfn :lower :tool.name)         [:like (sql/sqlfn :lower search-term)]}
                       {:a.id                            [:in pre-matched-app-ids]})))

    :else
    (let [search-term (str "%" (format-query-wildcards search-term) "%")]
      (sql/where q (or {(sql/sqlfn :lower :a.name)            [:like (sql/sqlfn :lower search-term)]}
                       {(sql/sqlfn :lower :a.description)     [:like (sql/sqlfn :lower search-term)]}
                       {(sql/sqlfn :lower :i.integrator_name) [:like (sql/sqlfn :lower search-term)]}
                       {(sql/sqlfn :lower :tool.name)         [:like (sql/sqlfn :lower search-term)]})))))

(defn- add-non-admin-where-clauses
  "If the admin option is not set, adds clauses to exclude apps that are not available in
   non-administrative app listings."
  [q {admin? :admin}]
  (if-not admin?
    (sql/where q {:v.deleted         false
                  :i.integrator_name [not= c/internal-app-integrator]})
    q))

(defn- get-app-search-base-query
  "Gets an app search select query. This function returns only a list of matching app IDs."
  [search-term query-opts]
  (as-> (sql/select* [:apps :a]) q
    (sql/join q [:app_versions :v] {:a.id :v.app_id})
    (sql/join q [:integration_data :i] {:v.integration_data_id :i.id})
    (sql/join q [:app_steps :s] {:v.id :s.app_version_id})
    (sql/join q [:tasks :t] {:s.task_id :t.id})
    (sql/join q [:tools :tool] {:t.tool_id :tool.id})
    (sql/join q [:job_types :jt] {:t.job_type_id :jt.id})
    (add-app-id-where-clause q query-opts)
    (add-app-type-where-clause q query-opts)
    (add-omitted-app-id-where-clause q query-opts)
    (add-tapis-pipeline-where-clause q query-opts)
    (add-search-term-where-clauses q search-term (:pre-matched-app-ids query-opts))
    (add-non-admin-where-clauses q query-opts)))

(defn count-matching-app-ids
  "Counts the identifiers that match a set of search parameters."
  [search-term query-opts]
  (as-> (get-app-search-base-query search-term query-opts) q
    (sql/fields q (sql/raw "count(DISTINCT a.id) AS total"))
    (query-spy "count-matching-app-ids::search_query:" q)
    (:total (first (sql/select q)))))

(defn find-matching-app-ids
  "Finds the identifiers of apps that match a set of search parameters."
  [search-term query-opts]
  (as-> (get-app-search-base-query search-term query-opts) q
    (sql/modifier q "DISTINCT")
    (sql/fields q :a.id)
    (query-spy "find-matching-app-ids::search_query:" q)
    (sql/select q)
    (map :id q)))
