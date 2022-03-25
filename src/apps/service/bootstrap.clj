(ns apps.service.bootstrap
  (:use [apps.service.apps :only [list-system-ids]]
        [apps.service.workspace :only [get-workspace]]
        [apps.webhooks :only [get-webhooks]]))

(defn bootstrap [current-user]
  (let [si (future (list-system-ids current-user))
        w  (future (get-workspace current-user))
        wh (future (get-webhooks (:shortUsername current-user)))]
    {:system_ids @si
     :workspace  @w
     :webhooks @wh}))
