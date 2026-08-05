(ns apps.service.groups
  (:require [apps.clients.groups :as ipg]))

(defn get-workshop-group []
  (select-keys (ipg/get-workshop-group)
               [:id :name :group_type :display_name :description]))

(defn get-workshop-group-members []
  (ipg/get-workshop-group-members))

(defn- member-subject
  "Shapes a successful membership result as a subject."
  [{:keys [subject_id source_id subject_name]}]
  (cond-> {:id subject_id :source_id source_id}
    subject_name (assoc :name subject_name)))

(defn update-workshop-group-members
  "Replaces the workshop group membership. The update is a full replacement, so
   the successful results are the group's new membership."
  [subject-ids]
  (let [results (:results (ipg/update-workshop-group-members subject-ids))]
    {:members  (mapv member-subject (filter :success results))
     :failures (mapv :subject_id (remove :success results))}))
