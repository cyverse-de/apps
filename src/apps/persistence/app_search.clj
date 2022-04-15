(ns apps.persistence.app-search
  (:use [kameleon.queries :only [add-query-offset add-query-limit add-query-sorting]]
        [kameleon.util :only [query-spy]]
        [kameleon.util.search :only [format-query-wildcards]]
        [korma.core :exclude [update]])
  (:require [apps.constants :as c]
            [otel.otel :as otel]))

(defn- get-app-category-subselect
  []
  (subselect [:app_category_app :aca] (where {:aca.app_id :a.id})))

(defn- add-app-id-where-clause
  [q {:keys [app-ids orphans public-app-ids]}]
  (if (and orphans (seq public-app-ids))
    (where q (or {:a.id [in app-ids]}
                 (and {:a.id [not-in public-app-ids]}
                      (not (exists (get-app-category-subselect))))))
    (where q {:a.id [in app-ids]})))

(defn- add-app-type-where-clause
  [q {:keys [app-type]}]
  (if app-type
    (where q {:jt.name app-type})
    q))

(defn- add-omitted-app-id-where-clause
  [q {:keys [omitted-app-ids]}]
  (if (seq omitted-app-ids)
    (where q {:a.id [not-in omitted-app-ids]})
    q))

(defn- external-step-subselect
  []
  (subselect [:app_steps :es]
             (where {:es.app_id  :a.id
                     :es.task_id nil})))

(defn- add-agave-pipeline-where-clause
  [q {agave-enabled? :agave-enabled :or {agave-enaled? "false"}}]
  (let [agave-enabled? (Boolean/parseBoolean agave-enabled?)]
    (if-not agave-enabled?
      (where q (not (exists (external-step-subselect))))
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
      (where q (or {(sqlfn lower :a.name)            [like (sqlfn lower search-term)]}
                   {(sqlfn lower :a.description)     [like (sqlfn lower search-term)]}
                   {(sqlfn lower :i.integrator_name) [like (sqlfn lower search-term)]}
                   {(sqlfn lower :tool.name)         [like (sqlfn lower search-term)]}
                   {:a.id                            [in pre-matched-app-ids]})))

    :else
    (let [search-term (str "%" (format-query-wildcards search-term) "%")]
      (where q (or {(sqlfn lower :a.name)            [like (sqlfn lower search-term)]}
                   {(sqlfn lower :a.description)     [like (sqlfn lower search-term)]}
                   {(sqlfn lower :i.integrator_name) [like (sqlfn lower search-term)]}
                   {(sqlfn lower :tool.name)         [like (sqlfn lower search-term)]})))))

(defn- add-non-admin-where-clauses
  "If the admin option is not set, adds clauses to exclude apps that are not available in
   non-administrative app listings."
  [q {admin? :admin}]
  (if-not admin?
    (where q {:a.deleted         false
              :i.integrator_name [not= c/internal-app-integrator]})
    q))

(defn- get-app-search-base-query
  "Gets an app search select query. This function returns only a list of matching app IDs."
  [search-term query-opts]
  (as-> (select* [:apps :a]) q
    (join q [:integration_data :i] {:a.integration_data_id :i.id})
    (join q [:app_steps :s] {:a.id :s.app_id})
    (join q [:tasks :t] {:s.task_id :t.id})
    (join q [:tools :tool] {:t.tool_id :tool.id})
    (join q [:job_types :jt] {:t.job_type_id :jt.id})
    (add-app-id-where-clause q query-opts)
    (add-app-type-where-clause q query-opts)
    (add-omitted-app-id-where-clause q query-opts)
    (add-agave-pipeline-where-clause q query-opts)
    (add-search-term-where-clauses q search-term (:pre-matched-app-ids query-opts))
    (add-non-admin-where-clauses q query-opts)))

(defn count-matching-app-ids
  "Counts the identifiers that match a set of search parameters."
  [search-term query-opts]
  (as-> (get-app-search-base-query search-term query-opts) q
    (fields q (raw "count(DISTINCT a.id) AS total"))
    (query-spy "count-matching-app-ids::search_query:" q)
    (:total (first (select q)))))

(defn find-matching-app-ids
  "Finds the identifiers of apps that match a set of search parameters."
  [search-term query-opts]
  (otel/with-span [s ["find-matching-app-ids" {:attributes {"apps.search-term" search-term}}]]
    (as-> (get-app-search-base-query search-term query-opts) q
      (modifier q "DISTINCT")
      (fields q :a.id)
      (query-spy "find-matching-app-ids::search_query:" q)
      (select q)
      (map :id q))))
