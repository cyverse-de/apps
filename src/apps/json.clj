(ns apps.json
  (:require [cheshire.core :as cheshire]
            [slingshot.slingshot :refer [throw+ try+]]))

(defn from-json
  "Parses a JSON string, throwing an informative exception if the JSON string
   can't be parsed."
  [str]
  (try+
   (cheshire/decode str true)
   (catch Exception e
     (throw+ {:type   ::invalid_request_body
              :reason "NOT_JSON"
              :detail (.getMessage e)}))))
