(ns apps.service.apps.combined.job-view
  (:use [apps.util.assertions :only [assert-not-nil]])
  (:require [apps.persistence.app-metadata :as ap]
            [apps.persistence.jobs :as jp]
            [apps.service.apps.combined.util :as util]))

(defn- remove-mapped-inputs
  [mapped-props group]
  (assoc group :parameters (remove (comp mapped-props :id) (:parameters group))))

(defn- reformat-group
  [app-name step-id group]
  (assoc group
    :name       (str app-name " - " (:name group))
    :label      (str app-name " - " (:label group))
    :parameters (mapv (fn [prop] (assoc prop :id (str step-id "_" (:id prop))))
                      (:parameters group))))

(defn- get-mapped-props
  [step-id]
  (->> (ap/load-target-step-mappings step-id)
       (map (fn [{ext-id :external_input_id id :input_id}]
              (str (first (remove nil? [ext-id id])))))
       (set)))

(defn- get-external-app
  [clients external-app-id]
  (assert-not-nil
   [:app-id external-app-id]
   (first (remove nil? (map #(.getAppJobView % external-app-id) clients)))))

(defn- get-external-groups
  [clients step external-app-id]
  (let [app          (get-external-app clients external-app-id)
        mapped-props (get-mapped-props (:step_id step))]
    (->> (:groups app)
         (map (partial remove-mapped-inputs mapped-props))
         (remove (comp empty? :parameters))
         (map (partial reformat-group (:name app) (:step_id step)))
         (doall))))

(defn- get-combined-groups
  [clients app-id groups]
  (loop [acc            []
         groups         groups
         [step & steps] (ap/load-app-steps app-id)
         step-number    1]
    (let [before-current-step #(<= (:step_number %) step-number)
          external-app-id     (:external_app_id step)]
      (cond
       ;; We're out of steps.
       (nil? step)
       acc

       ;; The current step is an external step.
       external-app-id
       (recur (concat acc (get-external-groups clients step external-app-id))
              groups
              steps
              (inc step-number))

       ;; The current step is a DE step.
       :else
       (recur (concat acc (take-while before-current-step groups))
              (drop-while before-current-step groups)
              steps
              (inc step-number))))))

(defn- format-app-submission-info
  [app clients current-client]
  [(.getJobTypes current-client)
   (if (= (.getClientName current-client) jp/de-client-name)
     (update-in app [:groups] (partial get-combined-groups clients (:id app)))
     app)])

(defn- get-app-from-client
  ([app-id clients current-client]
   (when-let [app (.getAppJobView current-client app-id)]
     (format-app-submission-info app clients current-client)))
  ([system-id app-id clients current-client]
   (-> (.getAppJobView current-client system-id app-id)
       (format-app-submission-info clients current-client))))

(defn- get-app*
  [app-id clients]
  (->> (map (partial get-app-from-client app-id clients) clients)
       (remove nil?)
       (first)))

(defn get-app
  ([app-id clients]
   (second (get-app* app-id clients)))
  ([system-id app-id clients]
   (->> (util/get-apps-client clients system-id)
        (get-app-from-client system-id app-id clients)
        second)))

(defn get-app-submission-info
  [app-id clients]
  (get-app* app-id clients))
