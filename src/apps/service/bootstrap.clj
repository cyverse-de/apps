(ns apps.service.bootstrap
  (:require
   [apps.service.apps :refer [list-system-ids]]
   [apps.service.workspace :refer [get-workspace]]
   [apps.webhooks :refer [get-webhooks]]))

(defn bootstrap [current-user]
  (let [si (future (list-system-ids current-user))
        w  (future (get-workspace current-user))
        wh (future (get-webhooks (:shortUsername current-user)))]
    {:system_ids @si
     :workspace  @w
     :webhooks @wh}))
