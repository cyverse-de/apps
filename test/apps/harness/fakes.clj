(ns apps.harness.fakes
  "In-process fakes for the services that apps calls over HTTP. Each fake keeps just enough state to behave like the
   real service for the requests apps makes, so tests describe what happens (share an app, then list it) rather than
   scripting the exact calls in between.

   Any request that no fake handles is answered with a 501 and recorded, as is any fake response that doesn't match
   the real service's schema. The endpoint test fixture fails the test when either happens, which turns a new
   outbound call into an obvious, one-place fix instead of a confusing downstream error."
  (:require
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

(defn- uuid [] (str (UUID/randomUUID)))

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

(defn- path-segments [uri]
  (mapv codec/url-decode (remove string/blank? (string/split uri #"/"))))

(defn- read-json-body [{:keys [body]}]
  (when body
    (let [text (slurp body)]
      (when-not (string/blank? text)
        (json/decode text true)))))

;; ---------------------------------------------------------------------------------------------------------------
;; Permissions service
;;
;; Schemas transcribed from the definitions in cyverse-de/permissions swagger.yml. Every user is treated as a member
;; of the de-users group, which is what makes public (group-shared) resources visible on lookup.
;; ---------------------------------------------------------------------------------------------------------------

(def permission-levels ["read" "admin" "write" "own"])
(def ^:private precedence (zipmap ["own" "write" "admin" "read"] (range)))

(s/defschema PermissionLevel (apply s/enum permission-levels))

(s/defschema SubjectOut
  {:id                s/Str
   :subject_id        s/Str
   :subject_type      (s/enum "user" "group")
   :subject_source_id s/Str})

(s/defschema ResourceOut
  {:id            s/Str
   :name          s/Str
   :resource_type s/Str})

(s/defschema Permission
  {:id               s/Str
   :subject          SubjectOut
   :resource         ResourceOut
   :permission_level PermissionLevel})

(s/defschema PermissionList {:permissions [Permission]})

(s/defschema AbbreviatedPermissionList
  {:permissions [{:id               s/Str
                  :resource_name    s/Str
                  :resource_type    s/Str
                  :permission_level PermissionLevel}]})

(def de-users-group-id "fake-de-users-group-id")

(defn- perms [] (vals (get-in @state [:permissions :grants])))

(defn- subject-out [subject-type subject-id]
  {:id                (str (UUID/nameUUIDFromBytes (.getBytes (str subject-type ":" subject-id))))
   :subject_id        subject-id
   :subject_type      subject-type
   :subject_source_id (if (= subject-type "user") "ldap" "g:gsa")})

(defn- resource-out [resource-type resource-name]
  {:id            (str (UUID/nameUUIDFromBytes (.getBytes (str resource-type ":" resource-name))))
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

(defn- subject-matches?
  "With lookup enabled a user also sees permissions granted to the groups they belong to."
  [subject-type subject-id lookup? {:keys [subject]}]
  (or (and (= (:subject_type subject) subject-type) (= (:subject_id subject) subject-id))
      (and lookup?
           (= subject-type "user")
           (= (:subject_type subject) "group")
           (= (:subject_id subject) de-users-group-id))))

(defn- at-least? [min-level {:keys [permission_level]}]
  (or (nil? min-level) (<= (precedence permission_level) (precedence min-level))))

(defn- best-per-resource
  "The real service returns only the most privileged permission per resource when lookup is enabled."
  [ps]
  (->> (group-by (comp (juxt :resource_type :name) :resource) ps)
       vals
       (map (partial apply min-key (comp precedence :permission_level)))))

(defn- subject-perms [{:keys [query-params]} subject-type subject-id & [resource-type resource-name]]
  (let [lookup?   (= "true" (get query-params "lookup"))
        min-level (get query-params "min_level")]
    (cond->> (perms)
      true          (filter (partial subject-matches? subject-type subject-id lookup?))
      resource-type (filter (comp #{resource-type} :resource_type :resource))
      resource-name (filter (comp #{resource-name} :name :resource))
      min-level     (filter (partial at-least? min-level))
      lookup?       best-per-resource)))

(defn- abbreviate [{:keys [id resource permission_level]}]
  {:id               id
   :resource_name    (:name resource)
   :resource_type    (:resource_type resource)
   :permission_level permission_level})

(defn- permissions-handler [{:keys [request-method] :as request} segments]
  (let [vs (vec segments)]
    (cond
      ;; PUT/DELETE /permissions/resources/:type/:name/subjects/:subject-type/:subject-id
      (and (= (subvec vs 0 (min 2 (count vs))) ["permissions" "resources"]) (= (count vs) 7) (= (vs 4) "subjects"))
      (let [[_ _ rt rn _ st sid] vs]
        (case request-method
          :put    (validated Permission (grant! rt rn st sid (:permission_level (read-json-body request))))
          :delete (do (revoke! rt rn st sid) {:status 200 :body ""})
          nil))

      ;; GET /permissions/resources/:type/:name
      (and (= request-method :get) (= (count vs) 4) (= (subvec vs 0 2) ["permissions" "resources"]))
      (let [[_ _ rt rn] vs]
        (validated PermissionList
                   {:permissions (filter (comp #{[rt rn]} (juxt :resource_type :name) :resource) (perms))}))

      ;; GET /permissions/abbreviated/subjects/:subject-type/:subject-id/:resource-type
      (and (= request-method :get) (= (count vs) 6) (= (subvec vs 0 3) ["permissions" "abbreviated" "subjects"]))
      (let [[_ _ _ st sid rt] vs]
        (validated AbbreviatedPermissionList {:permissions (map abbreviate (subject-perms request st sid rt))}))

      ;; GET /permissions/subjects/:subject-type/:subject-id[/:resource-type[/:resource-name]]
      (and (= request-method :get) (<= 4 (count vs) 6) (= (subvec vs 0 2) ["permissions" "subjects"]))
      (let [[_ _ st sid rt rn] vs]
        (validated PermissionList {:permissions (subject-perms request st sid rt rn)})))))

;; ---------------------------------------------------------------------------------------------------------------
;; iplant-groups
;;
;; iplant-groups declares its responses with the schemas in common-swagger-api, so the fake validates against those
;; same schemas. The DE users group (whatever the environment name) is the group that public resources are shared
;; with, and it exists from the start, as it does in a real deployment.
;; ---------------------------------------------------------------------------------------------------------------

(s/defschema GroupWithDetail (group-schema/group-with-detail "group"))

(defn- de-users-group? [group-name]
  (string/ends-with? group-name ":users:de-users"))

(defn- find-group [group-name]
  (or (get-in @state [:groups group-name])
      (when (de-users-group? group-name)
        {:name group-name :type "role" :id de-users-group-id :id_index "1"})))

(defn- groups-handler [{:keys [request-method]} segments]
  (let [vs (vec segments)]
    (cond
      ;; GET /groups/:name
      (and (= request-method :get) (= (count vs) 2) (= (vs 0) "groups"))
      (if-let [group (find-group (vs 1))]
        (validated GroupWithDetail group)
        (not-found (str "group not found: " (vs 1))))

      ;; PUT /groups/:name/members/:subject-id
      (and (= request-method :put) (= (count vs) 4) (= (vs 0) "groups") (= (vs 2) "members"))
      (if (find-group (vs 1))
        (do (swap! state update-in [:group-members (vs 1)] (fnil conj #{}) (vs 3))
            {:status 200 :body ""})
        (not-found (str "group not found: " (vs 1)))))))

;; ---------------------------------------------------------------------------------------------------------------
;; metadata
;;
;; Only AVUs so far. Schemas follow metadata.routes.schemas in cyverse-de/metadata.
;; ---------------------------------------------------------------------------------------------------------------

(s/defschema TargetIDList {:target-ids [(s/pred #(re-matches #"[0-9a-fA-F-]{36}" %) 'uuid-string?)]})

;; The metadata service's own AVU schema lives in common-swagger-api; UUIDs travel as strings in JSON.
(s/defschema Avu (-> metadata-schema/Avu
                     (dissoc (s/optional-key :avus))
                     (assoc :id s/Str :target_id s/Str)))

(s/defschema AvuList {:avus [Avu]})

(defn add-avu!
  "Attaches an AVU to a target, for tests that need an app to carry metadata (e.g. a beta or certified tag)."
  [target-type target-id {:keys [attr value unit] :or {unit ""}}]
  (let [now (System/currentTimeMillis)]
    (swap! state update-in [:metadata :avus [target-type (str target-id)]] (fnil conj [])
           {:id          (uuid)
            :attr        attr
            :value       value
            :unit        unit
            :target_id   (str target-id)
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
      target-id)))

(defn- metadata-handler [{:keys [request-method] :as request} segments]
  (cond
    (and (= request-method :post) (= segments ["avus" "filter-targets"]))
    (validated TargetIDList {:target-ids (vec (filter-targets (read-json-body request)))})

    ;; GET /avus/:target-type/:target-id
    (and (= request-method :get) (= (count segments) 3) (= (first segments) "avus"))
    (let [[_ target-type target-id] segments]
      (validated AvuList {:avus (vec (target-avus target-type target-id))}))))

;; ---------------------------------------------------------------------------------------------------------------
;; analyses and requests
;;
;; Both are Go services. Response shapes follow db.ConcurrentJobLimit in cyverse-de/analyses and model.RequestListing
;; in cyverse-de/requests. The default limit matches the one seeded by de-database.
;; ---------------------------------------------------------------------------------------------------------------

(s/defschema ConcurrentJobLimit
  {(s/optional-key :username) s/Str
   :concurrent_jobs           s/Int
   :is_default                s/Bool})

(def default-concurrent-job-limit 8)

(defn set-concurrent-job-limit!
  "Gives a user an explicit concurrent VICE job limit, as an administrator would through the analyses service."
  [username limit]
  (swap! state assoc-in [:analyses :job-limits username] limit))

(defn- analyses-handler [{:keys [request-method]} segments]
  (let [vs (vec segments)]
    (when (and (= request-method :get) (= (count vs) 3) (= (subvec vs 0 2) ["settings" "concurrent-job-limits"]))
      (let [username (vs 2)
            limit    (get-in @state [:analyses :job-limits username])]
        (validated ConcurrentJobLimit
                   (if limit
                     {:username username :concurrent_jobs limit :is_default false}
                     {:concurrent_jobs default-concurrent-job-limit :is_default true}))))))

(defn- requests-handler [{:keys [request-method]} segments]
  (when (and (= request-method :get) (= segments ["requests"]))
    (validated {:requests [s/Any]} {:requests []})))

;; ---------------------------------------------------------------------------------------------------------------
;; notifications
;;
;; Notifications are recorded rather than delivered, so tests can assert on what a user would have been told.
;; ---------------------------------------------------------------------------------------------------------------

(defn notifications-for
  "Returns the notifications sent to the given user (short username), oldest first."
  [username]
  (filter (comp #{username} :user) (get-in @state [:notifications])))

(defn- notifications-handler [{:keys [request-method] :as request} segments]
  (when (and (= request-method :post) (= segments ["notification"]))
    (swap! state update :notifications (fnil conj []) (read-json-body request))
    (json-response {})))

;; ---------------------------------------------------------------------------------------------------------------
;; Dispatch
;; ---------------------------------------------------------------------------------------------------------------

(def ^:private services
  "Maps the first path segment of a fake URL to the handler for that service. Configuration points each client's
   base URL at http://localhost:<port>/<prefix>."
  {"permissions" permissions-handler
   "groups"      groups-handler
   "metadata"    metadata-handler
   "analyses"    analyses-handler
   "requests"    requests-handler
   "notifications" notifications-handler})

(defn- handler [request]
  (let [request             (assoc request :query-params (some-> (:query-string request) codec/form-decode
                                                                 (#(if (map? %) % {}))))
        [prefix & segments] (path-segments (:uri request))
        service-handler     (services prefix)]
    (or (when service-handler (service-handler request segments))
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
