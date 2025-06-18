(ns korma.core)

(defmacro defentity
  [ent & body]
  `(def ~ent (-> {} ~@body)))
