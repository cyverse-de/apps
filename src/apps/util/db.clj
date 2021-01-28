(ns apps.util.db
  "This namespace contains some database utility functions. For the time being, these functions will use Korma to
   obtain database connections. This code will have to be modified when Korma is removed."
  (:require [korma.db :as db]))

(defmacro transaction
  "Executes queries within the body in a single transaction. Nested transactions are absorbed into the outermost
   transaction. For now, this is just an alias to korma.db/transaction. When Korma is removed, it will be necessary
   to come up with an alternative implementation."
  [& body]
  `(korma.db/transaction ~@body))

(defmacro with-transaction
  "Executes queries within the body in a single transaction. Nested transactions are absorbed into the outermost
   transaction. This function is similar to apps.util.db/transaction, except that an explicit variable binding
   is created in addition to the usual implicit variable binding."
  [[tx] & body]
  `(transaction
    (let [~tx db/*current-conn*]
      ~@body)))
