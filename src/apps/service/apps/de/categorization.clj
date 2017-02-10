(ns apps.service.apps.de.categorization
  (:use [apps.persistence.app-groups :only [add-app-to-category decategorize-app]]
        [kameleon.uuids :only [uuidify]])
  (:require [apps.clients.metadata :as metadata-client]
            [apps.clients.permissions :as perms-client]
            [apps.service.apps.de.validation :as av]
            [apps.util.config :as config]))

(defn validate-app-name-in-hierarchy-avus
  [username app-id app-name avus]
  (let [category-attrs    (set (config/workspace-metadata-category-attrs))
        hierarchy-avus    (->> avus
                               (filter #(contains? category-attrs (:attr %)))
                               (map #(select-keys % [:attr :value])))
        other-app-ids     (disj (set (keys (perms-client/load-app-permissions username))) app-id)
        hierarchy-app-ids (when-not (or (empty? other-app-ids) (empty? hierarchy-avus))
                            (metadata-client/filter-by-avus username other-app-ids hierarchy-avus))]
    (when-not (empty? hierarchy-app-ids)
      (av/validate-app-name-in-hierarchy app-name hierarchy-app-ids))))

(defn validate-app-name-in-current-hierarchy
  [username app-id app-name]
  (validate-app-name-in-hierarchy-avus
    username
    app-id
    app-name
    (-> (metadata-client/list-avus username app-id {:as :json})
        :body
        :avus)))

(defn- categorize-app
  "Associates an app with an app category."
  [{app-id :app_id category-ids :category_ids}]
  (let [app-id (uuidify app-id)]
    (decategorize-app app-id)
    (dorun (map (comp (partial add-app-to-category app-id) uuidify :id) category-ids))))

(defn categorize-apps
  "A service that categorizes one or more apps in the database."
  [categories]
  (dorun (map categorize-app categories)))
