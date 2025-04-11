(ns apps.translations.app-metadata
  (:require
   [apps.translations.app-metadata.external-to-preview :as e2p]
   [cheshire.core :as cheshire]
   [clojure.tools.logging :as log]))

(defn- log-as-json
  [msg obj]
  (log/trace msg (cheshire/encode obj {:pretty true})))

(defn template-cli-preview-req
  [external]
  (log-as-json "template-cli-preview-req - before:" external)
  (let [internal (e2p/translate-template external)]
    (log-as-json "template-cli-preview-req - after:" internal)
    internal))
