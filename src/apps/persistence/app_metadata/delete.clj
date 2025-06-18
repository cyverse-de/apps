(ns apps.persistence.app-metadata.delete
  "Functions used to remove apps from the database."
  (:require [apps.persistence.entities :as entities]
            [apps.user :refer [current-user]]
            [apps.util.db :refer [transaction]]
            [clojure.tools.logging :as log]
            [korma.core :as sql])
  (:refer-clojure :exclude [count]))

;; Declarations for special symbols used by Korma.
(declare count)

(defn- tasks-for-app
  "Loads the list of tasks associated with an app."
  [app-id]
  (sql/select entities/tasks
              (sql/join [:app_steps :step]
                        {:step.task_id :tasks.id})
              (sql/join [:app_versions :versions]
                        {:step.app_version_id :versions.id})
              (sql/where {:versions.app_id app-id})))

(defn- task-orphaned?
  "Determines whether or not a task is orphaned."
  [task-id]
  ((comp zero? :count first)
   (sql/select :app_steps
               (sql/aggregate (count :*) :count)
               (sql/where {:task_id task-id}))))

(defn- delete-orphaned-task
  "Deletes a task if it's orphaned (that is, if it's not used in any app). Task deletes should
   cascade to parameter groups, parameters, and other parameter related tables."
  [task-id]
  (when (task-orphaned? task-id)
    (sql/delete entities/tasks (sql/where {:id task-id}))))

(defn- remove-app
  "Removes an app from the database. App deletes should cascade to app related tables like ratings,
   references, steps, and mappings."
  [app-id]
  (sql/delete entities/apps (sql/where {:id app-id})))

(defn permanently-delete-app
  "Permanently removes an app from the database."
  [app-id]
  (transaction
   (let [tasks    (tasks-for-app app-id)
         task-ids (mapv :id tasks)]
     (log/warn (:username current-user) "permanently deleting App" app-id)
     (remove-app app-id)
     (dorun (map delete-orphaned-task task-ids)))))
