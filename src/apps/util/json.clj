(ns apps.util.json
  (:require
   [cheshire.core :as json]
   [clojure.tools.logging :as log]))

(defn log-json
  "Logs m as JSON under description, returning m unmodified. If redact-fn is
   given, it's applied to m before logging (but the original m is still
   returned) — use this to keep secrets (e.g. sensitive_environment) out of
   application logs without altering the value passed downstream."
  ([description m]
   (log-json description identity m))
  ([description redact-fn m]
   (->> (json/encode (redact-fn m))
        (str description ": ")
        (log/info))
   m))
