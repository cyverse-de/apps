(ns apps.clients.async-tasks
  (:require [apps.util.config :as config]
            [clojure.string :as string]
            [clojure.tools.logging :as log]
            [async-tasks-client.core :as async-tasks-client]))

(defn get-by-id
  [id]
  (async-tasks-client/get-by-id (config/async-tasks-client) id))

(defn delete-by-id
  [id]
  (async-tasks-client/delete-by-id (config/async-tasks-client) id))

(defn create-task
  [task]
  (async-tasks-client/create-task (config/async-tasks-client) task))

(defn add-status
  [id status]
  (async-tasks-client/add-status (config/async-tasks-client) id status))

(defn add-completed-status
  [id status]
  (async-tasks-client/add-completed-status (config/async-tasks-client) id status))

(defn add-behavior
  [id behavior]
  (async-tasks-client/add-behavior (config/async-tasks-client) id behavior))

(defn get-by-filter
  [filters]
  (async-tasks-client/get-by-filter (config/async-tasks-client) filters))

(defn run-async-thread
  [async-task-id thread-function prefix]
  (let [^Runnable task-thread (fn [] (thread-function async-task-id))]
    (.start (Thread. task-thread (str prefix "-" (string/replace async-task-id #".*/tasks/" "")))))
  async-task-id)

(defn new-task
  [type user data & [{:keys [timeout] :or {timeout "10m"}}]]
  (create-task
   {:type      type
    :username  user
    :data      data
    :statuses  [{:status "registered"}]
    :behaviors [{:type "statuschangetimeout"
                 :data {:statuses [{:start_status "running" :end_status "detected-stalled" :timeout timeout}]}}]}))

(defn update-fn
  [async-task-id status]
  (fn [detail]
    (log/info detail)
    (add-status async-task-id {:status status :detail detail})))
