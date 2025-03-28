(ns clojure-commons-hooks.config
  (:require [clj-kondo.hooks-api :as api]))

(defn- rewrite
  [node]
  (let [[sym desc _bindings prop-name] (rest (:children node))]
    (with-meta
      (api/list-node
       (list* (api/token-node 'defn)
              sym
              desc
              (api/vector-node [])
              (:lines prop-name)))
      (meta node))))

(defn defprop [{:keys [node]}]
  (println (str node))
  (let [new-node (rewrite node)]
    (println (str new-node))
    {:node new-node}))
