(ns apps.harness.system
  "Wires a real apps Ring handler to a throwaway database and the fake services, and provides helpers for calling
   endpoints in-process. The system starts once per JVM and is shared by every endpoint test namespace."
  (:require
   [apps.harness.db :as db]
   [apps.harness.fakes :as fakes]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.test :refer [is]]
   [ring.mock.request :as mock])
  (:import
   (java.io File)))

(defn- properties [db fake-services]
  (let [fake (partial fakes/base-url-for fake-services)]
    {"apps.db.host"                                 (:host db)
     "apps.db.port"                                 (str (:port db))
     "apps.db.name"                                 (:name db)
     "apps.db.user"                                 (:user db)
     "apps.db.password"                             (:password db)
     "apps.uid.domain"                              "iplantcollaborative.org"
     "apps.ui.base-url"                             "https://de.example.org"
     "apps.email.app-deletion.from"                 "noreply@example.org"
     "apps.email.app-request.from"                  "noreply@example.org"
     "apps.email.app-request.to"                    "support@example.org"
     ;; Tapis is an external HPC system rather than a DE service; these tests cover DE apps only. Config validation
     ;; still insists on the Tapis settings, so they get placeholder values.
     "apps.features.tapis"                          "false"
     "apps.tapis.key"                               "unused"
     "apps.tapis.secret"                            "unused"
     "apps.tapis.redirect-uri"                      "https://de.example.org/unused"
     "apps.tapis.callback-base"                     "https://de.example.org/unused"
     "apps.permissions.base-url"                    (fake "permissions")
     "apps.metadata.base-url"                       (fake "metadata")
     "apps.iplant-groups.base-url"                  (fake "groups")
     "apps.data-info.base-url"                      (fake "data-info")
     "apps.notificationagent.base-url"              (fake "notifications")
     "apps.email.base-url"                          (fake "email")
     "apps.jex.base-url"                            (fake "jex")
     "apps.vice.base-url"                           (fake "app-exposer")
     "apps.analyses.base-url"                       (fake "analyses")
     "apps.async-tasks.base-url"                    (fake "async-tasks")
     "apps.requests.base-url"                       (fake "requests")}))

(defn- write-properties ^File [props]
  (let [f (File/createTempFile "apps-endpoint-test" ".properties")]
    (.deleteOnExit f)
    (with-open [w (io/writer f)]
      (doseq [[k v] props]
        (.write w (str k " = " v "\n"))))
    f))

(defn- start! []
  (let [db            (db/start!)
        fake-services (fakes/start!)
        config-file   (write-properties (properties db fake-services))]
    ;; Loaded lazily so that the configuration exists before any namespace that memoizes a client is initialized.
    ((requiring-resolve 'apps.core/load-config-from-file) (.getPath config-file))
    {:db db :fakes fake-services :handler @(requiring-resolve 'apps.routes/app)}))

(defonce ^:private system (delay (let [sys (start!)]
                                   (.addShutdownHook (Runtime/getRuntime)
                                                     (Thread. #(do (fakes/stop! (:fakes sys)) (db/stop! (:db sys)))))
                                   sys)))

(defn with-system
  "A :once fixture that starts the shared system if it isn't already running."
  [f]
  @system
  (f))

(defn fail-on-unhandled-requests
  "An :each fixture that fails the test if apps made a request that none of the fakes know how to answer, or if a
   fake produced a response that doesn't match the real service's schema."
  [f]
  (fakes/clear-unhandled!)
  (f)
  (let [reqs (fakes/unhandled-requests)]
    (is (empty? reqs) (str "apps made requests the fakes couldn't answer correctly; update apps.harness.fakes: "
                           (pr-str reqs)))))

;; ---------------------------------------------------------------------------------------------------------------
;; Test data and request helpers
;; ---------------------------------------------------------------------------------------------------------------

(defn unique-name
  "Returns a name that won't collide with data created by other tests, since the database isn't reset between them."
  [prefix]
  (str prefix "-" (subs (str (random-uuid)) 0 8)))

(defn- body->json [response]
  (let [body (:body response)
        text (cond (string? body) body
                   (nil? body)    nil
                   :else          (slurp body))]
    (assoc response :body (if (and text (string/starts-with? (get-in response [:headers "Content-Type"] "")
                                                             "application/json"))
                            (json/decode text true)
                            text))))

(defn call
  "Calls the apps handler as the given user and returns the response with a decoded JSON body.

     (call user :get \"/apps\" {:query {:search \"wc\"}})
     (call user :post \"/apps/de\" {:body app-definition})"
  ([user method path] (call user method path {}))
  ([user method path {:keys [query body]}]
   (let [request (cond-> (mock/request method path)
                   true  (mock/query-string (merge (select-keys user [:user :email :first-name :last-name]) query))
                   body  (mock/json-body body))]
     (body->json ((:handler @system) request)))))

(defn new-user
  "Creates a user that no other test uses and logs them in. Terrain calls /bootstrap when a user logs in, which is
   what creates their workspace, so tests do the same."
  []
  (let [username (unique-name "testuser")
        user     {:user username :email (str username "@example.org") :first-name "Test" :last-name "User"}
        response (call user :get "/bootstrap")]
    (when-not (= 200 (:status response))
      (throw (ex-info "bootstrap failed for new test user" {:user user :response response})))
    user))
