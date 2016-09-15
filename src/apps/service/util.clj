(ns apps.service.util
  (:use [apps.transformers :only [param->long]]
        [apps.util.conversions :only [remove-nil-vals]])
  (:require [clojure.string :as string]
            [clojure-commons.exception-util :as cxu]
            [kameleon.uuids :as uuids])
  (:import [java.util UUID]))

(defn- app-sorter
  [sort-field sort-dir]
  (partial sort-by
           (keyword sort-field)
           (if (and sort-dir (= (string/upper-case sort-dir) "DESC"))
             #(compare (string/lower-case %2) (string/lower-case %1))
             #(compare (string/lower-case %1) (string/lower-case %2)))))

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
