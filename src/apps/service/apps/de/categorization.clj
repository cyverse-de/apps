(ns apps.service.apps.de.categorization
  (:require
   [apps.clients.metadata :as metadata-client]
   [apps.clients.permissions :as perms-client]
   [apps.persistence.app-groups :refer [add-app-to-category decategorize-app get-app-category]]
   [apps.persistence.app-listing :refer [get-app-listing]]
   [apps.service.apps.de.validation :as av]
   [apps.util.config :as config]
   [apps.util.db :refer [transaction]]
   [clojure-commons.exception-util :as cxu]
   [kameleon.uuids :refer [uuidify]]))

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
   (:avus (metadata-client/list-avus username app-id))))

(defn- validate-app
  [app-id category-ids]
  (let [app (get-app-listing app-id)]
    (when-not app
      (cxu/not-found (str "could not find app with ID: " app-id)))
    (av/validate-app-name (:name app) app-id (mapv :id category-ids))))

(defn- validate-category-id
  [category-id]
  (let [category (get-app-category category-id)]
    (when-not category
      (cxu/not-found (str "could not find app category with ID: " category-id)))
    (when (seq (:app_categories category))
      (cxu/bad-request (str "category " category-id " contains subcategories")))))

(defn- validate-categorization-request
  [app-id category-ids]
  (when (empty? category-ids)
    (cxu/bad-request "no categories provided in app categorization request"))
  (validate-app app-id category-ids)
  (dorun (map (comp validate-category-id uuidify :id) category-ids)))

(defn- categorize-app
  "Associates an app with an app category."
  [{app-id :app_id category-ids :category_ids}]
  (let [app-id (uuidify app-id)]
    (validate-categorization-request app-id category-ids)
    (decategorize-app app-id)
    (dorun (map (comp (partial add-app-to-category app-id) uuidify :id) category-ids))))

(defn categorize-apps
  "A service that categorizes one or more apps in the database."
  [categories]
  (transaction (dorun (map categorize-app categories))))
