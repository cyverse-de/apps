(ns apps.harness.contracts
  "Response schemas for the Go services that the fakes stand in for. Clojure services share their schemas through
   common-swagger-api, but the Go services can't, so this namespace builds plumatic schemas from copies of their own
   Swagger specs, kept in test/contracts. The fakes validate their responses against these, so a fake can't drift
   from the real service without the endpoint tests noticing once the copy is refreshed.

   Refresh the copies whenever one of these services changes its API, then rerun the endpoint tests and review the
   diff:

     lein with-profile +test run -m apps.harness.contracts

   This downloads each spec from the head of the service's main branch and records the commit it came from in
   test/contracts/sources.edn."
  (:require
   [cheshire.core :as json]
   [clj-http.client :as http]
   [clj-yaml.core :as yaml]
   [clojure.java.io :as io]
   [clojure.pprint :refer [pprint]]
   [clojure.string :as string]
   [schema.core :as s]))

(def ^:private contracts-dir "test/contracts")

(def ^:private sources
  "The services whose specs are copied, keyed by the name used in `definition`. The spec is stored under the same
   path it has in the service's repository."
  {:permissions {:repo "cyverse-de/permissions" :path "swagger.yml"}
   :analyses    {:repo "cyverse-de/analyses" :path "docs/swagger.json"}})

(defn- spec-file [{:keys [repo path]}]
  (io/file contracts-dir (last (string/split repo #"/")) path))

(def ^:private spec
  (memoize
   (fn [service]
     (let [f    (spec-file (sources service))
           text (slurp f)]
       (if (string/ends-with? (.getName f) ".json")
         (json/decode text true)
         (yaml/parse-string text))))))

;; ---------------------------------------------------------------------------------------------------------------
;; Swagger to plumatic schema
;;
;; Only the subset of Swagger 2.0 that these specs use is supported; anything else fails loudly so that a spec update
;; can't silently weaken a check. Objects are closed unless they declare additionalProperties: the schemas are only
;; used to check the fakes' own responses, so an unexpected key means a mistake in the fake.
;; ---------------------------------------------------------------------------------------------------------------

(declare ->schema)

(defn- ref-name [ref]
  (keyword (string/replace-first ref "#/definitions/" "")))

(defn- string-schema [{:keys [enum minLength maxLength]}]
  (cond
    enum                     (apply s/enum enum)
    (or minLength maxLength) (s/constrained s/Str
                                            #(<= (or minLength 0) (count %) (or maxLength Integer/MAX_VALUE))
                                            (symbol (str "length-between-" (or minLength 0) "-and-" maxLength)))
    :else                    s/Str))

(defn- object-schema [defs {:keys [properties required additionalProperties]}]
  (let [required? (set (map keyword required))]
    (cond-> (into {} (for [[k v] properties]
                       [(if (required? (keyword k)) (keyword k) (s/optional-key (keyword k))) (->schema defs v)]))
      additionalProperties (assoc s/Keyword (if (map? additionalProperties)
                                              (->schema defs additionalProperties)
                                              s/Any)))))

(defn- ->schema [defs {:keys [$ref type items] :as node}]
  (cond
    $ref               (->schema defs (or (defs (ref-name $ref))
                                          (throw (ex-info (str "undefined reference: " $ref) {:node node}))))
    (= type "object")  (object-schema defs node)
    (= type "array")   [(->schema defs items)]
    (= type "string")  (string-schema node)
    (= type "integer") s/Int
    (= type "number")  s/Num
    (= type "boolean") s/Bool
    :else              (throw (ex-info "unsupported Swagger schema" {:node node}))))

(defn definition
  "Returns the plumatic schema for a definition in a service's Swagger spec, e.g. (definition :permissions
   \"permission_list\")."
  [service definition-name]
  (let [defs (:definitions (spec service))]
    (->schema defs (or (defs (keyword definition-name))
                       (throw (ex-info (str "no definition named " definition-name " in the " (name service) " spec")
                                       {:service service :definition definition-name}))))))

;; ---------------------------------------------------------------------------------------------------------------
;; Refreshing the copies
;; ---------------------------------------------------------------------------------------------------------------

(defn- head-commit [repo]
  (get-in (http/get (str "https://api.github.com/repos/" repo "/commits/main") {:as :json}) [:body :sha]))

(defn update-specs!
  "Downloads the current spec of every service in `sources` and records the commit each one came from."
  []
  (let [commits (into (sorted-map)
                      (for [[service {:keys [repo path] :as source}] sources]
                        (let [sha (head-commit repo)
                              f   (spec-file source)]
                          (io/make-parents f)
                          (spit f (:body (http/get (str "https://raw.githubusercontent.com/" repo "/" sha "/" path))))
                          (println "updated" (.getPath f) "from" repo "at" sha)
                          [service (assoc source :commit sha)])))]
    (with-open [w (io/writer (io/file contracts-dir "sources.edn"))]
      (binding [*out* w] (pprint commits)))))

(defn -main [& _]
  (update-specs!)
  (shutdown-agents))
