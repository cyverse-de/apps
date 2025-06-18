(ns apps.util.db)

(defmacro with-transaction
  "Executes queries within the body in a single transaction. Nested transactions are absorbed into the outermost
   transaction. This function is similar to apps.util.db/transaction, except that an explicit variable binding
   is created in addition to the usual implicit variable binding."
  [[tx] & body]
  `(let [~tx "foo"]
     ~@body))
