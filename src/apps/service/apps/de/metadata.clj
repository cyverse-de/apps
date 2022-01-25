(ns apps.service.apps.de.metadata
  "DE app metadata services."
  (:use [clojure.java.io :only [reader]]
        [clojure-commons.client :only [build-url]]
        [clojure-commons.core :only [unique-by]]
        [apps.persistence.app-groups :only [add-app-to-category
                                            decategorize-app
                                            get-app-subcategory-id
                                            remove-app-from-category]]
        [apps.service.apps.de.validation :only [app-publishable?
                                                validate-app-existence
                                                verify-app-permission]]
        [apps.util.db :only [transaction]]
        [apps.validation :only [get-valid-user-id]]
        [apps.workspace :only [get-workspace]]
        [kameleon.uuids :only [uuidify]]
        [slingshot.slingshot :only [throw+ try+]])
  (:require [apps.clients.email :as email-client]
            [apps.clients.metadata :as metadata-client]
            [apps.clients.notifications :as notifications]
            [apps.clients.permissions :as perms-client]
            [apps.persistence.app-metadata :as amp]
            [apps.service.apps.communities :as communities]
            [apps.service.apps.de.docs :as app-docs]
            [apps.service.apps.de.permissions :as perms]
            [apps.translations.app-metadata :as atx]
            [apps.util.config :as config]
            [cheshire.core :as cheshire]
            [clj-http.client :as client]
            [clojure.tools.logging :as log]))

(defn- app-ids-from-deletion-request
  "Extracts the app IDs from a deletion request."
  [req]
  (mapv (comp uuidify :app_id) (:app_ids req)))

(defn validate-deletion-request
  "Validates an app deletion request."
  [{username :shortUsername} req]
  (when (empty? (:app_ids req))
    (throw+ {:type  :clojure-commons.exception/bad-request-field
             :error "no app identifiers provided"}))
  (when (and (nil? username) (not (:root_deletion_request req)))
    (throw+ {:type  :clojure-commons.exception/bad-request-field
             :error "no username provided for non-root deletion request"}))
  (let [app-ids (app-ids-from-deletion-request req)]
    (dorun (map validate-app-existence app-ids))
    (when-not (:root_deletion_request req)
      (perms/check-app-permissions username "own" app-ids))))

(defn- permanently-delete-app
  "Permanently deletes a single app from the database."
  [app-id]
  (amp/permanently-delete-app app-id)
  (try+
   (perms-client/delete-app-resource app-id)
   (catch [:status 404] _
     (log/warn "app resource" app-id "not found by permissions service"))))

(defn permanently-delete-apps
  "This service removes apps from the database rather than merely marking them as deleted."
  [user req]
  (println "Permanently deleting some apps: " req)
  (transaction
   (dorun (map permanently-delete-app (app-ids-from-deletion-request req)))
   (amp/remove-workflow-map-orphans))
  nil)

(defn delete-apps
  "This service marks existing apps as deleted in the database."
  [user req]
  (transaction (dorun (map amp/delete-app (app-ids-from-deletion-request req))))
  {})

(defn delete-app
  "This service marks an existing app as deleted in the database."
  [{username :shortUsername} app-id]
  (validate-app-existence app-id)
  (perms/check-app-permissions username "own" [app-id])
  (amp/delete-app app-id)
  {})

(defn preview-command-line
  "This service sends a command-line preview request to the JEX."
  [body]
  (let [jex-req (atx/template-cli-preview-req body)]
    (cheshire/decode-stream
     ((comp reader :body)
      (client/post
       (build-url (config/jex-base-url) "arg-preview")
       {:body             (cheshire/encode jex-req)
        :content-type     :json
        :as               :stream}))
     true)))

(defn rate-app
  "Adds or updates a user's rating and comment ID for the given app. The request must contain either
   the rating or the comment ID, and the rating must be between 1 and 5, inclusive."
  [user app-id {:keys [rating comment_id] :as request}]
  (validate-app-existence app-id)
  (let [user-id (get-valid-user-id (:username user))]
    (when (and (nil? rating) (nil? comment_id))
      (throw+ {:type  :clojure-commons.exception/bad-request-field
               :error (str "No rating or comment ID given")}))
    (when (or (> 1 rating) (> rating 5))
      (throw+ {:type  :clojure-commons.exception/bad-request-field
               :error (str "Rating must be an integer between 1 and 5 inclusive."
                           " Invalid rating (" rating ") for App ID " app-id)}))
    (when-not (contains? (perms-client/get-public-app-ids) app-id)
      (throw+ {:type  :clojure-commons.exception/bad-request-field
               :error (str "Unable to rate private app, " app-id)}))
    (amp/rate-app app-id user-id request)
    (amp/get-app-avg-rating app-id)))

(defn delete-app-rating
  "Removes a user's rating and comment ID for the given app."
  [user app-id]
  (validate-app-existence app-id)
  (let [user-id (get-valid-user-id (:username user))]
    (when-not (contains? (perms-client/get-public-app-ids) app-id)
      (throw+ {:type  :clojure-commons.exception/bad-request-field
               :error (str "Unable to remove rating from private app, " app-id)}))
    (amp/delete-app-rating app-id user-id)
    (amp/get-app-avg-rating app-id)))

