(ns apps.service.apps.combined.util
  (:use [apps.service.util :only [sort-apps apply-offset apply-limit uuid?]]
        [slingshot.slingshot :only [throw+]])
  (:require [apps.persistence.app-metadata :as ap]
            [apps.persistence.jobs :as jp]))

(defn apply-default-search-params
  [params]
  (assoc params
    :sort-field (or (:sort-field params) "name")
    :sort-dir   (or (:sort-dir params) "ASC")))

(defn combine-app-listings
  "Expects results to be a list of maps in a format like {:total int, :apps []}"
  [params results]
  (let [params (apply-default-search-params params)]
    (-> {:total (apply + (map :total results))
         :apps  (mapcat :apps results)}
        (sort-apps params)
        (apply-offset params)
        (apply-limit params))))

(defn get-apps-client
  ([clients]
   (or (first (filter #(.supportsSystemId % jp/de-client-name) clients))
       (throw+ {:type  :clojure-commons.exception/internal-system-error
                :error "default system ID not found"})))
  ([clients system-id]
     (or (first (filter #(.supportsSystemId % system-id) clients))
         (throw+ {:type  :clojure-commons.exception/bad-request-field
                  :error (str "unrecognized system ID " system-id)}))))

(defn apps-client-for-job
  [{system-id :system_id} clients]
  (get-apps-client clients system-id))

(defn apps-client-for-app-step
  [clients job-step]
  (if (:external_app_id job-step)
    (get-apps-client clients jp/agave-client-name)
    (get-apps-client clients jp/de-client-name)))

(defn is-de-job-step?
  [job-step]
  (= (:job-type job-step) jp/de-job-type))

(defn apps-client-for-job-step
  [clients job-step]
  (if (is-de-job-step? job-step)
    (get-apps-client clients jp/de-client-name)
    (get-apps-client clients jp/agave-client-name)))
