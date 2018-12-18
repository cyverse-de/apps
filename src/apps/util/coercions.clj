(ns apps.util.coercions
  (:require [ring.swagger.coerce :as rc]
            [ring.swagger.common :refer [value-of]]
            [schema.coerce :as sc]
            [schema.utils :as su]
            [slingshot.slingshot :refer [throw+]])
  (:import [java.util UUID]))

(defn coerce-string->long
  "When the given map contains the given key, converts its string value to a long."
  [m k]
  (if (contains? m k)
    (update m k rc/string->long)
    m))

(defn- stringify-uuids
  [v]
  (if (instance? UUID v)
    (str v)
    v))

(def ^:private custom-coercions {String stringify-uuids})

(defn- custom-coercion-matcher
  [schema]
  (or (rc/json-schema-coercion-matcher schema)
      (custom-coercions schema)))

(defn coerce
  [schema value]
  ((sc/coercer (value-of schema) custom-coercion-matcher) value))

(defn coerce!
  [schema value]
  (let [result (coerce schema value)]
    (if (su/error? result)
      (throw+ (assoc result :type :compojure.api.exception/response-validation))
      result)))
