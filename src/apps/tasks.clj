(ns apps.tasks
  (:require [apps.service.apps :as apps]))

(defn set-logging-context!
  "Sets the logging ThreadContext for the threads in the task thread pool."
  [cm]
  (apps/set-logging-context! cm))
