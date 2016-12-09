(ns apps.service.apps.combined.pipelines)

(defn format-pipeline [apps-client pipeline]
  (let [fix-task-id  (fn [step] (assoc step :task_id (or (:external_app_id step) (:task_id step))))
        fix-task-ids (fn [steps] (mapv fix-task-id steps))]
    (update-in (.formatPipelineTasks apps-client pipeline) [:steps] fix-task-ids)))
