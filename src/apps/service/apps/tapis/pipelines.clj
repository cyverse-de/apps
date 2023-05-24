(ns apps.service.apps.tapis.pipelines
  (:require [apps.util.service :as service]))

(defn- get-tapis-task
  [tapis external-app-id]
  ((comp first :tasks)
   (service/assert-found (.listAppTasks tapis external-app-id) "Tapis app" external-app-id)))

(defn- format-task
  [tapis external-app-ids {:keys [id] :as task}]
  (if-let [external-app-id (external-app-ids id)]
    (assoc (merge task (select-keys (get-tapis-task tapis external-app-id) [:inputs :outputs]))
           :id external-app-id)
    task))

(defn format-pipeline-tasks
  [tapis pipeline]
  (let [external-app-ids (into {} (map (juxt :task_id :external_app_id) (:steps pipeline)))]
    (update-in pipeline [:tasks] (partial map (partial format-task tapis external-app-ids)))))
