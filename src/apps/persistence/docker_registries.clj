(ns apps.persistence.docker-registries
  (:require [apps.persistence.entities :only [docker-registries]]
            [clojure.string :as string]
            [korma.core :as sql]))

(defn get-registry
  [name]
  (first (sql/select docker-registries
                 (sql/where {:name name}))))

(defn get-registry-from-image
  [image-name]
  (let [parts (string/split image-name #"/")]
    (loop [n (count parts)]
      (when-not (= n 0)
        (if-let [reg (get-registry (string/join "/" (take n parts)))]
          reg
          (recur (- n 1)))))))
