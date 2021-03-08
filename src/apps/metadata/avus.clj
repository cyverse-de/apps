(ns apps.metadata.avus
  (:require [apps.clients.metadata :as metadata-client]
            [apps.persistence.app-metadata :as app-db]
            [apps.service.apps.de.categorization :as categorization]
            [apps.service.apps.de.validation :as validation]
            [cheshire.core :as json]))

(defn- load-protected-avus
  [username app-id]
  (->> (:avus (metadata-client/list-avus username app-id))
       (filter (comp validation/protected-attrs :attr))))

(defn- remove-protected-avus
  [avus admin?]
  (if admin? avus (remove (comp validation/protected-attrs :attr) avus)))

(defn list-avus
  "Lists AVUs associated with an app. If `admin?` is `true` then all attributes will be listed. If `admin?`
   is `false` then protected attributes will not be included in the listing."
  [{username :shortUsername :as user} app-id admin?]
  (let [app (app-db/get-app app-id)]
    (validation/verify-app-permission user app "read" admin?)
    (as-> (metadata-client/list-avus username app-id) r
      (update r :avus remove-protected-avus admin?))))

(defn set-avus
  "Sets AVUs on an app. If `admin?` is `true` then any attribute may be updated and all attributes on the app
   will be replaced. If `admin?` is `false` then protected attributes may not be included in the request body,
   and any protected attributes that are already associated with the app will remain associated with the app."
  [{username :shortUsername :as user} app-id {:keys [avus]} admin?]
  (let [{app-name :name :as app} (app-db/get-app app-id)]
    (validation/verify-app-permission user app "write" admin?)
    (validation/validate-attrs-not-protected admin? avus)
    (categorization/validate-app-name-in-hierarchy-avus username app-id app-name avus)
    (let [new-avus (if admin? avus (concat avus (load-protected-avus username app-id)))]
      (as-> (json/encode {:avus new-avus}) r
        (metadata-client/set-avus username app-id r)
        (update r :avus remove-protected-avus admin?)))))

(defn update-avus
  "Adds AVUs to an app or updates existing AVUs on an app. If `admin?` is `true` then any attribute may be added.
   If `admin?` is `false` then protected attributes may not be included in the request body."
  [{username :shortUsername :as user} app-id {:keys [avus] :as request} admin?]
  (let [{app-name :name :as app} (app-db/get-app app-id)]
    (validation/verify-app-permission user app "write" admin?)
    (validation/validate-attrs-not-protected admin? avus)
    (categorization/validate-app-name-in-hierarchy-avus username app-id app-name avus)
    (as-> (json/encode request) r
      (metadata-client/update-avus username app-id r)
      (update r :avus remove-protected-avus admin?))))
