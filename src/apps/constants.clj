(ns apps.constants
  (:require [mescal.agave-de-v2.constants :as c]))

(def de-system-id "de")
(def hpc-system-id c/hpc-system-id)
(def interactive-system-id "interactive")

(def system-ids [de-system-id hpc-system-id interactive-system-id])

(def internal-app-integrator "Internal DE Tools")

(def executable-tool-type "executable")
(def internal-tool-type "internal")
(def interactive-tool-type "interactive")
