(ns apps.harness.fakes
  "In-process fakes for the services that apps calls over HTTP. Each fake keeps just enough state to behave like the
   real service for the requests apps makes, so tests describe what happens (share an app, then list it) rather than
   scripting the exact calls in between.

   Any request that no fake handles is answered with a 501 and recorded, as is any fake response that doesn't match
   the real service's schema. The endpoint test fixture fails the test when either happens, which turns a new
   outbound call into an obvious, one-place fix instead of a confusing downstream error."
  (:require
   [apps.harness.contracts :as contracts]
   [cheshire.core :as json]
   [common-swagger-api.schema.groups :as group-schema]
   [common-swagger-api.schema.metadata :as metadata-schema]
   [clojure.string :as string]
   [ring.adapter.jetty :as jetty]
   [ring.util.codec :as codec]
   [schema.core :as s])
  (:import
   (java.util UUID)
   (org.eclipse.jetty.server Server ServerConnector)))

;; ---------------------------------------------------------------------------------------------------------------
;; Shared state and plumbing
;; ---------------------------------------------------------------------------------------------------------------

(defonce ^:private state (atom {}))
(defonce ^:private unhandled (atom []))

(defn reset-state!
  "Clears all fake service state. Endpoint tests isolate themselves with unique users and names instead, so this is
   mostly useful at the REPL."
  []
  (reset! state {})
  (reset! unhandled []))

(defn unhandled-requests [] @unhandled)
(defn clear-unhandled! [] (reset! unhandled []))

(defn- uuid [] (str (random-uuid)))

(defn- name-uuid
  "Returns a UUID derived from the given strings, for fake IDs that have to stay the same across calls."
  [& ss]
  (str (UUID/nameUUIDFromBytes (.getBytes ^String (string/join ":" ss)))))

(defn- json-response
  ([body] (json-response 200 body))
  ([status body]
   {:status  status
    :headers {"Content-Type" "application/json"}
    :body    (json/encode body)}))

(defn- validated
  "Validates a fake response body against the schema of the real service's response before returning it. If the
   fake drifts from the contract, the violation is recorded alongside unhandled requests so that the test fails with
   the schema error instead of passing on a fiction."
  [schema body]
  (if-let [error (s/check schema body)]
    (do (swap! unhandled conj {:contract-violation error :body body})
        (json-response 500 {:reason "fake response violates the service's schema"}))
    (json-response body)))

(defn- not-found [reason]
  (json-response 404 {:reason reason}))

