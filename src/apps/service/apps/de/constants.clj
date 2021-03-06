(ns apps.service.apps.de.constants
  (:require [apps.util.config :as config]))

;; This is the system ID used for DE apps, which includes interactive apps and mixed pipelines.
(def system-id "de")

(def certified-avu
  "The AVU to add to an app that has been marked as reviewed and certified by DE administrators."
  {:attr  (config/workspace-metadata-certified-apps-attr)
   :value (config/workspace-metadata-certified-apps-value)
   :unit  ""})
