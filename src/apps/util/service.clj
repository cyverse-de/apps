(ns apps.util.service
  (:require
   [cheshire.core :as cheshire]
   [clojure-commons.assertions :as ca]
   [clojure.java.io :refer [reader]]
   [slingshot.slingshot :refer [throw+ try+]]))

(defn unrecognized-path-response
  "Builds the response to send for an unrecognized service path."
  []
  (let [msg "unrecognized service path"]
    (cheshire/encode {:reason msg})))

(defn parse-json
  "Parses a JSON request body."
  [body]
  (try+
   (if (string? body)
     (cheshire/decode body true)
     (cheshire/decode-stream (reader body) true))
   (catch Exception e
     (throw+ {:type  :clojure-commons.exception/invalid-json
              :error (str e)}))))

(def not-found ca/not-found)
(def not-unique ca/not-unique)
(def bad-request ca/bad-request)
(def assert-found ca/assert-found)
(def assert-valid ca/assert-valid)
