(ns apps.metadata.avus
  (:require [apps.clients.metadata :as metadata-client]
            [apps.persistence.app-metadata :as app-db]
            [apps.service.apps.de.categorization :as categorization]
            [apps.service.apps.de.validation :as validation]
            [cheshire.core :as json]))

(defn list-avus
  [{username :shortUsername :as user} app-id admin?]
  (let [app (app-db/get-app app-id)]
    (validation/verify-app-permission user app "read" admin?)
    (metadata-client/list-avus username app-id)))

(defn set-avus
  [{username :shortUsername :as user} app-id {:keys [avus] :as request} admin?]
  (let [{app-name :name :as app} (app-db/get-app app-id)]
    (validation/verify-app-permission user app "write" admin?)
    (validation/validate-attrs-not-protected admin? avus)
    (categorization/validate-app-name-in-hierarchy-avus username app-id app-name avus)
    (metadata-client/set-avus username app-id (json/encode request))))

(defn update-avus
  [{username :shortUsername :as user} app-id {:keys [avus] :as request} admin?]
  (let [{app-name :name :as app} (app-db/get-app app-id)]
    (validation/verify-app-permission user app "write" admin?)
    (validation/validate-attrs-not-protected admin? avus)
    (categorization/validate-app-name-in-hierarchy-avus username app-id app-name avus)
    (metadata-client/update-avus username app-id (json/encode request))))
