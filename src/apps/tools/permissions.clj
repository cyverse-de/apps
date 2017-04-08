(ns apps.tools.permissions
  (:require [apps.clients.permissions :as permissions]
            [clojure-commons.exception-util :as exception-util]
            [clojure.string :as string]))

(defn check-tool-permissions
  [user required-level tool-ids]
  (let [tool-ids            (set tool-ids)
        accessible-tool-ids (set (keys (permissions/load-tool-permissions user tool-ids required-level)))]
    (when-let [forbidden-tools (seq (clojure.set/difference tool-ids accessible-tool-ids))]
      (exception-util/forbidden (str "insufficient privileges for tools: " (string/join ", " forbidden-tools))))))