(defn- get-favorite-category-id
  "Gets the current user's Favorites category ID."
  [user]
  (get-app-subcategory-id
   (:root_category_id (get-workspace (:username user)))
   (config/workspace-favorites-app-category-index)))

(defn add-app-favorite
  "Adds the given app to the current user's favorites list."
  [user app-id]
  (let [app (amp/get-app app-id)
        fav-category-id (get-favorite-category-id user)]
    (verify-app-permission user app "read")
    (add-app-to-category app-id fav-category-id))
  nil)

(defn remove-app-favorite
  "Removes the given app from the current user's favorites list."
  [user app-id]
  (let [app (amp/get-app app-id)
        fav-category-id (get-favorite-category-id user)]
    (remove-app-from-category app-id fav-category-id))
  nil)

(defn- beta-avu
  "Builds the Beta AVU map from config values"
  []
  {:attr  (config/workspace-metadata-beta-attr-iri)
   :value (config/workspace-metadata-beta-value)
   :unit  ""
   :avus  [{:attr  "rdfs:label"
            :value (config/workspace-metadata-beta-attr-label)
            :unit  "attr"}]})

(defn- admin->communities-map
  "Takes a `community-name` and its corresponding `admin-set` and returns a map like the following:
  {admin1 [community-name],
   admin2 [community-name],
   admin3 [community-name]}"
  [community-name admin-set]
  (zipmap admin-set (repeat [community-name])))

(defn- notify-community-admins
  [username integrator-name app-name community-names]
  (->> community-names
       (map #(admin->communities-map % (communities/get-community-admin-set username %)))
       (apply merge-with into)
       ;; if community1 has admin1 and 2, community2 has admin2 and 3, and community3 has admin3,
       ;; then by this point there should be a map like the following:
       ;; {admin1 [community1],
       ;;  admin2 [community1, community2],
       ;;  admin3 [community2, community3]}
       (map (partial apply notifications/send-community-admin-notification username integrator-name app-name))
       dorun))

(defn- publish-app-metadata
  "Publishes all metadata sent in the publish request, including ontology AVU tags, but does not publish community tags.
   Instead, community admins are notified that the app integrator wishes to add their app to those communities."
  [username app-id app-name avus]
  (let [body (as-> avus m
               (remove #(= (config/workspace-metadata-communities-attr) (:attr %)) m)
               (conj m (beta-avu))
               (cheshire/encode {:avus m}))]
    (metadata-client/update-avus username app-id body))

  (if-let [community-names (communities/extract-full-community-names avus)]
    (notify-community-admins username
                             (:integrator_name (amp/get-integration-data-by-app-id app-id))
                             app-name
                             community-names)))

(defn- publish-app
  [{:keys [shortUsername username] :as user} {app-id :id :keys [name references avus] :as app}]
  (let [publication-requests (amp/list-app-publication-requests app-id nil false)
        request-ids          (mapv :id publication-requests)
        app-name             (or name (amp/get-app-name app-id))
        app                  (assoc app :version_id (amp/get-app-latest-version app-id))]
    (transaction
     (amp/update-app app)
     (amp/update-app-version app true)
     (when (:documentation app) (app-docs/add-app-docs user app-id app))
     (when references (amp/set-app-references app-id references))
     (decategorize-app app-id)
     (publish-app-metadata shortUsername app-id app-name avus)
     (perms-client/make-app-public shortUsername app-id)
     (when (seq publication-requests)
       (amp/mark-app-publication-requests-complete request-ids username)))
    (mapv (partial notifications/send-app-published-notification shortUsername app-name)
          (unique-by :requestor publication-requests))
    nil))

(defn- verify-app-documentation
  [user {app-id :id docs :documentation}]
  (when-not (or docs (app-docs/has-docs? app-id))
    (throw+ {:type  :clojure-commons.exception/bad-request-field
             :error (str "App " app-id " does not have documentation.")})))

(defn- publish-app-tools
  [app-id]
  (let [public-tool-ids (perms-client/get-public-tool-ids)]
    (->> (map :id (amp/get-app-tools app-id))
         (remove public-tool-ids)
         (map perms-client/make-tool-public)
         dorun)))

(defn make-app-public
  [user {app-id :id :as app}]
  (verify-app-documentation user app)
  (publish-app-tools app-id)
  (publish-app user app))

(defn create-publication-request
  [{username :username short-username :shortUsername :as user}
   {app-id :id app-name :name :keys [references avus] :as app}
   untrusted-tools]
  (transaction
   (let [app-name (or app-name (amp/get-app-name app-id))]
    (amp/update-app app)
    (when (:documentation app) (app-docs/add-app-docs user app-id app))
    (when references (amp/set-app-references app-id references))
    (publish-app-metadata short-username app-id app-name avus)
    (let [request-id (amp/create-publication-request username app-id)]
      (email-client/send-app-publication-request-email username app-name request-id untrusted-tools)))
   nil))

(defn get-app
  "This service obtains an app description that can be used to build a job submission form in
   the user interface."
  [app-id]
  (amp/get-app app-id))

(defn get-param-definitions
  [app-id]
  (filter (comp nil? :external_app_id) (amp/get-app-parameters app-id)))
