(ns apps.service.bootstrap
  (:use [apps.service.apps :only [list-system-ids]]
        [apps.service.workspace :only [get-workspace]]
        [apps.webhooks :only [get-webhooks]]))

(defn bootstrap [current-user]
  {:system_ids (list-system-ids current-user)
   :workspace  (get-workspace current-user)
   :webhooks (get-webhooks (:shortUsername current-user))})
