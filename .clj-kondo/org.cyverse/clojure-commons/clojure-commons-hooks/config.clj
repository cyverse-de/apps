(ns clojure-commons-hooks.config
  (:require [clj-kondo.hooks-api :as api]))

(defn defprop [{:keys [node]}]
  (let [[desc sym bindings _prop-name & _rest]       (:children node)
        [_props _config-vald _configs & _flag-props] bindings]
    {:node ((api/list-node
             (list*
              (api/token-node defn)
              (api/string-node desc)
              (api/token-node sym)
              (api/vector-node [])
              (api/string-node ""))))}))