(defn- path-segments [path]
  (mapv codec/url-decode (remove string/blank? (string/split path #"/"))))

(defn- match-route
  "Returns the path parameters when the request uses the given method and its path segments match the pattern, or nil
   otherwise. Pattern segments that start with a colon match any one segment and become keys in the result:

     (match-route :get \"/groups/:name\" request [\"groups\" \"g1\"]) ;=> {:name \"g1\"}"
  [method pattern {:keys [request-method]} segments]
  (let [pattern (path-segments pattern)]
    (when (and (= method request-method) (= (count pattern) (count segments)))
      (reduce (fn [params [p v]]
                (cond (string/starts-with? p ":") (assoc params (keyword (subs p 1)) v)
                      (= p v)                     params
                      :else                       (reduced nil)))
              {}
              (map vector pattern segments)))))

(defn- route
  "Dispatches a request to the first route, given as [method pattern handler], that matches it. The handler is called
   with the request and the path parameters."
  [routes request segments]
  (some (fn [[method pattern f]]
          (when-let [params (match-route method pattern request segments)]
            (f request params)))
        routes))

(defn- read-json-body [{:keys [body]}]
  (when body
    (let [text (slurp body)]
      (when-not (string/blank? text)
        (json/decode text true)))))

;; ---------------------------------------------------------------------------------------------------------------
;; Permissions service
;;
;; Responses are checked against the permissions service's own Swagger spec; see apps.harness.contracts. With lookup
;; enabled, a user also sees the permissions granted to the groups they belong to in the iplant-groups fake.
;; ---------------------------------------------------------------------------------------------------------------

(def ^:private precedence (zipmap ["own" "write" "admin" "read"] (range)))

(s/defschema Permission (contracts/definition :permissions "permission"))
(s/defschema PermissionList (contracts/definition :permissions "permission_list"))
(s/defschema AbbreviatedPermissionList (contracts/definition :permissions "abbreviated_permission_list"))

(declare groups-for-subject)

(defn- perms [] (vals (get-in @state [:permissions :grants])))

(defn- subject-out [subject-type subject-id]
  {:id                (name-uuid subject-type subject-id)
   :subject_id        subject-id
   :subject_type      subject-type
   :subject_source_id (if (= subject-type "user") "ldap" "g:gsa")})

(defn- resource-out [resource-type resource-name]
  {:id            (name-uuid resource-type resource-name)
   :name          resource-name
   :resource_type resource-type})

(defn- grant! [resource-type resource-name subject-type subject-id level]
  (let [k [resource-type resource-name subject-type subject-id]]
    (-> (swap! state update-in [:permissions :grants k]
               (fn [existing]
                 {:id               (or (:id existing) (uuid))
                  :subject          (subject-out subject-type subject-id)
                  :resource         (resource-out resource-type resource-name)
                  :permission_level level}))
        (get-in [:permissions :grants k]))))

(defn- revoke! [resource-type resource-name subject-type subject-id]
  (swap! state update-in [:permissions :grants] dissoc [resource-type resource-name subject-type subject-id]))

(defn- subjects
  "Returns the [subject-type subject-id] pairs whose permissions apply to a subject. With lookup enabled, a user's
   groups are included, as the real service does by asking iplant-groups."
  [subject-type subject-id lookup?]
  (cond-> #{[subject-type subject-id]}
    (and lookup? (= subject-type "user")) (into (map (fn [{:keys [id]}] ["group" id]))
                                                (groups-for-subject subject-id))))

(defn- at-least? [min-level {:keys [permission_level]}]
  (or (nil? min-level) (<= (precedence permission_level) (precedence min-level))))

(defn- best-per-resource
  "The real service returns only the most privileged permission per resource when lookup is enabled."
  [ps]
  (->> (group-by (comp (juxt :resource_type :name) :resource) ps)
       vals
       (map (partial apply min-key (comp precedence :permission_level)))))

(defn- subject-perms [{:keys [query-params]} {:keys [subject-type subject-id resource-type resource-name]}]
  (let [lookup?   (= "true" (get query-params "lookup"))
        min-level (get query-params "min_level")
        subjects  (subjects subject-type subject-id lookup?)]
    (cond->> (perms)
      true          (filter (comp subjects (juxt :subject_type :subject_id) :subject))
      resource-type (filter (comp #{resource-type} :resource_type :resource))
      resource-name (filter (comp #{resource-name} :name :resource))
      min-level     (filter (partial at-least? min-level))
      lookup?       best-per-resource)))

(defn- abbreviate [{:keys [id resource permission_level]}]
  {:id               id
   :resource_name    (:name resource)
   :resource_type    (:resource_type resource)
   :permission_level permission_level})

(defn- list-subject-perms [request params]
  (validated PermissionList {:permissions (subject-perms request params)}))

(def ^:private permissions-routes
  (let [grant-path "/permissions/resources/:resource-type/:resource-name/subjects/:subject-type/:subject-id"]
    [[:put grant-path
      (fn [request {:keys [resource-type resource-name subject-type subject-id]}]
        (validated Permission (grant! resource-type resource-name subject-type subject-id
                                      (:permission_level (read-json-body request)))))]

     [:delete grant-path
      (fn [_ {:keys [resource-type resource-name subject-type subject-id]}]
        (revoke! resource-type resource-name subject-type subject-id)
        {:status 200 :body ""})]

     [:get "/permissions/resources/:resource-type/:resource-name"
      (fn [_ {:keys [resource-type resource-name]}]
        (validated PermissionList
                   {:permissions (filter (comp #{[resource-type resource-name]} (juxt :resource_type :name) :resource)
                                         (perms))}))]

     [:get "/permissions/abbreviated/subjects/:subject-type/:subject-id/:resource-type"
      (fn [request params]
        (validated AbbreviatedPermissionList {:permissions (map abbreviate (subject-perms request params))}))]

     [:get "/permissions/subjects/:subject-type/:subject-id" list-subject-perms]
     [:get "/permissions/subjects/:subject-type/:subject-id/:resource-type" list-subject-perms]
     [:get "/permissions/subjects/:subject-type/:subject-id/:resource-type/:resource-name" list-subject-perms]]))

;; ---------------------------------------------------------------------------------------------------------------
;; iplant-groups
;;
;; iplant-groups declares its responses with the schemas in common-swagger-api, so the fake validates against those
;; same schemas. The DE users group (whatever the environment name) is the group that public resources are shared
;; with, and it exists from the start, as it does in a real deployment.
;; ---------------------------------------------------------------------------------------------------------------

(s/defschema GroupWithDetail (group-schema/group-with-detail "group"))

(def ^:private de-users-group-id (name-uuid "group" "de-users"))

(defn- de-users-group? [group-name]
  (string/ends-with? group-name ":users:de-users"))

(defn- find-group [group-name]
  (or (get-in @state [:groups group-name])
      (when (de-users-group? group-name)
        {:name group-name :type "role" :id de-users-group-id :id_index "1"})))

(defn- groups-for-subject
  "Returns the groups that a subject has been added to."
  [subject-id]
  (keep (fn [[group-name members]] (when (members subject-id) (find-group group-name)))
        (:group-members @state)))

(def ^:private groups-routes
  [[:get "/groups/:group-name"
    (fn [_ {:keys [group-name]}]
      (if-let [group (find-group group-name)]
        (validated GroupWithDetail group)
        (not-found (str "group not found: " group-name))))]

   [:put "/groups/:group-name/members/:subject-id"
    (fn [_ {:keys [group-name subject-id]}]
      (if (find-group group-name)
        (do (swap! state update-in [:group-members group-name] (fnil conj #{}) subject-id)
            {:status 200 :body ""})
        (not-found (str "group not found: " group-name))))]])

;; ---------------------------------------------------------------------------------------------------------------
;; metadata
;;
;; Only AVUs so far. The metadata service declares its responses with the schemas in common-swagger-api and in
;; metadata.routes.schemas, so UUIDs are kept as UUIDs here and encoded as strings on the way out, as they are there.
;; ---------------------------------------------------------------------------------------------------------------

;; metadata.routes.schemas.common/TargetIDList in cyverse-de/metadata.
(s/defschema TargetIDList {:target-ids [UUID]})

(defn add-avu!
  "Attaches an AVU to a target, for tests that need an app to carry metadata (e.g. a beta or certified tag)."
  [target-type target-id {:keys [attr value unit] :or {unit ""}}]
  (let [now (System/currentTimeMillis)]
    (swap! state update-in [:metadata :avus [target-type (str target-id)]] (fnil conj [])
           {:id          (random-uuid)
            :attr        attr
            :value       value
            :unit        unit
            :target_id   (parse-uuid (str target-id))
            :created_by  "fake-metadata"
            :modified_by "fake-metadata"
            :created_on  now
            :modified_on now})))

(defn- target-avus [target-type target-id]
  (get-in @state [:metadata :avus [target-type target-id]]))

(defn- filter-targets
  "Mirrors metadata.persistence.avu/filter-targets-by-attrs-values: a target matches when it has an AVU whose
   attribute is any of the requested attributes and whose value is any of the requested values."
  [{:keys [target-types target-ids avus]}]
  (let [attrs  (set (map :attr avus))
        values (set (map :value avus))]
    (for [target-id   (distinct target-ids)
          :when (some (fn [target-type]
                        (some #(and (attrs (:attr %)) (values (:value %))) (target-avus target-type target-id)))
                      target-types)]
      (parse-uuid target-id))))

(def ^:private metadata-routes
  [[:post "/avus/filter-targets"
    (fn [request _]
      (validated TargetIDList {:target-ids (vec (filter-targets (read-json-body request)))}))]

   [:get "/avus/:target-type/:target-id"
    (fn [_ {:keys [target-type target-id]}]
      (validated metadata-schema/AvuList {:avus (vec (target-avus target-type target-id))}))]])

;; ---------------------------------------------------------------------------------------------------------------
;; analyses and requests
;;
;; Both are Go services. Analyses responses are checked against its Swagger spec; see apps.harness.contracts. The
;; requests service doesn't publish a spec, so its one response is described by hand after model.RequestListing in
;; cyverse-de/requests. The default limit matches the one seeded by de-database.
;; ---------------------------------------------------------------------------------------------------------------

(s/defschema ConcurrentJobLimit (contracts/definition :analyses "db.ConcurrentJobLimit"))

(def default-concurrent-job-limit 8)

(defn set-concurrent-job-limit!
  "Gives a user an explicit concurrent VICE job limit, as an administrator would through the analyses service."
  [username limit]
  (swap! state assoc-in [:analyses :job-limits username] limit))

(def ^:private analyses-routes
  [[:get "/settings/concurrent-job-limits/:username"
    (fn [_ {:keys [username]}]
      (validated ConcurrentJobLimit
                 (if-let [limit (get-in @state [:analyses :job-limits username])]
                   {:username username :concurrent_jobs limit :is_default false}
                   {:concurrent_jobs default-concurrent-job-limit :is_default true})))]])

(def ^:private requests-routes
  [[:get "/requests" (fn [_ _] (validated {:requests [s/Any]} {:requests []}))]])

;; ---------------------------------------------------------------------------------------------------------------
;; notifications
;;
;; Notifications are recorded rather than delivered, so tests can assert on what a user would have been told.
;; ---------------------------------------------------------------------------------------------------------------

(defn notifications-for
  "Returns the notifications sent to the given user (short username), oldest first."
  [username]
  (filter (comp #{username} :user) (get-in @state [:notifications])))

(def ^:private notifications-routes
  [[:post "/notification"
    (fn [request _]
      (swap! state update :notifications (fnil conj []) (read-json-body request))
      (json-response {}))]])

;; ---------------------------------------------------------------------------------------------------------------
;; Dispatch
;; ---------------------------------------------------------------------------------------------------------------

(def ^:private services
  "Maps the first path segment of a fake URL to the routes for that service. Configuration points each client's base
   URL at http://localhost:<port>/<prefix>."
  {"permissions"   permissions-routes
   "groups"        groups-routes
   "metadata"      metadata-routes
   "analyses"      analyses-routes
   "requests"      requests-routes
   "notifications" notifications-routes})

(defn- handler [request]
  (let [request             (assoc request :query-params (some-> (:query-string request) codec/form-decode
                                                                 (#(if (map? %) % {}))))
        [prefix & segments] (path-segments (:uri request))]
    (or (some-> (services prefix) (route request (vec segments)))
        (do (swap! unhandled conj (select-keys request [:request-method :uri :query-string]))
            (json-response 501 {:reason (str "no fake for " (string/upper-case (name (:request-method request)))
                                             " " (:uri request))})))))

(defn start!
  "Starts the fake services on an ephemeral port and returns the base URL."
  []
  (let [^Server server (jetty/run-jetty #'handler {:port 0 :join? false})
        port           (.getLocalPort ^ServerConnector (first (.getConnectors server)))]
    {:server server :base-url (str "http://localhost:" port)}))

(defn stop! [{:keys [^Server server]}]
  (when server (.stop server)))

(defn base-url-for [{:keys [base-url]} prefix]
  (str base-url "/" prefix))
