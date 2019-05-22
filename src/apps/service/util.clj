(ns apps.service.util
  (:use [apps.transformers :only [param->long]]
        [apps.util.conversions :only [remove-nil-vals]]
        [common-swagger-api.schema.apps.admin.apps :only [AdminAppListingJobStatsKeys]])
  (:require [clojure.string :as string]
            [clojure-commons.exception-util :as cxu]
            [kameleon.uuids :as uuids])
  (:import [java.util UUID]))

(defn- app-sorter-keyfn
  [sort-field]
  (let [sort-field (keyword sort-field)]
    (condp contains? sort-field
      AdminAppListingJobStatsKeys (comp sort-field :job_stats)
      #{:average :total}          (comp sort-field :rating)
      sort-field)))

(defn- normalize-comparable
  [comparable]
  (cond
    (nil? comparable)        comparable
    (number? comparable)     comparable
    :else (string/lower-case (str comparable))))

(defn- app-sorter-comparator
  [sort-dir]
  (if (and sort-dir (= (string/upper-case sort-dir) "DESC"))
    #(compare (normalize-comparable %2) (normalize-comparable %1))
    #(compare (normalize-comparable %1) (normalize-comparable %2))))

(defn- app-sorter
  [sort-field sort-dir]
  (partial sort-by
           (app-sorter-keyfn sort-field)
           (app-sorter-comparator sort-dir)))

(defn sort-apps
  [res {:keys [sort-field sort-dir]} & [{:keys [default-sort-field]}]]
  (if-let [sort-field (or sort-field default-sort-field)]
    (update-in res [:apps] (app-sorter sort-field sort-dir))
    res))

(defn apply-offset
  [res params]
  (let [offset (param->long (:offset params "0"))]
    (if (pos? offset)
      (update-in res [:apps] (partial drop offset))
      res)))

(defn apply-limit
  [res params]
  (let [limit (param->long (:limit params "0"))]
    (if (pos? limit)
      (update-in res [:apps] (partial take limit))
      res)))

(defn uuid?
  [s]
  (or (instance? UUID s)
      (re-find #"\A\p{XDigit}{8}(?:-\p{XDigit}{4}){3}-\p{XDigit}{12}\z" s)))

(defn extract-uuids
  [ids]
  (seq (map uuids/uuidify (filter uuid? ids))))

(defn uuidify
  [id]
  (if-not (uuid? id)
    (cxu/bad-request (str "'" id "' is not a UUID"))
    (uuids/uuidify id)))

(defn default-search-params
  [params default-sort-field default-sort-dir]
  (remove-nil-vals
   {:limit          (:limit params 0)
    :offset         (:offset params 0)
    :sort-field     (keyword (:sort-field params default-sort-field))
    :sort-dir       (keyword (:sort-dir params default-sort-dir))
    :filter         (:filter params)
    :include-hidden (:include-hidden params false)}))

(defn format-job-stats [app admin?]
  (let [job-stats-keys [:job_count
                        :job_count_completed
                        :job_count_failed
                        :job_last_completed
                        :last_used]
        stats-to-show  (if admin? job-stats-keys [:job_count_completed :job_last_completed])
        app            (assoc app :job_stats (remove-nil-vals (select-keys app stats-to-show)))]
    (apply dissoc app job-stats-keys)))
