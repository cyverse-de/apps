(ns apps.service.groups
  (:require [apps.clients.groups :as groups-client]))

(defn get-workshop-group []
  (select-keys (groups-client/get-workshop-group)
               [:id :name :group_type :display_name :description]))

(defn get-workshop-group-members []
  ;; Selected rather than forwarded: the route validates this against the closed
  ;; GroupMembers schema, so any field the groups service adds to its own
  ;; response would otherwise fail response coercion here.
  (select-keys (groups-client/get-workshop-group-members) [:members]))

(defn update-workshop-group-members
  "Replaces the workshop group membership. The replacement results list only
   the changes performed -- kept members appear in neither list and removals
   report success -- so the new membership comes from re-reading the group;
   only the failures come from the results."
  [subject-ids]
  (let [results (:results (groups-client/update-workshop-group-members subject-ids))]
    {:members  (:members (get-workshop-group-members))
     :failures (mapv :subject_id (remove :success results))}))
