(ns apps.clients.groups
  (:require [apps.util.config :as config]
            [cemerick.url :as curl]
            [clj-http.client :as http]
            [clojure.string :as string]
            [clojure.tools.logging :as log]
            [slingshot.slingshot :refer [try+]]))

(def ^:private de-users-group "de-users")
(def ^:private workshop-users-group "workshop-users")

(defn- groups-url
  [& components]
  (str (apply curl/url (config/groups-base) components)))

(defn- as-de-grouper
  ([] (as-de-grouper {}))
  ([query-params]
   {:query-params (assoc query-params :user (config/de-grouper-user))
    :as           :json}))

(defn user-source? [subject-source-id]
  (= subject-source-id (config/grouper-user-source)))

(defn get-subject-type [subject-source-id]
  (if (user-source? subject-source-id) "user" "group"))

(defn lookup-subject
  "Retrieves user details for a single subject."
  [user short-username]
  (:body (http/get (groups-url "subjects" short-username)
                   {:query-params {:user user} :as :json})))

(defn lookup-subjects
  "Looks up multiple subjects by subject ID, returning a map of ID to subject."
  [subjects]
  (->> (http/post (groups-url "subjects" "lookup")
                  (assoc (as-de-grouper)
                         :form-params  {:subject_ids (vec (set subjects))}
                         :content-type :json))
       :body
       :subjects
       (map (juxt :id identity))
       (into {})))

(defn lookup-subject-groups
  "Retrieves the groups that a subject belongs to."
  [short-username]
  (:body (http/get (groups-url "subjects" short-username "groups") (as-de-grouper))))

(defn- lookup-group
  "Resolves a group's structured identity to the group itself, or nil if there is
   no such group."
  [group-type name]
  (try+
   (:body (http/get (groups-url "groups" "lookup")
                    (as-de-grouper {:group_type group-type :name name})))
   (catch [:status 404] _ nil)))

(defn- get-group-by-id
  [group-id]
  (try+
   (:body (http/get (groups-url "groups" group-id) (as-de-grouper)))
   (catch [:status 404] _ nil)))

;; Group IDs are 32 hex digits: Grouper's own format for imported groups, and
;; what `subjects.subject_id` defaults to for ones created since.
(def ^:private group-id-pattern #"^[0-9a-f]{32}$")

(defn- community-short-name
  "The short name from a legacy Grouper community path, or nil for anything
   else. Tags written before the migration hold the full path; only a path
   whose second-to-last segment is `communities` names a community, so taking
   the last segment of any other path could capture an unrelated group's name."
  [identifier]
  (let [segments (string/split identifier #":")]
    (when (and (<= 2 (count segments))
               (= "communities" (nth segments (- (count segments) 2))))
      (last segments))))

(defn lookup-community
  "Resolves a community identifier to the community itself, or nil. Accepts a
   group ID, a plain name, or a legacy colon-delimited Grouper community path,
   so that a browser holding a stale bundle still names something real."
  [identifier]
  (let [group (if (re-matches group-id-pattern identifier)
                (get-group-by-id identifier)
                (if-let [short-name (community-short-name identifier)]
                  (do (log/warn "resolving community identifier" identifier "as legacy Grouper path;"
                                "the requesting browser is probably running a stale bundle")
                      (lookup-group "community" short-name))
                  (lookup-group "community" identifier)))]
    (when (= "community" (:group_type group))
      group)))

(defn- create-group
  [group-type name]
  (:body (http/post (groups-url "groups")
                    (assoc (as-de-grouper)
                           :form-params  {:group_type group-type :name name}
                           :content-type :json))))

(defn- get-or-create-group
  [group-type name]
  (or (lookup-group group-type name)
      (create-group group-type name)))

;; The de-users group is created by the deployment rather than by apps, so a
;; missing one is a misconfiguration worth failing on rather than papering over.
(def de-users-group-id
  (memoize (fn [] (:id (:body (http/get (groups-url "groups" "lookup")
                                        (as-de-grouper {:group_type "system" :name de-users-group})))))))

(defn add-de-user
  "Adds a user to the de-users group."
  [subject-id]
  (http/put (groups-url "groups" (de-users-group-id) "members" subject-id)
            (as-de-grouper)))

(defn list-group-members-by-id
  "Lists the members of the group with the given ID."
  [user group-id]
  (:body (http/get (groups-url "groups" group-id "members")
                   {:query-params {:user user} :as :json})))

(defn get-workshop-group
  "Retrieves information about the workshop users group, creating it if necessary."
  []
  (get-or-create-group "system" workshop-users-group))

(defn get-workshop-group-members
  "Retrieves the list of workshop group members, creating the group if necessary."
  []
  (list-group-members-by-id (config/de-grouper-user) (:id (get-workshop-group))))

(defn update-workshop-group-members
  "Updates the list of workshop group members, creating the group if necessary."
  [subject-ids]
  (:body (http/put (groups-url "groups" (:id (get-workshop-group)) "members")
                   (assoc (as-de-grouper)
                          :form-params  {:members subject-ids}
                          :content-type :json))))

;; Both `admin` and `own` count: the importer maps Grouper's `admins` to
;; `admin`, but a community created natively grants `own` to whoever created it,
;; and that person administers it too.
(def ^:private community-admin-levels #{"admin" "own"})

(defn list-community-admins
  "Lists the administrators of the community with the given ID."
  [community-id]
  (->> (:permissions (:body (http/get (groups-url "groups" community-id "permissions") (as-de-grouper))))
       (filter (comp community-admin-levels :level))
       (map :subject)
       (filter (comp (partial = "user") :subject_type))
       (mapv (fn [{:keys [subject_id]}] {:id subject_id}))
       (remove (comp (partial = (config/de-grouper-user)) :id))
       (hash-map :members)))
